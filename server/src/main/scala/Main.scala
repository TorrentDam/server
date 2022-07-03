import cats.syntax.all.*
import cats.effect.syntax.all.*
import cats.data.{Kleisli, OptionT}
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.std.Random
import com.github.lavrov.bittorrent.dht.{Node, NodeId, PeerDiscovery, QueryHandler, RoutingTable, RoutingTableBootstrap}
import com.github.lavrov.bittorrent.wire.{Connection, Swarm}
import com.github.lavrov.bittorrent.{FileMapping, InfoHash, PeerId, TorrentFile}
import com.github.torrentdam.tracker.Client as TrackerClient
import com.github.torrentdam.bencode.encode
import com.github.torrentdam.bencode.format.BencodeFormat
import fs2.Stream
import fs2.io.net.{DatagramSocketGroup, Network, SocketGroup}
import org.http4s.headers.{Range, `Accept-Ranges`, `Content-Disposition`, `Content-Length`, `Content-Range`, `Content-Type`}
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.{HttpApp, MediaType, Response}
import org.legogroup.woof.{Logger, given}
import org.typelevel.ci.*
import sun.misc.Signal
import Routes.FileIndex
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.server.websocket.WebSocketBuilder
import cps.*
import cps.syntax.*
import cps.monads.catsEffect.{*, given}

import scala.concurrent.duration.*

object Main extends IOApp {

  def downloadPieceTimeout: FiniteDuration = 3.minutes
  def maxPrefetchBytes = 50 * 1000 * 1000

  def run(args: List[String]): IO[ExitCode] = asyncScope[IO]{
    given Logger[IO] = !mkLogger
    !registerSignalHandler
    val app: HttpWebSocketApp = !makeApp
    val bindPort = Option(System.getenv("PORT")).flatMap(_.toIntOption).getOrElse(9999)
    !serve(bindPort, app)
  }

  def registerSignalHandler: IO[Unit] =
    IO {
      Signal.handle(new Signal("INT"), _ => System.exit(0))
    }

  def resources(
    using Logger[IO]
  ): Resource[IO, (TorrentRegistry, ServerTorrent.Create, TorrentIndex, MetadataRegistry[IO])] =
    async[Resource[IO, _]]{
      given Random[IO] = !Resource.eval { Random.scalaUtilRandom[IO] }
      val selfId = !Resource.eval { PeerId.generate[IO] }
      val selfNodeId = !Resource.eval { NodeId.generate[IO] }
      given SocketGroup[IO] = !Network[IO].socketGroup()
      val routingTable = !Resource.eval { RoutingTable[IO](selfNodeId) }
      given DatagramSocketGroup[IO] = !Network[IO].datagramSocketGroup()
      val dhtNode = !Node(selfNodeId, QueryHandler(selfNodeId, routingTable))
      !Resource.eval { RoutingTableBootstrap(routingTable, dhtNode.client) }
      val peerDiscovery = !PeerDiscovery.make[IO](routingTable, dhtNode.client)
      val httpTrackerClient = !BlazeClientBuilder[IO].resource.map(httpClient =>
        TrackerClient.http(httpClient)
      )
      val udpTrackerClient = !summon[DatagramSocketGroup[IO]].openDatagramSocket().flatMap(socket =>
        TrackerClient.udp(selfId, socket)
      )
      val trackerClient = TrackerClient.dispatching(httpTrackerClient, udpTrackerClient)
      val metadataRegistry = !Resource.eval { MetadataRegistry[IO]() }
      val createServerTorrent = new ServerTorrent.Create(
        infoHash => peerInfo => Connection.connect[IO](selfId, peerInfo, infoHash),
        peerDiscovery,
        trackerClient,
        metadataRegistry
      )
      val torrentRegistry = !Resource.eval { TorrentRegistry() }
      val torrentIndex = !TorrentIndex()
      (torrentRegistry, createServerTorrent, torrentIndex, metadataRegistry)
    }


  type HttpWebSocketApp = WebSocketBuilder[IO] => HttpApp[IO]

  def makeApp(using Logger[IO]): Resource[IO, HttpWebSocketApp] = async[Resource[IO, _]]{
    import org.http4s.dsl.io.*
    val (torrentRegistry, createServerTorrent, torrentIndex, metadataRegistry) = !resources

    def handleSocket(wsBuilder: WebSocketBuilder[IO]) =
      val makeTorrent: SocketSession.MakeTorrent =
        (infoHash, trackers) =>
          torrentRegistry.getOrCreate(infoHash)(createServerTorrent(infoHash, trackers))
      SocketSession(makeTorrent, metadataRegistry, torrentIndex, wsBuilder)

    def handleGetTorrent(infoHash: InfoHash) =
      torrentRegistry
        .get(infoHash)
        .use { torrent =>
          val metadata = torrent.metadata
          val torrentFile = TorrentFile(metadata, None)
          val bcode =
            summon[BencodeFormat[TorrentFile]]
              .write(torrentFile)
              .toOption
              .get
          val filename = metadata.parsed.files match {
            case file :: Nil if file.path.nonEmpty => file.path.last
            case _ => infoHash.bytes.toHex
          }
          val bytes = encode(bcode)
          OptionT.liftF(
            Ok(
              bytes.toByteArray,
              `Content-Disposition`("inline", Map(ci"filename" -> s"$filename.torrent"))
            )
          )
        }
        .getOrElseF {
          NotFound("Torrent not found")
        }

    def handleGetData(infoHash: InfoHash, fileIndex: FileIndex, rangeOpt: Option[Range]) =
      torrentRegistry.get(infoHash).allocated.value.flatMap {
        case Some((torrent: ServerTorrent, release)) =>
          if (torrent.files.value.lift(fileIndex).isDefined) {
            val file = torrent.metadata.parsed.files(fileIndex)
            val extension = file.path.lastOption.map(_.reverse.takeWhile(_ != '.').reverse)
            val fileMapping = torrent.files
            val parallelPieces = scala.math.max(maxPrefetchBytes / torrent.metadata.parsed.pieceLength, 2).toInt
            def dataStream(span: FileMapping.Span) = {
              Stream
                .emits(span.beginIndex to span.endIndex)
                .covary[IO]
                .parEvalMap(parallelPieces) { index =>
                  torrent
                    .piece(index.toInt)
                    .timeoutTo(
                      downloadPieceTimeout,
                      IO.raiseError(PieceDownloadTimeout(index))
                    )
                    .tupleLeft(index)
                }
                .flatMap {
                  case (span.beginIndex, bytes) =>
                    bytes.drop(span.beginOffset)
                  case (span.endIndex, bytes) =>
                    bytes.take(span.endOffset)
                  case (_, bytes) => bytes
                }
                .onFinalize(release.value.void)
            }
            val mediaType =
              extension.flatMap(MediaType.forExtension).getOrElse(MediaType.application.`octet-stream`)
            val span0 = fileMapping.value(fileIndex)
            rangeOpt match {
              case Some(range) =>
                val first = range.ranges.head.first
                val second = range.ranges.head.second
                val advanced = span0.advance(first)
                val span = second.fold(advanced) { second =>
                  advanced.take(second - first)
                }
                val subRange = rangeOpt match {
                  case Some(range) =>
                    val first = range.ranges.head.first
                    val second = range.ranges.head.second.getOrElse(file.length - 1)
                    Range.SubRange(first, second)
                  case None =>
                    Range.SubRange(0L, file.length - 1)
                }
                PartialContent(
                  dataStream(span),
                  `Content-Type`(mediaType),
                  `Accept-Ranges`.bytes,
                  `Content-Range`(subRange, file.length.some)
                )
              case None =>
                val filename = file.path.lastOption.getOrElse(s"file-$fileIndex")
                Ok(
                  dataStream(span0),
                  `Accept-Ranges`.bytes,
                  `Content-Type`(mediaType),
                  `Content-Disposition`("inline", Map(ci"filename" -> filename)),
                  `Content-Length`.unsafeFromLong(file.length)
                )
            }
          }
          else {
            NotFound(s"Torrent does not contain file with index $fileIndex")
          }
        case None => NotFound("Torrent not found")
      }

    webSocketBuilder =>
      Routes.routes(
        handleSocket(webSocketBuilder),
        handleGetTorrent,
        handleGetData
      )
  }

  def serve(bindPort: Int, app: HttpWebSocketApp): IO[ExitCode] =
    BlazeServerBuilder[IO]
      .withHttpWebSocketApp(app)
      .bindHttp(bindPort, "0.0.0.0")
      .enableHttp2(true)
      .withoutBanner
      .serve
      .compile
      .lastOrError

  case class PieceDownloadTimeout(index: Long) extends Throwable(s"Timeout downloading piece $index")

  def mkLogger: IO[Logger[IO]] = async[IO] {
    import org.legogroup.woof.{*, given}
    import org.legogroup.woof.slf4j.*
    given Filter = Filter.atLeastLevel(LogLevel.Info)
    given Printer = JsonPrinter()
    val logger = !DefaultLogger.makeIo(Output.fromConsole[IO])
    !logger.registerSlf4j
    logger
  }
}

object Routes {
  val dsl = org.http4s.dsl.io

  def routes(
    handleSocket: IO[Response[IO]],
    handleGetTorrent: InfoHash => IO[Response[IO]],
    handleGetData: (InfoHash, FileIndex, Option[Range]) => IO[Response[IO]],
  ): HttpApp[IO] = {
    import dsl.*
    Kleisli {
      case GET -> Root => Ok("Success")
      case GET -> Root / "ws" => handleSocket
      case GET -> Root / "torrent" / InfoHash.fromString(infoHash) / "metadata" =>
        handleGetTorrent(infoHash)
      case req @ GET -> Root / "torrent" / InfoHash.fromString(infoHash) / "data" / FileIndexVar(index) =>
        handleGetData(infoHash, index, req.headers.get[Range])
      case _ => NotFound()
    }
  }

  type FileIndex = Int
  val FileIndexVar: PartialFunction[String, FileIndex] = Function.unlift { (in: String) =>
    in.toIntOption.filter(_ >= 0)
  }
}

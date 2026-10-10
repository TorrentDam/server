import cats.arrow.FunctionK
import cats.data.OptionT
import cats.effect.{ExitCode, IO, IOApp, Resource, ResourceIO}
import cats.effect.std.{Dispatcher, Random}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress}
import com.github.torrentdam.bencode.encode
import com.github.torrentdam.bencode.format.BencodeFormat
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.dht.{Node, NodeId, PeerDiscovery, QueryHandler, RoutingTable, RoutingTableBootstrap}
import com.github.torrentdam.bittorrent.wire.Connection
import com.github.torrentdam.bittorrent.{FileMapping, PeerId, TorrentFile}
import fs2.Stream
import org.legogroup.woof.{Logger, given}
import org.legogroup.woof.slf4j.registerSlf4j
import sun.misc.Signal
import zio.http.*
import zio.http.codec.PathCodec.{int, string}
import zio.interop.fromEffect
import zio.interop.ToEffectSyntax
import zio.interop.catz.implicits.rts
import zio.stream.interop.fs2z.*
import zio.{Chunk, RIO, ZIO}
import cps.*
import cps.syntax.*
import cps.monads.catsEffect.{*, given}

import scala.concurrent.duration.*

object Main extends IOApp {

  def downloadPieceTimeout: FiniteDuration = 3.minutes
  def maxPrefetchBytes = 50 * 1000 * 1000

  def run(args: List[String]): IO[ExitCode] = asyncScope[IO]{
    val dhtBootstrapNode = args match {
      case addressString :: _ =>
        val address = !SocketAddress
          .fromString(addressString)
          .liftTo[ResourceIO](new IllegalArgumentException("Invalid host"))
        Some(address)
      case _ => None
    }
    given logger: Logger[IO] = !mkLogger
    given Dispatcher[IO] = !Dispatcher.sequential[IO]
    !logger.registerSlf4j
    !registerSignalHandler
    val routes = !makeApp(dhtBootstrapNode)
    val bindPort = Option(System.getenv("PORT")).flatMap(_.toIntOption).getOrElse(9999)
    !serve(bindPort, routes)
    ExitCode.Success
  }

  def registerSignalHandler: IO[Unit] =
    IO {
      Signal.handle(new Signal("INT"), _ => System.exit(0))
    }

  // suppress stack trace report to circumvent https://github.com/typelevel/cats-effect/issues/3086
  override def reportFailure(err: Throwable): IO[Unit] = IO.unit

  def resources(
               dhtBootstrapNode: Option[SocketAddress[Host]]
               )(
     using Logger[IO]
   ): Resource[IO, (TorrentRegistry, ServerTorrent.Create, TorrentIndex, MetadataRegistry[IO])] =
     async[Resource[IO, _]]{
       given Random[IO] = !Resource.eval { Random.scalaUtilRandom[IO] }
       val selfId = !Resource.eval { PeerId.generate }
       val dhtNode = !Node(port = none, bootstrapNodeAddress = dhtBootstrapNode)
       val peerDiscovery = dhtNode.discovery
       val metadataRegistry = !Resource.eval { MetadataRegistry[IO]() }
       val createServerTorrent = new ServerTorrent.Create(
         infoHash => peerInfo => Connection.connect(selfId, peerInfo, infoHash),
         peerDiscovery,
         metadataRegistry
       )
       val torrentRegistry = !Resource.eval { TorrentRegistry() }
       val torrentIndex = !TorrentIndex()
       (torrentRegistry, createServerTorrent, torrentIndex, metadataRegistry)
     }

  type FileIndex = Int

  def ioToRIO(using Dispatcher[IO]): FunctionK[IO, RIO[Any, _]] =
    new FunctionK[IO, RIO[Any, _]] {
      def apply[A](fa: IO[A]): RIO[Any, A] = fromEffect(fa)
    }

  def makeApp(
    dhtBootstrapNode: Option[SocketAddress[Host]]
             )(using Logger[IO], Dispatcher[IO]): Resource[IO, Routes[Any, Response]] =
    resources(dhtBootstrapNode).map { (torrentRegistry, createServerTorrent, torrentIndex, metadataRegistry) =>
      val makeTorrent: SocketSession.MakeTorrent =
        (infoHash, trackers) =>
          torrentRegistry.getOrCreate(infoHash)(createServerTorrent(infoHash, trackers))

      val handleSocket = SocketSession(makeTorrent, metadataRegistry, torrentIndex)

      def handleGetTorrent(infoHash: InfoHash): ZIO[Any, Nothing, Response] =
        fromEffect {
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
              OptionT.liftF(IO.pure((s"$filename.torrent", encode(bcode).toByteArray)))
            }
            .value
        }
        .map {
          case Some((filename, bytes)) =>
            Response(
              status = Status.Ok,
              headers = Headers(
                Header.Custom("content-disposition", s"""inline; filename="$filename"""")
              ),
              body = Body.fromChunk(Chunk.fromArray(bytes)),
            )
          case None =>
            Response.error(Status.NotFound, "Torrent not found")
        }
        .catchAll(_ => ZIO.succeed(Response.status(Status.InternalServerError)))

      def handleGetData(
        infoHash: InfoHash,
        fileIndex: FileIndex,
        rangeOpt: Option[Header.Range],
      ): ZIO[Any, Nothing, Response] =
        fromEffect { torrentRegistry.get(infoHash).allocated.value }
          .catchAll(_ => ZIO.succeed(None: Option[(ServerTorrent, TorrentRegistry.Optional[Unit])]))
          .flatMap {
            case Some((torrent: ServerTorrent, release)) =>
              if torrent.files.value.lift(fileIndex).isDefined then
                ZIO.succeed {
                  val file = torrent.metadata.parsed.files(fileIndex)
                  val extension = file.path.lastOption.map(_.reverse.takeWhile(_ != '.').reverse)
                  val fileMapping = torrent.files
                  val parallelPieces = scala.math.max(maxPrefetchBytes / torrent.metadata.parsed.pieceLength, 2).toInt
                  def downloadPiece(index: Long) =
                    torrent
                      .piece(index.toInt)
                      .timeoutTo(
                        downloadPieceTimeout,
                        IO.raiseError(PieceDownloadTimeout(index))
                      )
                      .tupleLeft(index)
                  def dataStream(span: FileMapping.FileSpan) =
                    (
                      Stream
                        .emits(span.beginIndex to span.endIndex)
                        .covary[IO]
                        .parEvalMap(parallelPieces)(downloadPiece)
                    )
                    .flatMap {
                      case (span.beginIndex, bytes) =>
                        bytes.drop(span.beginOffset)
                      case (span.endIndex, bytes) =>
                        bytes.take(span.endOffset)
                      case (_, bytes) => bytes
                    }
                    .onFinalize(release.value.void)

                  def responseBody(stream: Stream[IO, Byte], contentLength: Long) =
                    Body.fromStream(stream.translate(ioToRIO).toZStream(), contentLength)

                  val mediaType =
                    extension
                      .flatMap(MediaType.forFileExtension)
                      .getOrElse(MediaType.application.`octet-stream`)
                  val span0 = fileMapping.value(fileIndex)
                  val rangeBounds: Option[(Long, Option[Long])] =
                    rangeOpt.flatMap {
                      case Header.Range.Single("bytes", first, second) =>
                        Some((first, second))
                      case Header.Range.Prefix("bytes", first) =>
                        Some((first, None))
                      case _ => None
                    }
                  rangeBounds match
                    case Some((first, second)) =>
                      val advanced = span0.advance(fileMapping.pieceLength, first)
                      val span = second.fold(advanced) { second =>
                        advanced.take(fileMapping.pieceLength, second - first)
                      }
                      val subRangeFirst = first
                      val subRangeLast = second.getOrElse(file.length - 1)
                      Response(
                        status = Status.PartialContent,
                        headers = Headers(
                          Header.AcceptRanges.Bytes,
                          Header.ContentType(mediaType),
                          Header.Custom("content-range", s"bytes $subRangeFirst-$subRangeLast/${file.length}"),
                        ),
                        body = responseBody(dataStream(span), file.length - subRangeFirst),
                      )
                    case None =>
                      val filename = file.path.lastOption.getOrElse(s"file-$fileIndex")
                      Response(
                        status = Status.Ok,
                        headers = Headers(
                          Header.AcceptRanges.Bytes,
                          Header.ContentType(mediaType),
                          Header.Custom("content-disposition", s"""inline; filename="$filename""""),
                        ),
                        body = responseBody(dataStream(span0), file.length),
                      )
                }
              else
                ZIO.succeed(
                  Response.error(Status.NotFound, s"Torrent does not contain file with index $fileIndex")
                )
            case None =>
              ZIO.succeed(Response.error(Status.NotFound, "Torrent not found"))
          }

      Routes(
        Method.GET / "" -> handler(Response.text("Success")),
        Method.GET / "ws" -> handler(handleSocket.toResponse),
        Method.GET / "torrent" / string("infoHash") / "metadata" -> handler {
          (infoHash: String, _: Request) =>
            InfoHash.fromString.lift(infoHash) match
              case Some(infoHash) =>
                handleGetTorrent(infoHash)
              case None =>
                ZIO.succeed(Response.error(Status.NotFound, "Torrent not found"))
        },
        Method.GET / "torrent" / string("infoHash") / "data" / int("fileIndex") -> handler {
          (infoHash: String, fileIndex: Int, req: Request) =>
            val validFileIndex = Option.when(fileIndex >= 0)(fileIndex)
            (InfoHash.fromString.lift(infoHash), validFileIndex) match
              case (Some(infoHash), Some(fileIndex)) =>
                handleGetData(infoHash, fileIndex, req.header(Header.Range))
              case _ =>
                ZIO.succeed(Response.error(Status.NotFound, "Torrent not found"))
        },
      )
    }

  def serve(bindPort: Int, routes: Routes[Any, Response])(using Dispatcher[IO]): IO[Nothing] =
    Server
      .serve(routes)
      .provide(Server.defaultWithPort(bindPort))
      .toEffect[IO]

  case class PieceDownloadTimeout(index: Long) extends Throwable(s"Timeout downloading piece $index")

  def mkLogger: IO[Logger[IO]] = asyncScope[IO] {
    import org.legogroup.woof.{*, given}
    import org.legogroup.woof.slf4j.*
    given Filter = Filter.atLeastLevel(LogLevel.Info)
    given Printer = JsonPrinter()
    !DefaultLogger.makeIo(Output.fromConsole[IO])
  }

  extension (self: FileMapping.FileSpan) {
    def advance(pieceLength: Long, begin: Long): FileMapping.FileSpan =
      val spansPieces = begin / pieceLength
      val remainder = begin % pieceLength
      val index = self.beginIndex + spansPieces + (self.beginOffset + remainder) / pieceLength
      val offset = (self.beginOffset + remainder) % pieceLength
      self.copy(beginIndex = index, beginOffset = offset)

    def take(pieceLength: Long, bytes: Long): FileMapping.FileSpan =
      val s0 = advance(pieceLength, bytes)
      self.copy(endIndex = s0.beginIndex, endOffset = self.beginOffset)
  }
}

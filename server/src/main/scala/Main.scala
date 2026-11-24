import cats.data.OptionT
import cats.effect.std.Dispatcher
import cats.effect.std.Random
import cats.effect.syntax.all.*
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.comcast.ip4s.Host
import com.comcast.ip4s.SocketAddress
import com.github.torrentdam.bencode.encode
import com.github.torrentdam.bencode.format.BencodeFormat
import com.github.torrentdam.bittorrent.dht.Node
import com.github.torrentdam.bittorrent.wire.Connection
import com.github.torrentdam.bittorrent.FileMapping
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.PeerId
import com.github.torrentdam.bittorrent.TorrentFile
import org.legogroup.woof.given
import org.legogroup.woof.slf4j.registerSlf4j
import org.legogroup.woof.Logger
import scala.concurrent.duration.*
import sun.misc.Signal
import zio.durationInt
import zio.http.*
import zio.http.codec.PathCodec.int
import zio.http.codec.PathCodec.string
import zio.interop.fromEffect
import zio.stream.ZStream
import zio.Chunk
import zio.Duration as ZDuration
import zio.Scope
import zio.Task
import zio.ZIO
import zio.ZIOAppArgs
import zio.ZIOAppDefault

object Main extends ZIOAppDefault {

  def downloadPieceTimeout: ZDuration = 3.minutes
  def maxPrefetchBytes = 50 * 1000 * 1000

  def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] =
    for
      args <- getArgs.map(_.toList)
      (dhtEnabled, dhtBootstrapNode) = parseArgs(args)
      dispatcher <- Interop.acquireDispatcher
      given Dispatcher[IO] = dispatcher
      logger <- fromEffect(mkLogger)
      given Logger[IO] = logger
      _ <- fromEffect(logger.registerSlf4j)
      _ <- registerSignalHandler
      bindPort = Option(System.getenv("PORT")).flatMap(_.toIntOption).getOrElse(9999)
      routes <- makeRoutes(dhtEnabled, dhtBootstrapNode)
      _ <- Server.serve(routes).provide(Server.defaultWithPort(bindPort))
    yield ()

  def parseArgs(args: List[String]): (Boolean, Option[SocketAddress[Host]]) =
    val argv = args.filter(_ != "--")
    argv match
      case "--no-dht" :: rest =>
        (false, rest.headOption.flatMap(SocketAddress.fromString(_)))
      case first :: rest =>
        (true, SocketAddress.fromString(first).orElse(rest.headOption.flatMap(SocketAddress.fromString(_))))
      case Nil =>
        (true, None)

  def parseBootstrapNode(addressString: Option[String]): ZIO[Any, Throwable, Option[SocketAddress[Host]]] =
    addressString match
      case Some(value) =>
        ZIO
          .fromOption(SocketAddress.fromString(value))
          .mapError(_ => new IllegalArgumentException("Invalid host"))
          .map(Some(_))
      case None =>
        ZIO.succeed(None)

  def registerSignalHandler: ZIO[Any, Nothing, Unit] =
    ZIO.succeed {
      Signal.handle(new Signal("INT"), _ => System.exit(0))
    }

  def resources(
    dhtEnabled: Boolean,
    dhtBootstrapNode: Option[SocketAddress[Host]]
  )(using
    Dispatcher[IO],
    Logger[IO]
  ): ZIO[Scope, Throwable, (TorrentRegistry, ServerTorrent.Create, TorrentIndex, MetadataRegistry)] =
    for
      _ <- fromEffect(Random.scalaUtilRandom[IO])
      selfId <- fromEffect(PeerId.generate)
      peerDiscovery <-
        if dhtEnabled then
          Interop.acquireCats(Node(port = none, bootstrapNodeAddress = dhtBootstrapNode)).map(_.discovery)
        else ZIO.succeed(emptyPeerDiscovery)
      metadataRegistry <- MetadataRegistry()
      createServerTorrent = new ServerTorrent.Create(
        infoHash => peerInfo => Connection.connect(selfId, peerInfo, infoHash),
        peerDiscovery,
        metadataRegistry
      )
      torrentRegistry <- TorrentRegistry()
      torrentIndex <- TorrentIndex()
    yield (torrentRegistry, createServerTorrent, torrentIndex, metadataRegistry)

  def emptyPeerDiscovery: com.github.torrentdam.bittorrent.dht.PeerDiscovery =
    new com.github.torrentdam.bittorrent.dht.PeerDiscovery {
      def discover(infoHash: InfoHash): fs2.Stream[cats.effect.IO, com.github.torrentdam.bittorrent.PeerInfo] =
        fs2.Stream.empty
      def findNodes(
        nodeId: com.github.torrentdam.bittorrent.dht.NodeId
      ): fs2.Stream[cats.effect.IO, com.github.torrentdam.bittorrent.dht.NodeInfo] =
        fs2.Stream.empty
    }

  type FileIndex = Int

  def makeRoutes(
    dhtEnabled: Boolean,
    dhtBootstrapNode: Option[SocketAddress[Host]]
  )(using Dispatcher[IO], Logger[IO]): ZIO[Scope, Throwable, Routes[Any, Response]] =
    for
      (torrentRegistry, createServerTorrent, torrentIndex, metadataRegistry) <- resources(dhtEnabled, dhtBootstrapNode)
      makeTorrent: SocketSession.MakeTorrent =
        (infoHash, trackers) =>
          ZIO.scoped {
            torrentRegistry.getOrCreate(infoHash)(createServerTorrent(infoHash, trackers)).flatten
          }
      handleSocket = SocketSession(makeTorrent, metadataRegistry, torrentIndex)

      handleGetTorrent = (infoHash: InfoHash) =>
        ZIO
          .scoped(torrentRegistry.get(infoHash))
          .flatMap {
            case Some(torrent) =>
              ZIO.succeed {
                val metadata = torrent.metadata
                val torrentFile = TorrentFile(metadata, None)
                val bcode = summon[BencodeFormat[TorrentFile]].write(torrentFile).toOption.get
                val filename = metadata.parsed.files match
                  case file :: Nil if file.path.nonEmpty => file.path.last
                  case _                                 => infoHash.bytes.toHex
                Response(
                  status = Status.Ok,
                  headers = Headers(Header.Custom("content-disposition", s"""inline; filename="$filename.torrent"""")),
                  body = Body.fromChunk(Chunk.fromArray(encode(bcode).toByteArray))
                )
              }
            case None =>
              ZIO.succeed(Response.error(Status.NotFound, "Torrent not found"))
          }
          .catchAll(_ => ZIO.succeed(Response.status(Status.InternalServerError)))

      handleGetData = (infoHash: InfoHash, fileIndex: FileIndex, rangeOpt: Option[Header.Range]) =>
        ZIO
          .scoped {
            torrentRegistry.get(infoHash).flatMap {
              case Some(torrent) =>
                ZIO.succeed {
                  val file = torrent.metadata.parsed.files(fileIndex)
                  val extension = file.path.lastOption.map(_.reverse.takeWhile(_ != '.').reverse)
                  val fileMapping = torrent.files
                  val mediaType =
                    extension.flatMap(MediaType.forFileExtension).getOrElse(MediaType.application.`octet-stream`)
                  val span0 = fileMapping.value(fileIndex)
                  val rangeBounds: Option[(Long, Option[Long])] =
                    rangeOpt.flatMap {
                      case Header.Range.Single("bytes", first, second) => Some((first, second))
                      case Header.Range.Prefix("bytes", first)         => Some((first, None))
                      case _                                           => None
                    }
                  rangeBounds match
                    case Some((first, second)) =>
                      val advanced = span0.advance(fileMapping.pieceLength, first)
                      val span = second.fold(advanced)(second => advanced.take(fileMapping.pieceLength, second - first))
                      val subRangeFirst = first
                      val subRangeLast = second.getOrElse(file.length - 1)
                      Response(
                        status = Status.PartialContent,
                        headers = Headers(
                          Header.AcceptRanges.Bytes,
                          Header.ContentType(mediaType),
                          Header.Custom("content-range", s"bytes $subRangeFirst-$subRangeLast/${file.length}")
                        ),
                        body = responseBody(torrent, span, file.length - subRangeFirst)
                      )
                    case None =>
                      val filename = file.path.lastOption.getOrElse(s"file-$fileIndex")
                      Response(
                        status = Status.Ok,
                        headers = Headers(
                          Header.AcceptRanges.Bytes,
                          Header.ContentType(mediaType),
                          Header.Custom("content-disposition", s"""inline; filename="$filename"""")
                        ),
                        body = responseBody(torrent, span0, file.length)
                      )
                }
              case None =>
                ZIO.succeed(Response.error(Status.NotFound, "Torrent not found"))
            }
          }
          .catchAll(_ => ZIO.succeed(Response.status(Status.InternalServerError)))
    yield Routes(
      Method.GET / "" -> handler(Response.text("Success")),
      Method.GET / "ws" -> handler(handleSocket.toResponse),
      Method.GET / "torrent" / string("infoHash") / "metadata" -> handler { (infoHash: String, _: Request) =>
        InfoHash.fromString.lift(infoHash) match
          case Some(infoHash) => handleGetTorrent(infoHash)
          case None           => ZIO.succeed(Response.error(Status.NotFound, "Torrent not found"))
      },
      Method.GET / "torrent" / string("infoHash") / "data" / int("fileIndex") -> handler {
        (infoHash: String, fileIndex: Int, req: Request) =>
          val validFileIndex = Option.when(fileIndex >= 0)(fileIndex)
          (InfoHash.fromString.lift(infoHash), validFileIndex) match
            case (Some(infoHash), Some(fileIndex)) =>
              handleGetData(infoHash, fileIndex, req.header(Header.Range))
            case _ =>
              ZIO.succeed(Response.error(Status.NotFound, "Torrent not found"))
      }
    )

  def responseBody(torrent: ServerTorrent, span: FileMapping.FileSpan, contentLength: Long): Body =
    val parallelPieces =
      scala.math.max(maxPrefetchBytes / torrent.metadata.parsed.pieceLength, 2).toInt
    val dataStream =
      ZStream
        .fromIterable(span.beginIndex to span.endIndex)
        .mapZIOPar(parallelPieces)(index =>
          torrent
            .piece(index.toInt)
            .flatMap(_.runFold(Chunk.empty[Byte])((acc, b) => acc :+ b))
            .map((index.toLong, _))
        )
        .flatMap { case (index, bytes: Chunk[Byte]) =>
          if index == span.beginIndex then ZStream.fromChunk(bytes.drop(span.beginOffset.toInt))
          else if index == span.endIndex then ZStream.fromChunk(bytes.take(span.endOffset.toInt))
          else ZStream.fromChunk(bytes)
        }
    Body.fromStream(dataStream, contentLength)

  def mkLogger: IO[Logger[IO]] =
    import org.legogroup.woof.{Filter, JsonPrinter, LogLevel, Output, DefaultLogger, Printer, given}
    given Filter = Filter.atLeastLevel(LogLevel.Info)
    given Printer = JsonPrinter()
    DefaultLogger.makeIo(Output.fromConsole[IO])

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

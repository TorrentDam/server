import java.nio.file.Paths
import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import com.github.torrentdam.bittorrent.{FileMapping, InfoHash, PeerInfo}
import com.github.torrentdam.bittorrent.wire.{Connection, DownloadMetadata, Swarm, Torrent}
import com.github.torrentdam.bittorrent.TorrentMetadata.Lossless
import org.legogroup.woof.{Logger, given}
import zio.stream.ZStream
import zio.stream.interop.fs2z.*
import zio.interop.fromEffect
import zio.{Chunk, Promise, Scope, Task, UIO, ZIO}

trait ServerTorrent {
  def files: FileMapping
  def stats: Task[ServerTorrent.Stats]
  def piece(index: Int): Task[ZStream[Any, Throwable, Byte]]
  def metadata: Lossless
}

object ServerTorrent {

  sealed trait Phase
  object Phase {
    case class PeerDiscovery(done: Task[FetchingMetadata]) extends Phase
    case class FetchingMetadata(fromPeers: ZStream[Any, Nothing, Int], done: Task[Ready]) extends Phase
    case class Ready(infoHash: InfoHash, serverTorrent: ServerTorrent) extends Phase
  }

  case class Error() extends Throwable

  private def create(
    torrentStats: Task[Torrent.Stats],
    downloadPiece: Int => Task[Chunk[Byte]],
    selfMetadata: Lossless
  ): UIO[ServerTorrent] =
    for
      multiplexer <- Multiplexer(downloadPiece)
    yield new ServerTorrent {
      def files: FileMapping = FileMapping.fromMetadata(metadata.parsed)
      def stats: Task[Stats] =
        torrentStats.map { stats =>
          Stats(
            connected = stats.connected,
            availability = files.value.map { span =>
              val range = span.beginIndex.toInt to span.endIndex.toInt
              val available = range.count(stats.availability.contains)
              available.toDouble / range.size
            }
          )
        }
      def piece(index: Int): Task[ZStream[Any, Throwable, Byte]] = multiplexer.get(index)
      def metadata: Lossless = selfMetadata
    }

  class Create(
    connect: InfoHash => PeerInfo => cats.effect.Resource[IO, Connection],
    peerDiscovery: com.github.torrentdam.bittorrent.dht.PeerDiscovery,
    metadataRegistry: MetadataRegistry
  )(using
    Dispatcher[IO],
    Logger[IO]
  ) {
    def apply(infoHash: InfoHash, trackers: List[String]): ZIO[Scope, Throwable, Phase.PeerDiscovery] =
      for
        peerDiscoveryDone <- Promise.make[Throwable, Phase.FetchingMetadata]
        fiber <- createInPhases(peerDiscoveryDone, infoHash).forkDaemon
      yield Phase.PeerDiscovery(peerDiscoveryDone.await)

    private def createInPhases(
      peerDiscoveryOutcome: Promise[Throwable, Phase.FetchingMetadata],
      infoHash: InfoHash
    ): ZIO[Any, Throwable, Unit] =
      ZIO.scoped {
        for
          swarm <- Interop.acquireCats {
            Swarm(
              peerDiscovery.discover(infoHash).covary[IO],
              connect(infoHash)
            )
          }
          fetchingMetadataDone <- Promise.make[Throwable, Phase.Ready]
          countSignal: fs2.concurrent.Signal[cats.effect.IO, Int] = swarm.connected.count
          fromPeersZ = countSignal.continuous.translate(Interop.ioToRIO).toZStream()
          _ <- peerDiscoveryOutcome.succeed(
            Phase.FetchingMetadata(fromPeersZ.catchAll(_ => ZStream.empty), fetchingMetadataDone.await)
          )
          metadata <- metadataRegistry.get(infoHash).flatMap {
            case Some(value) => ZIO.succeed(value)
            case None        => fromEffect(DownloadMetadata(swarm))
          }
          _ <- fromEffect(summon[Logger[IO]].info(s"Metadata downloaded"))
          _ <- metadataRegistry.put(infoHash, metadata)
          torrent <- Interop.acquireCats(Torrent.make(metadata, swarm))
          pieceStore <- PieceStore.disk(Paths.get("/tmp", s"bittorrent-${infoHash.toString}"))
          serverTorrent <- create(
            fromEffect(torrent.stats),
            index => fetchPiece(torrent, pieceStore, index),
            metadata
          )
          _ <- fetchingMetadataDone.succeed(Phase.Ready(infoHash, serverTorrent))
        yield ()
      }

    private def fetchPiece(torrent: Torrent, pieceStore: PieceStore, index: Int): Task[Chunk[Byte]] =
      for
        cached <- pieceStore.get(index)
        result <- cached match
          case Some(bytes) => ZIO.succeed(bytes)
          case None =>
            fromEffect(torrent.downloadPiece(index.toLong)).flatMap { vector =>
              pieceStore.put(index, vector).map(_ => Chunk.fromArray(vector.toArray))
            }
      yield result
  }

  case class Stats(
    connected: Int,
    availability: List[Double]
  )
}

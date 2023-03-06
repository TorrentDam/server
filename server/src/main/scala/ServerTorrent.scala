import java.nio.file.Paths
import ServerTorrent.Phase.FetchingMetadata
import cats.syntax.all.*
import cats.effect.kernel.{Deferred, Resource}
import cats.effect.{Concurrent, IO, Resource}
import com.github.lavrov.bittorrent.{FileMapping, InfoHash, MagnetLink, PeerInfo}
import com.github.lavrov.bittorrent.wire.{Connection, DownloadMetadata, Swarm, Torrent}
import com.github.lavrov.bittorrent.TorrentMetadata.Lossless
import com.github.lavrov.bittorrent.dht.PeerDiscovery
import com.github.torrentdam.tracker.Client as TrackerClient
import fs2.Stream
import fs2.concurrent.Signal
import org.http4s.Uri
import org.legogroup.woof.{Logger, given}

trait ServerTorrent {
  def files: FileMapping
  def stats: IO[ServerTorrent.Stats]
  def piece(index: Int): IO[Stream[IO, Byte]]
  def metadata: Lossless
}

object ServerTorrent {

  sealed trait Phase
  object Phase {
    case class PeerDiscovery(done: IO[FetchingMetadata]) extends Phase
    case class FetchingMetadata(fromPeers: Signal[IO, Int], done: IO[Ready]) extends Phase
    case class Ready(infoHash: InfoHash, serverTorrent: ServerTorrent) extends Phase
  }

  case class Error() extends Throwable

  private def create(torrent: Torrent, pieceStore: PieceStore[IO])(using Logger[IO]): IO[ServerTorrent] = {

    def fetch(index: Int): IO[Stream[IO, Byte]] = {
      for {
        bytes <- pieceStore.get(index)
        bytes <- bytes match {
          case Some(bytes) => IO.pure(bytes)
          case None =>
            for {
              bytes <- torrent.downloadPiece(index)
              bytes <- pieceStore.put(index, bytes)
            } yield bytes
        }
      } yield bytes
    }

    for {
      multiplexer <- Multiplexer(fetch)
    } yield {
      new ServerTorrent {
        def files: FileMapping = FileMapping.fromMetadata(torrent.metadata.parsed)
        def stats: IO[Stats] =
          for {
            stats <- torrent.stats
          } yield Stats(
            connected = stats.connected,
            availability = files.value.map { span =>
              val range = span.beginIndex.toInt to span.endIndex.toInt
              val available = range.count(stats.availability.contains)
              available.toDouble / range.size
            }
          )
        def piece(index: Int): IO[Stream[IO, Byte]] = multiplexer.get(index)
        def metadata: Lossless = torrent.metadata
      }
    }
  }

  class Create(
    connect: InfoHash => PeerInfo => Resource[IO, Connection],
    peerDiscovery: PeerDiscovery,
    trackerClient: TrackerClient,
    metadataRegistry: MetadataRegistry[IO]
  )(
    using
    logger: Logger[IO],
  ) {

    def apply(infoHash: InfoHash, trackers: List[String]): Resource[IO, Phase.PeerDiscovery] =

      val trackerPeers: Stream[IO, PeerInfo] =
        for
          announceUri <- Stream.emits(
            trackers.mapFilter(uri => Uri.fromString(uri).toOption)
          )
          result <- Stream.evalSeq(
            trackerClient
              .get(announceUri, infoHash).map {
                case TrackerClient.Response.Success(peers) => peers
                case _ => Nil
              }
              .flatTap(peers =>
                logger.info(s"Received ${peers.size} from $announceUri")
              )
              .handleErrorWith(e =>
                logger.info(s"Could not get peers from $announceUri").as(Nil)
              )
          )
        yield result

      def createInPhases(peerDiscoveryOutcome: FallibleDeferred[IO, Phase.FetchingMetadata]): Resource[IO, ServerTorrent] =
        for
          swarm <- Swarm(
            trackerPeers merge peerDiscovery.discover(infoHash),
            connect(infoHash),
          )
          fetchingMetadataDone <- Resource.eval(FallibleDeferred[IO, Phase.Ready])
          _ <- Resource.eval(
            peerDiscoveryOutcome.complete(FetchingMetadata(swarm.connected.count, fetchingMetadataDone.get))
          )
          metadata <- Resource.eval(
            metadataRegistry.get(infoHash).flatMap {
              case Some(value) => value.pure[IO]
              case None => DownloadMetadata(swarm).flatTap(metadataRegistry.put(infoHash, _))
            }
          )
          _ <- Resource.eval(logger.info(s"Metadata downloaded"))
          torrent <- Torrent.make(metadata, swarm)
          pieceStore <- PieceStore.disk[IO](Paths.get(s"/tmp", s"bittorrent-${infoHash.toString}"))
          serverTorrent <- Resource.eval(create(torrent, pieceStore))
          _ <- Resource.eval(fetchingMetadataDone.complete(Phase.Ready(infoHash, serverTorrent)))
        yield serverTorrent

      for {
        peerDiscoveryDone <- Resource.eval(FallibleDeferred[IO, Phase.FetchingMetadata])
        _ <- createInPhases(peerDiscoveryDone).useForever.background
      } yield Phase.PeerDiscovery(peerDiscoveryDone.get)

    end apply
  }

  case class Stats(
    connected: Int,
    availability: List[Double]
  )

  trait FallibleDeferred[F[_], A] {
    def complete(a: A): F[Unit]
    def fail(e: Throwable): F[Unit]
    def get: F[A]
  }

  object FallibleDeferred {
    def apply[F[_], A](implicit F: Concurrent[F]): F[FallibleDeferred[F, A]] = {
      for {
        underlying <- Deferred[F, Either[Throwable, A]]
      } yield new FallibleDeferred[F, A] {
        def complete(a: A): F[Unit] = underlying.complete(a.asRight).void
        def fail(e: Throwable): F[Unit] = underlying.complete(e.asLeft).void
        def get: F[A] = underlying.get.flatMap(F.fromEither)
      }
    }
  }

}

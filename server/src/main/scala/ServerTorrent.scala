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
import org.typelevel.log4cats.StructuredLogger

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

  private def create(torrent: Torrent[IO], pieceStore: PieceStore[IO]): IO[ServerTorrent] = {

    def fetch(index: Int): IO[Stream[IO, Byte]] = {
      for {
        bytes <- pieceStore.get(index)
        bytes <- bytes match {
          case Some(bytes) => IO.pure(bytes)
          case None =>
            for {
              bytes <- torrent.piece(index)
              bytes <- pieceStore.put(index, bytes)
            } yield bytes
        }
      } yield bytes
    }

    for {
      multiplexer <- Multiplexer[IO](fetch)
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
    connect: InfoHash => PeerInfo => Resource[IO, Connection[IO]],
    peerDiscovery: PeerDiscovery[IO],
    trackerClient: TrackerClient,
    metadataRegistry: MetadataRegistry[IO]
  )(
    implicit
    logger: StructuredLogger[IO],
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
              .handleErrorWith(e =>
                logger.info(s"Could not get peers from $announceUri").as(Nil)
              )
          )
        yield result
      Resource {
        def backgroundTask(peerDiscoveryDone: FallibleDeferred[IO, Phase.FetchingMetadata]): IO[Unit] = {
          Swarm(
            trackerPeers ++ peerDiscovery.discover(infoHash),
            connect(infoHash),
            30
          )
            .use { swarm =>
              val getMetadata = metadataRegistry.get(infoHash).flatMap {
                case Some(value) => value.pure[IO]
                case None => DownloadMetadata(swarm.connected.stream).flatTap(metadataRegistry.put(infoHash, _))
              }
              swarm.connected.count.discrete.find(_ > 0).compile.drain >>
                FallibleDeferred[IO, Phase.Ready].flatMap { fetchingMetadataDone =>
                  peerDiscoveryDone.complete(FetchingMetadata(swarm.connected.count, fetchingMetadataDone.get)).flatMap {
                    _ =>
                      getMetadata.flatMap { metadata =>
                        logger.info(s"Metadata downloaded") >>
                          Torrent.make(metadata, swarm).use { torrent =>
                            PieceStore.disk[IO](Paths.get(s"/tmp", s"bittorrent-${infoHash.toString}")).use { pieceStore =>
                              create(torrent, pieceStore).flatMap { serverTorrent =>
                                fetchingMetadataDone.complete(Phase.Ready(infoHash, serverTorrent)).flatMap { _ =>
                                  IO.never
                                }
                              }
                            }
                          }
                      }
                  }
                }
            }
            .orElse(
              peerDiscoveryDone.fail(Error())
            )
        }

        for {
          peerDiscoveryDone <- FallibleDeferred[IO, Phase.FetchingMetadata]
          fiber <- backgroundTask(peerDiscoveryDone).start
        } yield (Phase.PeerDiscovery(peerDiscoveryDone.get), fiber.cancel)
      }

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

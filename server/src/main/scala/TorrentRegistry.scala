import cats.Show.Shown

import java.nio.file.Paths
import cats.data.OptionT
import cats.effect.*
import cats.effect.kernel.{Deferred, Ref}
import cats.implicits.*
import com.github.torrentdam.bittorrent.InfoHash
import org.legogroup.woof.{Logger, given}

import scala.concurrent.duration.*

trait TorrentRegistry {
  import TorrentRegistry.Optional
  def getOrCreate(infoHash: InfoHash)(createTorrent: Resource[IO, ServerTorrent.Phase.PeerDiscovery]): Resource[IO, IO[ServerTorrent.Phase.PeerDiscovery]]
  def get(infoHash: InfoHash): Resource[Optional, ServerTorrent]
}

object TorrentRegistry {

  type Optional[A] = OptionT[IO, A]

  def apply()(implicit logger: Logger[IO]): IO[TorrentRegistry] =
    for {
      ref <- Ref.of[IO, Registry](emptyRegistry)
    } yield new Impl(ref)

  case class UsageCountingCell(
    get: IO[ServerTorrent.Phase.PeerDiscovery],
    ready: Option[ServerTorrent],
    close: IO[Unit],
    count: Int,
    usedCount: Int
  )

  private type Registry = Map[InfoHash, UsageCountingCell]
  private val emptyRegistry: Registry = Map.empty

  private class Impl(ref: Ref[IO, Registry])(implicit
    logger: Logger[IO]
  ) extends TorrentRegistry {
    import Logger.withLogContext

    def getOrCreate(
      infoHash: InfoHash
    )(
      createTorrent: Resource[IO, ServerTorrent.Phase.PeerDiscovery]
    ): Resource[IO, IO[ServerTorrent.Phase.PeerDiscovery]] =
      Resource {
        ref
          .modify { registry =>
            registry.get(infoHash) match {
              case Some(cell) =>
                val updatedCell = cell.copy(count = cell.count + 1, usedCount = cell.usedCount + 1)
                val updatedRegistry = registry.updated(infoHash, updatedCell)
                (updatedRegistry, Right(updatedCell.get))
              case None =>
                val torrentDeferred = Deferred.unsafe[IO, Either[Throwable, ServerTorrent.Phase.PeerDiscovery]]
                val closeDeferred = Deferred.unsafe[IO, Unit]
                val getTorrent = torrentDeferred.get.flatMap(IO.fromEither)
                val closeTorrent = closeDeferred.complete(()).void
                val createdCell = UsageCountingCell(getTorrent, none, closeTorrent, 1, 1)
                val updatedRegistry = registry.updated(infoHash, createdCell)
                (updatedRegistry, Left((createdCell.get, torrentDeferred.complete, closeDeferred.get)))
            }
          }
          .flatMap {
            case Right(get) =>
              logger.debug(s"Found existing torrent") >>
              IO.pure((get, release(infoHash)))
            case Left((get, complete, waitClosed)) =>
              logger.info(s"Make new torrent") >>
              createTorrent
                .use(phase =>
                  complete(phase.asRight) >>
                  cacheReadyPhase(phase).start >>
                  waitClosed
                )
                .handleErrorWith { e =>
                  logger.error(s"Could not create torrent $e") >>
                    complete(e.asLeft)
                }
                .start
                .as((get, release(infoHash)))
          }
          .withLogContext("infoHash", infoHash.toString)
      }

    def get(infoHash: InfoHash): Resource[Optional, ServerTorrent] =
      Resource {
        for {
          torrent <- OptionT(
            ref
              .modify { registry =>
                registry.get(infoHash) match {
                  case Some(cell) if cell.ready.isDefined =>
                    val updatedCell = cell.copy(count = cell.count + 1, usedCount = cell.usedCount + 1)
                    val updatedRegistry = registry.updated(infoHash, updatedCell)
                    (updatedRegistry, cell.ready)
                  case _ =>
                    (registry, none)
                }
              }
          )
          _ <- logger.debug(s"Found existing torrent").to[Optional]
        } yield (torrent, release(infoHash).to[Optional])
      }

    private def cacheReadyPhase(peerDiscovery: ServerTorrent.Phase.PeerDiscovery): IO[Unit] = {
      for {
        fetchingMetadata <- peerDiscovery.done
        ready <- fetchingMetadata.done
        _ <- ref.update { registry =>
          registry.updatedWith(ready.infoHash)(
            _.map(
              _.copy(ready = ready.serverTorrent.some)
            )
          )
        }
      } yield ()
    }

    private def release(infoHash: InfoHash)(implicit logger: Logger[IO]): IO[Unit] =
      logger.debug(s"Release torrent") >>
      ref
        .modify { registry =>
          val cell = registry(infoHash)
          val updatedCell = cell.copy(count = cell.count - 1)
          (registry.updated(infoHash, updatedCell), updatedCell)
        }
        .flatMap { cell =>
          if (cell.count == 0)
            scheduleClose(infoHash, _.usedCount == cell.usedCount)
          else
            logger.debug(s"Torrent is still in use ${cell.count}")
        }

    private def scheduleClose(infoHash: InfoHash, closeIf: UsageCountingCell => Boolean)(implicit
      logger: Logger[IO]
    ): IO[Unit] = {
      val idleTimeout = 30.minutes
      val waitAndTry =
        logger.debug(s"Schedule torrent closure in $idleTimeout") >>
        IO.sleep(idleTimeout) >>
        tryClose(infoHash, closeIf)
      waitAndTry.start.void
    }

    private def tryClose(infoHash: InfoHash, closeIf: UsageCountingCell => Boolean)(implicit
      logger: Logger[IO]
    ): IO[Unit] = {
      ref.modify { registry =>
        registry.get(infoHash) match {
          case Some(cell) if closeIf(cell) =>
            (
              registry.removed(infoHash),
              cell.close >> logger.info(s"Closed torrent")
            )
          case _ =>
            (
              registry,
              IO.unit
            )
        }
      }.flatten
    }
  }
}

import cats.Show.Shown

import java.nio.file.Paths
import cats.data.OptionT
import cats.effect.*
import cats.effect.kernel.{Deferred, Ref}
import cats.implicits.*
import com.github.lavrov.bittorrent.InfoHash
import org.typelevel.log4cats.{Logger, StructuredLogger}

import scala.concurrent.duration.*

trait TorrentRegistry {
  def get(infoHash: InfoHash): Resource[IO, IO[ServerTorrent.Phase.PeerDiscovery]]
  def tryGet(infoHash: InfoHash): Resource[OptionT[IO, _], ServerTorrent]
}

object TorrentRegistry {

  type Optional[A] = OptionT[IO, A]

  def make(
    createTorrent: ServerTorrent.Create
  )(implicit logger: StructuredLogger[IO]): IO[TorrentRegistry] =
    for {
      ref <- Ref.of[IO, Registry](emptyRegistry)
    } yield new Impl(ref, createTorrent)

  case class UsageCountingCell(
    get: IO[ServerTorrent.Phase.PeerDiscovery],
    resolved: Option[ServerTorrent],
    close: IO[Unit],
    count: Int,
    usedCount: Int
  )

  private type Registry = Map[InfoHash, UsageCountingCell]
  private val emptyRegistry: Registry = Map.empty

  private class Impl(ref: Ref[IO, Registry], createTorrent: ServerTorrent.Create)(implicit
    logger: StructuredLogger[IO]
  ) extends TorrentRegistry {

    def get(infoHash: InfoHash): Resource[IO, IO[ServerTorrent.Phase.PeerDiscovery]] =
      Resource {
        implicit val logger: Logger[IO] = loggerWithContext(infoHash)
        ref
          .modify { registry =>
            registry.get(infoHash) match {
              case Some(cell) =>
                val updatedCell = cell.copy(count = cell.count + 1, usedCount = cell.usedCount + 1)
                val updatedRegistry = registry.updated(infoHash, updatedCell)
                (updatedRegistry, Right(updatedCell.get))
              case None =>
                val completeDeferred = Deferred.unsafe[IO, Either[Throwable, ServerTorrent.Phase.PeerDiscovery]]
                val closeDeferred = Deferred.unsafe[IO, Unit]
                val getTorrent = completeDeferred.get.flatMap(IO.fromEither)
                val closeTorrent = closeDeferred.complete(()).void
                val createdCell = UsageCountingCell(getTorrent, none, closeTorrent, 1, 1)
                val updatedRegistry = registry.updated(infoHash, createdCell)
                (updatedRegistry, Left((createdCell.get, completeDeferred.complete, closeDeferred.get)))
            }
          }
          .flatMap {
            case Right(get) =>
              logger.debug(s"Found existing torrent") >>
              IO.pure((get, release(infoHash)))
            case Left((get, complete, cancel)) =>
              logger.info(s"Make new torrent") >>
              make(infoHash, complete(_).void, cancel).as((get, release(infoHash)))
          }
      }

    def tryGet(infoHash: InfoHash): Resource[Optional, ServerTorrent] =
      Resource {
        implicit val logger: Logger[IO] = loggerWithContext(infoHash)
        for {
          torrent <- OptionT(
            ref
              .modify { registry =>
                registry.get(infoHash) match {
                  case Some(cell) if cell.resolved.isDefined =>
                    val updatedCell = cell.copy(count = cell.count + 1, usedCount = cell.usedCount + 1)
                    val updatedRegistry = registry.updated(infoHash, updatedCell)
                    (updatedRegistry, cell.resolved)
                  case _ =>
                    (registry, none)
                }
              }
          )
          _ <- logger.debug(s"Found existing torrent").to[Optional]
        } yield (torrent, release(infoHash).to[Optional])
      }

    private def make(
      infoHash: InfoHash,
      complete: Either[Throwable, ServerTorrent.Phase.PeerDiscovery] => IO[Unit],
      waitCancel: IO[Unit]
    )(implicit logger: Logger[IO]): IO[Unit] = {
      createTorrent(infoHash)
        .use { phase =>
          complete(phase.asRight) >>
          cacheWhenDone(phase).start >>
          waitCancel
        }
        .handleErrorWith { e =>
          logger.error(s"Could not create torrent $e") >>
          complete(e.asLeft)
        }
        .start
        .void
    }

    private def cacheWhenDone(peerDiscovery: ServerTorrent.Phase.PeerDiscovery): IO[Unit] = {
      for {
        fetchingMetadata <- peerDiscovery.done
        ready <- fetchingMetadata.done
        _ <- ref.update { registry =>
          val cell = registry(ready.infoHash)
          val updatedCell = cell.copy(resolved = ready.serverTorrent.some)
          registry.updated(ready.infoHash, updatedCell)
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

    private def loggerWithContext(infoHash: InfoHash): Logger[IO] =
      logger.addContext(("infoHash", infoHash.toString: Shown))
  }
}

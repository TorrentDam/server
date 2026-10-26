import com.github.torrentdam.bittorrent.InfoHash
import org.legogroup.woof.{Logger, given}
import zio.{Promise, Ref, Scope, Task, UIO, ZIO}

import java.util.concurrent.TimeUnit

trait TorrentRegistry {
  def getOrCreate(infoHash: InfoHash)(createTorrent: ZIO[Scope, Throwable, ServerTorrent.Phase.PeerDiscovery])
    : ZIO[Scope, Throwable, Task[ServerTorrent.Phase.PeerDiscovery]]
  def get(infoHash: InfoHash): ZIO[Scope, Throwable, Option[ServerTorrent]]
}

object TorrentRegistry {

  case class UsageCountingCell(
    get: Task[ServerTorrent.Phase.PeerDiscovery],
    ready: Option[ServerTorrent],
    close: UIO[Unit],
    count: Int,
    usedCount: Int
  )

  def apply(): UIO[TorrentRegistry] =
    for
      ref <- Ref.make(Map.empty[InfoHash, UsageCountingCell])
    yield Impl(ref)

  private class Impl(ref: Ref[Map[InfoHash, UsageCountingCell]]) extends TorrentRegistry {
    import zio.interop.catz.implicits.rts

    def getOrCreate(
      infoHash: InfoHash
    )(createTorrent: ZIO[Scope, Throwable, ServerTorrent.Phase.PeerDiscovery])
      : ZIO[Scope, Throwable, Task[ServerTorrent.Phase.PeerDiscovery]] =
      for
        existing <- ref.modify { registry =>
          registry.get(infoHash) match
            case Some(cell) =>
              val updatedCell = cell.copy(count = cell.count + 1, usedCount = cell.usedCount + 1)
              (Some(updatedCell.get), registry.updated(infoHash, updatedCell))
            case None => (None, registry)
        }
        get <- existing match
          case Some(get) => ZIO.succeed(get)
          case None =>
            for
              torrentDeferred <- Promise.make[Throwable, ServerTorrent.Phase.PeerDiscovery]
              closeDeferred <- Promise.make[Nothing, Unit]
              get = torrentDeferred.await
              closeTorrent = closeDeferred.succeed(()).unit
              cell = UsageCountingCell(get, None, closeTorrent, 1, 1)
              _ <- ref.update(_.updated(infoHash, cell))
              fiber <- createTorrent
                .flatMap(phase => torrentDeferred.succeed(phase) *> cacheReadyPhase(phase))
                .catchAll(e => torrentDeferred.fail(e))
                .forkDaemon
            yield get
      yield get

    def get(infoHash: InfoHash): ZIO[Scope, Throwable, Option[ServerTorrent]] =
      for
        torrent <- ref.modify { registry =>
          registry.get(infoHash) match
            case Some(cell) if cell.ready.isDefined =>
              val updatedCell = cell.copy(count = cell.count + 1, usedCount = cell.usedCount + 1)
              (cell.ready, registry.updated(infoHash, updatedCell))
            case _ => (None, registry)
        }
      yield torrent

    private def cacheReadyPhase(peerDiscovery: ServerTorrent.Phase.PeerDiscovery): Task[Unit] =
      for
        fetchingMetadata <- peerDiscovery.done
        ready <- fetchingMetadata.done
        _ <- ref.update { registry =>
          registry.updatedWith(ready.infoHash)(_.map(_.copy(ready = Some(ready.serverTorrent))))
        }
      yield ()

    private def release(infoHash: InfoHash): UIO[Unit] =
      for
        cell <- ref.modify { registry =>
          val cell = registry(infoHash)
          val updatedCell = cell.copy(count = cell.count - 1)
          (updatedCell, registry.updated(infoHash, updatedCell))
        }
        _ <- if cell.count == 0 then scheduleClose(infoHash, _.usedCount == cell.usedCount)
        else ZIO.unit
      yield ()

    private def scheduleClose(infoHash: InfoHash, closeIf: UsageCountingCell => Boolean): UIO[Unit] =
      val idleTimeout = zio.Duration(30, TimeUnit.MINUTES)
      (for
        _ <- ZIO.sleep(idleTimeout)
        _ <- tryClose(infoHash, closeIf)
      yield ()).forkDaemon.unit

    private def tryClose(infoHash: InfoHash, closeIf: UsageCountingCell => Boolean): UIO[Unit] =
      ref.modify { registry =>
        registry.get(infoHash) match
          case Some(cell) if closeIf(cell) => (cell.close, registry.removed(infoHash))
          case _ => (ZIO.unit, registry)
      }.flatten
  }
}

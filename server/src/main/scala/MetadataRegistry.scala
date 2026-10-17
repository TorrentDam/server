import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.TorrentMetadata.Lossless
import zio.{Hub, Ref, Task, UIO, ZIO}
import zio.stream.ZStream

trait MetadataRegistry {
  def recent: Task[List[(InfoHash, Lossless)]]
  def get(infoHash: InfoHash): Task[Option[Lossless]]
  def put(infoHash: InfoHash, metadata: Lossless): Task[Boolean]
  def subscribe: ZStream[Any, Nothing, (InfoHash, Lossless)]
}

object MetadataRegistry {

  case class State(map: Map[InfoHash, Lossless], list: List[(InfoHash, Lossless)]) {
    def put(infoHash: InfoHash, metadata: Lossless): Option[State] =
      if map.contains(infoHash) then None
      else Some(State(map.updated(infoHash, metadata), (infoHash, metadata) :: list))

    def get(infoHash: InfoHash): Option[Lossless] = map.get(infoHash)
    def recent: List[(InfoHash, Lossless)] = list
  }

  object State {
    def empty: State = State(Map.empty, List.empty)
  }

  def apply(): UIO[MetadataRegistry] =
    for
      ref <- Ref.make(State.empty)
      hub <- Hub.unbounded[(InfoHash, Lossless)]
    yield new Impl(ref, hub)

  private class Impl(ref: Ref[State], hub: Hub[(InfoHash, Lossless)]) extends MetadataRegistry {
    def recent: Task[List[(InfoHash, Lossless)]] = ref.get.map(_.recent)
    def get(infoHash: InfoHash): Task[Option[Lossless]] = ref.get.map(_.get(infoHash))
    def put(infoHash: InfoHash, metadata: Lossless): Task[Boolean] =
      for
        success <- ref.modify { state =>
          state.put(infoHash, metadata) match
            case Some(updated) => (true, updated)
            case None         => (false, state)
        }
        _ <- if success then hub.publish((infoHash, metadata)) else ZIO.unit
      yield success
    def subscribe: ZStream[Any, Nothing, (InfoHash, Lossless)] = ZStream.fromHub(hub)
  }
}

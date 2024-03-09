import cats.Monad
import cats.implicits.*
import cats.effect.implicits.*
import cats.effect.Concurrent
import cats.effect.kernel.Ref
import com.github.torrentdam.bittorrent.InfoHash
import com.github.torrentdam.bittorrent.TorrentMetadata.Lossless
import fs2.Stream
import fs2.concurrent.Topic

trait MetadataRegistry[F[_]] {

  def recent: F[Iterable[(InfoHash, Lossless)]]

  def get(infoHash: InfoHash): F[Option[Lossless]]

  def put(infoHash: InfoHash, metadata: Lossless): F[Boolean]

  def subscribe: Stream[F, (InfoHash, Lossless)]
}

object MetadataRegistry {

  case class State(map: Map[InfoHash, Lossless], list: List[(InfoHash, Lossless)]) {

    def put(infoHash: InfoHash, metadata: Lossless): Option[State] =
      if (map.contains(infoHash)) none
      else State(map.updated(infoHash, metadata), (infoHash, metadata) :: list).some

    def get(infoHash: InfoHash): Option[Lossless] = map.get(infoHash)

    def recent: List[(InfoHash, Lossless)] = list
  }

  object State {
    def empty: State = State(Map.empty, List.empty)
  }

  def apply[F[_]: Concurrent](): F[MetadataRegistry[F]] =
    for {
      ref <- Ref.of[F, State](State.empty)
      topic <- Topic[F, (InfoHash, Lossless)]
    } yield {

      new MetadataRegistry[F] {

        def recent: F[Iterable[(InfoHash, Lossless)]] =
          ref.get.map(_.recent)

        def get(infoHash: InfoHash): F[Option[Lossless]] =
          ref.get.map(_.get(infoHash))

        def put(infoHash: InfoHash, metadata: Lossless): F[Boolean] =
          ref
            .modify { map =>
              map.put(infoHash, metadata) match
                case Some(state) => (state, true)
                case None => (map, false)
            }
            .flatTap { success =>
              Monad[F].whenA(success) {
                topic.publish1((infoHash, metadata)).start
              }
            }

        def subscribe: Stream[F, (InfoHash, Lossless)] = topic.subscribe(1)
      }

    }

}

import zio.{Ref, Task, UIO, ZIO}

trait TorrentIndex {
  import TorrentIndex.Entry
  def search(text: String): Task[List[Entry]]
}

object TorrentIndex {

  def apply(): UIO[TorrentIndex] =
    for
      ref <- Ref.make(Index())
    yield impl(ref.get)

  private def impl(entries: Task[Index]): TorrentIndex =
    new TorrentIndex {
      def search(text: String): Task[List[Entry]] = {
        val words = text.toLowerCase.split(' ')
        for
          index <- entries
        yield
          index.entries
            .view
            .map { case (searchField, entry) =>
              val score = words.map(word => if searchField.contains(word) then word.length else 0).sum
              (entry, score)
            }
            .filter(_._2 > 0)
            .toList
            .sortBy(-_._2)
            .take(100)
            .map(_._1)
      }
    }

  case class Index(entries: List[(String, Entry)] = List.empty)
  case class Entry(name: String, infoHash: String, size: Long, ext: List[String])
  object Entry {
    given jsonRW: upickle.default.ReadWriter[Entry] = upickle.default.macroRW
  }
}

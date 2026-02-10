import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.syntax.all.*
import org.legogroup.woof.{Logger, given}

import scala.concurrent.duration.*
import scala.util.chaining.scalaUtilChainingOps

trait TorrentIndex {
  import TorrentIndex.Entry

  def search(text: String): IO[List[Entry]]
}

object TorrentIndex {

  def apply()(implicit logger: Logger[IO]): Resource[IO, TorrentIndex] = {
    Resource.eval {
      for
        ref <- Ref.of[IO, Index](Index())
      yield
        impl(ref.get)
    }
  }

  private def impl(entries: IO[Index]): TorrentIndex = {
    new TorrentIndex {
      def search(text: String): IO[List[Entry]] = {
        val words = text.toLowerCase.split(' ')
        for (index <- entries)
        yield
          index.entries
            .view
            .map {
              case (searchField, entry) =>
                val score = words.map(word => if (searchField.contains(word)) word.length else 0).sum
                (entry, score)
            }
            .filter(_._2 > 0)
            .toList
            .sortBy(_._2)(using Ordering[Int].reverse)
            .take(100)
            .map(_._1)
      }
    }
  }

  case class Index(entries: List[(String, Entry)] = List.empty)
  case class Entry(name: String, infoHash: String, size: Long, ext: List[String])
  object Entry {
    implicit val jsonRW: upickle.default.ReadWriter[Entry] = upickle.default.macroRW
  }
}

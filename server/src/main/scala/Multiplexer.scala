import cats.effect.{Async, IO}
import cats.effect.kernel.{Deferred, Ref}
import cats.implicits.*
import cats.effect.implicits.*
import fs2.Stream
import org.legogroup.woof.{Logger, given}

trait Multiplexer {
  def get(index: Int): IO[Multiplexer.Result]
}

object Multiplexer {

  type Result = Stream[IO, Byte]

  def apply(request: Int => IO[Stream[IO, Byte]])(using Logger[IO]): IO[Multiplexer] = {
    for {
      pieces <- IO.ref(Map.empty[Int, Deferred[IO, Either[Throwable, Result]]])
    } yield new Multiplexer {
      def get(index: Int): IO[Stream[IO, Byte]] = {
        def cleanup =
          pieces.update { pieces =>
            pieces.removed(index)
          }
        def requestAndCache = IO.uncancelable { _ =>
          for
            (deferred, effect) <- pieces.modify { pieces =>
            pieces.get(index) match
              case Some(deferred) => (pieces, (deferred, IO.unit))
              case _ =>
                val deferred = Deferred.unsafe[IO, Either[Throwable, Result]]
                val updated = pieces.updated(index, deferred)
                val effect =
                  request(index)
                    .attempt
                    .flatTap(deferred.complete)
                    .onError(e => Logger[IO].error(s"Could not download piece $index: ${e.getMessage}"))
                    .guarantee(cleanup)
                    .start
                    .void
                (updated, (deferred, effect))
            }
            _ <- effect
          yield deferred
        }
        for
          deferred <- requestAndCache
          result <- deferred.get
          byteStream <- IO.fromEither(result)
        yield byteStream
      }
    }
  }
}

import cats.effect.Async
import cats.effect.kernel.{Deferred, Ref}
import cats.implicits.*
import cats.effect.implicits.*
import fs2.Stream

trait Multiplexer[F[_]] {
  def get(index: Int): F[Multiplexer.Result[F]]
}

object Multiplexer {

  type Result[F[_]] = Stream[F, Byte]

  def apply[F[_]](request: Int => F[Stream[F, Byte]])(implicit F: Async[F]): F[Multiplexer[F]] = {
    for {
      pieces <- Ref.of(Map.empty[Int, Deferred[F, Either[Throwable, Result[F]]]])
    } yield new Multiplexer[F] {
      def get(index: Int): F[Stream[F, Byte]] = {
        def cleanup =
          pieces.update { pieces =>
            pieces.removed(index)
          }
        def requestAndCache = F.uncancelable(_ =>
          for
            (deferred, effect) <- pieces.modify { pieces =>
            pieces.get(index) match
              case Some(deferred) => (pieces, (deferred, F.unit))
              case _ =>
                val deferred = Deferred.unsafe[F, Either[Throwable, Result[F]]]
                val updated = pieces.updated(index, deferred)
                val effect =
                  request(index).attempt
                    .flatTap(deferred.complete)
                    .guarantee(cleanup)
                    .start
                    .void
                (updated, (deferred, effect))
            }
            _ <- effect
          yield deferred
        )
        for
          deferred <- requestAndCache
          result <- deferred.get
          byteStream <- F.fromEither(result)
        yield byteStream
      }
    }
  }
}

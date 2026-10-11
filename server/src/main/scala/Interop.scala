import cats.arrow.FunctionK
import cats.effect.{IO, Resource}
import cats.effect.std.Dispatcher
import zio.interop.fromEffect
import zio.{RIO, Scope, ZIO}

object Interop {

  def ioToRIO(using Dispatcher[IO]): FunctionK[IO, RIO[Any, _]] =
    new FunctionK[IO, RIO[Any, _]] {
      def apply[A](fa: IO[A]): RIO[Any, A] = fromEffect(fa)
    }

  def acquireCats[A](resource: Resource[IO, A])(using Dispatcher[IO]): ZIO[Scope, Throwable, A] =
    fromEffect(resource.allocated).flatMap { (value, release) =>
      ZIO.addFinalizer(fromEffect(release).orDie).as(value)
    }
}

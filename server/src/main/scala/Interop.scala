import cats.arrow.FunctionK
import cats.effect.syntax.all.*
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import cats.effect.std.Dispatcher
import zio.interop.fromEffect
import zio.{RIO, Scope, ZIO}

object Interop {

  private val ioRuntime: IORuntime = IORuntime.global

  /**
   * Allocates a cats-effect Dispatcher to run IO effects from ZIO (see [[ioToRIO]] and [[acquireCats]]).
   * The dispatcher's threads are shut down when the scope closes.
   */
  def acquireDispatcher: ZIO[Scope, Throwable, Dispatcher[IO]] =
    ZIO
      .acquireRelease(
        ZIO.attempt(Dispatcher.parallel[IO].allocated.unsafeRunSync()(using ioRuntime))
      ) { case (_, release) =>
        ZIO.succeed(release.unsafeRunAndForget()(using ioRuntime))
      }
      .map(_._1)

  def ioToRIO(using Dispatcher[IO]): FunctionK[IO, RIO[Any, _]] =
    new FunctionK[IO, RIO[Any, _]] {
      def apply[A](fa: IO[A]): RIO[Any, A] = fromEffect(fa)
    }

  def acquireCats[A](resource: Resource[IO, A])(using Dispatcher[IO]): ZIO[Scope, Throwable, A] =
    fromEffect(resource.allocated).flatMap { (value, release) =>
      ZIO.addFinalizer(fromEffect(release).orDie).as(value)
    }
}

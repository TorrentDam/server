import zio.{Chunk, Promise, Ref, Task, UIO, ZIO}
import zio.stream.ZStream

trait Multiplexer {
  def get(index: Int): Task[ZStream[Any, Throwable, Byte]]
}

object Multiplexer {

  def apply(request: Int => Task[Chunk[Byte]]): UIO[Multiplexer] =
    for
      pieces <- Ref.make(Map.empty[Int, Promise[Throwable, Chunk[Byte]]])
    yield new Impl(pieces, request)

  private class Impl(
    pieces: Ref[Map[Int, Promise[Throwable, Chunk[Byte]]]],
    request: Int => Task[Chunk[Byte]]
  ) extends Multiplexer {

    def get(index: Int): Task[ZStream[Any, Throwable, Byte]] =
      for
        deferred <- requestAndCache(index)
        result <- deferred.await
      yield ZStream.fromChunk(result)

    private def requestAndCache(index: Int): Task[Promise[Throwable, Chunk[Byte]]] =
      ZIO.uninterruptibleMask { restore =>
        for
          existing <- pieces.get
          result <- existing.get(index) match
            case Some(deferred) => ZIO.succeed(deferred)
            case None =>
              for
                deferred <- Promise.make[Throwable, Chunk[Byte]]
                _ <- pieces.update(_.updated(index, deferred))
                _ <- restore(
                  request(index)
                    .tapBoth(
                      error => deferred.fail(error),
                      chunk => deferred.succeed(chunk)
                    )
                    .ignore
                    .forkDaemon
                )
                _ <- ensureCleanup(index)
              yield deferred
        yield result
      }

    private def ensureCleanup(index: Int): UIO[Unit] =
      pieces.update(_.removed(index))
  }
}

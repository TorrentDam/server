import java.nio.file.{Files as JFiles, Path}
import scodec.bits.ByteVector
import zio.{Chunk, Ref, Scope, Task, ZIO}

import java.nio.file.StandardOpenOption.*

trait PieceStore {
  def get(index: Int): Task[Option[Chunk[Byte]]]
  def put(index: Int, bytes: ByteVector): Task[Chunk[Byte]]
}

object PieceStore {

  def disk(directory: Path): ZIO[Scope, Throwable, PieceStore] =
    for
      _ <- ZIO.attempt(JFiles.createDirectories(directory))
      _ <- ZIO.addFinalizer {
        ZIO.attempt {
          JFiles.walk(directory).sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => JFiles.deleteIfExists(p))
        }.ignore
      }
      availability <- Ref.make(Set.empty[Int])
    yield Impl(directory, availability)

  private case class Impl(directory: Path, availability: Ref[Set[Int]]) extends PieceStore {
    def get(index: Int): Task[Option[Chunk[Byte]]] =
      for
        available <- availability.get
        result <- if available(index) then
          ZIO
            .attempt(pieceFile(index))
            .flatMap(file =>
              ZIO
                .attempt(JFiles.readAllBytes(file))
                .map(bytes => Some(Chunk.fromArray(bytes)))
            )
        else ZIO.succeed(None)
      yield result

    def put(index: Int, bytes: ByteVector): Task[Chunk[Byte]] =
      val chunk = Chunk.fromArray(bytes.toArray)
      for
        _ <- ZIO.attempt {
          val file = pieceFile(index)
          JFiles.write(file, bytes.toArray, CREATE, TRUNCATE_EXISTING, WRITE)
        }
        _ <- availability.update(_ + index)
      yield chunk

    private def pieceFile(index: Int) =
      directory.resolve(index.toString)
  }
}

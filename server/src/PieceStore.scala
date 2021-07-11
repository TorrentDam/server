import java.nio.file.Path

import cats.implicits.*
import cats.effect.kernel.Ref
import cats.effect.{Concurrent, Resource, Async}
import fs2.{Stream, Chunk}
import fs2.io.file.Files
import scodec.bits.ByteVector

import scala.collection.immutable.BitSet
import scala.jdk.StreamConverters.*

trait PieceStore[F[_]] {
  def get(index: Int): F[Option[Stream[F, Byte]]]
  def put(index: Int, bytes: ByteVector): F[Stream[F, Byte]]
}

object PieceStore {
  def disk[F[_]](
    directory: Path
  )(using Files[F], Concurrent[F]): Resource[F, PieceStore[F]] = {

    val createDirectory =
      Files[F].createDirectories(directory)

    val deleteDirectory =
      Files[F].deleteDirectoryRecursively(directory)

    Resource.make(createDirectory)(_ => deleteDirectory).evalMap { directory =>
      for {
        availability <- Ref.of(BitSet.empty)
      } yield new Impl(directory, availability)
    }
  }

  private class Impl[F[_]](directory: Path, availability: Ref[F, BitSet])(
    using
    Files[F],
    Concurrent[F],
  ) extends PieceStore[F] {

    def get(index: Int): F[Option[Stream[F, Byte]]] =
      for {
        availability <- availability.get
        available = availability(index)
      } yield if (available) readFile(pieceFile(index)).some else none

    def put(index: Int, bytes: ByteVector): F[Stream[F, Byte]] = {
      val file = pieceFile(index)
      val byteStream = Stream.chunk[F, Byte](Chunk.byteVector(bytes))
      for {
        _ <- Files[F].writeAll(file)(byteStream).compile.drain
        _ <- availability.update(_ + index)
      } yield readFile(file)
    }

    private def pieceFile(index: Int) =
      directory.resolve(index.toString)

    private def readFile(file: Path) =
      Files[F].readAll(file, 1024 * 1024)
  }
}

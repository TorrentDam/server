package com.github.lavrov.bittorrent.app.protocol

import com.github.lavrov.bittorrent.InfoHash
import scodec.bits.ByteVector
import upickle.default.{macroRW, ReadWriter}

sealed trait Command
object Command {
  case class RequestTorrent(infoHash: InfoHash, trackers: List[String]) extends Command

  import CommonFormats.*
  implicit val rw: ReadWriter[Command] =
    ReadWriter.merge(
      macroRW[RequestTorrent],
    )
}

sealed trait Event
object Event {
  case class RequestAccepted(infoHash: InfoHash) extends Event

  case class TorrentPeersDiscovered(infoHash: InfoHash, connected: Int) extends Event
  case class TorrentMetadataReceived(infoHash: InfoHash, name: String, files: List[File]) extends Event
  case class File(path: List[String], size: Long)

  case class TorrentError(infoHash: InfoHash, message: String) extends Event

  case class TorrentStats(infoHash: InfoHash, connected: Int, availability: List[Double]) extends Event

  import CommonFormats.*
  implicit val fileRW: ReadWriter[File] = macroRW
  implicit val eventRW: ReadWriter[Event] =
    ReadWriter.merge(
      macroRW[RequestAccepted],
      macroRW[TorrentPeersDiscovered],
      macroRW[TorrentMetadataReceived],
      macroRW[TorrentError],
      macroRW[TorrentStats],
    )
}

object CommonFormats {

  implicit val infoHashRW: ReadWriter[InfoHash] =
    implicitly[ReadWriter[String]].bimap(
      infoHash => infoHash.bytes.toHex,
      string => InfoHash(ByteVector.fromValidHex(string))
    )
}

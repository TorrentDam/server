package com.github.lavrov.bittorrent.app.protocol

import com.github.torrentdam.bittorrent.InfoHash
import scodec.bits.ByteVector
import upickle.default.{macroRW, ReadWriter}

sealed trait Message
sealed trait Command extends Message
sealed trait Event extends Message

object Message {

  case object Ping extends Message
  case object Pong extends Message
  case class RequestTorrent(infoHash: InfoHash, trackers: List[String]) extends Command
  case class RequestAccepted(infoHash: InfoHash) extends Event

  case class TorrentPeersDiscovered(infoHash: InfoHash, connected: Int) extends Event
  case class TorrentMetadataReceived(infoHash: InfoHash, name: String, files: List[File]) extends Event
  case class File(path: List[String], size: Long)

  case class TorrentError(infoHash: InfoHash, message: String) extends Event

  case class TorrentStats(infoHash: InfoHash, connected: Int, availability: List[Double]) extends Event

  implicit val infoHashRW: ReadWriter[InfoHash] =
    implicitly[ReadWriter[String]].bimap(
      infoHash => infoHash.bytes.toHex,
      string => InfoHash(ByteVector.fromValidHex(string))
    )
  implicit val fileRW: ReadWriter[File] = macroRW
  implicit val eventRW: ReadWriter[Message] =
    ReadWriter.merge(
      macroRW[Ping.type],
      macroRW[Pong.type],
      macroRW[RequestTorrent],
      macroRW[RequestAccepted],
      macroRW[TorrentPeersDiscovered],
      macroRW[TorrentMetadataReceived],
      macroRW[TorrentError],
      macroRW[TorrentStats],
    )
}
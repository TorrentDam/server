package com.github.lavrov.bittorrent.app.protocol

import scodec.bits.ByteVector
import upickle.default.{macroRW, ReadWriter}

final case class InfoHash(bytes: ByteVector) {
  override def toString: String = bytes.toHex
}

object InfoHash {

  def fromString(source: String): Option[InfoHash] =
    ByteVector.fromHex(source).toOption
      .filter(_.length == 20)
      .map(InfoHash.apply)
}

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

  given infoHashRW: ReadWriter[InfoHash] =
    summon[ReadWriter[String]].bimap(
      infoHash => infoHash.bytes.toHex,
      string => InfoHash(ByteVector.fromValidHex(string))
    )
  given fileRW: ReadWriter[File] = macroRW
  given eventRW: ReadWriter[Message] =
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

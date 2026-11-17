import com.github.torrentdam.bittorrent.InfoHash
import com.github.lavrov.bittorrent.app.protocol
import com.github.lavrov.bittorrent.app.protocol.{Message, Event, Command}
import zio.http.{ChannelEvent, Handler, WebSocketApp, WebSocketFrame}
import zio.{Scope, Task, UIO, ZIO, durationInt}
import zio.Duration as ZDuration

import scala.concurrent.duration.*
import scala.util.Try

object SocketSession {

  def apply(
    makeTorrent: MakeTorrent,
    metadataRegistry: MetadataRegistry,
    torrentIndex: TorrentIndex
  ): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      val send: Message => UIO[Unit] = { message =>
        channel
          .send(ChannelEvent.Read(WebSocketFrame.Text(upickle.default.write(message))))
          .catchAll(_ => ZIO.unit)
      }
      val process: Message => UIO[Unit] = {
        case Message.Ping =>
          send(Message.Pong)
        case command: Command =>
          ZIO.logDebug(s"Received $command") *>
            handleCommand(command, makeTorrent, send)
        case _ =>
          ZIO.unit
      }
      val receiveLoop = channel.receiveAll {
        case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
          JsonMessage.lift(text).map(process).getOrElse(ZIO.unit)
        case _ =>
          ZIO.unit
      }
      val pingLoop = ZIO.sleep(ZDuration.fromScala(10.seconds)) *>
        channel.send(ChannelEvent.Read(WebSocketFrame.Ping)).catchAll(_ => ZIO.unit)
      // end the session when the receive loop ends (client disconnect),
      // interrupting the ping loop instead of waiting for it forever
      receiveLoop.race(pingLoop.forever)
    }

  private def handleCommand(
    command: Command,
    makeTorrent: MakeTorrent,
    send: Event => UIO[Unit]
  ): UIO[Unit] =
    command match
      case Message.RequestTorrent(infoHash, trackers) =>
        val infoHash0 = InfoHash(infoHash.bytes)
        send(Message.RequestAccepted(infoHash)) *>
          handleRequestTorrent(infoHash0, trackers, makeTorrent, send).forkDaemon.unit

  private def handleRequestTorrent(
    infoHash: InfoHash,
    trackers: List[String],
    makeTorrent: MakeTorrent,
    send: Event => UIO[Unit]
  ): UIO[Unit] =
    val messageInfoHash = protocol.InfoHash(infoHash.bytes)
    val outcome =
      for
        peerDiscovery <- makeTorrent(infoHash, trackers)
        fetchingMetadata <- peerDiscovery.done
        fromPeersStream = fetchingMetadata.fromPeers
        _ <- fromPeersStream
          .mapZIO(count => send(Message.TorrentPeersDiscovered(messageInfoHash, count)))
          .runDrain
          .forkDaemon
        ready <- fetchingMetadata.done
        _ <- sendStatsLoop(infoHash, ready, send)
      yield ()
    outcome.catchAll { _ =>
      send(Message.TorrentError(messageInfoHash, "Could not fetch metadata"))
    }.ignore

  private def sendStatsLoop(
    infoHash: InfoHash,
    phase: ServerTorrent.Phase.Ready,
    send: Event => UIO[Unit]
  ): UIO[Unit] =
    val loop =
      for
        stats <- phase.serverTorrent.stats
        _ <- send(Message.TorrentStats(protocol.InfoHash(infoHash.bytes), stats.connected, stats.availability))
        _ <- ZIO.sleep(ZDuration.fromScala(5.seconds))
      yield ()
    loop.forever.catchAll(_ => ZIO.unit)

  private val JsonMessage: PartialFunction[String, Message] =
    ((input: String) => Try(upickle.default.read[Message](input)).toOption).unlift

  trait MakeTorrent {
    def apply(infoHash: InfoHash, trackers: List[String]): Task[ServerTorrent.Phase.PeerDiscovery]
  }
}

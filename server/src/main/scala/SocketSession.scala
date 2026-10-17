import cats.effect.std.Dispatcher
import com.github.torrentdam.bittorrent.InfoHash
import com.github.lavrov.bittorrent.app.protocol
import com.github.lavrov.bittorrent.app.protocol.{Message, Event, Command}
import org.legogroup.woof.{Logger, given}
import zio.http.{ChannelEvent, Handler, WebSocketApp, WebSocketFrame}
import zio.interop.catz.implicits.rts
import zio.interop.{fromEffect, ToEffectSyntax}
import zio.{Scope, Task, UIO, ZIO, durationInt}
import zio.Duration as ZDuration

import scala.concurrent.duration.*
import scala.util.Try

object SocketSession {

  def apply(
    makeTorrent: MakeTorrent,
    metadataRegistry: MetadataRegistry,
    torrentIndex: TorrentIndex
  )(using
    dispatcher: Dispatcher[cats.effect.IO],
    logger: Logger[cats.effect.IO]
  ): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      val send: Message => UIO[Unit] = { message =>
        channel
          .send(ChannelEvent.Read(WebSocketFrame.Text(upickle.default.write(message))))
          .catchAll(_ => ZIO.unit)
      }
      val sendEvent: Event => cats.effect.IO[Unit] = { event =>
        send(event).toEffect[cats.effect.IO]
      }
      val process: Message => UIO[Unit] = {
        case Message.Ping =>
          send(Message.Pong)
        case command: Command =>
          fromEffect {
            summon[Logger[cats.effect.IO]].debug(s"Received $command")
          }
            .catchAll(_ => ZIO.unit) *>
            handleCommand(command, makeTorrent, sendEvent)
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
      receiveLoop
        .zipPar(pingLoop.forever)
        .ensuring(ZIO.unit)
    }

  private def handleCommand(
    command: Command,
    makeTorrent: MakeTorrent,
    send: Event => cats.effect.IO[Unit]
  )(using dispatcher: Dispatcher[cats.effect.IO]): UIO[Unit] =
    command match
      case Message.RequestTorrent(infoHash, trackers) =>
        val infoHash0 = InfoHash(infoHash.bytes)
        fromEffect(send(Message.RequestAccepted(infoHash)))
          .catchAll(_ => ZIO.unit) *>
          handleRequestTorrent(infoHash0, trackers, makeTorrent, send).forkDaemon.unit

  private def handleRequestTorrent(
    infoHash: InfoHash,
    trackers: List[String],
    makeTorrent: MakeTorrent,
    send: Event => cats.effect.IO[Unit]
  )(using dispatcher: Dispatcher[cats.effect.IO]): UIO[Unit] =
    val messageInfoHash = protocol.InfoHash(infoHash.bytes)
    val outcome =
      for
        peerDiscovery <- makeTorrent(infoHash, trackers)
        fetchingMetadata <- peerDiscovery.done
        fromPeersStream = fetchingMetadata.fromPeers
        _ <- fromPeersStream
          .mapZIO(count =>
            fromEffect(send(Message.TorrentPeersDiscovered(messageInfoHash, count))).catchAll(_ => ZIO.unit)
          )
          .runDrain
          .forkDaemon
        ready <- fetchingMetadata.done
        _ <- sendStatsLoop(infoHash, ready, send)
      yield ()
    outcome.catchAll { _ =>
      fromEffect(send(Message.TorrentError(messageInfoHash, "Could not fetch metadata"))).catchAll(_ => ZIO.unit)
    }.ignore

  private def sendStatsLoop(
    infoHash: InfoHash,
    phase: ServerTorrent.Phase.Ready,
    send: Event => cats.effect.IO[Unit]
  )(using dispatcher: Dispatcher[cats.effect.IO]): UIO[Unit] =
    val loop =
      for
        stats <- phase.serverTorrent.stats
        _ <- fromEffect(send(Message.TorrentStats(protocol.InfoHash(infoHash.bytes), stats.connected, stats.availability)))
        _ <- ZIO.sleep(ZDuration.fromScala(5.seconds))
      yield ()
    loop.forever.catchAll(_ => ZIO.unit)

  private val JsonMessage: PartialFunction[String, Message] =
    ((input: String) => Try(upickle.default.read[Message](input)).toOption).unlift

  trait MakeTorrent {
    def apply(infoHash: InfoHash, trackers: List[String]): Task[ServerTorrent.Phase.PeerDiscovery]
  }
}

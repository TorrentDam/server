import cats.effect.{Concurrent, IO, Resource}
import cats.effect.std.Supervisor
import cats.implicits.*
import com.github.torrentdam.bittorrent.InfoHash
import com.github.lavrov.bittorrent.app.protocol
import com.github.lavrov.bittorrent.app.protocol.{Message, Event, Command}
import org.legogroup.woof.{Logger, given}
import zio.http.{ChannelEvent, Handler, WebSocketApp, WebSocketFrame}
import zio.interop.catz.implicits.rts
import zio.interop.ToEffectSyntax
import zio.interop.fromEffect
import zio.ZIO
import cps.*
import cps.syntax.*
import cps.monads.catsEffect.{*, given}

import scala.concurrent.duration.*
import scala.util.Try

object SocketSession {

  def apply(
    makeTorrent: MakeTorrent,
    metadataRegistry: MetadataRegistry[IO],
    torrentIndex: TorrentIndex
  )(using
    F: Concurrent[IO],
    dispatcher: cats.effect.std.Dispatcher[IO],
    logger: Logger[IO]
  ): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      val send: Message => ZIO[Any, Nothing, Unit] = { message =>
        channel
          .send(ChannelEvent.Read(WebSocketFrame.Text(upickle.default.write(message))))
          .catchAll(_ => ZIO.unit)
      }
      val sendEvent: Event => IO[Unit] = { event =>
        send(event).toEffect[IO]
      }
      val allocatedIO: IO[(CommandHandler, IO[Unit])] =
        CommandHandler(sendEvent, makeTorrent, metadataRegistry, torrentIndex).allocated
      fromEffect(logger.info("Session started")) *> fromEffect(allocatedIO).flatMap { allocated =>
        val handler = allocated._1
        val closeHandler = allocated._2
        def process: Message => ZIO[Any, Nothing, Unit] = {
          case Message.Ping =>
            send(Message.Pong)
          case command: Command =>
            fromEffect {
              async[IO] {
                !logger.debug(s"Received $command")
                !handler.handle(command)
              }
            }.catchAll(_ => ZIO.unit)
          case _ =>
            ZIO.unit
        }
        val receiveLoop = channel.receiveAll {
          case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
            JsonMessage.lift(text).map(process).getOrElse(ZIO.unit)
          case _ =>
            ZIO.unit
        }
        val pingLoop = ZIO.sleep(zio.Duration.fromScala(10.seconds)) *>
          channel.send(ChannelEvent.Read(WebSocketFrame.Ping)).catchAll(_ => ZIO.unit)
        receiveLoop
          .zipPar(pingLoop.forever)
          .ensuring {
            fromEffect {
              closeHandler *> logger.info("Session closed")
            }.catchAll(_ => ZIO.unit)
          }
      }
    }

  private val JsonMessage: PartialFunction[String, Message] =
    ((input: String) => Try(upickle.default.read[Message](input)).toOption).unlift

  class CommandHandler(
    send: Event => IO[Unit],
    makeTorrent: MakeTorrent,
    metadataRegistry: MetadataRegistry[IO],
    torrentIndex: TorrentIndex,
    supervisor: Supervisor[IO]
  )(using
    F: Concurrent[IO],
    logger: Logger[IO]
  ) {

    def handle(command: Command): IO[Unit] =
      command match
        case Message.RequestTorrent(infoHash, trackers) =>
          for
            _ <- send(Message.RequestAccepted(infoHash))
            _ <- handleRequestTorrent(InfoHash(infoHash.bytes), trackers)
          yield ()

    private def handleRequestTorrent(infoHash: InfoHash, trackers: List[String]): IO[Unit] =
      val messageInfoHash = protocol.InfoHash(infoHash.bytes)
      supervisor.supervise(
        makeTorrent(infoHash, trackers)
          .use { getTorrent =>
            getTorrent
              .flatMap { (phase: ServerTorrent.Phase.PeerDiscovery) =>
                phase.done
              }
              .flatMap { (phase: ServerTorrent.Phase.FetchingMetadata) =>
                phase.fromPeers.discrete
                  .evalTap { count =>
                    send(Message.TorrentPeersDiscovered(messageInfoHash, count))
                  }
                  .interruptWhen(phase.done.void.attempt)
                  .compile
                  .drain >>
                phase.done
              }
              .flatMap { (phase: ServerTorrent.Phase.Ready) =>
                val metadata = phase.serverTorrent.metadata.parsed
                val files = metadata.files.map(f => Message.File(f.path, f.length))
                send(Message.TorrentMetadataReceived(messageInfoHash, metadata.name, files)) >>
                phase.serverTorrent.pure[IO]
              }
              .timeout(5.minutes)
              .flatMap { torrent =>
                sendTorrentStats(messageInfoHash, torrent)
              }
          }
          .orElse {
            send(Message.TorrentError(messageInfoHash, "Could not fetch metadata"))
          }
      ).void

    private def sendTorrentStats(infoHash: protocol.InfoHash, torrent: ServerTorrent): IO[Nothing] =
      val sendStats =
        for
          stats <- torrent.stats
          _ <- send(Message.TorrentStats(infoHash, stats.connected, stats.availability))
          _ <- IO.sleep(5.seconds)
        yield ()
      sendStats.foreverM
  }

  object CommandHandler {
    def apply(
      send: Event => IO[Unit],
      makeTorrent: MakeTorrent,
      metadataRegistry: MetadataRegistry[IO],
      torrentIndex: TorrentIndex
    )(using
      F: Concurrent[IO],
      logger: Logger[IO]
    ): Resource[IO, CommandHandler] =
      for
        supervisor <- Supervisor[IO]
      yield
        new CommandHandler(
          send,
          makeTorrent,
          metadataRegistry,
          torrentIndex,
          supervisor
        )
  }

  trait MakeTorrent {
    def apply(infoHash: InfoHash, trackers: List[String]): Resource[IO, IO[ServerTorrent.Phase.PeerDiscovery]]
  }
}

import cats.effect.kernel.Deferred
import cats.effect.{Concurrent, IO, Resource}
import cats.effect.std.Queue
import cats.effect.std.Supervisor
import cats.implicits.*
import com.github.torrentdam.bittorrent.InfoHash
import com.github.lavrov.bittorrent.app.protocol.{Message, Event, Command}
import fs2.Stream
import org.legogroup.woof.{Logger, given}
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import cps.*
import cps.syntax.*
import cps.monads.catsEffect.{*, given}

import scala.concurrent.duration.*
import scala.util.Try

object SocketSession {

  def apply(
    makeTorrent: MakeTorrent,
    metadataRegistry: MetadataRegistry[IO],
    torrentIndex: TorrentIndex,
    webSocketBuilder: WebSocketBuilder[IO]       
  )(implicit
    F: Concurrent[IO],
    logger: Logger[IO]
  ): IO[Response[IO]] =
    for
      _ <- logger.info("Session started")
      input <- Queue.unbounded[IO, WebSocketFrame]
      output <- Queue.unbounded[IO, WebSocketFrame]
      send = (str: String) => output.offer(WebSocketFrame.Text(str))
      sendMessage = (m: Message) => send(upickle.default.write(m))
      (handler, closeHandler) <- CommandHandler(sendMessage, makeTorrent, metadataRegistry, torrentIndex).allocated
      fiber <- processor(input, sendMessage, handler).compile.drain.start
      pingFiber <- (IO.sleep(10.seconds) >> input.offer(WebSocketFrame.Ping())).foreverM.start
      response <- webSocketBuilder
        .withOnClose(fiber.cancel >> pingFiber.cancel >> closeHandler >> logger.info("Session closed"))
        .build(
          Stream.fromQueueUnterminated(output),
          _.evalMap(input.offer),
        )
    yield response

  private def processor(
    input: Queue[IO, WebSocketFrame],
    send: Message => IO[Unit],
    commandHandler: CommandHandler
  )(implicit logger: Logger[IO]): Stream[IO, Unit] =
    Stream.fromQueueUnterminated(input).evalMap {
      case WebSocketFrame.Text(JsonMessage(message), _) =>
        message match
          case Message.Ping =>
            send(Message.Pong)
          case command: Command => async[IO] {
            !logger.debug(s"Received $command")
            !commandHandler.handle(command)
          }
          case _ => IO.unit
      case _ => IO.unit
    }

  private val JsonMessage: PartialFunction[String, Message] =
    ((input: String) => Try(upickle.default.read[Message](input)).toOption).unlift

  class CommandHandler(
    send: Event => IO[Unit],
    makeTorrent: MakeTorrent,
    metadataRegistry: MetadataRegistry[IO],
    torrentIndex: TorrentIndex,
    supervisor: Supervisor[IO]
  )(implicit
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
                    send(Message.TorrentPeersDiscovered(infoHash, count))
                  }
                  .interruptWhen(phase.done.void.attempt)
                  .compile
                  .drain >>
                phase.done
              }
              .flatMap { (phase: ServerTorrent.Phase.Ready) =>
                val metadata = phase.serverTorrent.metadata.parsed
                val files = metadata.files.map(f => Message.File(f.path, f.length))
                send(Message.TorrentMetadataReceived(infoHash, metadata.name, files)) >>
                phase.serverTorrent.pure[IO]
              }
              .timeout(5.minutes)
              .flatMap { torrent =>
                sendTorrentStats(infoHash, torrent)
              }
          }
          .orElse {
            send(Message.TorrentError(infoHash, "Could not fetch metadata"))
          }
      ).void

    private def sendTorrentStats(infoHash: InfoHash, torrent: ServerTorrent): IO[Nothing] =
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
    )(implicit
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

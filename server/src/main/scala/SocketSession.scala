import cats.effect.kernel.Deferred
import cats.effect.{Concurrent, IO, Resource}
import cats.effect.std.Queue
import cats.effect.std.Supervisor
import cats.implicits.*
import com.github.lavrov.bittorrent.InfoHash
import com.github.lavrov.bittorrent.app.protocol.{Command, Event}
import fs2.Stream
import org.legogroup.woof.{Logger, given}
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame

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
      sendEvent = (e: Event) => send(upickle.default.write(e))
      (handler, closeHandler) <- CommandHandler(sendEvent, makeTorrent, metadataRegistry, torrentIndex).allocated
      fiber <- processor(input, send, handler).compile.drain.start
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
    send: String => IO[Unit],
    commandHandler: CommandHandler
  )(implicit logger: Logger[IO]): Stream[IO, Unit] =
    Stream.fromQueueUnterminated(input).evalMap {
      case WebSocketFrame.Text("ping", _) =>
        send("pong")
      case WebSocketFrame.Text(Cmd(command), _) =>
        for
          _ <- logger.debug(s"Received $command")
          _ <- commandHandler.handle(command)
        yield ()
      case _ => IO.unit
    }

  private val Cmd: PartialFunction[String, Command] =
    ((input: String) => Try(upickle.default.read[Command](input)).toOption).unlift

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
        case Command.RequestTorrent(infoHash, trackers) =>
          for
            _ <- send(Event.RequestAccepted(infoHash))
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
                    send(Event.TorrentPeersDiscovered(infoHash, count))
                  }
                  .interruptWhen(phase.done.void.attempt)
                  .compile
                  .drain >>
                phase.done
              }
              .flatMap { (phase: ServerTorrent.Phase.Ready) =>
                val metadata = phase.serverTorrent.metadata.parsed
                val files = metadata.files.map(f => Event.File(f.path, f.length))
                send(Event.TorrentMetadataReceived(infoHash, metadata.name, files)) >>
                phase.serverTorrent.pure[IO]
              }
              .timeout(5.minutes)
              .flatMap { torrent =>
                sendTorrentStats(infoHash, torrent)
              }
          }
          .orElse {
            send(Event.TorrentError(infoHash, "Could not fetch metadata"))
          }
      ).void

    private def sendTorrentStats(infoHash: InfoHash, torrent: ServerTorrent): IO[Nothing] =
      val sendStats =
        for
          stats <- torrent.stats
          _ <- send(Event.TorrentStats(infoHash, stats.connected, stats.availability))
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

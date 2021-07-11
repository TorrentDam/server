import $ivy.`com.lihaoyi::mill-contrib-artifactory:$MILL_VERSION`

import mill._
import scalalib._
import scalafmt.ScalafmtModule
import mill.api.Result
import coursier.maven.MavenRepository
import mill.contrib.artifactory.ArtifactoryPublishModule


object protocol extends Module with Publishing {
  def ivyDeps = Agg(
    Deps.common,
    Deps.upickle,
    Deps.`scodec-bits`,
  )
  object js extends JsModule with Publishing {
    def sources = protocol.sources
    def ivyDeps = protocol.ivyDeps
    def artifactName = protocol.artifactName
  }
}

object server extends Module with NativeImageModule {
  def moduleDeps = List(protocol)
  def ivyDeps = Agg(
    Deps.bittorrent,
    Deps.`fs2-io`,
    Deps.http4s.core,
    Deps.http4s.dsl,
    Deps.http4s.server,
    Deps.log4cats,
    Deps.`logback-classic`,
    Deps.requests,
  )
}

trait Module extends ScalaModule with ScalafmtModule {
  def scalaVersion = "3.0.1"
  def scalacOptions = Seq(
    "-source:future",
    "-Ykind-projector:underscores",
  )
  def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository(
        "https://maven.pkg.github.com/TorrentDam/bencode",
        T.env.get("GITHUB_TOKEN").map { token =>
          coursier.core.Authentication("lavrov", token)
        }
      )
    )
  }
  trait TestModule extends Tests with mill.scalalib.TestModule.Munit {
    def ivyDeps = Agg(
      Deps.`munit-cats-effect`
    )
  }
}

trait JsModule extends Module with scalajslib.ScalaJSModule {
  def scalaJSVersion = "1.6.0"
  import mill.scalajslib.api.ModuleKind
  def moduleKind = ModuleKind.CommonJSModule
}

trait NativeImageModule extends ScalaModule {
  private def javaHome = T.input {
    T.ctx().env.get("JAVA_HOME") match {
      case Some(homePath) => Result.Success(os.Path(homePath))
      case None => Result.Failure("JAVA_HOME env variable is undefined")
    }
  }

  private def nativeImagePath = T.input {
    val path = javaHome()/"bin"/"native-image"
    if (os exists path) Result.Success(path)
    else Result.Failure(
      "native-image is not found in java home directory.\n" +
        "Make sure JAVA_HOME points to GraalVM JDK and " +
        "native-image is set up (https://www.graalvm.org/docs/reference-manual/native-image/)"
    )
  }

  def nativeImage = T {
    import ammonite.ops._
    implicit val workingDirectory = T.ctx().dest
    %%(
      nativeImagePath(),
      "-jar", assembly().path,
      "--no-fallback",
      "--initialize-at-build-time=scala",
      "--initialize-at-build-time=scala.runtime.Statics",
      "--enable-all-security-services",
      "--enable-http",
      "--enable-https",
      "--allow-incomplete-classpath",
    )
    finalMainClass()
  }
}

trait Publishing extends ArtifactoryPublishModule {
  import mill.scalalib.publish._

  def artifactoryUri  = "https://maven.pkg.github.com/TorrentDam/server"

  def artifactorySnapshotUri = ""

  def pomSettings = PomSettings(
    description = "Server for TorrentDam",
    organization = "com.github.torrentdam",
    url = "https://github.com/TorrentDam/server",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("TorrentDam", "server"),
    developers = Seq(
      Developer("lavrov", "Vitaly Lavrov","https://github.com/lavrov")
    )
  )

  def publishVersion = "0.5.0"
}

object Deps {

  val common = ivy"com.github.torrentdam::common::${Versions.bittorrent}"
  val bittorrent = ivy"com.github.torrentdam::bittorrent::${Versions.bittorrent}"

  val `scodec-bits` = ivy"org.scodec::scodec-bits::${Versions.`scodec-bits`}"

  val `fs2-io` = ivy"co.fs2::fs2-io:${Versions.fs2}"

  val http4s = new {
    val core = ivy"org.http4s::http4s-core:${Versions.http4s}"
    val dsl = ivy"org.http4s::http4s-dsl:${Versions.http4s}"
    val server = ivy"org.http4s::http4s-blaze-server:${Versions.http4s}"
  }

  val requests = ivy"com.lihaoyi::requests:${Versions.requests}"

  val log4cats = ivy"org.typelevel::log4cats-slf4j::${Versions.log4cats}"
  val `log4cats-noop` = ivy"org.typelevel::log4cats-noop::${Versions.log4cats}"
  val `logback-classic` = ivy"ch.qos.logback:logback-classic:${Versions.logback}"

  val upickle = ivy"com.lihaoyi::upickle::${Versions.upickle}"

  val `munit-cats-effect` = ivy"org.typelevel::munit-cats-effect-3::1.0.5"
}

object Versions {
  val bittorrent = "1.0.0-RC3"
  val `cats-effect` = "3.1.1"
  val fs2 = "3.0.6"
  val `scodec-bits` = "1.1.27"
  val upickle = "1.4.0"
  val http4s = "1.0.0-M23"
  val requests = "0.6.9"
  val log4cats = "2.1.1"
  val logback = "1.2.3"
}

import $ivy.`com.lihaoyi::mill-contrib-artifactory:$MILL_VERSION`

import mill._
import scalalib._
import scalafmt.ScalafmtModule
import mill.eval.Result
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
    ivy"org.http4s::http4s-core:${Versions.http4s}",
    ivy"org.http4s::http4s-dsl:${Versions.http4s}",
    ivy"org.http4s::http4s-blaze-server:${Versions.http4s}",
    ivy"io.7mind.izumi::logstage-adapter-slf4j:${Versions.logstage}",
    ivy"com.lihaoyi::requests:${Versions.requests}",
  )
}

trait Module extends ScalaModule with ScalafmtModule {
  def scalaVersion = "2.13.4"
  def scalacOptions = List(
    "-language:higherKinds",
    "-Ymacro-annotations",
  )
  def repositories = super.repositories ++ Seq(
      MavenRepository("https://dl.bintray.com/lavrov/maven")
  )
  def scalacPluginIvyDeps = Agg(
    ivy"org.typelevel:::kind-projector:0.11.1",
    ivy"com.olegpy::better-monadic-for:0.3.1",
  )
  trait TestModule extends Tests {
    def ivyDeps = Agg(
      ivy"org.scalameta::munit:0.7.12",
    )
    def testFrameworks = Seq(
      "munit.Framework"
    )
  }
}

trait JsModule extends Module with scalajslib.ScalaJSModule {
  def scalaJSVersion = "1.5.0"
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
    versionControl = VersionControl.github("TorrentDam", "derver"),
    developers = Seq(
      Developer("lavrov", "Vitaly Lavrov","https://github.com/lavrov")
    )
  )

  def publishVersion = "0.5.0"
}

object Versions {
  val `cats-effect` = "2.2.0"
  val logstage = "1.0.0-M1"
  val `scodec-bits` = "1.1.14"
  val upickle = "1.0.0"
  val http4s = "0.21.11"
  val requests = "0.5.1"
}

object Deps {

  val common = ivy"com.github.torrentdam::common::0.3.0"
  val bittorrent = ivy"com.github.torrentdam::bittorrent::0.3.0"

  val `scodec-bits` = ivy"org.scodec::scodec-bits::${Versions.`scodec-bits`}"

  val logstage = ivy"io.7mind.izumi::logstage-core::${Versions.logstage}"

  val upickle = ivy"com.lihaoyi::upickle::${Versions.upickle}"
}


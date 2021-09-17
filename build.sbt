
lazy val root = project.in(file("."))

lazy val protocol = crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Pure)
  .settings(commonSettings)
  .settings(
    version := "1.0.1",
    libraryDependencies ++= List(
      Deps.common.value,
      Deps.upickle.value,
      Deps.`scodec-bits`.value,
    )
  )

lazy val server = project
  .dependsOn(protocol.jvm)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= List(
      Deps.bittorrent,
      Deps.`cats-effect`,
      Deps.`fs2-io`,
      Deps.http4s.core,
      Deps.http4s.dsl,
      Deps.http4s.server,
      Deps.log4cats,
      Deps.`logback-classic`,
      Deps.requests,
    ),
    nativeImageOptions ++= List(
      "--no-fallback",
      "--allow-incomplete-classpath",
      "--enable-https",
    ),
    nativeImageJvmIndex := "jabba",
    nativeImageJvm := "graalvm-ce-java11",
    nativeImageVersion := "21.1.0",
  )
  .enablePlugins(NativeImagePlugin)

lazy val commonSettings: List[Setting[_]] = List(
  scalaVersion := "3.0.2",
  scalacOptions ++= List(
    "-source:future",
    "-Ykind-projector:underscores",
  ),
  libraryDependencies ++= List(
    Deps.`munit-cats-effect` % Test
  ),
  organization := "com.github.torrentdam.server",
  githubOwner := "TorrentDamDev",
  githubRepository := "server",
  resolvers += Resolver.githubPackages("TorrentDamDev"),
)

lazy val Deps = new {

  val common = Def.setting { "com.github.torrentdam" %%% "common" % Versions.bittorrent }
  val bittorrent = "com.github.torrentdam" %% "bittorrent" % Versions.bittorrent

  val `scodec-bits` = Def.setting {"org.scodec" %%% "scodec-bits" % Versions.`scodec-bits` }

  val `cats-effect` = "org.typelevel" %% "cats-effect" % Versions.`cats-effect`
  val `fs2-io` = "co.fs2" %% "fs2-io" % Versions.fs2

  val http4s = new {
    val core = "org.http4s" %% "http4s-core" % Versions.http4s
    val dsl = "org.http4s" %% "http4s-dsl" % Versions.http4s
    val server = "org.http4s" %% "http4s-blaze-server" % Versions.http4s
  }

  val requests = "com.lihaoyi" %% "requests" % Versions.requests

  val log4cats = "org.typelevel" %% "log4cats-slf4j" % Versions.log4cats
  val `logback-classic` = "ch.qos.logback" % "logback-classic" % Versions.logback

  val upickle = Def.setting {"com.lihaoyi" %%% "upickle" % Versions.upickle }

  val `munit-cats-effect` = "org.typelevel" %% "munit-cats-effect-3"  % "1.0.5"
}

lazy val Versions = new {
  val bittorrent = "1.0.0-RC5"
  val `cats-effect` = "3.2.8"
  val fs2 = "3.1.2"
  val `scodec-bits` = "1.1.27"
  val upickle = "1.4.0"
  val http4s = "1.0.0-M23"
  val requests = "0.6.9"
  val log4cats = "2.1.1"
  val logback = "1.2.3"
}

import sbt.Keys.credentials

lazy val root = project.in(file(".")).aggregate(server)

lazy val protocol = crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Pure)
  .settings(commonSettings ++ publishSettings)
  .settings(
    description := "Bittorrent server protocol",
    libraryDependencies ++= List(
      Deps.bittorrent.common.value,
      Deps.upickle.value,
      Deps.`scodec-bits`.value,
    )
  )

lazy val server = project
  .dependsOn(protocol.jvm)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= List(
      Deps.bittorrent.bittorrent,
      Deps.bittorrent.dht,
      Deps.`cats-effect`,
      Deps.`fs2-io`,
      Deps.http4s.core,
      Deps.http4s.dsl,
      Deps.http4s.server,
      Deps.http4s.client,
      Deps.`woof-core`,
      Deps.`woof-slf4j`,
    ),
  )
  .enablePlugins(JavaAppPackaging)

lazy val commonSettings: List[Setting[_]] = List(
  organization := "io.github.torrentdam.server",
  version := sys.env.getOrElse("VERSION", "SNAPSHOT"),
  scalaVersion := "3.5.0",
  scalacOptions ++= List(
    "-source:future",
    "-Ykind-projector:underscores",
  ),
  libraryDependencies ++= List(
    Deps.`cps-async-cats-effect`,
    Deps.`munit-cats-effect` % Test
  )
)

lazy val publishSettings: List[Setting[_]] = List(
  publishTo := {
    val nexus = "https://s01.oss.sonatype.org/"
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  credentials ++= {
    sys.env.get("SONATYPE_CREDS") match {
      case Some(credentials) =>
        val Array(username, password) = credentials.split(':')
        List(
          Credentials(
            "Sonatype Nexus Repository Manager",
            "s01.oss.sonatype.org",
            username,
            password
          )
        )
      case None => List.empty[Credentials]
    }
  },
  developers := List(
    Developer(
      id = "lavrov",
      name = "Vitaly Lavrov",
      email = "lavrovvv@gmail.com",
      url = url("https://github.com/lavrov")
    )
  ),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/TorrentDam/server"),
      "scm:git@github.com:TorrentDam/server.git"
    )
  ),
  licenses := List("Unlicense" -> url("https://unlicense.org/")),
  homepage := Some(url("https://torrentdam.github.io/"))
)

lazy val Deps = new {

  val bittorrent = new {
    private val org = "io.github.torrentdam.bittorrent"
    val common = Def.setting { org %%% "common" % Versions.bittorrent }
    val bittorrent = org %% "bittorrent" % Versions.bittorrent
    val dht = org %% "dht" % Versions.bittorrent
  }

  val `scodec-bits` = Def.setting {"org.scodec" %%% "scodec-bits" % Versions.`scodec-bits` }

  val `cats-effect` = "org.typelevel" %% "cats-effect" % Versions.`cats-effect`
  val `fs2-io` = "co.fs2" %% "fs2-io" % Versions.fs2

  val http4s = new {
    val core = "org.http4s" %% "http4s-core" % Versions.http4s
    val dsl = "org.http4s" %% "http4s-dsl" % Versions.http4s
    val server = "org.http4s" %% "http4s-ember-server" % Versions.http4s
    val client = "org.http4s" %% "http4s-ember-client" % Versions.http4s
  }

  val `woof-core` = "org.legogroup" %% "woof-core"  % Versions.woof
  val `woof-slf4j` = "org.legogroup" %% "woof-slf4j"  % Versions.woof

  val upickle = Def.setting {"com.lihaoyi" %%% "upickle" % Versions.upickle }

  val `munit-cats-effect` = "org.typelevel" %% "munit-cats-effect-3"  % "1.0.7"

  val `cps-async-cats-effect` = "com.github.rssh" %% "cps-async-connect-cats-effect" % Versions.`cps-async`
}

lazy val Versions = new {
  val bittorrent = "3.4.0"
  val `cats-effect` = "3.5.4"
  val fs2 = "3.9.4"
  val `scodec-bits` = "1.1.27"
  val upickle = "2.0.0"
  val http4s = "1.0.0-M40"
  val `cps-async` = "0.9.11"
  val woof = "0.6.0"
}

server
======

Serves files from BitTorrent network

## Build

### Native executable with GraalVM native-image

Run server on JVM with agent enabled to build configuration files for native-image.
```scala
$ sbt server/nativeImageRunAgent
```

Copy configuration files into resources where native-image will find them.
```sh
$ cp -f server/target/native-image-configs/* server/src/main/resources/META-INF/native-image/
```

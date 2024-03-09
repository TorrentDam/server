FROM eclipse-temurin:21-jre-jammy

COPY ./server/target/universal/stage /opt/bittorrent-server

ENTRYPOINT ["/opt/bittorrent-server/bin/server", "-Dcats.effect.tracing.mode=none"]

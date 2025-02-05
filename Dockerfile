FROM eclipse-temurin:23-jre-noble

COPY ./server/target/universal/stage /opt/bittorrent-server

ENTRYPOINT ["/opt/bittorrent-server/bin/server", "-Dcats.effect.tracing.mode=none"]

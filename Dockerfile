FROM eclipse-temurin:latest

COPY ./server/target/universal/stage /opt/bittorrent-server

ENTRYPOINT ["/opt/bittorrent-server/bin/server", "-J-Xmx300m"]

FROM ubuntu

COPY ./server/target/native-image/server /opt/bittorrent-server

ENTRYPOINT ["/opt/bittorrent-server", "-Xmx300m"]

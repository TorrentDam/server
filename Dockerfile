FROM ibm-semeru-runtimes:open-17-jre-focal

COPY ./server/target/universal/stage /opt/bittorrent-server

ENTRYPOINT ["/opt/bittorrent-server/bin/server", "-J-Xmx300m"]

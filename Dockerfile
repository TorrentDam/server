FROM openjdk:18-jdk-slim

COPY ./out/server/assembly/dest/out.jar /opt/bittorrent-server.jar

ENTRYPOINT ["java", "-Xmx250m", "-jar", "/opt/bittorrent-server.jar"]

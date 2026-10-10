FROM eclipse-temurin:25-jre-noble
COPY ./out/server/assembly.dest/out.jar /opt/torrentdam/server.jar
ENTRYPOINT ["java", \
            "-Dcats.effect.tracing.mode=none", \
            "-XX:+UnlockExperimentalVMOptions", \
            "-XX:+UseCompactObjectHeaders", \
            "-jar", \
            "/opt/torrentdam/server.jar"]

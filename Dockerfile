FROM amazoncorretto:25.0.0-alpine3.22@sha256:807ea3c4000a052986cd1e7097a883f9cd7a6e527f73841f462e3d04851b8835 AS runtime

COPY target/*.jar /app/leader-elector.jar
COPY target/lib/ /app/lib/

WORKDIR /app

ENTRYPOINT ["java", "-jar", "/app/leader-elector.jar"]

FROM amazoncorretto:25.0.0-alpine3.22 AS runtime

COPY target/*.jar /app/leader-elector.jar
COPY target/lib/ /app/lib/

WORKDIR /app

ENTRYPOINT ["java", "-jar", "/app/leader-elector.jar"]

FROM amazoncorretto:25.0.0-alpine3.22 AS runtime

COPY target/*-shaded.jar /app/leader-elector.jar
WORKDIR /app

ENTRYPOINT ["java", "-jar", "/app/leader-elector.jar"]

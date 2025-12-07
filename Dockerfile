FROM amazoncorretto:25.0.0-alpine3.22

COPY target/leader-elector-*.jar /app/leader-elector.jar

WORKDIR /app

ENTRYPOINT ["java", "-jar", "leader-elector.jar"]

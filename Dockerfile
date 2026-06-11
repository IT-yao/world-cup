FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY . .
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/classes ./target/classes
COPY --from=build /app/target/dependency ./target/dependency
COPY --from=build /app/public ./public
CMD ["java", "-cp", "target/classes:target/dependency/*", "WatchPartyServer"]

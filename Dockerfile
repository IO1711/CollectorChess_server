FROM maven:3.9.14-eclipse-temurin-21-noble AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21.0.10_7-jre-noble
WORKDIR /app
COPY --from=build /app/target/CollectorChess-0.0.1-SNAPSHOT.jar CollectorChess.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "CollectorChess.jar"]

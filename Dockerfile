FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw -B -pl mail-api -am clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
ENV SPRING_PROFILES_ACTIVE=production
RUN useradd --system --uid 10001 mailplatform
COPY --from=build /workspace/mail-api/target/mail-api-*.jar /app/app.jar
RUN chown -R mailplatform:mailplatform /app
USER mailplatform
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]

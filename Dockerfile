FROM eclipse-temurin:17-jdk-jammy@sha256:96ac8197b2cbc2bb0f9fbcbf91dbb0015f43b3c76a1cb351892da12e7bb91bed AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy@sha256:c6a5dd70fb24f4e07ef88cbda8efb613a5a649a1af071a41de8eaabd1ee6b5da
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && groupadd --system catalog \
    && useradd --system --gid catalog --home-dir /app catalog \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build --chown=catalog:catalog /workspace/target/product-catalog-*.jar app.jar

USER catalog
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

# Etapa de build: compila o bootJar com cache de dependências em layer própria.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies --quiet > /dev/null 2>&1 || true

# Só o código de produção: mudanças em testes não invalidam esta camada.
COPY src/main src/main
RUN ./gradlew --no-daemon bootJar -x test

# Etapa de runtime: JRE mínima, usuário não-root, curl para healthcheck.
FROM eclipse-temurin:21-jre AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app app

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

USER app
EXPOSE 8080

# Log JSON por padrão em qualquer deploy da imagem; sobrescrevível (compose usa json,local).
ENV SPRING_PROFILES_ACTIVE="json"

# Flags de memória fixas e sem shell entre o init e a JVM. Sem `sh -c`, o java é
# o PID 1 e recebe SIGTERM direto (graceful shutdown), e some o word-splitting de
# uma variável expandida: um JAVA_OPTS com espaço dentro de um argumento quebrava
# o boot com "Could not find or load main class". Ajuste extra de JVM entra por
# JAVA_TOOL_OPTIONS, que a própria JVM lê e concatena, sem poder apagar estas.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]

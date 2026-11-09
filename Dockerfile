# Stage 1: Compile PostFlight using Java 17
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /src

COPY backend/src/main/java/com/postflight/core/ ./backend/src/main/java/com/postflight/core/
COPY backend/src/main/java/com/postflight/local/ ./backend/src/main/java/com/postflight/local/

RUN mkdir -p /out && \
    find backend/src/main/java/com/postflight/core backend/src/main/java/com/postflight/local -name '*.java' > /tmp/sources.txt && \
    javac --release 17 -encoding UTF-8 -d /out @/tmp/sources.txt

# Stage 2: Run with the Java 17 runtime
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

COPY --from=builder /out/ /app/classes/
COPY web/ /app/web/
COPY docs/seed.json /app/docs/seed.json

# Give the non-root application user access to local demo storage
RUN mkdir -p /app/data && chown -R 10001:10001 /app

USER 10001:10001

# Public demo: disable state-changing API operations
ENV POSTFLIGHT_PUBLIC_DEMO=true
ENV PORT=10000

EXPOSE 10000

CMD ["java", "--add-modules", "jdk.httpserver", "-Dpostflight.root=/app", "-cp", "/app/classes", "com.postflight.local.Main"]

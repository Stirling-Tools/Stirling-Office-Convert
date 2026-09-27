FROM eclipse-temurin:25-jre-noble@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e

LABEL org.opencontainers.image.title="stirling-office-convert" \
      org.opencontainers.image.description="Stirling-PDF PDF to Word, OpenDocument, RTF, text, PowerPoint and Excel converter demo (Stirling Office Convert)" \
      org.opencontainers.image.vendor="Stirling-PDF" \
      org.opencontainers.image.url="https://www.stirlingpdf.com"

RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app
COPY stirling-office-convert-app.jar /app/stirling-office-convert-app.jar

ENV HOST=0.0.0.0 \
    PORT=80 \
    MAX_UPLOAD_MB=100 \
    MAX_PAGES=300 \
    CONVERT_TIMEOUT_SECONDS=180 \
    MAX_QUEUED=8 \
    QUEUE_WAIT_SECONDS=120 \
    MAX_PER_CLIENT=4 \
    REQUEST_TIMEOUT_SECONDS=300 \
    MAX_CONNECTIONS=1000 \
    EXIT_WHEN_STUCK=true

USER app
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && grep -q " 200 " <&3'

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.awt.headless=true", "-jar", "/app/stirling-office-convert-app.jar"]

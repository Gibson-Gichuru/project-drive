FROM golang:1.26.11-alpine AS build
LABEL org.opencontainers.image.source="https://github.com/Gibson-Gichuru/project-drive"
WORKDIR /src
COPY . .
RUN CGO_ENABLED=0 go build -mod=vendor -trimpath -ldflags="-s -w" -o /out/server ./cmd/server \
    && CGO_ENABLED=0 go build -mod=vendor -trimpath -ldflags="-s -w" -o /out/migrate ./cmd/migrate

FROM build AS integration
RUN apk add --no-cache gcc musl-dev
CMD ["go", "test", "-race", "-count=1", "-v", "./internal/httpapi", "-run", "TestTripIntegration"]

FROM scratch AS runtime
WORKDIR /app
COPY --from=build /out/server /app/server
COPY --from=build /out/migrate /app/migrate
COPY --from=build /src/migrations /app/migrations
USER 65532:65532
EXPOSE 8080
CMD ["/app/server"]

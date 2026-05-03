# syntax=docker/dockerfile:1

FROM golang:1.23-bookworm AS build
WORKDIR /src
COPY go.mod main.go ./
RUN go mod tidy && CGO_ENABLED=0 go build -o /seccomp-exporter .

FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends bash ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /seccomp-exporter /usr/local/bin/seccomp-exporter
COPY docker-entrypoint.sh /docker-entrypoint.sh
COPY examples/seccomp-allowlist.txt /etc/seccomp/allowlist.txt
RUN chmod +x /docker-entrypoint.sh
EXPOSE 9101
ENTRYPOINT ["/docker-entrypoint.sh"]

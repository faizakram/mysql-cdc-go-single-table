FROM golang:1.21-alpine AS builder
WORKDIR /build

# Build argument to bust cache when needed
ARG CACHEBUST=1

COPY go.mod ./
COPY ./src ./src
RUN apk add --no-cache git
RUN go env -w GOPROXY=https://proxy.golang.org
RUN go mod tidy
RUN go mod download

RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /usr/local/bin/mysql-cdc ./src

FROM alpine:3.18
RUN apk add --no-cache tzdata ca-certificates
COPY --from=builder /usr/local/bin/mysql-cdc /usr/local/bin/mysql-cdc
WORKDIR /app
ENV TZ=UTC
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1
ENTRYPOINT ["/usr/local/bin/mysql-cdc"]

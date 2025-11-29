FROM golang:1.21-alpine AS builder
WORKDIR /build
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
ENTRYPOINT ["/usr/local/bin/mysql-cdc"]

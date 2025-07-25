FROM golang:1.24 AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /k8s-leader-elector ./cmd/k8s-leader-elector

FROM gcr.io/distroless/base-debian12:latest
COPY --from=build /k8s-leader-elector /k8s-leader-elector
ENTRYPOINT ["/k8s-leader-elector"]

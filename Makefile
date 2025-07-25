APP=k8s-leader-elector
REGISTRY=ghcr.io/jabrown93
TAG ?= $(shell git describe --tags --always --dirty)
PLATFORMS=linux/amd64,linux/arm64

.PHONY: build docker-build docker-push

build:
	go build -o bin/$(APP) ./cmd/k8s-leader-elector

docker-build:
	docker buildx build \
	  --platform=$(PLATFORMS) \
	  -t $(REGISTRY)/$(APP):$(TAG) \
	  .

docker-push:
	docker buildx build \
	  --platform=$(PLATFORMS) \
	  -t $(REGISTRY)/$(APP):$(TAG) \
	  --push \
	  .

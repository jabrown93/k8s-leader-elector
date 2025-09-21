APP=k8s-leader-elector
REGISTRY=ghcr.io/jabrown93
TAG ?= $(shell git describe --tags --always --dirty)
PLATFORMS=linux/amd64,linux/arm64

.PHONY: build docker-build docker-push

build:
	mvn clean install

docker-build: build
	docker buildx build \
	  --platform=$(PLATFORMS) \
	  -t $(REGISTRY)/$(APP):$(TAG) \
	  -t $(REGISTRY)/$(APP):latest \
	  .

docker-push:
	docker buildx build \
	  --platform=$(PLATFORMS) \
	  -t $(REGISTRY)/$(APP):$(TAG) \
	  -t $(REGISTRY)/$(APP):latest \
	  --push \
	  .

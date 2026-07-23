#!/bin/bash
# Build & push mvsews/mvp (run on a host with Docker + docker login mvsews)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-1.1.16}"
JAR="build/libs/MultiversePortals-${VERSION}.jar"
if [[ ! -f "$JAR" ]]; then
  echo "Missing $JAR — run gradle jar first" >&2
  exit 1
fi

IMAGE="mvsews/mvp"
echo "Building ${IMAGE}:${VERSION} ..."
docker build \
  -f docker/Dockerfile \
  --build-arg "MVP_JAR=${JAR}" \
  --build-arg "PAPER_VERSION=1.21.10" \
  -t "${IMAGE}:${VERSION}" \
  -t "${IMAGE}:latest" \
  .

echo "Pushing ${IMAGE}:${VERSION} and :latest ..."
docker push "${IMAGE}:${VERSION}"
docker push "${IMAGE}:latest"
echo "Done: docker pull ${IMAGE}:latest"

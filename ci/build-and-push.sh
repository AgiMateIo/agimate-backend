#!/bin/bash
set -euo pipefail

# ──────────────────────────────────────────────────────────
# Build and push Docker image to Container Registry
#
# Usage:   ./ci/build-and-push.sh <service-name>
# Example: ./ci/build-and-push.sh user-api
#
# Environment variables:
#   REGISTRY     — Container Registry URL
#   CR_USERNAME  — Container Registry login
#   CR_PASSWORD  — Container Registry password
# ──────────────────────────────────────────────────────────

SERVICE=$1
TAG="${TAG:-$(git describe --tags --always)}"

if [ -z "$SERVICE" ]; then
  echo "❌ Service name required: ./ci/build-and-push.sh <service-name>"
  exit 1
fi

if [ -z "${REGISTRY:-}" ]; then
  echo "❌ REGISTRY is not set"
  exit 1
fi

if [ -z "${CR_USERNAME:-}" ] || [ -z "${CR_PASSWORD:-}" ]; then
  echo "❌ CR_USERNAME and CR_PASSWORD must be set"
  exit 1
fi

IMAGE="${REGISTRY}/${SERVICE}"

echo "══════════════════════════════════════════"
echo "  Service:  ${SERVICE}"
echo "  Image:    ${IMAGE}:${TAG}"
echo "══════════════════════════════════════════"

# ── Docker build ─────────────────────────────────────────
echo "▶ Building Docker image..."
docker build \
  -t "${IMAGE}:${TAG}" \
  -t "${IMAGE}:latest" \
  -f "services/${SERVICE}/Dockerfile" \
  "services"

# ── Docker push ──────────────────────────────────────────
echo "▶ Logging in to ${REGISTRY}..."
echo "${CR_PASSWORD}" | docker login "${REGISTRY}" -u "${CR_USERNAME}" --password-stdin

echo "▶ Pushing ${IMAGE}:${TAG}..."
docker push "${IMAGE}:${TAG}"
docker push "${IMAGE}:latest"

echo "✅ ${SERVICE} pushed as ${IMAGE}:${TAG}"

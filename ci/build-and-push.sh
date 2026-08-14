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

# ── Cleanup old tags of this service ─────────────────────
# Every runner shares one Docker daemon (runner.sh mounts /var/run/docker.sock), so a
# global operation here reaches into a neighbouring build. `docker image prune -f` was
# deleting intermediate layers of whatever else was building, and `docker rmi -f <ID>`
# dropped other services' tags along with ours: all three images are built from the
# same base layer and therefore share its ID. Remove only our own tags, and by name —
# `docker rmi <repo>:<tag>` drops a reference, and layers are freed only once the last
# reference to them is gone, so anything still in use survives.
echo "▶ Removing old ${IMAGE} tags..."
docker images "${IMAGE}" --format '{{.Repository}}:{{.Tag}}' \
  | grep -v ':<none>$' \
  | while read -r REF; do
      docker rmi "$REF" 2>/dev/null || true
    done

# ── Docker build ─────────────────────────────────────────
echo "▶ Building Docker image..."
docker build \
  -t "${IMAGE}:${TAG}" \
  -t "${IMAGE}:latest" \
  -f "services/${SERVICE}/Dockerfile" \
  "services"

# ── Docker push ──────────────────────────────────────────
registry_login() {
  echo "${CR_PASSWORD}" | docker login "${REGISTRY}" -u "${CR_USERNAME}" --password-stdin
}

# A push can fail for no lasting reason: on 14 August user-api got a 401 on a blob HEAD
# request moments after a successful login, while the next two jobs of the same run
# pushed fine with the very same credentials. Without a retry, one such refusal throws
# away a finished Gradle build. Log in again before each attempt — if the token exchange
# is what broke, retrying with the same stale state just hits the identical 401.
push_with_retry() {
  local ref="$1" attempt=1 max=3
  until docker push "$ref"; do
    if [ "$attempt" -ge "$max" ]; then
      echo "❌ Push failed after ${max} attempts: $ref"
      return 1
    fi
    local delay=$((attempt * 10))
    echo "⚠ Push failed (attempt ${attempt}/${max}), retrying in ${delay}s..."
    sleep "$delay"
    attempt=$((attempt + 1))
    registry_login >/dev/null
  done
}

echo "▶ Logging in to ${REGISTRY}..."
registry_login

echo "▶ Pushing ${IMAGE}:${TAG}..."
push_with_retry "${IMAGE}:${TAG}"
push_with_retry "${IMAGE}:latest"

echo "✅ ${SERVICE} pushed as ${IMAGE}:${TAG}"

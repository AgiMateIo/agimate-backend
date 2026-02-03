#!/bin/bash
set -euo pipefail

# ──────────────────────────────────────────────────────────
# Update image version in agimate-infra
#
# Usage:   ./ci/update-infra.sh <service-name>
# Example: ./ci/update-infra.sh user-api
#
# Environment variables:
#   INFRA_REPO_TOKEN — Git token for pushing to agimate-infra
# ──────────────────────────────────────────────────────────

SERVICE=$1
REGISTRY="${REGISTRY:-agimate.cr.cloud.ru}"
TAG="${TAG:-$(git describe --tags --always)}"
INFRA_REPO="${INFRA_REPO:-gitverse.ru/agimate/agimate-infra.git}"

if [ -z "$SERVICE" ]; then
  echo "❌ Service name required: ./ci/update-infra.sh <service-name>"
  exit 1
fi

IMAGE="${REGISTRY}/${SERVICE}"
WORKDIR=$(mktemp -d)

echo "▶ Cloning infra repo..."
git clone "https://oauth2:${INFRA_REPO_TOKEN}@${INFRA_REPO}" "${WORKDIR}"

cd "${WORKDIR}"

echo "▶ Updating image: ${SERVICE} → ${IMAGE}:${TAG}"
kustomize edit set image "${SERVICE}=${IMAGE}:${TAG}"

# ── Check for changes ────────────────────────────────────
if git diff --quiet; then
  echo "⏭ No changes in infra repo, skipping"
  rm -rf "${WORKDIR}"
  exit 0
fi

# ── Commit and push ─────────────────────────────────────
git config user.name "CI Bot"
git config user.email "ci@agimate.com"
git add kustomization.yaml
git commit -m "deploy: ${SERVICE} ${TAG}"
git push

echo "✅ Infra repo updated: ${SERVICE} → ${TAG}"

# ── Cleanup ──────────────────────────────────────────────
rm -rf "${WORKDIR}"

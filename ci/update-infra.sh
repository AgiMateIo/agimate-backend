#!/bin/bash
set -euo pipefail

# ──────────────────────────────────────────────────────────
# Update image versions in agimate-infra
#
# Usage:   ./ci/update-infra.sh <service1> [service2] ...
# Example: ./ci/update-infra.sh user-api mobile-api
#
# Environment variables:
#   INFRA_REPO       — Infra repo URL (without https://)
#   INFRA_REPO_TOKEN — Git token for pushing to agimate-infra
# ──────────────────────────────────────────────────────────

if [ $# -eq 0 ]; then
  echo "❌ Usage: ./ci/update-infra.sh <service1> [service2] ..."
  exit 1
fi

TAG="${TAG:-$(git describe --tags --always)}"

if [ -z "${INFRA_REPO:-}" ]; then
  echo "❌ INFRA_REPO is not set"
  exit 1
fi

WORKDIR=$(mktemp -d)
trap "rm -rf ${WORKDIR}" EXIT

echo "▶ Cloning infra repo..."
git clone "https://oauth2:${INFRA_REPO_TOKEN}@${INFRA_REPO}" "${WORKDIR}"
cd "${WORKDIR}"

# Update each service
for SERVICE in "$@"; do
  echo "▶ Updating ${SERVICE} → ${TAG}..."
  ./scripts/update-image.sh "${SERVICE}" "${TAG}"
done

# Single commit for all changes
./scripts/commit-changes.sh

echo "✅ Infra updated: $@ → ${TAG}"

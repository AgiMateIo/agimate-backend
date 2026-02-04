#!/bin/bash
set -euo pipefail

# ──────────────────────────────────────────────────────────
# Update image versions in agimate-infra
#
# Usage:   ./ci/update-infra.sh <service1> [service2] ...
# Example: ./ci/update-infra.sh user-api mobile-api
#
# Environment variables:
#   INFRA_REPO_SSH    — SSH URL (e.g., git@gitverse.ru:org/infra.git)
#   INFRA_DEPLOY_KEY  — Private SSH key for pushing
# ──────────────────────────────────────────────────────────

if [ $# -eq 0 ]; then
  echo "❌ Usage: ./ci/update-infra.sh <service1> [service2] ..."
  exit 1
fi

TAG="${TAG:-$(git describe --tags --always)}"

if [ -z "${INFRA_REPO_SSH:-}" ]; then
  echo "❌ INFRA_REPO_SSH is not set"
  exit 1
fi

if [ -z "${INFRA_DEPLOY_KEY:-}" ]; then
  echo "❌ INFRA_DEPLOY_KEY is not set"
  exit 1
fi

# Temporary directory for work and SSH key
WORKDIR=$(mktemp -d)
SSH_KEY_FILE="${WORKDIR}/.deploy_key"

# Cleanup on exit — removes key and workdir
trap "rm -rf ${WORKDIR}" EXIT

# Setup temporary SSH key
echo "$INFRA_DEPLOY_KEY" > "$SSH_KEY_FILE"
chmod 600 "$SSH_KEY_FILE"

# Use temporary key for git operations (key never touches ~/.ssh)
export GIT_SSH_COMMAND="ssh -i ${SSH_KEY_FILE} -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null"

echo "▶ Cloning infra repo..."
git clone "${INFRA_REPO_SSH}" "${WORKDIR}/repo"
cd "${WORKDIR}/repo"

# Update each service
for SERVICE in "$@"; do
  echo "▶ Updating ${SERVICE} → ${TAG}..."
  ./scripts/update-image.sh "${SERVICE}" "${TAG}"
done

# Single commit for all changes
./scripts/commit-changes.sh

echo "✅ Infra updated: $@ → ${TAG}"

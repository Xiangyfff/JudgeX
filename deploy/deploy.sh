#!/usr/bin/env bash
# Update a deployed JudgeX: pull, build both halves, rebuild the judge image if it is
# missing, restart the service, then prove it is alive.
#
# First-time installation is documented in docs/DEPLOY.md; this script only updates.
set -euo pipefail

REPO_DIR="${REPO_DIR:-/opt/judgex}"
BASE_URL="${BASE_URL:-http://127.0.0.1:18900}"

cd "$REPO_DIR"

echo "==> fetching"
git pull --ff-only

echo "==> building backend"
(cd backend && mvn -q -DskipTests package)

echo "==> building frontend"
(cd frontend && npm ci --no-audit --no-fund && npm run build)

if ! docker image inspect judgex-runner:latest >/dev/null 2>&1; then
    echo "==> building judge image (772 MB, one-off)"
    docker build -t judgex-runner:latest docker/runner
fi

echo "==> restarting service"
sudo systemctl restart judgex

echo "==> waiting for health"
for i in $(seq 1 30); do
    if curl -fsS "$BASE_URL/api/health" >/dev/null 2>&1; then
        curl -fsS "$BASE_URL/api/health"; echo
        echo "==> ok"
        exit 0
    fi
    sleep 1
done

echo "!! service did not come up; last 40 log lines:" >&2
sudo journalctl -u judgex -n 40 --no-pager >&2
exit 1

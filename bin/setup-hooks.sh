#!/usr/bin/env bash
set -euo pipefail
repo_root=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$repo_root"

echo "[setup-hooks] configuring git hooks path to .githooks"
git config core.hooksPath .githooks

echo "[setup-hooks] running mission-verify manual check"
./scripts/mission-verify.sh manual || {
  echo "[setup-hooks] mission-verify reported an issue; fix it before committing." >&2
  exit 1
}

echo "[setup-hooks] done"

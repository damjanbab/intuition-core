#!/usr/bin/env bash
set -euo pipefail

stage=${1:-manual}
allow_no_git=${MISSION_VERIFY_ALLOW_NO_GIT:-}
verbose=${MISSION_VERIFY_VERBOSE:-}
root_dir=""
git_available=0

if git_root=$(git rev-parse --show-toplevel 2>/dev/null); then
  root_dir="$git_root"
  git_available=1
else
  if [[ -n "$allow_no_git" ]]; then
    root_dir=$(pwd)
  else
    echo "[mission-verify] error: must run inside a git repository (set MISSION_VERIFY_ALLOW_NO_GIT=1 to bypass for testing)." >&2
    exit 1
  fi
fi

cd "$root_dir"

function log_info() {
  if [[ -n "$verbose" ]]; then
    echo "[mission-verify] $1"
  fi
}

function fail() {
  echo "[mission-verify] ERROR: $1" >&2
  exit 1
}

branch=""
if [[ $git_available -eq 1 ]]; then
  branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)
fi

if [[ -z "$branch" ]]; then
  branch=${MISSION_VERIFY_BRANCH:-}
fi

if [[ -z "$branch" ]]; then
  fail "unable to determine current branch; set MISSION_VERIFY_BRANCH to test outside git"
fi

if [[ ! "$branch" =~ ^mission/(M-[0-9]{8}-[0-9]{3})$ ]]; then
  fail "branch '$branch' must follow mission/M-YYYYMMDD-### convention"
fi
mission_id="${BASH_REMATCH[1]}"
mission_file="missions/${mission_id}.md"
worklog_file="worklogs/${mission_id}/WORKLOG.md"

log_info "branch=$branch"

[[ -f "$mission_file" ]] || fail "mission file '$mission_file' not found"
[[ -f "$worklog_file" ]] || fail "worklog '$worklog_file' not found"

if grep -q '<MISSION_ID>' "$worklog_file"; then
  fail "worklog '$worklog_file' still contains template placeholders"
fi

status=$(sed -n 's/^\- \*\*Status:\*\* `\([^`]\+\)`/\1/p' "$mission_file" | head -n1)
if [[ -z "$status" ]]; then
  fail "unable to determine status from $mission_file"
fi

function board_file_for_status() {
  case "$1" in
    draft) echo "missions/board/00_INBOX.md" ;;
    open) echo "missions/board/10_OPEN.md" ;;
    in_progress) echo "missions/board/20_IN_PROGRESS.md" ;;
    blocked) echo "missions/board/30_BLOCKED.md" ;;
    review) echo "missions/board/40_REVIEW.md" ;;
    done) echo "missions/board/50_DONE.md" ;;
    archived) echo "missions/board/60_ARCHIVED.md" ;;
    *) echo "" ;;
  esac
}

board_file=$(board_file_for_status "$status")
if [[ -z "$board_file" ]]; then
  fail "unknown mission status '$status'"
fi

[[ -f "$board_file" ]] || fail "board file '$board_file' missing"

grep -q "$mission_id" "$board_file" || fail "mission $mission_id not listed in $board_file"

if [[ $git_available -eq 1 ]]; then
  # ensure mission branch is current and no merge conflicts
  if git diff --name-only --diff-filter=U | grep -q .; then
    fail "resolve merge conflicts before continuing"
  fi
  if [[ "$stage" == "pre-push" ]]; then
    if [[ -n $(git status --porcelain) ]]; then
      fail "working tree must be clean before pushing"
    fi
  fi
fi

# basic check for worklog progress
if ! grep -q "Worklog – $mission_id" "$worklog_file"; then
  fail "worklog for $mission_id missing header"
fi

if [[ "$stage" == "pre-push" ]]; then
  if ! grep -Fq -- "- Tests (commands + results):" "$worklog_file"; then
    fail "worklog missing test summary entry"
  fi
fi

log_info "mission $mission_id verified for stage '$stage'"
exit 0

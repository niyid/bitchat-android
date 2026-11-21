#!/usr/bin/env bash
# check-upstream-sync.sh
#
# Checks whether the local branch (default "with-monero") is behind
# upstream/main, and whether it has diverged from origin/<branch>.
#
# Usage:
#   ./check-upstream-sync.sh              # default branch: with-monero
#   ./check-upstream-sync.sh mybranch     # check a different local branch
#
# Exit codes:
#   0  up to date with upstream
#   1  upstream has new commits (merge/rebase needed)
#   2  error (not a repo, missing branch/remote, ...)

set -euo pipefail

BRANCH="${1:-with-monero}"
UPSTREAM_REMOTE="upstream"
UPSTREAM_BRANCH="main"
ORIGIN_REMOTE="origin"
UP_REF="$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"

die() { echo "ERROR: $*" >&2; exit 2; }

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "not inside a git repository"
git rev-parse --verify --quiet "refs/heads/$BRANCH" >/dev/null || die "local branch '$BRANCH' not found"
git remote get-url "$UPSTREAM_REMOTE" >/dev/null 2>&1 || die "remote '$UPSTREAM_REMOTE' not configured (git remote add $UPSTREAM_REMOTE <url>)"

echo "== Fetching remotes =="
git fetch --quiet "$UPSTREAM_REMOTE"
HAS_ORIGIN=0
if git remote get-url "$ORIGIN_REMOTE" >/dev/null 2>&1; then
  git fetch --quiet "$ORIGIN_REMOTE"
  HAS_ORIGIN=1
fi
git rev-parse --verify --quiet "$UP_REF" >/dev/null || die "'$UP_REF' not found after fetch"

echo
echo "== Branch status =="
# --no-optional-locks avoids the noisy "Refresh index" progress output
git --no-optional-locks status --short --branch

echo
echo "== Commits on $UP_REF not yet in $BRANCH =="
BEHIND_LOG=$(git log "$BRANCH..$UP_REF" --oneline)
if [ -z "$BEHIND_LOG" ]; then
  echo "(none — $BRANCH is fully caught up with upstream)"
else
  echo "$BEHIND_LOG"
fi

echo
echo "== Commits on $BRANCH not in $UP_REF (your work) =="
AHEAD_LOG=$(git log "$UP_REF..$BRANCH" --oneline)
if [ -z "$AHEAD_LOG" ]; then
  echo "(none)"
else
  echo "$AHEAD_LOG"
fi

echo
echo "== Divergence count (ahead / behind vs $UP_REF) =="
read -r AHEAD BEHIND < <(git rev-list --left-right --count "$BRANCH...$UP_REF")
echo "  ahead (unique to $BRANCH): $AHEAD"
echo "  behind (unique to upstream): $BEHIND"

echo
echo "== Local branch vs $ORIGIN_REMOTE/$BRANCH =="
PUSHED=0
if [ "$HAS_ORIGIN" -eq 1 ] && git rev-parse --verify --quiet "refs/remotes/$ORIGIN_REMOTE/$BRANCH" >/dev/null; then
  read -r L_ONLY O_ONLY < <(git rev-list --left-right --count "$BRANCH...$ORIGIN_REMOTE/$BRANCH")
  echo "  local-only: $L_ONLY   origin-only: $O_ONLY"
  PUSHED=1
else
  echo "  N/A ($ORIGIN_REMOTE/$BRANCH does not exist)"
fi

echo
if [ "$BEHIND" -eq 0 ]; then
  echo "RESULT: Up to date with $UP_REF. No merge/rebase needed."
  exit 0
fi

echo "RESULT: Upstream has moved ($BEHIND new commit(s))."
if [ "$PUSHED" -eq 1 ]; then
  echo "Branch is already pushed, so prefer a merge (a rebase would require a force-push):"
  echo "  git merge $UP_REF"
else
  echo "Branch is not pushed yet, so either works:"
  echo "  git merge $UP_REF        # keeps history, may conflict"
  echo "  git rebase $UP_REF       # linear history, may conflict"
fi
exit 1

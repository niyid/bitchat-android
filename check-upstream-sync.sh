#!/usr/bin/env bash
# check-upstream-sync.sh
#
# Checks whether the local "with-monero" branch is behind upstream/main,
# and whether it's diverged from origin/with-monero.
#
# Usage:
#   ./check-upstream-sync.sh              # uses default branch names below
#   ./check-upstream-sync.sh mybranch     # check a different local branch
#
# Run from inside the repo, or set REPO_DIR below.

set -euo pipefail

BRANCH="${1:-with-monero}"
UPSTREAM_REMOTE="upstream"
UPSTREAM_BRANCH="main"
ORIGIN_REMOTE="origin"

echo "== Fetching remotes =="
git fetch "$UPSTREAM_REMOTE"
git fetch "$ORIGIN_REMOTE"

echo
echo "== Branch status =="
git status --short --branch

echo
echo "== Commits on $UPSTREAM_REMOTE/$UPSTREAM_BRANCH not yet in $BRANCH =="
BEHIND=$(git log "$BRANCH..$UPSTREAM_REMOTE/$UPSTREAM_BRANCH" --oneline)
if [ -z "$BEHIND" ]; then
  echo "(none — $BRANCH is fully caught up with upstream)"
else
  echo "$BEHIND"
fi

echo
echo "== Commits on $BRANCH not in $UPSTREAM_REMOTE/$UPSTREAM_BRANCH (your work) =="
AHEAD=$(git log "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH..$BRANCH" --oneline)
if [ -z "$AHEAD" ]; then
  echo "(none)"
else
  echo "$AHEAD"
fi

echo
echo "== Divergence count (ahead / behind vs upstream/main) =="
git rev-list --left-right --count "$BRANCH...$UPSTREAM_REMOTE/$UPSTREAM_BRANCH" \
  | awk '{print "  ahead (unique to '"$BRANCH"'): " $1 "\n  behind (unique to upstream): " $2}'

echo
echo "== Local branch vs origin/$BRANCH =="
LOCAL_VS_ORIGIN=$(git rev-list --left-right --count "$BRANCH...$ORIGIN_REMOTE/$BRANCH" 2>/dev/null || echo "N/A")
echo "  $LOCAL_VS_ORIGIN  (format: <local-only> <origin-only>)"

echo
if [ -z "$BEHIND" ]; then
  echo "RESULT: Up to date. No rebase/merge needed."
else
  echo "RESULT: Upstream has moved. Consider merging or rebasing:"
  echo "  git merge $UPSTREAM_REMOTE/$UPSTREAM_BRANCH        # merge (keeps history, may conflict)"
  echo "  git rebase $UPSTREAM_REMOTE/$UPSTREAM_BRANCH        # rebase (linear history, may conflict)"
fi

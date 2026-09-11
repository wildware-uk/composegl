#!/usr/bin/env bash
#
# Mirrors these pages onto the GitHub wiki.
#
# The wiki is a second git repository beside the code one, so it cannot be pushed by the
# same commit. This directory stays the source of truth — the pages are reviewed like
# code here — and the wiki is a copy kept in step by running this.
#
# The pictures are not copied. They are linked by their raw.githubusercontent URL in the
# code repository, so regenerating them with `./gradlew :composegl-demo:docShots` and
# pushing updates the wiki at the same time.
#
# The wiki repository does not exist until somebody saves one page through the web
# interface. If this says "Repository not found", that is why.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
remote="$(git -C "$here" remote get-url origin | sed 's/\.git$//').wiki.git"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git clone --quiet "$remote" "$work"
# A mirror, not a copy: a page deleted here has to be deleted there too, or the wiki keeps
# showing a page nobody can find in the repository any more.
find "$work" -maxdepth 1 -name '*.md' -delete
cp "$here"/*.md "$work/"
git -C "$work" add -A

if git -C "$work" diff --cached --quiet; then
  echo "wiki already matches docs/wiki"
  exit 0
fi

git -C "$work" commit --quiet -m "docs: sync from docs/wiki"
git -C "$work" push --quiet origin HEAD
echo "pushed $(ls "$here"/*.md | wc -l) pages to $remote"

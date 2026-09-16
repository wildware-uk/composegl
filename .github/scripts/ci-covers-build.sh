#!/usr/bin/env bash
#
# Proves that splitting the build across several runners did not quietly drop anything.
#
# `./gradlew build` used to run on one machine. It now runs as the legs in .github/ci-legs.json,
# one per runner. That is only safe while the legs together still ask for every task `build` would
# have asked for — and "still" is the hard part: a new target, a new module or a new check can land
# in `build` without landing in any leg, and nothing would say so. The tests would simply stop
# running, and CI would go green faster.
#
# So this asks Gradle, with --dry-run, what `build` would do and what each leg would do, and fails
# if `build` names a task no leg names. It is cheap: --dry-run configures the build and prints the
# task graph without executing any of it.
set -euo pipefail

cd "$(dirname "$0")/../.."
work="$(mktemp -d)"

# The task graph of one Gradle invocation, one task path per line.
graph() {
    # shellcheck disable=SC2086 # the leg's tasks and -x flags are meant to be split into words.
    ./gradlew $1 --dry-run -q | grep '^:' | sed 's/ SKIPPED$//' | sort -u
}

graph build > "$work/build.txt"
echo "./gradlew build wants $(wc -l < "$work/build.txt") tasks"

: > "$work/legs.txt"
count="$(jq 'length' .github/ci-legs.json)"
for i in $(seq 0 $((count - 1))); do
    id="$(jq -r ".[$i].id" .github/ci-legs.json)"
    tasks="$(jq -r ".[$i].tasks" .github/ci-legs.json)"
    graph "$tasks" > "$work/$id.txt"
    echo "leg $id wants $(wc -l < "$work/$id.txt") tasks"
    cat "$work/$id.txt" >> "$work/legs.txt"
done
sort -u -o "$work/legs.txt" "$work/legs.txt"

missing="$(comm -23 "$work/build.txt" "$work/legs.txt")"
if [ -n "$missing" ]; then
    echo
    echo "These tasks are part of ./gradlew build but no CI leg runs them:"
    echo "$missing" | sed 's/^/  /'
    echo
    echo "Add them to a leg in .github/ci-legs.json. CI must not test less than it used to."
    exit 1
fi

echo "Every task of ./gradlew build is claimed by a leg."

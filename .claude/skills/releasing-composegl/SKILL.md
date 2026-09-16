---
name: releasing-composegl
description: Use when Shaun asks for a composegl release or version bump ("release as 0.6.0", "cut a release", "publish to Central"), or when a release run failed, a stray vX.Y.Z tag is on master, or you need to check what a release costs against the Maven Central quota.
---

# Releasing composegl

## Overview

A release is **two separate workflows**, and only the second one is permanent. Everything up to
and including the upload can be undone; publishing to Maven Central can never be undone.

**The go-ahead rule:** never start a release unless Shaun asked for it *in that message*. Merging
work, finishing a feature, or a green build is not a request. `-f kind=snapshot` is the one
release-ish thing that needs no go-ahead.

The version is **not written in any file**. `build.gradle.kts` derives it from git: exactly on a
`vX.Y.Z` tag it is `X.Y.Z`, on any commit after it is `X.Y+1.0-SNAPSHOT`.

## Before you touch a workflow

| Check | Command | Why |
|---|---|---|
| Shaun asked, in this message | — | The whole policy hangs on this |
| Master is green | `gh run list --workflow=ci.yml --branch master --limit 3` | `release.yml` runs the tests first; red master wastes 20 minutes |
| Right version comes out | `git describe --tags --abbrev=0` | Last tag + `kind` must equal the number Shaun said |
| No stray tag | `git tag -l vX.Y.Z` | A tag already there means someone half-released |
| Quota | see **Quota** below | Publishing is permanent; the bill is not yours to guess at |

**Which `kind` gives which number.** From the last tag `vX.Y.Z`:

| kind | produces |
|---|---|
| `patch` | X.Y.Z+1 |
| `minor` | X.Y+1.0 |
| `major` | X+1.0.0 |

Only those three are reachable. If the number Shaun named is not one of them, do not pick the
nearest — ask him which he meant. A number nobody can produce usually means someone is working
from a version that does not exist.

**A flaky test blocks releases.** If CI is red on a timing-dependent test, fix the test — do not
re-roll the dice. A release that dies 17 minutes in on a wall-clock assertion has cost more than
the fix would have.

## The steps

**1. Rehearse (free, changes nothing).**
```bash
gh workflow run release.yml -f kind=rehearse --ref master
```
Builds and signs, uploads nothing, tags nothing. Proves the signing key works before anything
becomes permanent. It reports a snapshot number — that is expected and says nothing about what
`minor` will produce.

**2. Cut it.** `kind` is `patch`, `minor` or `major` — the one that turns the last tag into the
number Shaun asked for.
```bash
gh workflow run release.yml -f kind=minor --ref master
RUN=$(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch $RUN
```
Read these steps in the log, in order:
- **Work out the version** — must name the exact version. Anything else: stop.
- **Central accepts the credentials** — catches a dead token before the tag exists.
- **Build and sign** — signatures written > 0.
- **Tag it** — pushes `vX.Y.Z`. First step that leaves a mark.
- **Central validated the bundle** — prints the deployment id. Green "Publish" only means Central
  *took* the upload; `VALIDATED` is what matters.

```bash
gh run view $RUN --log | grep -o 'deployment [0-9a-f-]*' | tail -1
```

**3. Confirm with Shaun, then publish permanently.** Show him the version, the deployment id, and
the quota position. Then and only then:
```bash
gh workflow run central-publish.yml -f deployment_id=<id> -f confirm=X.Y.Z
```
A validated deployment that is never published can simply be dropped. A published one cannot.

**4. Prove it.** Central's index lags 10-30 minutes.
```bash
curl -sI https://repo1.maven.org/maven2/dev/wildware/composegl/composegl-ui/X.Y.Z/composegl-ui-X.Y.Z.pom
```
`200` is the proof. Spot-check two or three other modules.

**5. Publish the wiki. Part of the release, not an afterthought.**
```bash
bash docs/wiki/push.sh
```
Editing `docs/wiki` does not publish anything — until this runs, the live wiki still documents the
previous version. Run it after the docs commit in step 6, and check it reports the page count.

**6. Start the next version.** This is the step that gets forgotten.

Master builds as `X.Y.Z` — a *release* version — until the next commit lands. That is correct
straight after a release and is **not** the stray-tag problem; do not delete this tag. The next
commit makes master `X.Y+1.0-SNAPSHOT` by itself, so land the follow-up promptly:

- `README.md`: the version line and every `implementation(...)` snippet
- `docs/wiki/Home.md` and every dependency snippet in `docs/wiki`
- a new `docs/wiki/Whats-new-X.Y.Z.md`, following the last one (`git log --oneline vPREV..vX.Y.Z`)
- `CLAUDE.md`'s file-count arithmetic if the published module list changed

Then run `bash docs/wiki/push.sh` again, so the live wiki carries the new version's docs.

```bash
./gradlew -q :composegl-ui:properties | grep '^version:'   # expect X.Y+1.0-SNAPSHOT
```

## Quota

Each release publishes every artifact of every module in the `published` set in the root
`build.gradle.kts`, and each file counts four times (the file plus `.asc`, `.md5`, `.sha1`).
Adding a module to that set makes every future release bigger, permanently.

Check where the org stands before promising a release — the last publish's log says it plainly:
```bash
R=$(gh run list --workflow=central-publish.yml --limit 1 --json databaseId --jq '.[0].databaseId')
gh run view $R --log | grep -iE "limit|over 90%"
```
As of Sept 2026 the `wildware` org is **over** its monthly File Count and Release Count limits,
with enforcement starting **1 October 2026**. Tell Shaun the position and let him decide; it is his
account, not a technical detail to absorb silently.

## When it goes wrong

| Where it died | What to do |
|---|---|
| Before **Tag it** | Nothing happened. Fix, re-run, no cleanup. |
| After **Tag it**, nothing on Central | Delete the tag — `git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z` — then fix and start again. Safe *only* because nothing reached Central. |
| Central says `FAILED` | Nothing published; the JSON says why. Delete the tag, fix, retry. |
| Not `VALIDATED` after ten minutes | `gh workflow run central-status.yml -f deployment_id=<id>` |
| Already `PUBLISHED` and wrong | Nothing can be undone. The only move is a new version. |

## Red flags — stop

- About to run `central-publish.yml` without Shaun confirming **this** version, in **this** conversation
- About to release because work merged or a feature finished, rather than because he asked
- About to delete a tag that has reached Central, or force anything
- About to "just re-run" a release that failed on a flaky test
- About to bump a version number in a file — there isn't one; the tag decides

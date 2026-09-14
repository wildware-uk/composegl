# composegl

## Releasing: snapshots by default, full versions on request only

**Never cut a release unless Shaun has explicitly asked for it in that message.**
Merging a PR is not a request to release. Finishing a feature is not a request to
release. "Land it" means merge to master and stop.

Work accumulates on master as `X.Y.0-SNAPSHOT`. Features land there and wait. A full
version goes out only when Shaun gives the go-ahead, and then it is one release
covering everything that accumulated since the last one.

### How the version is decided

There is nothing in the repository to edit. `build.gradle.kts` works it out from git:

| State of the tree | Version |
|---|---|
| Exactly on a `vX.Y.Z` tag | `X.Y.Z` — a real release |
| Any commit after the last tag | next minor as `X.Y+1.0-SNAPSHOT` |

So master normally builds as a snapshot on its own, and that is the intended state.
**A `vX.Y.Z` tag sitting on master is what breaks this** — it makes master build as a
release version, and the commit after it skips a minor. If a tag was cut prematurely,
delete it (`git tag -d`, `git push origin :refs/tags/vX.Y.Z`) rather than working
around it. Safe as long as that version never reached Maven Central.

### Cutting a release, when asked

`gh workflow run release.yml -f kind=patch|minor|major` — builds, signs, tags, and
uploads to the Sonatype portal. That step is undoable; the deployment can be dropped.

Publishing is a **second, separate, permanent** step:
`gh workflow run central-publish.yml -f deployment_id=<id> -f confirm=X.Y.Z`, with the
id from the release run's "Central validated the bundle" step. A version on Maven
Central can never be deleted, replaced or edited. Confirm before running it.

`-f kind=snapshot` publishes a snapshot and tags nothing. That is the one release-ish
thing that does not need a go-ahead.

### Why the cadence matters

Each release publishes **322 files** to Maven Central (15 modules × 5 artifacts × 4
files: the file plus `.asc`, `.md5`, `.sha1`). The free monthly threshold is ~1,167
files, so roughly **3 releases a month** is the ceiling. Four releases in four days in
September 2026 put the `wildware` org over its limit; enforcement starts 1 October
2026. This is the concrete reason for snapshot-by-default.

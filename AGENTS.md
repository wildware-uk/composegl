# Agents working on composegl

## The version

The version Gradle builds is the version we are building towards. Check it with:

```bash
./gradlew -q :composegl-ui:properties | grep '^version:'
```

**Do not increase the version number without explicit instructions** from the repository owner in
that message. That covers every way it can move:

- no `vX.Y.Z` tag, created or pushed;
- no release workflow run (`release.yml` with `patch`, `minor` or `major`, or `central-publish.yml`);
- no edits to the version logic in `build.gradle.kts`, and no `-PcomposeglVersion` passed to change it.

The number is not written in a file. `build.gradle.kts` works it out from git: exactly on a `vX.Y.Z`
tag it is `X.Y.Z`, and on any later commit it is the next minor as `X.Y+1.0-SNAPSHOT`. Finishing a
feature or merging work is not a reason to move it. Work lands on master and builds as the current
snapshot until the owner asks for a release. See `CLAUDE.md` for how a release is cut when asked.

When a release is cut on the owner's instruction, the docs follow it in the same change: the version
line at the top of `README.md` and `docs/wiki/Home.md`, and every dependency snippet in `README.md`
and `docs/wiki`, say that version.

## Definition of done

A change is not done until **the wiki is updated for every feature it adds or changes**:

- New feature: documented on the wiki page it belongs to (or a new page linked from `Home.md`), with
  a short example that uses the real API.
- Changed feature: every page that mentions it says what is true now. Renamed or removed API is fixed
  everywhere it appears in `docs/wiki` and `README.md`, not only where the change was made.
- Every class, function and parameter named in the docs exists with that shape in the code.
- The pages live in `docs/wiki` and land in the same change as the code. After it is on master, run
  `bash docs/wiki/push.sh` so the GitHub wiki matches; editing `docs/wiki` alone does not publish it.

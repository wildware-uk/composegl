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

# Releasing

How a version of ComposeGL gets onto Maven Central.

**The short version:** `git tag v0.2.0 && git push --tags`. Everything else is
already set up.

---

## What is published

Six modules, under `uk.wildware.composegl`:

| | |
|---|---|
| `composegl-ui` | the toolkit. JVM and Linux x64 |
| `composegl-effects` | the shipped shader effects. JVM and Linux x64 |
| `composegl-gdx` | the LibGDX backend. JVM |
| `composegl-lwjgl3` | the raw OpenGL backend. JVM |
| `composegl-android` | the Android half of a backend. An `.aar` |
| `composegl-testing` | the shared scenes and golden comparison. JVM |

The demos and the spikes are not published, and the list that decides is in the
root `build.gradle.kts`. Getting that wrong is permanent, which is why it is a
list of names rather than a rule about what a module is called.

---

## The version comes from the tag

`build.gradle.kts` runs `git describe --tags` and reads the answer:

| what git says | the version |
|---|---|
| `v0.2.0` | `0.2.0` |
| `v0.2.0-4-gabc1234` | `0.2.0-SNAPSHOT` |
| nothing | `0.1.0-SNAPSHOT` |

So there is no number to bump in a file, no commit that says "prepare 0.2.0", and
no way for the tag and the artifact to disagree.

It also means CI must check out the **whole** history. A shallow clone has no
tags, and would publish a snapshot over whatever was asked for.

---

## What the tag sets off

`.github/workflows/release.yml`, which builds everything again — a tag is not a
promise that anything still compiles — and then runs `publishToMavenCentral`.

That **uploads** the release and leaves it sitting in the Central portal for a
human to press the button on. Deliberate: a version on Central can never be
deleted or replaced, so the last step is a person looking at it.

---

## The four secrets

The workflow does nothing until these exist in the repository's settings:

| | |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | the two halves of a user token from [central.sonatype.com](https://central.sonatype.com) |
| `MAVEN_CENTRAL_PASSWORD` | |
| `SIGNING_KEY` | a GPG secret key, ASCII-armoured: `gpg --armor --export-secret-keys` |
| `SIGNING_PASSWORD` | its passphrase |

Central refuses unsigned artifacts, so the public half of that key has to be on a
keyserver — `keys.openpgp.org` or `keyserver.ubuntu.com` — before the first
release.

The namespace `uk.wildware` also has to be verified once, by putting a code the
portal gives you into a **DNS TXT record on `wildware.uk`**.

---

## Trying it without publishing anything

```bash
./gradlew publishToMavenLocal
```

Everything lands in `~/.m2/repository/uk/wildware/composegl`. Point a real game
at it with `mavenLocal()` and you are testing exactly what a release would be.

---

## One thing that is not obvious

`composegl-android` ships an **empty** javadoc jar. Central refuses a release
with no javadoc jar at all, and the Android plugin's generator is a version of
Dokka old enough to fail on a modern JDK's version string. Everything in that
module is documented in the source and here. The other five generate theirs
normally.

---

## What next

- **[[Backends]]** — which module a game actually needs
- **[[Testing]]** — what CI runs before any of this

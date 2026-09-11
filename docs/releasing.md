# Releasing

How a version of ComposeGL gets onto Maven Central.

**The short version:** `git tag v0.2.0 && git push --tags`. Everything else is
already set up.

---

## What is published

Seven modules, under `dev.wildware.composegl`:

| | |
|---|---|
| `composegl-ui` | the toolkit. JVM and Linux x64 |
| `composegl-effects` | the shipped shader effects. JVM and Linux x64 |
| `composegl-gdx` | the LibGDX backend. JVM |
| `composegl-lwjgl3` | the raw OpenGL backend. JVM |
| `composegl-android` | the Android half of a backend. An `.aar` |
| `composegl-robovm` | the iOS half of a backend. JVM |
| `composegl-testing` | the shared scenes and golden comparison. JVM |

The demos and the spikes are not published, and the list that decides is in the
root `build.gradle.kts`. It is opt-in: a new module stays unpublished, silently,
until its name is added there. Getting that wrong is permanent, which is why it is a
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

`.github/workflows/release.yml`, which first refuses any tag that is not exactly
`vX.Y.Z` — anything else builds a snapshot, and Central refuses snapshots — then
builds everything again (a tag is not a promise that anything still compiles) and
runs `publishToMavenCentral`.

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

Central refuses unsigned artifacts, and it checks every signature against a
keyserver, so the **public** half of that key has to be on one before the first
release. Without it every file fails with "Could not find a public key by the key
fingerprint".

`keys.openpgp.org` takes JSON, not a form:

```bash
gpg --armor --export <fingerprint> > pub.asc
python3 -c "import json; print(json.dumps({'keytext': open('pub.asc').read()}))" \
  | curl -sS -H 'Content-Type: application/json' --data @- \
    https://keys.openpgp.org/vks/v1/upload
```

It answers `"unpublished"` for the address on the key, which is the point: the key
material is served, the email is not, unless whoever owns that address confirms a
mail. Signatures verify either way. Check it took:

```bash
curl -sS https://keys.openpgp.org/vks/v1/by-fingerprint/<fingerprint> | head -1
```

The namespace `dev.wildware` also has to be verified once, by putting a code the
portal gives you into a **DNS TXT record on `wildware.dev`**. That is done — it is
why the coordinates are `dev.wildware` and not `uk.wildware`, which was never
verified and which Central refused with "Namespace is not allowed". Check the
record is still there with:

```bash
dig +short TXT wildware.dev
```

---

## Trying it without publishing anything

```bash
./gradlew publishToMavenLocal
```

Everything lands in `~/.m2/repository/dev/wildware`. Point a real game at it with
`mavenLocal()` and you are testing exactly what a release would be.

To try the **secrets** without publishing anything, run the Release workflow by
hand from the Actions tab. A run started that way does everything a real release
does — builds, asks Central whether it likes the token, signs every artifact —
and uploads nothing. Do that after changing a secret. Finding out that a key is
wrong is cheap on a run that cannot publish and expensive on one that can.

---

## One thing that is not obvious

`composegl-android` ships an **empty** javadoc jar. Central refuses a release
with no javadoc jar at all, and the Android plugin's generator is a version of
Dokka old enough to fail on a modern JDK's version string. Everything in that
module is documented in the source and here. The other six generate theirs
normally.

---

## When it goes wrong

The upload does not wait for Central to make up its mind — the plugin prints
"Skipping deployment validation!" and stops. So the release job asks afterwards,
and fails the run if Central refused. To ask about a deployment by hand, run the
**Central deployment status** workflow with the id the release printed.

A refused deployment publishes nothing, so the fix is to correct what it named and
re-run the release job on the same tag. Nothing has to be renumbered.

---

## What next

- **[[Backends]]** — which module a game actually needs
- **[[Testing]]** — what CI runs before any of this

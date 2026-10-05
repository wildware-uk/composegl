# Solo-dev project settings

## Repo
- GitHub repo: wildware-uk/composegl
- Main branch: master
- Worktree folder: .claude/worktrees (gitignored)
- Landing: direct push to master (no pull request). Squash the branch to one or a few clear
  commits, rebase or merge `origin/master`, retest, then `git push origin HEAD:master`.
- CI to check after landing: `CI` (`.github/workflows/ci.yml`). The `Pages` workflow also runs on
  every push to master; red there counts too.

## Labels
- Claim label: `in progress`
- Blocked label: `blocked`
- Epics / tracking issues (never picked): `roadmap` label
- Priority: none set; oldest first.

## Read first
- `AGENTS.md` (version rules and the definition of done: the wiki is updated in the same change)
- `CLAUDE.md` (release policy)

## Standing owner rules
- Never move the version: no `vX.Y.Z` tag, no `release.yml` with `patch|minor|major`, no
  `central-publish.yml`, no edits to the version logic in `build.gradle.kts`. Merging is not a
  release request.
- The library owns rendering. Shaders, batching, atlases, framebuffer layers and effects live in
  the shared renderer (`composegl-render` / `composegl-ui`), never inside a backend module. A
  backend is a thin wrapper: GL bindings, state save/restore, input, platform services.
- Every feature needs tests that drive real input through composed UI (`uiTest`) where the change
  is behavioural, not just unit tests of helpers.
- A feature added or changed is documented in `docs/wiki` in the same change, with a short example
  using the real API. After it lands on master, run `bash docs/wiki/push.sh` so the GitHub wiki
  matches. Pure internal performance work with no API change needs no wiki edit; say so on the card.
- "The showcase" means `composegl-demo-web` (published to GitHub Pages), not
  `composegl-demo-showcase`. A visible feature goes into `composegl-demo-web` (a `Section` in
  `ShowcaseState.kt`, routed in `Showcase.kt`, built with `Page`/`Card` from `Parts.kt`).
- Backtick test names in any `commonTest` source set contain no commas: Kotlin/Native rejects
  them. Use a dash.
- Before calling anything blocked upstream or on missing hardware, test the claim against the
  newest version of the dependency. Twice it was a stale pin.
- Commit messages: conventional prefix (`feat(ui):`, `fix(renderer):`, `perf(render):`...) then a
  plain-words sentence, matching `git log`.

## Decisions a developer must not make
- Any issue whose title or body starts "DO NOT START THIS FEATURE UNTIL EXPLICITLY INSTRUCTED".
- Releases of any kind except `-f kind=snapshot`, and only after the owner asked.
- Public API removals or renames not asked for by the issue.

Everything else is yours. **Never ask the owner which fix or approach to take** (owner,
2026-10-05: "its your job to pick the best solutions"). That includes speed-against-look
trade-offs, small visual differences, and which of several designs to build. Pick the best one:
fast on a phone, no frozen or wrong animation, and as close to today's look as you can get it.
Write the choice and the reasons on the issue, with before/after pictures and numbers, and carry
on. Never label an issue `blocked` or file one as "an owner's call" for a technical choice.

## Backlog
- Skip: issues titled "DO NOT START THIS FEATURE UNTIL EXPLICITLY INSTRUCTED" (#189-#192); the
  `roadmap` label; `blocked-externally` unless the newest dependency version now allows it.
- Order: oldest first.
- Owner requests are filed as issues with: plain `gh issue create`.

## Build notes
- JDK 21, Gradle wrapper. Gradle runs in parallel by default; GL suites need `--no-parallel`.
- Module tests: multiplatform modules (`composegl-ui`, `composegl-render`, `composegl-korge`, ...)
  use `:<module>:jvmTest`; plain JVM modules (`composegl-gdx`, `composegl-lwjgl3`, demos) use
  `:<module>:test`.
- After touching any `commonTest`: `./gradlew :<module>:compileTestKotlinLinuxX64` (catches
  Native-only failures in seconds).
- No Chrome on this machine, so wasm browser tests (`wasmJsBrowserTest`, `ShowcaseBrowserTest`)
  may not run locally; if they cannot, say so on the card and rely on CI after landing.
- Wrap every Gradle run in `timeout` and run it in the background; a GL context that never comes
  up hangs forever.

## Per-ticket tests
- Always: `./gradlew :<each module you touched>:jvmTest` (or `:test`), plus
  `:composegl-ui:jvmTest` if you touched `composegl-render` or `composegl-ui`.
- Plus, if the change touches drawing, the renderer, or a backend: the GL suites on a real
  context, under Xvfb:
  `xvfb-run -a -s "-screen 0 1280x1024x24" ./gradlew :composegl-gdx:test :composegl-gdx:testGl30 :composegl-lwjgl3:test :composegl-lwjgl3:testGl30 :composegl-lwjgl3:testGles3 :composegl-lwjgl3:testGles2 --no-parallel`
  These compare against golden images; a golden that changes must be explained on the card.
- Verdict: Gradle exit code; failing goldens leave actual/expected/diff under
  `**/build/screenshots/**`.
- Known failures on main: on this machine `:composegl-lwjgl3:testGles2` and `testGles3` crash
  after every test has passed (master does the same; found on #234). Read the test report, not
  the exit code, for those two. Check any other failure against `origin/master` in a detached
  worktree in the same session.

## Never per ticket
- The whole `./gradlew build` and the native iOS legs. CI runs them after landing.
- The Android emulator suite (`connectedAndroidDeviceTest`).
- Any release workflow.

## Looking at it
- Wiki pictures with the real renderer:
  `COMPOSEGL_DOC_SHOTS=<your scratch dir> xvfb-run -a ./gradlew :composegl-demo:docShots`
- Showcase pages: `ShowcaseBrowserTest` writes `composegl-demo-web/build/screenshots` (needs Chrome).
- Performance issues: show before/after numbers (frame time, draw calls, allocations, GL calls) from
  a test or a benchmark, not just a claim.
- Display / GPU: run GL work under `xvfb-run -a`. An NVIDIA RTX 2070 SUPER is present; check
  `nvidia-smi` if rendering looks slow or falls back to software.

## Review rounds
- This replaces the agent file's five-round cap. Keep going past five rounds while each failed
  round finds something new and you fix it. Release and label `blocked` only when the same finding
  comes back after you fixed it, or after round 10. The lead, not the owner, unblocks it.
- When a rare case keeps failing review, prefer making that case take the old, slower road over
  piling on special cases; keep the speed-up for the common case.

## Reviewer reads
- `AGENTS.md`, `CLAUDE.md`, and the "Standing owner rules" above.
- Pass this rule to every reviewer: run every command in the foreground under `timeout` (at most
  600 s each); never `run_in_background` or Monitor. A reviewer is a subagent, and on 2026-10-05
  two reviewers hung for an hour each waiting for a background Gradle run's notice that never came.
- A reviewer whose token count has not moved for 20 minutes and that has no running process is
  hung: TaskStop it and spawn a fresh one for the same round.
- The wiki page(s) the change touches in `docs/wiki`.

## Dashboard
- Project slug: `composegl`
- Card style: one card per issue, replies on it with `post_message` and `update_id`.
- Pictures: one per message, full size; post a picture on anything visible. Owner judges by seeing it.
- Upload: `create_upload` returns a URL on `https://agents.wildware.dev`; keep its path and token
  but PUT to `http://127.0.0.1:8010` instead:
  `curl -X PUT -H "Content-Type: image/png" --data-binary @shot.png http://127.0.0.1:8010/api/upload/<id>.<token>`
  Expect `201`; then pass the id in `media_ids`.
- Questions for the owner go through `request_input` on the dashboard, never the terminal.

## Box
- Load cap: 20 (24 cores, other projects share the machine) - wait above it before any heavy run.
- Memory: 31 GB shared with other projects' builds and an Android emulator. Before a heavy run,
  `free -g` must show at least 6 GB available; below that, wait as for load. A run the system
  killed for low memory is rerun once memory is back, without asking anyone.
- Shared paths to namespace: `COMPOSEGL_DOC_SHOTS` and any other output dir - use your own
  scratch dir. Never use a fixed `/tmp` path.
- Other: the GPU and the Gradle daemon cache (`~/.gradle`) are shared with other projects. Never
  run `./gradlew --stop` or kill a daemon you did not start.

## Launch notes
- Land straight on master; there are no pull requests in this project.
- Never move the version or cut a release.

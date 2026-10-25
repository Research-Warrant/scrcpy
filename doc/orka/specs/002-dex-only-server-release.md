# Spec 002 — Dex-only server release for Orka

- **Status:** Draft
- **Date:** 2026-10-23
- **Owner:** Platform
- **Ticket:** [ORK-101](https://app.clickup.com/t/1248h53atk3) — Publish the dex-only scrcpy server that Orka embeds as a tagged release
- **Related:** scrcpy-fork Spec 001 — Pull-request CI and AOA contract tests (dependency: it adds the
  PR workflow and `doc/orka/aoa-lane-contract.md`, and back-merges master into develop; its
  decision 2 hands this spec the `release` job's wait on `test-scrcpy-server`, work item 6);
  orka-backend Spec 0023 — android_webrtc_scrcpy stream type on the embedded fork server (overlap:
  it embeds the file this spec publishes and pins `ServerSHA256` to it); POC sources
  `tools/scrcpy-fork/README.md`, `GADS/provider/scrcpy/jar.go`,
  `GADS/resources/scrcpy-server-v5.0.1-orka.1` (+ `.LICENSE`, `.NOTICE`), and the POC contract
  `docs/specs/scrcpy-video-in-gads/aoa-lane-contract.md` §6.2-6.3 (orka-POC branch
  `feature/scrcpy-server-android-stream`).

## Goal

Orka's provider embeds one scrcpy-server file and trusts it by SHA-256 only. The POC built that
file on a laptop with `server/build_without_gradle.sh`. Nothing in Research-Warrant/scrcpy says
which build is the one Orka ships, and nobody can download it.

This spec adds a small release path for that dex-only file and keeps it apart from the `rw-*`
client releases. A tag `v5.0.1-orka.N` builds the server with a pinned toolchain and publishes
`scrcpy-server-v5.0.1-orka.N` plus `SHA256SUMS.txt`. The first release, `v5.0.1-orka.1`, is cut
from `7ef1607c`, and the spec records whether CI produces the same bytes as the POC's local
build. orka-backend 0023 then pins whatever this release publishes.

## Current state

Research-Warrant/scrcpy. The default branch is `master` (`origin/HEAD -> origin/master`). Lines
below are on `master` at `1b8630f3` unless marked otherwise. Fetched on 2026-10-23.

**Branches and trees**
- `develop` is at `7ef1607cf8347c52d1b603ff5399298ad4cabab2`, the merge of PR #16 (fix
  `bc6cf662d878447eac6b51f17826024d272207b7` on top of the AOA transport, PR #15).
- `master` is at `1b8630f3`, the merge of PR #17 (develop into master, 2026-10-23 16:59 +0200).
  This means **the promotion of develop to master has already happened**. `git diff 7ef1607c
  1b8630f3` touches only `.github/workflows/release.yml` (+29 lines, the PR #14 CI fix).
- The `server/` tree is `e1b32de3a6cd8be69c25a779157910c73e4e3d8a` at `bc6cf662`, at `7ef1607c`
  and at `1b8630f3`. The server source is identical in all three.
- That stops holding once Spec 001 lands. Its PR 2 adds `SoonBlockedPrivateApi` to the
  `@SuppressLint` on `server/src/main/java/com/genymobile/scrcpy/aoa/AccessoryOpener.java:23`, so
  the `develop` `server/` tree differs from `e1b32de3`, and `master` follows at the next promotion.
  Whether the dex bytes change is **Not established** (001 acceptance 4). After 001, only
  `7ef1607c` and `1b8630f3` keep the exact tree the POC built.

**The existing release (`rw-*`)**
- `.github/workflows/release.yml:3-10` runs on a push to `master` and on `workflow_dispatch`. It
  has no tag trigger.
- `:29-37` sets the version to `rw-<meson version>-<run_number>` and the tag to
  `rw-v<meson version>-<run_number>`.
- `:68-91` (`build-scrcpy-server`) runs `release/build_server.sh`, which is a Gradle
  `assembleRelease`. It copies `server-release-unsigned.apk` to `scrcpy-server`
  (`release/build_server.sh:11-14`).
- `:93-106` (`test-build-scrcpy-server-without-gradle`) builds the dex-only file, then throws it
  away: there is no upload step.
- `:719-723` packages `scrcpy-server-$VERSION` (`release/package_server.sh:9`) and writes
  `SHA256SUMS.txt` over seven files (`release/generate_checksums.sh:7-14`).
- `:659-668`: the `release` job needs `prepare-release-metadata`, `build-scrcpy-server` and the
  seven client packages, **not** `test-scrcpy-server`, so a red server test does not stop a
  release. Spec 001 (decision 2 and Out of scope) hands this gap to this spec; work item 6 closes
  it.
- `:731-755` creates the tag and the GitHub release on a push to master only. `gh release create`
  has no `--latest` flag (`:753-755`), so every `rw-*` release becomes Latest.
- `release/` at `7ef1607c` holds only the upstream scripts (`build_*.sh`, `package_*.sh`,
  `generate_checksums.sh`, `test_*.sh`, `release.sh`, `build_common`). `release/.gitignore`
  ignores `/work` and `/output` only.

**Releases, tags and runs**
- `rw-v5.0.1-8` was published 2026-10-23T05:44Z from `6d196573`, before AOA merged. Its
  `scrcpy-server-rw-5.0.1-8` is a Gradle APK of 733930 bytes and has no `aoa=`.
- Run `39974414465` (Build, master push of PR #17) has finished (conclusion `failure`:
  `test-scrcpy-server` is red, see Spec 001). Its `release` job still published `rw-v5.0.1-9`
  from `1b8630f3` (2026-10-23T15:15Z), now the Latest release: a Gradle APK *with* `aoa=`, whose
  bytes differ from the embedded file.
- There is no `v*-orka.*` tag. The fork's remote has only 7 tags, all of its own `rw` release
  series (`rw-v3.3.4,` to `rw-v5.0.1-9`); none of upstream's `v1.0`…`v5.0.1` tags is on it.
  Upstream v5.0.1's commit `a60891ae` is in develop's history, which is all this design needs.

**The dex-only build**
- `server/build_without_gradle.sh:17` hardcodes `SCRCPY_VERSION_NAME=5.0.1`.
- `:19-20` default to platform `36` and build-tools `36.0.0`.
- `:24` defaults `BUILD_DIR` to `build_manual` relative to the current directory.
- `:107-115` runs d8 and renames `classes.zip` to `scrcpy-server`.
- The result is a zip with one `classes.dex` entry, timestamped at the epoch, so the zip layer adds
  no nondeterminism.
- The version is also set in `server/build.gradle:11` (`versionName "5.0.1"`) and in
  `meson.build:2` (`version: '5.0.1'`).
- `doc/` has no `orka/` folder yet. Spec 001 adds `doc/orka/aoa-lane-contract.md`.

**CI toolchain evidence**
- Run `39903884854`, job `test-build-scrcpy-server-without-gradle`, succeeded on runner image
  `ubuntu-24.04` `20261012.334.1`.
- It used Zulu `17.0.20+8` (resolved from `java-version: '17'`), `android-36` and build-tools
  `36.0.0`. Both SDK packages were preinstalled on the image.

**POC reference (orka-POC, `feature/scrcpy-server-android-stream`)**
- `GADS/provider/scrcpy/jar.go:24-32` pins `ServerVersion = "5.0.1"`,
  `ServerResource = "scrcpy-server-v5.0.1-orka.1"` and `ServerSHA256 = 8c3255380f76…85a3`. The
  comment at `:26-28` still says the jar is "also kept as patches in tools/scrcpy-fork", but POC
  commit `dbaf74b` deleted those patches.
- The embedded file is 108861 bytes, SHA-256
  `8c3255380f76e8756531c82a1954c0c57ab4601324cf433d937bc894b79585a3`.
- `tools/scrcpy-fork/README.md:3-23` gives the rebuild steps. It also says PR #16 is open, which is
  stale because PR #16 has merged.
- `GADS/resources/scrcpy-server-v5.0.1-orka.1.NOTICE` names commit `bc6cf662` but still carries
  the previous build's `Size: 108595` and `SHA-256: 9c6c9bf7…f657`, and a `Patches:` line that
  points at the deleted patches. orka-backend 0023 fixes this when it copies the file (see
  Handoff).
- Contract §6.3 (POC `aoa-lane-contract.md:432-451`) already plans this release. The asset is the
  `build_without_gradle.sh` file. If CI produces other bytes, the embedded file and `ServerSHA256`
  follow the asset.

**Local reproduction (2026-10-23, for this spec)**
- `git archive 7ef1607c server` built twice with OpenJDK `17.0.20.1`, `android-36` (Pkg.Revision
  2) and build-tools `36.0.0` (Pkg.Revision `36.0.0`, `d8 --version` `8.10.9-dev`) gives 108861
  bytes and `8c3255380f76…85a3` both times. That matches the POC embed.
- The same tree built with OpenJDK 27 (`-source 1.8 -target 1.8`) gives `c328ae32cb7f…308c`. The
  JDK major version changes the bytes. The JDK patch level has not been shown to matter.

## Design

### Work item 1. `release/build_orka_server.sh` (new)

One script does the whole job. CI runs it, and an operator can run it locally, so both build the
same way. It follows the style of the other `release/*.sh` scripts (`set -e`, `cd` to the repo
root).

**The script is tooling; the tag is the source.** The script runs from whatever checkout carries
it (`master` or `develop`), but it never builds or reads that checkout's `server/`,
`server/build.gradle` or `meson.build`. It builds the tag's tree. This is what lets the first
release build `7ef1607c`, a commit that predates the script (work item 5).

- **Input:** `TAG` (required), `ANDROID_HOME`, and an optional `EXPECT_SHA256`.
- **Work and output directories.**
  - `W=release/work/orka-server`, with `src/`, `a/` and `b/` under it. `release/.gitignore`
    already ignores `/work`. The script deletes `W` at the start of each run, so a rerun never
    reuses an old build.
  - `release/output-orka/`, also deleted at the start. The same PR adds `/output-orka` to
    `release/.gitignore`, so a local run leaves `git status` clean.
- **Tag check.** Reject `TAG` unless it matches `^v([0-9]+\.[0-9]+\.[0-9]+)-orka\.([1-9][0-9]*)$`.
  This runs before `TAG` is used in any command or path.
- **Source tree.**
  - If `refs/tags/$TAG` is missing locally, run `git fetch --no-tags origin
    "refs/tags/$TAG:refs/tags/$TAG"`.
  - Require `git cat-file -t "refs/tags/$TAG"` to print `tag`, so the tag is annotated.
  - Extract the tag's tree with `git archive "$TAG" server meson.build | tar -x -C "$W/src"`.
    This is how the local baseline in Current state was built.
- **Version check.** The version captured from the tag must equal all three of these, read from
  `$W/src`, or the script exits non-zero with a message naming the mismatch:
  - `SCRCPY_VERSION_NAME` in `server/build_without_gradle.sh`;
  - `versionName` in `server/build.gradle`;
  - `version` in `meson.build`.

  This matters because the provider passes `5.0.1` as the server version, and the server exits on
  any other value (contract §6.2).
- **Toolchain check.**
  - Require `javac -version` to report major version 17, or exit non-zero.
  - Require `$ANDROID_HOME/platforms/android-$ANDROID_PLATFORM/android.jar` and
    `$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/d8`, with the defaults `36` and `36.0.0` from the
    build script, or exit non-zero.
  - Compare the `Pkg.Revision` in each package's `source.properties` with the pins in the script:
    `PIN_PLATFORM_REVISION=2` and `PIN_BUILD_TOOLS_REVISION=36.0.0` (the local baseline). Treat a
    mismatch like a SHA mismatch (decision 6): print it loudly, record it, and do not fail.
- **Build twice.** Run `BUILD_DIR="$W/a" "$PWD/$W/src/server/build_without_gradle.sh"`, then the
  same with `$W/b`, and `cmp` the two outputs. The absolute path matters because the build script
  resolves its sources from `dirname "$0"`. If the outputs differ, fail with "build is not
  deterministic on this host".
- **Package.** Write these files into `release/output-orka/`:
  - `scrcpy-server-$TAG` (the build output);
  - `SHA256SUMS.txt`, from `sha256sum scrcpy-server-$TAG`, in the format of
    `generate_checksums.sh`;
  - `provenance.md`, described below.
- **`provenance.md`** records:
  - the tag and the commit it peels to (`git rev-parse "$TAG^{commit}"`);
  - the `server/` tree id (`git rev-parse "$TAG:server"`);
  - the commit the script itself ran from (`git rev-parse HEAD`), labelled as the tooling commit;
  - the output of `java -version`;
  - the `Pkg.Revision` of the platform and of build-tools (from each `source.properties`), and a
    line `toolchain matches pins: yes|no (<which package, expected, found>)`;
  - `d8 --version`;
  - the runner image name and version when the `ImageOS` and `ImageVersion` variables are set;
  - the size in bytes and the SHA-256;
  - the double-build result;
  - when `EXPECT_SHA256` is set, a line `matches expected build: yes|no (expected <sha>)`;
  - one licence paragraph: Apache-2.0, a modified build of scrcpy v5.0.1, with a link to `LICENSE`
    at the tag.
- **A mismatch is not a failure** (decision 6). This covers both the SHA and the SDK revisions.
  The script prints it loudly and records it in the file.

### Work item 2. `.github/workflows/orka-server-release.yml` (new)

```yaml
name: Orka server release

on:
  push:
    tags:
      - 'v*-orka.*'
  workflow_dispatch:
    inputs:
      tag:
        description: 'Existing tag to build and release (v<version>-orka.<n>)'
        required: true
      expect_sha256:
        description: 'SHA-256 of a local build to compare against (optional)'
        required: false

permissions:
  contents: write

concurrency:
  group: orka-server-release-${{ inputs.tag || github.ref_name }}
  cancel-in-progress: false

jobs:
  release:
    runs-on: ubuntu-24.04
    env:
      TAG: ${{ inputs.tag || github.ref_name }}
      EXPECT_SHA256: ${{ inputs.expect_sha256 }}
      ANDROID_PLATFORM: '36'
      ANDROID_BUILD_TOOLS: '36.0.0'
      GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    steps:
      - uses: actions/checkout@v6
        with:
          ref: ${{ github.sha }}  # the tooling; the script extracts the tag's tree itself
          fetch-depth: 0          # brings the tags, so the script can read the annotated tag
      - uses: actions/setup-java@v5
        with:
          distribution: 'zulu'
          java-version: '17.0.20'
      - name: Install the pinned SDK packages
        run: |
          yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install \
            "platforms;android-${ANDROID_PLATFORM}" "build-tools;${ANDROID_BUILD_TOOLS}"
      - name: Refuse to overwrite a published release
        run: |
          if gh release view "$TAG" >/dev/null 2>&1; then
            echo "Release $TAG already exists; cut a new orka.N instead" >&2; exit 1
          fi
      - name: Build, check and package
        run: release/build_orka_server.sh
      - name: Job summary
        run: cat release/output-orka/provenance.md >> "$GITHUB_STEP_SUMMARY"
      - name: Publish
        run: |
          gh release create "$TAG" \
            "release/output-orka/scrcpy-server-$TAG" release/output-orka/SHA256SUMS.txt \
            --verify-tag --latest=false --title "$TAG" \
            --notes-file release/output-orka/provenance.md
```

- The checkout is the workflow's own commit (`github.sha`), never the tag. On a tag push the two
  are the same commit. On a dispatch from `master` they differ: the script comes from `master` and
  builds the tag's tree, which is how `v5.0.1-orka.1` builds `7ef1607c` (work item 1).
- The `yes |` answers the licence prompt if the image ever drops the packages, as Spec 001's
  `server-checks.yml` does. `sdkmanager` cannot pin a package revision, so the script's
  `Pkg.Revision` check is what makes a drift visible (decision 3).
- Apart from work item 6's one `needs` entry, `release.yml` is not edited. Its triggers (`:3-10`)
  do not include tag pushes, so the two workflows never run on the same event.
- `run:` blocks read the inputs only through `env` and never inline `${{ inputs.* }}`. The tag
  pattern is checked by the script before the tag is used in a path. Together these block script
  injection through `workflow_dispatch` inputs.
- `--verify-tag` makes `gh` fail rather than create a tag. Tags are always created by a person
  (decision 5).

### Work item 3. `doc/orka/server-release.md` (new, next to Spec 001's contract copy)

The operator page covers:
- what the Orka asset is, and why it is not the `rw-*` Gradle APK;
- the naming rule (decision 2);
- how to cut a release: an annotated tag, then the push trigger, or the dispatch for a commit
  that does not carry the workflow;
- how to rebuild locally: from a `master` or `develop` checkout, `TAG=<tag>
  release/build_orka_server.sh` builds that tag's tree, even for a tag that predates the script;
  and how to verify a download with `sha256sum -c SHA256SUMS.txt`;
- the rule that a byte change means a new `orka.N` and a matching orka-backend `ServerSHA256`
  change, while `versionName` stays `5.0.1`.

It replaces the POC's `tools/scrcpy-fork/README.md` as the rebuild reference. orka-backend 0023's
`docs/scrcpy-fork.md` links here instead of copying the steps.

### Work item 4. `doc/orka/aoa-lane-contract.md` §6.3 (file added by Spec 001)

- Replace the conditional "When the owner publishes the fork release…" paragraph with the actual
  release: tag, commit, asset name, size, SHA-256, and whether CI matched the local build.
- Drop the reference to `tools/scrcpy-fork/README.md`.

### Work item 5. Cut `v5.0.1-orka.1` (operations, after work items 1-3 and 6 reach `master`)

Work items 1-3 and 6 reach `master` only through the owner's promotion of `develop` (rollout
step 3). The dispatch below waits for it. If the owner declines, the agent uses the decision 5(b)
fallback instead.

1. The owner creates an annotated tag on `7ef1607cf8347c52d1b603ff5399298ad4cabab2` and pushes
   it:

   ```sh
   git tag -a v5.0.1-orka.1 7ef1607c -m "Orka scrcpy-server 5.0.1 with aoa (PR #15, #16)"
   git push origin v5.0.1-orka.1
   ```

   That commit has no `orka-server-release.yml`, so the push starts nothing.
2. Dispatch the workflow from `master`. The run checks out `master` for the workflow and the script,
   and builds the tree of the tag, `7ef1607c` (work item 1):

   ```sh
   gh workflow run orka-server-release.yml --ref master \
     -f tag=v5.0.1-orka.1 \
     -f expect_sha256=8c3255380f76e8756531c82a1954c0c57ab4601324cf433d937bc894b79585a3
   ```

3. Record the outcome: add a `## Amendment (release, YYYY-MM-DD)` section to this spec, update the
   contract §6.3 text, and send the handoff to orka-backend 0023 (below).

### Work item 6. Align the existing workflows with the release (two one-line edits)

Spec 001 (decision 2, Out of scope) hands the first edit to this spec. Each edit is its own commit
in the same PR as work items 1-3.

1. **`.github/workflows/release.yml:660-668`:** add `test-scrcpy-server` to the `release` job's
   `needs`. A red server test then stops a `rw-*` release instead of publishing next to it
   (decision 11). Commit: `ci(release): make the client release wait on the server tests`.
2. **`.github/workflows/server-checks.yml` (added by Spec 001):** change `java-version: '17'` to
   `'17.0.20'` in the shared setup. The PR job `server-dex` then reports its SHA with the release's
   JDK, so a PR's reported SHA and the next release's SHA cannot drift apart through the floating
   `17` (decision 3). Commit: `ci: build the server checks with the release JDK patch`.

### Handoff to orka-backend 0023

- **If the CI bytes match:** 0023 embeds the asset as `resources/scrcpy-server-v5.0.1-orka.1` with
  `ServerSHA256 = 8c3255380f76…85a3`, unchanged from the POC.
- **If they differ:** 0023 embeds the CI asset and pins its SHA-256. The POC's hardware evidence
  then no longer covers those bytes, so 0023's verification has to repeat one lane D and one lane E
  check on hardware (see Test plan, item 6).
- **Either way**, the `.NOTICE` that 0023 copies must name tag `v5.0.1-orka.1` and commit
  `7ef1607c` (server tree `e1b32de3`) and give the release's actual size and SHA. Under the
  decision 5(b) fallback it names the tagged develop commit instead, and states its one source
  difference from `7ef1607c` (the `@SuppressLint` annotation). Drop the stale `108595` and
  `9c6c9bf7…` lines and the `Patches:` line. The `jar.go` comment points at the tag, not at
  `tools/scrcpy-fork`.

## Decisions

1. **A separate server-only workflow on `v*-orka.*` tags. Do not add an asset to the `rw-*`
   release.** This is D6 from the port plan.
   - Alternative (b) was to add a dex-only asset to `release.yml`. That would tie the asset name to
     `run_number` (`rw-v5.0.1-N`), so orka-backend's resource name would change with every master
     push. It would also rebuild seven client packages to publish one 109 KB file.
   - **Recommendation: (a).** It leaves the `release.yml` build and the Gradle APK alone (work
     item 6 only adds a `needs` entry), and the resource name stays stable.
2. **Naming: tag `v<versionName>-orka.<n>`, asset `scrcpy-server-<tag>`, checksums
   `SHA256SUMS.txt`.**
   - The asset name equals orka-backend's `ServerResource`, so 0023 copies it with no rename.
   - The plan said "SHA256SUMS". This spec uses the `.txt` name that
     `release/generate_checksums.sh:14` already gives the `rw-*` releases.
   - `<n>` goes up for any byte change, including a toolchain change. `versionName` stays `5.0.1`,
     per contract §6.2 and an owner decision already taken.
3. **The toolchain is pinned where the tools allow it, checked where they do not, and recorded.
   Changing it is a release event.**
   - Fixed by the workflow: runner `ubuntu-24.04` (not `ubuntu-latest`) and Zulu `17.0.20` (the
     patch that the existing CI job used and that matches the local OpenJDK 17.0.20.1 build).
     Work item 6 gives Spec 001's PR job `server-dex` the same JDK patch, so the SHA a PR reports
     and the SHA the next release publishes come from the same `javac`.
   - Checked by the script: `sdkmanager --install` takes a package name, not a revision, and may
     install a newer revision of `platforms;android-36` or `build-tools;36.0.0`. The script
     compares both `Pkg.Revision` values with its pins (platform `2`, build-tools `36.0.0`) and
     treats a mismatch like a SHA mismatch: reported, recorded in the provenance, not fatal
     (decision 6). Updating a pin is a commit to the script and, if the bytes change, a new
     `orka.N`.
   - The local OpenJDK 27 build shows that the JDK major version changes the bytes. The pin is
     therefore a correctness setting, not a matter of taste.
4. **Every release proves determinism on its own runner by building twice.** It costs about 30 s.
   Without it, "the CI asset is the source of truth" could pin a file that the same toolchain cannot
   reproduce.
5. **People create tags, and the workflow only builds and publishes them.**
   - The first tag goes on exactly `7ef1607c`, through the dispatch path, because that commit
     predates the workflow. The dispatch runs the workflow and script from `master` and builds the
     tag's tree (work items 1-2).
   - From then on, a tag pushed on any commit that carries the workflow releases itself.
   - Alternative (b) is to tag a `develop` commit that carries the workflow and let the tag push
     release it. That is the fallback if `master` cannot receive the workflow because the owner
     does not promote `develop` (rollout step 3; `workflow_dispatch` only runs workflows that exist
     on the default branch).
     - That commit's `server/` tree is **not** `e1b32de3`: Spec 001 lands first and changes the
       `@SuppressLint` on `AccessoryOpener.java:23`. So the fallback rests on dex equality, not
       on tree equality.
     - Before the owner tags it, the candidate commit must produce SHA-256 `8c325538…85a3`. The
       evidence is Spec 001's `server-dex` summary for that commit (same JDK patch after work
       item 6) or a local run of the script against a local, unpushed tag. If Spec 001's CI
       `server-dex` run on `7ef1607c` (its acceptance 4) already showed a different SHA, that CI
       SHA is the expected value instead.
     - The tag push carries no `expect_sha256` input, so the published notes have no
       `matches expected build:` line. The owner's tag message states the one source difference
       from `7ef1607c` (the annotation), and the agent records the dex comparison in the
       amendment (work item 5 step 3).
     - If the bytes differ, the fallback ships new bytes and the "if they differ" handoff applies,
       including the hardware check (Test plan, item 6).
   - **Recommendation: (a)**, so that the tag names the commit the POC built.
   - Later tags on `develop` or `master` follow the same rule: a new `orka.N` is cut only when the
     server bytes differ from the latest `orka` release. A source change that leaves the bytes
     unchanged, such as Spec 001's annotation if d8 drops it, needs no release.
6. **A mismatch with the local build is recorded and does not block the release.**
   - The CI asset becomes the source of truth, and orka-backend 0023 pins its SHA. Contract §6.3
     already allows this: "The provider trusts the hash, not where the file came from".
   - Blocking would leave Orka without a published file over a difference that has no functional
     cause.
7. **Published assets are immutable.** The workflow refuses to run when the release exists, and
   the assets are never edited or replaced. A fix ships as `orka.<n+1>`. Once orka-backend pins a
   SHA, the release it came from must never be deleted.
8. **The `orka` releases are never Latest and never prereleases.**
   - `--latest=false` keeps the `rw-*` client release as the repository's Latest.
     `release.yml:753` marks every `rw-*` release Latest, so a later `rw-*` release stays Latest in
     any case.
   - Not marking them prerelease keeps them visible as stable provenance.
9. **Licence:** the assets ship with a notes paragraph and a link to the Apache-2.0 `LICENSE` at
   the tag, with no separate licence asset. The `.LICENSE` and `.NOTICE` files that travel with the
   embedded copy belong to orka-backend 0023.
10. **Tag protection (owner, repository settings).** Add a tag ruleset on `v*-orka.*` that blocks
    updates and deletions and limits creation to maintainers. If a tag moved, the commit named in
    0023's NOTICE would no longer be the commit that produced the pinned SHA. This is a settings
    change, not code, and it is listed in Rollout after the workflow dry run, whose throwaway tag
    matches the same pattern and must still be deletable.
11. **The `rw-*` release waits on the server tests (work item 6).** Spec 001 decision 2 hands this
    to this spec, and this spec takes it rather than leave it without an owner.
    - Today a red `test-scrcpy-server` does not stop `release` (`release.yml:659-668`), which is how
      run `39974414465` could publish with a failing lint.
    - It is a separate commit, so it can be reverted without touching the Orka release.
    - It takes effect on `master` at the owner's promotion (rollout step 3). Spec 001's lint fix
      reaches `master` in the same promotion, so the first master push under the new `needs` has a
      green server test.
    - Alternative (b) was to decline it and amend Spec 001 to drop the item. Rejected: a release
      that publishes over a failing server check is the gap Spec 001 set out to close.

**Owner decisions this spec keeps:**
- the scrcpy fork is the server jar, one file for lanes D and E;
- the provider trusts the SHA-256, not the version string;
- the jar is embedded in orka-backend and is not downloaded at image build (D1). The release is
  provenance, not a build input.

## Out of scope

- **Promoting develop to master as a public AOA release.** That is an owner call. It has in fact
  already happened (`1b8630f3`, PR #17), and run `39974414465` published `rw-v5.0.1-9`, a
  Gradle APK with `aoa=`. This spec depends on the next promotion only for the workflow file that
  the dispatch path needs. That promotion is rollout step 3, an owner action; without it the agent
  uses the decision 5(b) fallback.
- **The `rw-*` client releases, `release.yml` and the Gradle server APK.** Unchanged apart from
  work item 6's `needs: test-scrcpy-server` entry (decision 11).
- **Pull-request CI, the back-merge of master into develop, contract unit tests and the
  `doc/orka/aoa-lane-contract.md` copy.** These belong to scrcpy-fork 001. Although `master` now
  contains `develop`, 001's back-merge is a PR from `master` into `develop` merged with a merge
  commit, not a fast-forward (001 decision 1).
- **Any change to the wire protocol or to `versionName`.** Contract §6.2 applies.
- **Embedding, `ServerSHA256`, the `.LICENSE`/`.NOTICE` files and orka-backend
  `docs/scrcpy-fork.md`.** These belong to orka-backend 0023.
- **Signing the asset (Sigstore, GPG) and SLSA build attestations.** Possible later. The SHA pin in
  orka-backend is the integrity check.

## Acceptance criteria

An agent can check each item with `git`, `gh` and a shell. `R=Research-Warrant/scrcpy`.

1. `.github/workflows/orka-server-release.yml` and `release/build_orka_server.sh` exist on
   `develop` and on `master`. The workflow's `on:` contains only `push.tags: ['v*-orka.*']` and
   `workflow_dispatch`, and its checkout step uses `ref: ${{ github.sha }}`, not the tag. For the
   PR:
   - `git diff <base> -- .github/workflows/release.yml` shows only `- test-scrcpy-server` added to
     the `release` job's `needs` (work item 6);
   - `git diff <base> -- .github/workflows/server-checks.yml` shows only `java-version` changed
     from `'17'` to `'17.0.20'`;
   - `release/.gitignore` contains `/output-orka`.
2. With ANDROID_HOME pointing at an SDK that has android-36 and build-tools 36.0.0 and JDK 17 on
   the path, `TAG=v5.0.1-orka.1 release/build_orka_server.sh`, run with the script from a
   `master` or `develop` checkout and building the tag `v5.0.1-orka.1` (on `7ef1607c`, which has
   no copy of the script), produces
   `release/output-orka/{scrcpy-server-v5.0.1-orka.1,SHA256SUMS.txt,provenance.md}`.
   - `sha256sum -c SHA256SUMS.txt` passes in that folder.
   - `provenance.md` names commit `7ef1607c…`, server tree `e1b32de3…` and, separately, the
     tooling commit.
   - `git status --porcelain` is empty afterwards.
3. The script rejects all of these with a non-zero exit and a message naming the cause:
   - `TAG=v5.0.2-orka.1` (version mismatch);
   - `TAG=v5.0.1-rw.1` and `TAG=v5.0.1-orka.0` (pattern);
   - a lightweight tag (not annotated);
   - JDK 27 on the path (toolchain).

   With an SDK whose `android-36` `Pkg.Revision` is not `2`, the script exits 0, prints the
   mismatch and writes `toolchain matches pins: no` into `provenance.md`.
4. `git ls-remote --tags https://github.com/$R 'refs/tags/v5.0.1-orka.1^{}'` prints
   `7ef1607cf8347c52d1b603ff5399298ad4cabab2`, and the tag is annotated. Under the decision 5(b)
   fallback it prints the tagged `develop` commit instead, and the amendment records that commit's
   dex check.
5. `gh release view v5.0.1-orka.1 -R $R --json assets,isLatest,isPrerelease,isDraft` shows exactly
   the assets `scrcpy-server-v5.0.1-orka.1` and `SHA256SUMS.txt`, with `isLatest=false`,
   `isPrerelease=false` and `isDraft=false`.
6. `gh release download v5.0.1-orka.1 -R $R`, then `sha256sum -c SHA256SUMS.txt`, passes. The
   notes contain the commit, the server tree `e1b32de3a6cd8be69c25a779157910c73e4e3d8a`, the
   tooling commit, the JDK, d8 and SDK revisions, a `toolchain matches pins:` line, the runner
   image, the size, the SHA-256, `double build: identical` and a `matches expected build:` line.
   Under the decision 5(b) fallback (a tag push, no `expect_sha256`), the tree is that commit's,
   the `matches expected build:` line is absent, and the amendment carries the comparison
   instead.
7. The outcome is recorded in three places:
   - the amendment in this spec;
   - `doc/orka/aoa-lane-contract.md` §6.3;
   - the handoff note to orka-backend 0023.

   If it matches, the asset is 108861 bytes with SHA-256 `8c3255380f76…85a3`. If not, the new
   size and SHA are stated together with the toolchain difference found.
8. Re-dispatching the workflow for `v5.0.1-orka.1` fails at "Refuse to overwrite a published
   release", and the asset SHA is unchanged afterwards.
9. `gh release list -R $R` still shows an `rw-v5.0.1-*` release as Latest.
10. `doc/orka/server-release.md` exists, and its local rebuild command, run with the script from
    `master` or `develop` and `TAG=v5.0.1-orka.1`, reproduces the asset (item 2) on a JDK 17 host.
11. On `develop` (and on `master` after the promotion), the `release` job in
    `.github/workflows/release.yml` lists `test-scrcpy-server` under `needs`.

## Test plan

1. **Script, offline (no GitHub).** Work in a scratch clone and never push from it.
   - Create a local annotated tag `v5.0.1-orka.1` on `7ef1607c` there, check out the PR branch,
     and run the script. This is the positive build of acceptance 2: the script from the branch,
     the source from the tag.
   - Run the acceptance 3 negative cases, each with its own local tag where one is needed.
   - Optionally `shellcheck release/build_orka_server.sh`.
2. **Local reproduction baseline, already run on 2026-10-23.**
   - OpenJDK 17.0.20.1 + android-36 r2 + build-tools 36.0.0 gave `8c3255380f76…85a3`, 108861
     bytes, on two builds.
   - OpenJDK 27 gave `c328ae32…308c`.
   - This is the expected value the dispatch passes in.
3. **Workflow dry run before the real tag, and before the tag ruleset** (rollout step 4). The
   owner pushes a throwaway annotated tag such as `v5.0.1-orka.0-test` on a `develop` commit that
   carries the workflow. It matches the `v*-orka.*` trigger, so the push starts a run, and that run
   must fail at the script's pattern check. This exercises the trigger and the guard without
   publishing anything. The owner deletes the throwaway tag afterwards; it never had a release.
   The tag also matches the ruleset's `v*-orka.*` pattern, which is why this runs before the
   ruleset exists (rollout step 5). A dry run repeated later needs the owner's ruleset bypass to
   delete its tag.
4. **The real run** is work item 5, checked against acceptance items 4-9.
5. **Cross-environment determinism (extra evidence, not a gate).** Run the script at the tag in a
   clean `ubuntu:24.04` container with Zulu 17.0.20 and the two SDK packages, and compare the result
   with the published asset. The in-job double build is the gate.
6. **Hardware: only if the CI bytes differ from `8c325538…`.** This check is owned by orka-backend
   0023's verification but triggered by this spec's result. Push the CI asset to one Android 12
   phone (lane D) and one Android 13+ phone (lane E). The targets are the POC numbers:
   - lane D: first RTP about 1.1 s after the offer, and a keyframe every 2 s with
     `i-frame-interval` 1 (POC spec §8.1/8.4);
   - lane E: the accessory link comes up with no fallback to lane D, as the 12 links on Android
     13-16 did on the dev server (POC `dbaf74b`);
   - exit codes as in contract §1.3.

   If the bytes match, the POC field evidence already covers the file and no hardware run is
   needed.

## Rollout and rollback

Estimated size: S (one release workflow, one script and one tag).

**Rollout**
1. Spec 001 lands: PR CI exists and `develop` has `master`'s CI fix.
2. A PR to `develop` adds work items 1-3 and 6. Commits follow the fork's conventional style, for
   example:
   - `build(release): add a script that builds and checks the Orka server release`
   - `ci(release): add the Orka dex-only server release workflow`
   - `docs(orka): document how to cut and verify an Orka server release`
   - `ci(release): make the client release wait on the server tests`
   - `ci: build the server checks with the release JDK patch`
3. **Owner action, outside the coding agent's rights:** promote `develop` to `master` the same way
   PR #17 did. This puts the workflow on the default branch for `workflow_dispatch`, and it also
   carries Spec 001's lint fix and work item 6 to `master`. As always, a push to master builds an
   `rw-v5.0.1-N` client release (`release.yml:3-6`); that is existing behaviour and is accepted.
   The dispatch path (work item 5) waits for this step. If the owner declines, the agent uses the
   decision 5(b) fallback instead.
4. The owner runs the workflow dry run (Test plan, item 3) and deletes its throwaway tag.
5. The owner adds the tag ruleset (decision 10). It comes after step 4 because the dry-run tag
   matches `v*-orka.*` and could not be deleted afterwards.
6. Work item 5: tag, dispatch, record the outcome.
7. Work item 4 and the handoff to orka-backend 0023.

**Rollback**
- Before orka-backend pins a SHA, a bad release can be deleted together with its tag
  (`gh release delete v5.0.1-orka.1 --cleanup-tag`). Only the owner does this.
- After a pin, never delete the release. Supersede it with `orka.<n+1>` and a matching orka-backend
  change.
- Removing the workflow has no effect on `rw-*` releases.
- Work item 6 is two separate commits. Reverting the `needs` commit restores today's `rw-*`
  release flow without touching the Orka release.

## Risks

- **CI bytes differ from the local build.** Likely causes are a different Zulu/OpenJDK `javac`, a
  different d8 build under the same `36.0.0` label, or a different `android.jar` revision.
  - Mitigations: the pinned toolchain, provenance recorded per release, and decision 6. The cost is
    a re-pin in 0023 plus the conditional hardware check.
  - **Not established:** whether Zulu 17.0.20+8 and OpenJDK 17.0.20.1 emit identical class files
    for this tree. The first run settles it.
- **The runner image changes under the pin.** `ubuntu-24.04` images are refreshed weekly, and
  `sdkmanager` may resolve a new revision of `platforms;android-36` or `build-tools;36.0.0`. The
  script's `Pkg.Revision` check against its pins reports the drift in the job log and the release
  notes, and the double build shows whether it is stable. A drift that changes bytes is a new
  `orka.N`, never a replaced asset.
- **The default-branch requirement for `workflow_dispatch`.** The dispatch path does not exist
  until the workflow is on `master`, which needs the owner's promotion (rollout step 3). The
  fallback is decision 5(b), which needs the candidate commit's dex to match before it is tagged.
- **A red server test now blocks a `rw-*` release** (work item 6). That is intended. If upstream
  tooling (an unpinned Gradle or lint) turns the test red without a code change, the `rw-*`
  release waits until a fix lands. The Orka release is unaffected.
- **Tag mutability.** Without the ruleset, a force-pushed tag would break provenance silently. The
  release notes record the commit, so a check against the notes still detects it.
- **Script injection through dispatch inputs.** This is covered by passing inputs only through
  `env` and by the pattern check run before any other use.
- **Confusing the assets.** `rw-v5.0.1-9` and later (Gradle APK with `aoa=`) are also called
  `scrcpy-server-*` and are close to `v5.0.1-orka.*` by name. `doc/orka/server-release.md` and the
  release notes state that Orka consumes only `scrcpy-server-v*-orka.*`. orka-backend 0023's
  `jar_test.go` (the resource SHA equals the constant) catches a wrong file.
- **Stale POC provenance text** (`.NOTICE` size and SHA lines, the `jar.go` comment, the README
  saying "PR #16 open") gets copied into Orka. This is covered by the handoff list above.

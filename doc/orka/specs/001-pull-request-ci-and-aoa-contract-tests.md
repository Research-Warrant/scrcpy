# Spec 001 — Pull-request CI and AOA contract tests

- **Status:** Draft
- **Date:** 2026-10-23
- **Owner:** Platform
- **Ticket:** [ORK-92](https://app.clickup.com/t/1248h53atjt) — Run server CI on fork pull requests and cover the AOA lane contract in tests
- **Related:** depends on nothing. Followed by scrcpy-fork Spec 002 — Dex-only server release for Orka (release asset; it builds on the CI this spec adds; its work item 6 makes `release` wait on `test-scrcpy-server`); orka-backend Spec 0033 — AOA USB layer, link and fallback policy (copies `doc/orka/aoa-lane-contract.md` byte-identical as its `docs/aoa-lane-contract.md`, decision 7 there, and asserts this spec's Appendix A vectors in `TestWireMatchesTheForkVectors`; decision 6 here); orka-backend Spec 0023 — android_webrtc_scrcpy stream type on the embedded fork server (pins the jar SHA-256); orka-backend Spec 0034 — AOA video and control lane for Android 13+. POC sources: orka-POC `docs/specs/scrcpy-video-in-gads/aoa-lane-contract.md` (normative contract, §7 "Tests both sides need"), `tools/scrcpy-fork/README.md` (rebuild steps), `GADS/provider/aoalane/link_test.go:108-136` (the provider's contract vectors), `GADS/provider/scrcpy/args_test.go:36-66,218-228` (lane D and lane E command lines); fork commits `4dbb1643`, `9c44a074`, `e254c2a3` (PR #15), `bc6cf662` (PR #16, merged as `7ef1607c`).

Target repository: `Research-Warrant/scrcpy` only. Line numbers below are on `origin/develop` (`7ef1607c`) unless marked `master` (`1b8630f3`). Commands run in a local clone of `Research-Warrant/scrcpy`, written `<scrcpy-fork>` where a path is needed.

## Goal

The fork's server is now the jar Orka embeds, and lane E (AOA) depends on its wire format. Yet no pull request in the fork runs any check: PRs #15 and #16 merged with no CI, and the first CI run that saw the AOA code (the master push of PR #17) failed. This spec makes every fork PR run the server checks, fixes the one error that fails them today, and closes the gaps between the fork's unit tests and the contract's §7 list, so a later wire or behaviour change in the server breaks a test before it reaches a phone. It also puts the contract next to the code that implements it.

It changes no wire byte, no server behaviour and, by design, not the bytes of the dex-only jar (decision 5).

## Current state

### Branches and existing CI

- `develop` (`7ef1607c`) is an ancestor of `master` (`1b8630f3`). `git diff origin/develop origin/master` touches only `.github/workflows/release.yml` (+29 lines): PR #14's fix `d6f70d63` ("fix(ci): pass VERSION to every job that runs a release script") plus the merge commits of PRs #14 and #17. So the back-merge is conflict-free: master is develop plus that fix.
- **PR #17 already promoted develop to master** (merged 2026-10-23T14:59Z, "Implement AOA transport for video and control streaming over USB"). Master contains AOA.
- The only workflow is `.github/workflows/release.yml`. It triggers on `push` to `master` and on `workflow_dispatch` (master `:3-10`); there is no `pull_request` trigger on either branch. Its server jobs are:
  - `test-scrcpy-server` (master `:46-66`), which runs `release/test_server.sh`;
  - `build-scrcpy-server` (`:68-91`), the Gradle APK;
  - `test-build-scrcpy-server-without-gradle` (`:93-106`), which runs `server/build_without_gradle.sh` with no `ANDROID_PLATFORM` or `ANDROID_BUILD_TOOLS` set.
  
  The `release` job (master `:659-668`) needs `build-scrcpy-server` and the client packages, **not** `test-scrcpy-server`. A red server test therefore does not stop a release.
- **The master run of PR #17 (run `39974414465`) fails `test-scrcpy-server`.** `:server:testDebugUnitTest` ran, and then `:server:lintDebug` failed:
  ```
  server/src/main/java/com/genymobile/scrcpy/aoa/AccessoryOpener.java:117: Error: Reflective access to mSystemThread will throw an exception when targeting API 36 and above [SoonBlockedPrivateApi]
  ```
  - The line comes from PR #16 (`bc6cf662`).
  - The class-level suppression at `AccessoryOpener.java:23` is `@SuppressLint("PrivateApi,DiscouragedPrivateApi,BlockedPrivateApi")`, without `SoonBlockedPrivateApi`. Upstream suppresses the same check on its own reflection in `Workarounds.java:25`, `FakeContext.java:103` and `wrappers/DisplayControl.java:13`.
  - `test-build-scrcpy-server-without-gradle` passed (25 s).
  - The run's `release` job succeeded anyway: `rw-v5.0.1-9` was published from `1b8630f3` (2026-10-23T15:15Z, marked Latest) with `test-scrcpy-server` red.
- Branch protection: none on `develop` or `master` (the GitHub API answers 404 "Branch not protected").
- The `ubuntu-latest` label is moving to Ubuntu 26 from 2026-10-19 (run annotation). The Android SDK on the new image is **Not established**.

### Scripts the CI will call

- `release/test_server.sh:9` runs `"$GRADLE" -p server check`. That task includes:
  - checkstyle, which does not gate, because of `ignoreFailures = true` (`config/android-checkstyle.gradle:13`);
  - the JUnit tests (`server/build.gradle:29`, `junit:junit:4.13.2`);
  - Android lint.
  
  `release/build_common:5` falls back to `git describe --tags --always` when `VERSION` is unset.
- `server/build_without_gradle.sh` details:
  - `:17` `SCRCPY_VERSION_NAME=5.0.1`
  - `:19-20` defaults to `ANDROID_PLATFORM` 36 and `ANDROID_BUILD_TOOLS` 36.0.0
  - `:24` `BUILD_DIR` defaults to `build_manual`
  - `:64` compiles `aoa/*.java`
  - `:115` (d8 branch) writes the dex-only zip to `$BUILD_DIR/scrcpy-server`.
  
  The jar the POC embeds is this output at `bc6cf662`: 108861 bytes, SHA-256 `8c3255380f76e8756531c82a1954c0c57ab4601324cf433d937bc894b79585a3` (POC `tools/scrcpy-fork/README.md:19-20`). `7ef1607c` has the same tree.

### The AOA code and its tests

The fork differs from upstream v5.0.1 (`a60891ae`) in 13 paths under `server/`:

- **Added:** `aoa/{AccessoryLink,AccessoryOpener,AoaException,AoaProtocol,MessageParser}.java`, plus 3 test files.
- **Modified:** `Options.java`, `Server.java`, `control/ControlChannel.java`, `device/DesktopConnection.java`, `build_without_gradle.sh`. Each carries a "Modified by Research Warrant" line (contract §6.1).

The upstream tag `v5.0.1` is not on the fork's remote, which carries only 7 tags of its own `rw` release series (`rw-v3.3.4,` to `rw-v5.0.1-9`). The commit `a60891ae` is in develop's history, which is all the upstream-delta guard (PR 3 item 7) needs.

| Code | Where |
|---|---|
| Header, HELLO, PING, BYE, nonce | `aoa/AoaProtocol.java:27-52` (constants), `:63-75` writeHeader, `:92-104` parseHeader, `:119-130` hello, `:136-139` isHelloHostFor, `:160-175` parseNonce |
| Exit codes 20-29 | `aoa/AoaException.java:11-20`, reasonOf `:50-75` |
| Parser (resync before the handshake, strict after) | `aoa/MessageParser.java:30-80`. It never checks `type`; that is AccessoryLink's job |
| Liveness constants | `aoa/AccessoryLink.java:32-37`: `HELLO_RESEND_MS` 500, `HANDSHAKE_TIMEOUT_MS` 10000, `PING_INTERVAL_MS` 1000, `WATCHDOG_PERIOD_MS` 250, `SILENCE_TIMEOUT_NS` 4e9, `WRITE_BLOCKED_TIMEOUT_NS` 4e9. All are `private` |
| Accessory wait | `aoa/AccessoryOpener.java:26` `MIN_SDK = 33` (public), `:29-30` `ACCESSORY_WAIT_MS` 5000 and `ACCESSORY_POLL_MS` 250 (private) |
| Unknown channel 0 types ignored | `AccessoryLink.java:230-248` (`default:` branch) |
| Option parsing and validation | `Options.java:431-433` (`aoa`), `:600-621` (`validateAoa`, exit 29) |
| Exit-code mapping | `Server.java:220-237`: `AoaException` maps to its code, any other throwable to 1, then `System.exit` |

Existing tests: `OptionsAoaTest` (5), `aoa/AoaProtocolTest` (15) and `aoa/MessageParserTest` (7). Coverage against contract §7 and this ticket's list:

| Contract item | Covered by | Gap |
|---|---|---|
| Header encode (§3.2) | `AoaProtocolTest:19-23` (control channel only) | No vector shared with the provider. POC `link_test.go:110` uses a video message `4f4b010100000001aa` |
| Header parse: valid, invalid, flags ignored | `AoaProtocolTest:37-60` | The whole channel range 0-255 is not swept. The maximum length on channel 0 is not checked |
| Resync before the handshake, rejection after | `MessageParserTest:127-182` | none |
| HELLO layout | `AoaProtocolTest:62-87,157-166` | Its vector (pid 1234) is not the provider's (pid `0x0a0b0c0d`, `link_test.go:116-118`) |
| HELLO_HOST bytes | only `isHelloHostFor` (`:89-100`) | No byte vector. Provider: `4f4b010002000008 01020304 00000000` |
| PING, PONG, BYE | `:102-114` | No PONG header vector. No `BYE(stop,"x")` vector shared with the provider (`link_test.go:130`) |
| Channel 2 as a byte stream split in 16376-byte messages (§3.6) | none | The largest `SET_CLIPBOARD` (262,144 bytes with its header), which spans messages, is never reassembled in a test |
| Unknown channel 0 types pass the parser | none | The parser could start rejecting them and nothing would fail |
| Liveness constants (§4.1) | none | All private, untested |
| Exit codes 20-29 (§1.3) | `AoaProtocolTest:143-155` (names by index) | No explicit value table. `reasonOf` outside 20-29 is untested. `Server.main` mapping untested (it calls `System.exit`) |
| Option rules, exit 29 (§1.2) | `OptionsAoaTest:47-63` | `list_*` with `aoa` is untested; see the wording gap below |
| Without `aoa=`, the server behaves exactly as upstream (§1.2) | `OptionsAoaTest:21-26` (one case) | The provider's real lane D command lines are not parsed. Nothing guards against an AOA change leaking into another upstream file |

Wording gap: contract §1.2 says "`list_*` … ignore `aoa`". `validateAoa` actually runs in list mode too, so `list_encoders=true aoa=… ` without `audio=false` exits 29. The provider never combines them (POC `args.go`, `ModeListEncoders` has no nonce), so the doc is what needs fixing, not the code.

The PR #16 fix is not in the contract. Scrcpy's `Workarounds` mark the process's `ActivityThread` as a system thread, and `UsbAccessory`'s constructor then rejects the Binder `IUsbSerialReader` with `IllegalArgumentException`, so the fork server at `e254c2a3` failed every lane E launch (POC `dbaf74b`). `AccessoryOpener.open` (`:63-82`) therefore clears `ActivityThread.mSystemThread` through `setSystemThread` (`:106-126`) while it waits for, reads and opens the accessory, and restores it in a `finally`. Contract §2 (POC `aoa-lane-contract.md:95-141`) still calls itself "exactly the accprobe approach"; accprobe never ran under `Workarounds`, and nothing in the contract or the tests says why the toggle exists.

There is no `doc/orka/` directory. The contract exists only in the POC, and its paths are POC paths (`GADS/...` at `:4`, `:10-11`, `:216`, `:293`, `:436-444`; `tools/scrcpy-fork/README.md` at `:442`).

## Design / Changes

Three PRs into `develop`, in order. Commits use the fork's style: `type(scope): lower-case subject`.

PR 1 is a pure back-merge, so the specs folder `doc/orka/specs/` (this spec and Spec 002) lands with PR 2, the first PR that adds content, in its own `docs(orka): …` commit.

### PR 1 — Back-merge master into develop

- Open a PR from `master` into `develop` and merge it with a merge commit, as the repository's other merges are.
- Result: `d6f70d63` is on develop, and `git diff origin/master origin/develop -- .github/workflows/release.yml` is empty.
- There is nothing to resolve, because develop is an ancestor of master.
- `release.yml` stays untouched by this spec after that.

### PR 2 — Pull-request workflow and the lint fix

The workflow commit (item 2) comes first and the `@SuppressLint` commit (item 1) second. Push the workflow commit alone first: its `server-dex` run compares two identical server trees and must report equal SHA-256 values (a control for the comparison itself). The run after the annotation commit then compares base and head with the annotation as the only server change (acceptance 4). The `server-test` job is expected to fail on the first push (the lint error) and pass on the second.

1. **`server/src/main/java/com/genymobile/scrcpy/aoa/AccessoryOpener.java:23`.**
   - Change the annotation to `@SuppressLint("PrivateApi,DiscouragedPrivateApi,BlockedPrivateApi,SoonBlockedPrivateApi")`. This is the same suppression upstream uses for its own reflection in `Workarounds.java:25`.
   - Commit: `fix(server): suppress the SoonBlockedPrivateApi lint error on the accessory opener`.
   - Leave the 2 lint warnings alone.
2. **New `.github/workflows/server-checks.yml`.**
   - Name: `Server checks`.
   - Triggers: `pull_request` with `branches: [develop, master]`, `push` with `branches: [develop]` (decision 9), and `workflow_dispatch`. The default branch is `master`, and GitHub offers `workflow_dispatch` only for workflows present there, so the manual trigger works only after the next promotion (rollout step 3). Nothing in this spec depends on it.
   - `permissions: contents: read`.
   - Concurrency group `server-checks-${{ github.ref }}` with `cancel-in-progress: true`.
   - Every job runs on `ubuntu-24.04` (decision 4).
   - Shared setup steps: `actions/checkout@v6`, then `actions/setup-java@v5` with `zulu` and `17`, as `release.yml` does, then:
     ```sh
     yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "platforms;android-36" "build-tools;36.0.0"
     ```
   - Job `server-test`:
     - env `VERSION: pr-${{ github.event.number || github.sha }}` and `GRADLE: gradle`;
     - runs `release/test_server.sh`, which is checkstyle, unit tests and lint.
   - Job `server-dex`:
     - env `ANDROID_PLATFORM: "36"`, `ANDROID_BUILD_TOOLS: "36.0.0"`, `BUILD_DIR: out`;
     - runs `server/build_without_gradle.sh`, then `sha256sum out/scrcpy-server` and `stat -c %s out/scrcpy-server`;
     - on `pull_request` only, also builds the PR base: a second `actions/checkout@v6` of `${{ github.event.pull_request.base.sha }}` into `path: base`, then `BUILD_DIR=out-base base/server/build_without_gradle.sh` (the script resolves its sources from `dirname "$0"`), with the same size and SHA-256 report;
     - writes both pairs (head, and base when present) into `$GITHUB_STEP_SUMMARY`, with one line saying whether the two SHA-256 values are equal. Both builds use the same runner and toolchain, so a difference comes from the sources;
     - uploads `out/scrcpy-server` as artifact `scrcpy-server-dex` with `retention-days: 14`.
     - It does not compare against a pinned hash; the release asset and its pin belong to Spec 002 and orka-backend 0023.
   - Job `upstream-delta`, added in PR 3, once its script exists. Until then the workflow has 2 jobs.
   - Do not run the client builds (meson, Windows, macOS). This workflow covers the server only.
   - Commit: `ci: run the server checks on pull requests to develop and master`.

### PR 3 — Contract tests, the contract doc and the upstream-delta guard

All new tests are plain JVM JUnit 4 under `server/src/test/java/com/genymobile/scrcpy/`, in the style of the existing ones (`Assert.*`, a `bytes(int...)` helper). They must not call `android.*` methods: the unit-test `android.jar` stubs throw. Only test files and docs change in this PR, so the dex is unchanged (decision 5).

1. **`aoa/AoaContractVectorsTest.java`: the shared vectors.** These are the provider's vectors (`link_test.go:108-136`: video message, HELLO_HOST, HELLO, `BYE(stop,"x")`, `MaxPayload`) plus the PONG header, `MAX_IO`, `HEADER_LENGTH` and the `isHelloHostFor` check, which the provider does not assert yet and must add (orka-backend 0033, decision 6). The table becomes the contract's Appendix A (item 6), the single source both sides assert.
   - Video message: `AoaProtocol.message(CHANNEL_VIDEO, 0, {0xaa})` gives `4f 4b 01 01 00 00 00 01 aa`.
   - HELLO_HOST: `message(CHANNEL_SESSION, TYPE_HELLO_HOST, 01020304 00000000)` gives `4f 4b 01 00 02 00 00 08 01 02 03 04 00 00 00 00`, and `isHelloHostFor(0x01020304, …)` is true on its payload.
   - HELLO: `hello(0x01020304, 34, 0x0a0b0c0d, 3, "5.0.1", "orka-0001e240")`.
     - Header: `4f 4b 01 00 01 00 00 22`.
     - Payload: `01 02 03 04 | 00 22 | 0a 0b 0c 0d | 00 00 00 03 | 05 "5.0.1" | 0d "orka-0001e240"`.
   - PONG header: `writeHeader(buf, CHANNEL_SESSION, TYPE_PONG, 12)` gives `4f 4b 01 00 04 00 00 0c`.
   - BYE: `bye(BYE_STOP, "x")` gives `4f 4b 01 00 05 00 00 02 00 78`.
   - Sizes: `MAX_IO` 16384, `HEADER_LENGTH` 8, `MAX_PAYLOAD` 16376.
2. **`aoa/AoaChannelFramingTest.java`: channels 0-3.**
   - Sweep every channel byte 0-255 through `parseHeader`. A header is valid only for 0, 1 and 2; channel 3 (reserved for audio) and above give -1.
   - Channel 0 accepts lengths 0 and 16376. Channels 1 and 2 accept 1 and 16376 and reject 0 and 16377.
   - Type byte: after a handshake, a channel 0 message with an unknown type (`0x7f`) passes the parser to the handler without an exception (§3.4: the receiver ignores it).
   - Channel 2 as a byte stream (§3.6):
     - a 262,144-byte control stream (the largest `SET_CLIPBOARD`, header included: `MESSAGE_MAX_SIZE = 1 << 18` and `CLIPBOARD_TEXT_MAX_LENGTH = MESSAGE_MAX_SIZE - 14` in `control/ControlMessageReader.java:14-16`) is cut into `ceil(262144 / 16376)` = 17 messages, as `pumpLoop` (`AccessoryLink.java:251-268`) does;
     - every message is at most `MAX_IO`;
     - the stream is fed to a strict `MessageParser` in `MAX_IO` reads;
     - the concatenated channel 2 payloads equal the input.
3. **`aoa/AoaLivenessConstantsTest.java`: §4.1 and §1.3 timings.**
   - Read the `private static final` fields by reflection (`getDeclaredField`, `setAccessible(true)`). Each assertion names its contract row:
     - `AccessoryLink`: `HELLO_RESEND_MS` 500, `HANDSHAKE_TIMEOUT_MS` 10000, `PING_INTERVAL_MS` 1000, `SILENCE_TIMEOUT_NS` 4_000_000_000, `WRITE_BLOCKED_TIMEOUT_NS` 4_000_000_000, `WATCHDOG_PERIOD_MS` 250 (§4.2);
     - `AccessoryOpener`: `ACCESSORY_WAIT_MS` 5000, `ACCESSORY_POLL_MS` 250, `MIN_SDK` 33.
   - Initialising these classes in the JVM runs no Android code; they have no static initialiser beyond the constants. **Not established** until the test runs.
   - If class initialisation fails under the stub `android.jar`, fall back to parsing the constants from the source files (`server/src/main/java/...`, relative to the Gradle project directory). Do not change the main sources (decision 5).
4. **`aoa/AoaExitCodesTest.java`: §1.3.**
   - An explicit table checks that `AoaException.SDK_TOO_OLD == 20` … `BAD_OPTION == 29` and that `reasonOf(code)` is the contract's REASON for each.
   - `reasonOf(0)`, `reasonOf(1)`, `reasonOf(19)` and `reasonOf(30)` are `"UNKNOWN"`.
   - `getMessage()` is `"aoa: <REASON>: <detail>"` for every code.
   - Each option failure path throws `AoaException` with code 29:
     - malformed nonces (already in `AoaProtocolTest:124-135`, referenced, not duplicated);
     - each rule in `validateAoa`.
   - Upstream failures stay non-AOA. A wrong client version (`Options.parse("4.0", …)`) throws `IllegalArgumentException`, not `AoaException`, so `Server.main` still maps it to 1.
   - The mapping inside `Server.main` itself is not unit-tested (decision 5). Its check is the owner-run device step in the test plan.
5. **`OptionsUpstreamParityTest.java`: the server without `aoa=` (§1.2).**
   - Parse these command lines through `Options.parse(BuildConfig.VERSION_NAME, …)`, without the `CLASSPATH… Server` prefix. For each, assert `isAoa()` is false, `getAoaNonce()` is 0, and `isTunnelForward()`, `getVideo()`, `getAudio()`, `getControl()`, `getScid()`, `getMaxSize()` and `getVideoBitRate()` match the arguments:
     - the provider's lane D video line from `args_test.go:38-41` (H.264, 1280, 30);
     - the H.265 owner profile line from `:60-63` (720 px, 25 fps, 1 Mbit/s, i-frame-interval 1);
     - the control-only line (`tunnel_forward=true video=false audio=false control=true`, `:125`);
     - `list_encoders=true`.
   - The lane E line from `:222-225` parses with `isAoa()` true and nonce `0x0a0b0c0d`.
   - `scid=00000001 aoa=0a0b0c0d audio=false list_encoders=true` parses, and `getList()` is true. This records the real list-mode behaviour behind the wording fix in item 6.
   - Do not pass unknown keys: `Options.java:590` logs through `Ln.w`, which calls the stubbed `android.util.Log`.
6. **`doc/orka/aoa-lane-contract.md`.** Copy the POC file (unchanged since `1b54615`; cite it at `afa48da`, the POC commit the rest of this work refers to), then make only these edits (decision 8). The result is the exact text orka-backend 0033 copies byte-identical as `docs/aoa-lane-contract.md`, so every edit must read correctly in both repositories. The edits are the union of 0033's work item 6 list and this spec's own:
   - **Status paragraph** (`:3-5`), one text valid in both repositories: the contract is normative for orka-backend `provider/aoausb` and `provider/aoalane` and for fork `com.genymobile.scrcpy.aoa`; it is kept byte-identical at fork `doc/orka/aoa-lane-contract.md` and orka-backend `docs/aoa-lane-contract.md`; any change, wire or wording, edits both copies (one PR per repository, linked); a wire change also bumps the header `version` byte. If code and this file disagree, fix one of them in the same change (kept from the POC). One history sentence says it was derived from orka-POC `docs/specs/scrcpy-video-in-gads/aoa-lane-contract.md` at `afa48da`.
   - **Background** (`:7-12`): the POC notes and the spike become "research record in orka-POC, not shipped" (orka-POC `tools/aoa-spike/accprobe/AccProbe.java`, `notes/lane-e-aoa.md`). The reference implementation becomes orka-backend `provider/aoausb` and `provider/aoalane` and the fork's `com.genymobile.scrcpy.aoa`. Drop "this is ported to orka-backend later" (`:19`).
   - **Paths:** every `GADS/provider/...` becomes "orka-backend `provider/...`" (`:4`, `:10-11`, `:216`, `:437`), `GADS/resources` becomes "orka-backend `resources/`" (`:436`, `:443-444`), and `GADS/docs/android-direct-transport.md` (`:293`) becomes "orka-backend `docs/android-direct-transport.md`" (orka-backend Spec 0032), adding that the rule file itself is in IaC `deploy/dev-server/host/` (IaC Spec 003). No `GADS/` string is left.
   - **§1.2**, `list_*` row: "List modes exit before opening a connection. `aoa` is still validated when present (§1.2 rules apply); the provider never combines them."
   - **§2 heading and a new §2.4 "System-thread toggle":** the heading drops "exactly" ("the accprobe approach, plus §2.4"). §2.4 says that steps 3-5 of §2.1 (waiting for, reading and opening the accessory) run with `ActivityThread.currentActivityThread().mSystemThread` cleared through reflection and restored in a `finally`. Reason: scrcpy's `Workarounds` mark the process's `ActivityThread` as a system thread, and `UsbAccessory`'s constructor then requires a local `IUsbSerialReader` Stub, which is only true inside system_server, so unmarshalling the accessory throws `IllegalArgumentException` on every launch. accprobe never hit this because it did not run under `Workarounds`. Removing the toggle sends every Android 13+ phone to lane D, silently. Cite fork `bc6cf662` (PR #16) and `AccessoryOpener.open`.
   - **§3.2:** one explicit sentence that channel 3 is reserved for audio and invalid today.
   - **§5.7:** "3 consecutive failures within 10 min" becomes "3 failures within 10 min with no healthy session in between" (0033 decision 8; the code does not change).
   - **§6.3:** "The POC embeds" becomes "orka-backend embeds", with the resource and `provider/scrcpy/jar.go` paths repo-qualified as above. The pinned build is fork merge `7ef1607c` (PR #16, the same server tree as `bc6cf662`, on master since PR #17), 108861 bytes, SHA-256 `8c3255380f76e8756531c82a1954c0c57ab4601324cf433d937bc894b79585a3`, built with fork `server/build_without_gradle.sh` (android-36, build-tools 36.0.0, JDK 17). The release asset is scrcpy-fork Spec 002, and the embed and pin are orka-backend Spec 0023. Remove the `tools/scrcpy-fork/README.md` pointer.
   - **§7:** name the fork test classes that cover each item, plus the orka-backend Go tests that cover the same items (Spec 0033: `link_test.go`, `policy_test.go`, `lock_linux_test.go`, `TestWireMatchesTheForkVectors`), so 0033 copies the file with no edit of its own, and name §7's owner-run check "Android 13+ phones open accessory links with no fallback to lane D" as the regression check for §2.4.
   - New **"Appendix A — Test vectors":** the hex vectors and sizes of item 1. Both sides assert every row: the fork in `AoaContractVectorsTest`, orka-backend in `TestWireMatchesTheForkVectors` (0033).
   - Later edits to this file, including Spec 002 work item 4's §6.3 release record, follow the status paragraph's rule: both copies, same bytes.
7. **Upstream-delta guard.**
   - New `doc/orka/upstream-delta.txt`:
     - first line `base: v5.0.1 a60891aea193d92e7e5c3942700eca63f9d19a5f`;
     - then one `<status> <path>` line per changed path under `server/`, excluding `server/src/test/`, as `git diff --name-status a60891ae HEAD -- server/ ':!server/src/test'` prints it today (10 lines: 5 `A`, 5 `M`).
   - New `.github/orka/check-upstream-delta.sh` (bash, `set -euo pipefail`), with no network access:
     - read the `base:` hash from the file and check, in the full checkout, that it is a commit (`git cat-file -e <hash>^{commit}`) and an ancestor of `HEAD` (`git merge-base --is-ancestor <hash> HEAD`); `a60891ae` already is on develop. The tag name on the `base:` line is a label only. Do not fetch from `github.com/Genymobile`: `v5.0.1` is an annotated tag (object `a0c27ff3…`), so a fetched ref would need an explicit `^{commit}` peel, and a shallow fetch into a full clone is not needed;
     - diff the live `git diff --name-status <hash> HEAD -- server/ ':!server/src/test'` against the file, and fail when they differ (an added, removed or re-classified path);
     - fail when an `M` path does not contain `Modified by Research Warrant` (contract §6.1, Apache-2.0 §4(b));
     - on failure, print the diff of the two lists.
   - Job `upstream-delta` in `server-checks.yml`: checkout with `fetch-depth: 0`, then run the script.
   - Commits:
     - `test(server): cover the AOA lane contract vectors, framing, timings and exit codes`
     - `docs(orka): add the AOA lane contract next to the server code`
     - `ci: guard the server's delta against upstream v5.0.1`

## Decisions

1. **The back-merge is a merge of `master` into `develop` through a PR.** Develop is an ancestor of master, so this is a fast-forward in content. A PR keeps the review trail, and the repository merges with merge commits. Rejected: cherry-picking `d6f70d63`, which leaves develop and master with different histories for the same file.
2. **A separate, light workflow; `release.yml` is not changed.**
   - `release.yml` computes release metadata, builds 7 client targets (including a 38-minute `windows-11-arm` job) and publishes on master. Adding `pull_request` to it would make every PR a release build.
   - The new file copies only the server steps.
   - Making `release` wait on `test-scrcpy-server` is worth doing, but it changes the release flow. Spec 002 owns it (its work item 6 and decision 11).
3. **The PR gate is `gradle -p server check`, lint included, and the lint error is fixed here.**
   - The lint error is real for the check but intended in the code. Upstream suppresses the same check for the same kind of reflection.
   - The suppression is per class, as upstream does it. Rejected: disabling the check in `build.gradle`, which would hide it for upstream code too.
4. **Pinned runner and SDK.**
   - `ubuntu-24.04`, not `ubuntu-latest` (moving to Ubuntu 26), with `platforms;android-36` and `build-tools;36.0.0` installed explicitly. Without that, the dex job could change toolchain silently between runs.
   - JDK 17 Zulu, as `release.yml`.
   - The job only reports the dex SHA-256. Whether CI bytes equal the local `8c325538…` build is Spec 002's record.
5. **Nothing in this spec changes the dex-only jar.**
   - The jar Orka embeds is pinned by SHA (contract §6.3), and Spec 002 tags `7ef1607c`.
   - So: tests and docs only, plus one `@SuppressLint`. `SuppressLint` has class retention; d8 is expected to drop it, but that is **Not established**. PR 2 verifies it (acceptance 4).
   - Liveness constants are read by reflection rather than moved into a public class. The `Server.main` exit mapping is not refactored into a testable helper.
   - Option (b), extracting `Server.exitStatusOf(Throwable)` and moving the constants into a pure `AoaTiming` class, is deferred to the next change that ships a new jar anyway.
6. **One vector table for both sides: the contract's Appendix A.**
   - The fork's `AoaContractVectorsTest` asserts every Appendix A row. The rows that POC `link_test.go:108-136` already asserts keep its exact bytes; the PONG header, `MAX_IO`, `HEADER_LENGTH` and `isHelloHostFor` rows are new for the provider.
   - orka-backend 0033's `TestWireMatchesTheForkVectors` must assert Appendix A (the vectors of `AoaContractVectorsTest`), not the fork's older `AoaProtocolTest` vectors (header `…12 34`, HELLO with pid 1234, BYE reason 1 "ok"). Those stay as fork-only unit tests. The planner amends 0033's work item 5 to match.
7. **"Exactly as upstream" is enforced by the code and by the diff.**
   - The parity tests cover the provider's real lane D command lines. The delta guard makes any new upstream file touched by Orka a reviewed, listed change with the Apache notice.
   - An upstream sync PR (`chore(sync): …`) updates the `base:` line and the list in the same PR.
8. **One contract text, two byte-identical copies, normative for both sides** (orka-backend 0033 decision 7).
   - The fork copy lands first, already carrying every edit 0033 lists, so 0033 copies it unchanged and its acceptance 7 passes with no extra fork docs PR.
   - There is no split authority: the fork copy is not more canonical for §1-§4 than the backend copy is for §5. Any change edits both copies.
   - Paths are repo-qualified so the same bytes read correctly in both repositories. The POC copy is frozen as history; the POC is a testbed.
   - Wording fixes (§1.2 list modes, §2.4, §5.7) are not wire changes.
9. **The workflow also runs on pushes to `develop`.** Merge commits on develop otherwise get no signal until master. This costs about 2 minutes per push.
10. **Owner decisions kept as they are:**
    - the fork is the server jar;
    - `versionName` stays `5.0.1`;
    - capability is known by the jar's SHA-256 (§6.2);
    - no wire change;
    - Android 12 stays on lane D;
    - no Wi-Fi and no Ethernet over USB.

## Rollout and rollback

Estimated size: S (three small PRs, tests and docs plus one annotation).

1. PR 1 (back-merge), then PR 2 (workflow and lint fix), which must be green on its own PR, then PR 3 (tests, doc and guard), green.
2. Owner action, outside the coding agent's rights: in repository settings, make `Server checks / server-test`, `server-dex` and `upstream-delta` required on `develop` and `master`. Until then the checks report but do not block.
3. The lint fix reaches master only through the next develop-to-master promotion. That is an owner call. Until it happens, `release.yml`'s `test-scrcpy-server` stays red on master pushes; this does not block releases (`:659-668`).
4. Rollback: revert PR 2 or PR 3. Tests and docs are additive. The workflow is a separate file, so `release.yml` and the published `rw-*` releases are unaffected. If the `@SuppressLint` change turns out to alter the dex (acceptance 4), revert that one commit and add `server/lint-baseline.xml` for that issue instead. A baseline changes no Java source.

## Out of scope

- **The release asset, its tag and SHA record:** scrcpy-fork Spec 002. So is making `release` wait on `test-scrcpy-server` (Spec 002 work item 6).
- **Any change to the wire protocol or the header `version` byte.** It would ship a new jar and requires orka-backend 0023 and 0033 changes in the same rollout.
- **Refactoring `Server.main` or `AccessoryLink` for testability** (decision 5, option b).
- **Instrumented or Robolectric tests of `AccessoryOpener`/`AccessoryLink` on Android.** They need a device or emulator with an accessory, which no CI runner has.
- **Client (`app/`) builds and tests on PRs.**
- **Promoting develop to master.** PR #17 already did it once; the next promotion is an owner call.
- **Branch protection settings:** an owner action, listed under rollout.
- **Editing the POC copy of the contract.**

## Acceptance criteria

All of these can be checked from a clone and with `gh` (read-only):

1. `git merge-base --is-ancestor d6f70d63 origin/develop` succeeds. `git diff origin/master origin/develop -- .github/workflows/release.yml` is empty after PR 1.
2. `.github/workflows/server-checks.yml` exists. It has `pull_request` for `develop` and `master` and runs no client job. On PR 2 and PR 3, `gh pr checks <n> -R Research-Warrant/scrcpy` shows every `Server checks` job as passing.
3. `release/test_server.sh` (CI job `server-test`) passes. The log shows `:server:testDebugUnitTest` and `:server:lintDebug` succeeding, with no `SoonBlockedPrivateApi` error.
4. The `server-dex` job summary for PR 2's last run shows size and SHA-256 for the head and for the base (develop after PR 1, whose server tree is `7ef1607c`'s). The run of the workflow-only commit shows equal base and head values.
   - Expected: head and base equal, and both 108861 bytes with `8c3255380f76e8756531c82a1954c0c57ab4601324cf433d937bc894b79585a3`, the same as the local build of `bc6cf662`.
   - Head and base equal but not `8c325538…`: the CI toolchain differs from the local one; record it for Spec 002.
   - Head differs from base: the annotation changed the dex; apply the rollback in rollout step 4.
5. The 4 new test classes and `OptionsUpstreamParityTest` exist. Gradle's test report lists them with 0 failures and 0 skipped. The existing 27 AOA tests still pass unchanged.
6. Every vector and size in `AoaContractVectorsTest` is a row of `doc/orka/aoa-lane-contract.md` Appendix A, and every Appendix A row is asserted there (a reviewer checks the two lists against each other). The Appendix A rows that POC `GADS/provider/aoalane/link_test.go:108-136` also asserts (video message, HELLO_HOST, HELLO, `BYE(stop,"x")`, max payload 16376) have the same hex bytes there.
7. `.github/orka/check-upstream-delta.sh` exits 0 on PR 3's head. On a throwaway local commit it exits non-zero with a readable diff in both of these cases:
   - one that adds a line to `server/src/main/java/com/genymobile/scrcpy/device/Device.java`;
   - one that removes the notice line from `Options.java`.
   
   Neither commit is pushed.
8. `diff` of the POC contract against `doc/orka/aoa-lane-contract.md` shows only the edits listed in PR 3 item 6. `grep -nE 'GADS/|PR #16 open|tools/scrcpy-fork' doc/orka/aoa-lane-contract.md` prints nothing (orka-backend 0033 acceptance 7). The status paragraph names both copies and says they are kept byte-identical. §2.4 exists and names `mSystemThread`.
9. No file under `server/src/main/` changes in PR 3 (`git diff --stat <PR2 head>..<PR3 head> -- server/src/main` is empty).

## Test plan

**Unit (CI, every PR):**
- `gradle -p server check` runs:
  - the existing `OptionsAoaTest`, `AoaProtocolTest` and `MessageParserTest`;
  - the new `AoaContractVectorsTest`, `AoaChannelFramingTest`, `AoaLivenessConstantsTest`, `AoaExitCodesTest` and `OptionsUpstreamParityTest`;
  - upstream's tests (`ControlMessageReaderTest`, `DeviceMessageWriterTest` and others), which must be untouched.
- Locally, the agent needs JDK 17 and an Android SDK with platform 36. This machine has no Android SDK, so CI is the reference.

**Build (CI):** `server/build_without_gradle.sh` with the pinned toolchain, plus the size and SHA report.

**Guard (CI):** the upstream-delta script, plus its two negative cases, run locally once (acceptance 7).

**Device checks (owner-run; the coding agent must not touch phones).** They are only needed if acceptance 4 shows the dex changed, and they are recommended once for the `Server.main` mapping and for §2.4.

Steps 1 and 2 are owner-run and do not depend on the POC's `poc.sh`: its adb-server mode (`./poc.sh up`) sees no phones under the dev server's USB bus split. They need one phone reachable by a plain adb client whose USB no provider owns, for example a phone attached to the owner's workstation, or a dev-server phone on a USB bus outside the provider's split. The Orka provider cannot run these checks for the owner once orka-backend Spec 0023 lands: it never launches the server with a zero nonce or on Android 12 with `aoa=`. Never run a host adb client where a provider owns the phones' USB (direct cells or the AOA lane): it starts an adb server that competes with them for the USB interfaces. Push the jar under test (the `scrcpy-server-dex` artifact of the PR's `server-dex` job, or a local `server/build_without_gradle.sh` build) and check it:
```sh
adb -s <serial> push scrcpy-server /data/local/tmp/orka-scrcpy.jar
adb -s <serial> shell sha256sum /data/local/tmp/orka-scrcpy.jar
```
1. Run:
   ```sh
   adb -s <serial> shell 'CLASSPATH=/data/local/tmp/orka-scrcpy.jar app_process / com.genymobile.scrcpy.Server 5.0.1 scid=0001e240 aoa=00000000 audio=false; echo exit=$?'
   ```
   Expect `exit=29` and the line `[server] ERROR: aoa: BAD_OPTION: aoa must not be 00000000`.
2. On an Android 12 phone, the same command with a valid nonce (`aoa=0a0b0c0d`) gives `exit=20` (`SDK_TOO_OLD`). The provider never sends that, so this checks the mapping only.

Steps 3 and 4 use the POC tooling on the dev server (`deploy/orka-dev-poc/`) and run with `./poc.sh up-aoa`. In that mode the POC provider owns the phones' USB through its direct cells, and there is no adb CLI path to them: no host `adb` client may be run while up-aoa is active, because it would start a server that competes with the cells for the USB interfaces.

3. With the provider on lane E, Android 13+ phones open accessory links with no fallback to lane D. This is the regression check for contract §2.4 (the `mSystemThread` toggle): without it every launch fails and every phone falls to lane D. The POC reached 12 links on Android 13-16 on the dev server (`dbaf74b`).
4. Stream targets from the POC field runs:
   - first access unit within 1.3-1.7 s, 24 fps at 0.79 Mbit/s over the data channel with the 720 px / 25 fps / 1 Mbit/s H.265 profile (POC spec §8);
   - relaunch without re-entry p95 ≤ 0.41 s (§8.5 4c).

## Risks

- **The `@SuppressLint` change may alter the dex.** That is **Not established** (d8 is expected to drop class-retention annotations). Acceptance 4 catches it, and rollout step 4 replaces it with a lint baseline. A changed jar would otherwise force Spec 002 to tag another commit and orka-backend 0023 to re-pin.
- **The reflection test breaks on a rename.** That is intended: renaming a liveness constant is a contract-relevant change and must touch the test. The fallback (parsing the source) applies only if class initialisation fails under the stub `android.jar`.
- **Runner drift.**
  - `ubuntu-24.04` will eventually be retired.
  - The preinstalled `gradle` (9.8.1 in run `39974414465`) is unpinned, as in `release.yml`.
  - The explicit `sdkmanager` install keeps the SDK fixed. A new Gradle could change lint results. When this happens, CI fails visibly on a PR, not silently on master.
- **The two contract copies drift.** Decision 8 and acceptance 8 make the fork copy match what 0033 copies; later, the status paragraph's rule, review and the shared Appendix A vectors keep them aligned. There is no cross-repo CI check.
- **Friction on upstream syncs.** Every `chore(sync)` PR must update `doc/orka/upstream-delta.txt`. That is the point of the guard. The script's failure output says what to change.
- **Master stays red until the next promotion** (rollout step 3). Releases still publish, because `release` does not need `test-scrcpy-server`. Readers of the master Actions tab should know the cause is this lint error.

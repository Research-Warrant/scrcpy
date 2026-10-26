# Orka server release

Orka's provider (orka-backend) embeds one scrcpy-server file and trusts it by SHA-256 only
(`aoa-lane-contract.md` §6.2-6.3). This page says which file that is, how it is released and how to
check it. Design and decisions: [Spec 002](specs/002-dex-only-server-release.md).

## What the asset is

- `scrcpy-server-v<version>-orka.<n>`: the dex-only zip that `server/build_without_gradle.sh` builds
  (one `classes.dex`, android-36, build-tools 36.0.0, JDK 17). Lanes D and E run this same file.
- It is **not** the `scrcpy-server-rw-*` file of the `rw-*` client releases. That one is the Gradle
  APK of `release/build_server.sh`: other bytes from the same commit. Orka consumes only
  `scrcpy-server-v*-orka.*`.
- Each release has `SHA256SUMS.txt` and notes with its provenance: tag, commit, `server/` tree,
  tooling commit, JDK, d8 and SDK revisions, runner image, size, SHA-256 and the double-build result.
  `orka` releases are never marked Latest.

## Naming

- Tag `v<versionName>-orka.<n>`, annotated, for example `v5.0.1-orka.1`. Asset
  `scrcpy-server-<tag>`, checksums `SHA256SUMS.txt`.
- `versionName` stays `5.0.1` (the provider passes it and the server rejects any other).
- Any byte change, including one from a toolchain change, is a new `<n>`, together with an
  orka-backend change of `ServerSHA256` (and its resource). A source change that leaves the bytes
  unchanged needs no release.
- Published assets are never replaced or deleted once orka-backend pins them; a fix ships as
  `orka.<n+1>`.

## Cutting a release

People create tags; the workflow `.github/workflows/orka-server-release.yml` only builds and
publishes them, and refuses a tag that already has a release.

- **Commit that carries the workflow:** push an annotated tag and the push starts the release:

  ```sh
  git tag -a v5.0.1-orka.2 <commit> -m "<what changed in the server>"
  git push origin v5.0.1-orka.2
  ```

- **Commit that predates the workflow** (such as `v5.0.1-orka.1` on `7ef1607c`): push the tag (it
  starts nothing), then dispatch the workflow from `master`, which builds the tag's tree with
  `master`'s script. Pass the SHA-256 of a local build to have the notes say whether CI matched it:

  ```sh
  gh workflow run orka-server-release.yml --ref master \
    -f tag=v5.0.1-orka.1 -f expect_sha256=<sha256 of the local build>
  ```

A SHA-256 or SDK revision that differs from the expected one is printed and recorded in the notes,
not fatal: the CI asset is the source of truth, and orka-backend pins what it publishes.

## Rebuilding locally

From a `master` or `develop` checkout, with JDK 17 on the path and `ANDROID_HOME` holding
`platforms;android-36` and `build-tools;36.0.0`:

```sh
TAG=v5.0.1-orka.1 release/build_orka_server.sh
```

The script builds the tag's tree, not the checkout's, so it works for a tag that predates it. It
fetches the tag if it is missing, refuses a lightweight tag, a version that differs from the tag's,
and a JDK other than 17, builds twice and fails if the two builds differ. The output is in
`release/output-orka/` (`scrcpy-server-<tag>`, `SHA256SUMS.txt`, `provenance.md`).

## Verifying a download

```sh
gh release download v5.0.1-orka.1 -R Research-Warrant/scrcpy
sha256sum -c SHA256SUMS.txt
```

The SHA-256 must also equal orka-backend's `ServerSHA256`.

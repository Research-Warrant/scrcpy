#!/bin/bash
# Research Warrant: build, check and package the dex-only scrcpy-server that Orka embeds, from the tree
# of an annotated tag v<version>-orka.<n> (doc/orka/server-release.md). The script is tooling: it never
# builds this checkout's server/, so it can release a tag that predates it.
#
#     TAG=v5.0.1-orka.1 [EXPECT_SHA256=<sha>] release/build_orka_server.sh
set -e
cd "$(dirname "${BASH_SOURCE[0]}")"
cd .. # root project dir

PIN_PLATFORM_REVISION=2
PIN_BUILD_TOOLS_REVISION=36.0.0
ANDROID_PLATFORM=${ANDROID_PLATFORM:-36}
ANDROID_BUILD_TOOLS=${ANDROID_BUILD_TOOLS:-36.0.0}
export ANDROID_PLATFORM ANDROID_BUILD_TOOLS

fail() {
    echo "build_orka_server: $*" >&2
    exit 1
}

# Checked before TAG is used in any command or path (it may come from a workflow_dispatch input)
if [[ ! "$TAG" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)-orka\.([1-9][0-9]*)$ ]]; then
    fail "TAG '$TAG' does not match v<x.y.z>-orka.<n> (n >= 1)"
fi
version=${BASH_REMATCH[1]}
if [[ -n "$EXPECT_SHA256" && ! "$EXPECT_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
    fail "EXPECT_SHA256 is not a lowercase SHA-256"
fi

W=release/work/orka-server
OUT=release/output-orka
rm -rf "$W" "$OUT"
mkdir -p "$W/src" "$W/a" "$W/b" "$OUT"

# Source tree: the annotated tag's, never this checkout's
if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    git fetch --no-tags origin "refs/tags/$TAG:refs/tags/$TAG"
fi
[[ "$(git cat-file -t "refs/tags/$TAG")" == tag ]] || fail "$TAG is not an annotated tag"
git archive "$TAG" server meson.build | tar -x -C "$W/src"

# The provider passes the version to the server, which exits on any other (contract §6.2)
check_version() {
    [[ "$2" == "$version" ]] || fail "version mismatch: the tag says $version, $1 at $TAG says '$2'"
}
check_version "SCRCPY_VERSION_NAME in server/build_without_gradle.sh" \
    "$(sed -n 's/^SCRCPY_VERSION_NAME=\(.*\)$/\1/p' "$W/src/server/build_without_gradle.sh")"
check_version "versionName in server/build.gradle" "$(sed -n 's/^ *versionName "\(.*\)"$/\1/p' "$W/src/server/build.gradle")"
check_version "version in meson.build" "$(sed -n "s/^ *version: '\(.*\)',\$/\1/p" "$W/src/meson.build" | head -n1)"

# Toolchain: the JDK major changes the bytes, so it is a hard requirement
javac_major=$(javac -version 2>&1 | sed -n 's/^javac \([0-9]*\).*/\1/p')
[[ "$javac_major" == 17 ]] || fail "javac must be JDK 17, found: $(javac -version 2>&1)"
PLATFORM_DIR="$ANDROID_HOME/platforms/android-$ANDROID_PLATFORM"
BUILD_TOOLS_DIR="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS"
[[ -f "$PLATFORM_DIR/android.jar" ]] || fail "missing $PLATFORM_DIR/android.jar"
[[ -x "$BUILD_TOOLS_DIR/d8" ]] || fail "missing $BUILD_TOOLS_DIR/d8"
platform_revision=$(sed -n 's/^Pkg.Revision=//p' "$PLATFORM_DIR/source.properties")
build_tools_revision=$(sed -n 's/^Pkg.Revision=//p' "$BUILD_TOOLS_DIR/source.properties")
# sdkmanager cannot pin a revision: a drift is recorded loudly, not fatal (the SHA shows its effect)
drift=""
if [[ "$platform_revision" != "$PIN_PLATFORM_REVISION" ]]; then
    drift="android-$ANDROID_PLATFORM: expected $PIN_PLATFORM_REVISION, found $platform_revision; "
fi
if [[ "$build_tools_revision" != "$PIN_BUILD_TOOLS_REVISION" ]]; then
    drift+="build-tools $ANDROID_BUILD_TOOLS: expected $PIN_BUILD_TOOLS_REVISION, found $build_tools_revision; "
fi
if [[ -z "$drift" ]]; then
    pins="yes"
else
    pins="no (${drift%; })"
    echo "WARNING: toolchain differs from the pins: ${drift%; }" >&2
fi

# Build twice from the tag's own build script (it resolves its sources from dirname "$0")
BUILD_DIR="$PWD/$W/a" "$PWD/$W/src/server/build_without_gradle.sh"
BUILD_DIR="$PWD/$W/b" "$PWD/$W/src/server/build_without_gradle.sh"
cmp "$W/a/scrcpy-server" "$W/b/scrcpy-server" || fail "build is not deterministic on this host"

ASSET="scrcpy-server-$TAG"
cp "$W/a/scrcpy-server" "$OUT/$ASSET"
(cd "$OUT" && sha256sum "$ASSET" > SHA256SUMS.txt)
sha256=$(cut -d' ' -f1 "$OUT/SHA256SUMS.txt")
size=$(stat -c %s "$OUT/$ASSET")
expected_line=""
if [[ -n "$EXPECT_SHA256" ]]; then
    if [[ "$sha256" == "$EXPECT_SHA256" ]]; then
        expected_line="- matches expected build: yes (expected $EXPECT_SHA256)"
    else
        expected_line="- matches expected build: no (expected $EXPECT_SHA256)"
        echo "WARNING: SHA-256 $sha256 differs from the expected $EXPECT_SHA256" >&2
    fi
fi
runner=""
if [[ -n "$ImageOS" && -n "$ImageVersion" ]]; then
    runner="- runner image: $ImageOS $ImageVersion"
fi

{
    echo "## Orka scrcpy-server $TAG"
    echo
    echo "Dex-only build of \`server/build_without_gradle.sh\`: the file Orka's provider embeds and pins by SHA-256. It is not the Gradle \`rw-*\` APK."
    echo
    echo "- tag: \`$TAG\`, commit \`$(git rev-parse "$TAG^{commit}")\`"
    echo "- server tree: \`$(git rev-parse "$TAG:server")\`"
    echo "- tooling commit (this script): \`$(git rev-parse HEAD)\`"
    echo "- android-$ANDROID_PLATFORM Pkg.Revision $platform_revision, build-tools $ANDROID_BUILD_TOOLS Pkg.Revision $build_tools_revision"
    echo "- toolchain matches pins: $pins"
    echo "- d8: $("$BUILD_TOOLS_DIR/d8" --version 2>&1 | head -n1)"
    [[ -z "$runner" ]] || echo "$runner"
    echo "- size: $size bytes"
    echo "- SHA-256: \`$sha256\`"
    echo "- double build: identical"
    [[ -z "$expected_line" ]] || echo "$expected_line"
    echo
    echo "java -version:"
    echo
    echo '```'
    java -version 2>&1
    echo '```'
    echo
    echo "Licence: Apache-2.0. This is a modified build of scrcpy v$version; see [LICENSE](https://github.com/Research-Warrant/scrcpy/blob/$TAG/LICENSE) at the tag."
} > "$OUT/provenance.md"

cat "$OUT/provenance.md"
echo "Generated $OUT/{$ASSET,SHA256SUMS.txt,provenance.md}"

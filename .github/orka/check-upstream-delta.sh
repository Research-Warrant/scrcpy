#!/usr/bin/env bash
# Research Warrant: fail when the server's delta against upstream differs from doc/orka/upstream-delta.txt,
# or when a modified upstream file lacks the "Modified by Research Warrant" notice (Apache-2.0 §4(b)).
# An upstream sync updates the base: line and the list in the same PR. Needs a full clone, no network.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

LIST=doc/orka/upstream-delta.txt
base=$(sed -n 's/^base: [^ ]* \([0-9a-f]\{40\}\)$/\1/p' "$LIST")
if [[ -z "$base" ]]; then
    echo "$LIST: first line must be 'base: <label> <40-hex commit>'" >&2
    exit 1
fi
if ! git cat-file -e "$base^{commit}" 2>/dev/null; then
    echo "base $base is not a commit in this clone (a full clone is needed: fetch-depth: 0)" >&2
    exit 1
fi
if ! git merge-base --is-ancestor "$base" HEAD; then
    echo "base $base is not an ancestor of HEAD" >&2
    exit 1
fi

expected=$(tail -n +2 "$LIST")
actual=$(git diff --name-status "$base" HEAD -- server/ ':!server/src/test')
if [[ "$expected" != "$actual" ]]; then
    echo "The server's delta against $base differs from $LIST (< listed, > actual):" >&2
    diff <(echo "$expected") <(echo "$actual") >&2 || true
    echo "Review the upstream change, then update $LIST in the same PR." >&2
    exit 1
fi

missing=0
while IFS=$'\t' read -r status path; do
    if [[ "$status" == M ]] && ! grep -q 'Modified by Research Warrant' "$path"; then
        echo "$path: modified upstream file without a 'Modified by Research Warrant' notice line" >&2
        missing=1
    fi
done <<< "$actual"
if [[ $missing -ne 0 ]]; then
    exit 1
fi

echo "Upstream delta matches $LIST ($(wc -l <<< "$actual") paths against $base)"

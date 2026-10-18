#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
submodule="$repo_root/app/src/main/cpp/usbipdcpp"
# Repeated builds may already have the patch applied. Never silently ignore a conflict.
for patch in "$repo_root"/patches/usbipdcpp/*.patch; do
    if git -C "$submodule" apply --reverse --check "$patch" 2>/dev/null; then
        echo "Already applied: $(basename -- "$patch")"
    else
        git -C "$submodule" apply --check "$patch"
        git -C "$submodule" apply "$patch"
        echo "Applied: $(basename -- "$patch")"
    fi
done

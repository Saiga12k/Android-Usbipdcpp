#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
submodule="$repo_root/app/src/main/cpp/usbipdcpp"
patch="$repo_root/patches/usbipdcpp/0001-fix-control-transfer-direction.patch"

# Repeated builds may already have the patch applied. Never silently ignore a conflict.
if git -C "$submodule" apply --reverse --check "$patch" 2>/dev/null; then
    echo "usbipdcpp control-transfer direction patch is already applied"
else
    git -C "$submodule" apply --check "$patch"
    git -C "$submodule" apply "$patch"
    echo "Applied usbipdcpp control-transfer direction patch"
fi

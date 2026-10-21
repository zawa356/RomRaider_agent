#!/usr/bin/env bash
# CI-5 P1: build-offline-tools.sh must never wipe anything outside <repo>/build/<subdir>. Owned fixtures only: a fresh
# victim directory created by this test next to a fresh link inside build/. Nothing else is touched.
#   bash scripts/test-build-offline-tools-guard.sh [--expect-red]
set -uo pipefail
cd "$(dirname "$0")/.."
root=$(pwd -P); red=0; [[ "${1:-}" == "--expect-red" ]] && red=1
windows=0; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) windows=1;; esac
tag="p9guard-$$-$RANDOM"; victim="$root/build/.$tag-victim"; mkdir -p "$victim/inner"; echo keep > "$victim/inner/keep.txt"
outside="$(dirname "$root")/.$tag-outside"; mkdir -p "$outside"; echo keep > "$outside/keep.txt"
link="build/$tag-link"
if [[ $windows == 1 ]]; then cmd //c mklink //J "$(cygpath -w "$root/$link")" "$(cygpath -w "$victim")" >/dev/null; else ln -s "$victim" "$link"; fi
checks=0; reds=0
refuses(){ local label=$1; shift
  if bash scripts/build-offline-tools.sh "$@" >/dev/null 2>&1; then
    if [[ $red == 1 ]]; then echo "RED $label accepted"; reds=$((reds+1)); else echo "FAIL $label accepted"; exit 1; fi
  else echo "PASS $label refused"; checks=$((checks+1)); fi; }
refuses "out dir through a link/junction inside build/" "$link/x"
[[ -f "$victim/inner/keep.txt" ]] && { echo "PASS victim behind the link intact"; checks=$((checks+1)); } || { echo "$([[ $red == 1 ]] && echo RED || echo FAIL) victim behind the link was deleted"; reds=$((reds+1)); [[ $red == 1 ]] || exit 1; }
refuses "the link itself as out dir" "$link"
[[ -f "$victim/inner/keep.txt" ]] || { echo "$([[ $red == 1 ]] && echo RED || echo FAIL) victim deleted via link"; reds=$((reds+1)); [[ $red == 1 ]] || exit 1; }
refuses "parent traversal build/../.. to an owned outside dir" "build/../../$(basename "$outside")"
[[ -f "$outside/keep.txt" ]] && { echo "PASS outside dir intact"; checks=$((checks+1)); } || { echo "$([[ $red == 1 ]] && echo RED || echo FAIL) outside dir deleted"; reds=$((reds+1)); [[ $red == 1 ]] || exit 1; }
refuses "build itself" "build"
refuses "build/ with trailing slash only" "build/"
refuses "absolute path" "$root/build/$tag-abs"
refuses "dot component" "build/./$tag-dot"
if [[ $red == 0 ]]; then
  bash scripts/build-offline-tools.sh "build/$tag-ok" >/dev/null && [[ -f "build/$tag-ok/romlogger-offline-tools.jar" ]] && { echo "PASS normal build/<dir> still builds"; checks=$((checks+1)); } || { echo "FAIL normal build"; exit 1; }
fi
# Cleanup of owned fixtures only: the link entry itself (not its target), then the owned directories.
if [[ $windows == 1 ]]; then cmd //c rmdir "$(cygpath -w "$root/$link")" >/dev/null 2>&1; else rm -f "$link"; fi
rm -rf "$victim" "$outside" "build/$tag-ok" "build/$tag-dot" "build/$tag-abs"
if [[ $red == 1 ]]; then echo "RED_COUNT $reds"; [[ $reds -gt 0 ]] || exit 1; exit 0; fi
echo "Offline tools OUT_DIR guard passed $checks checks"

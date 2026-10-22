#!/usr/bin/env bash
# CI driver for Phase VII-IX synthetic suites (public GitHub-hosted runners).
#   bash scripts/ci-phase7-9.sh GROUP      GROUP = phase7 | phase8 | phase9
# Every suite runs under the current JDK17 x64 (build + run). When ROMLOGGER_CI_JAVA8_X86_HOME is set (Windows job), the
# same suite is then re-run with --run-only under that actual Java 8 x86 runtime, after asserting sun.arch.data.model=32.
# All suites run even if one fails; the exit code is non-zero if any failed. A summary is written to GITHUB_STEP_SUMMARY.
# Synthetic fixtures only; no RomRaider.jar, ECU, network service or credential is used.
set -uo pipefail
cd "$(dirname "$0")/.."
umask 077
group="${1:?group}"
case "$group" in
 phase7) suites=("test-phase7.sh" "test-phase7-review-closure.sh");;
 phase8) suites=("test-phase8-inventory.sh" "test-phase-xa-zip.sh" "test-phase-xb-zip.sh" "test-phase8-readiness.sh" "test-phase8-multisession.sh");;
 phase9) suites=("test-phase9-cli.sh" "test-phase9-reader.sh" "test-phase9-inventory-race.sh"
                 "phase7-packaged.sh Phase9WorkdirTest" "phase7-packaged.sh Phase9LauncherEnvTest" "phase7-packaged.sh Phase9ReaderEntryTest"
                 "x64only:test-build-offline-tools-guard.sh");;
 *) echo "unknown group $group"; exit 2;;
esac
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
x86="${ROMLOGGER_CI_JAVA8_X86_HOME:-}"
# A Windows path (C:\...) in a bash PATH prefix is not searched: convert it, or the x86 check sees JDK17 (fails closed).
if [[ -n $x86 ]] && command -v cygpath >/dev/null; then x86=$(cygpath -u "$x86"); fi
model(){ java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *sun.arch.data.model = //p' | tr -d '\r'; }
echo "### $group on $(uname -s) (x86 rerun: $([[ -n $x86 ]] && echo yes || echo NOT_RUN))" >> "$summary"
echo "| suite | runtime | exit | PASS lines |" >> "$summary"; echo "|---|---|---|---|" >> "$summary"
failed=0;mkdir -p build/ci-logs
for s in "${suites[@]}"; do
  x64only=0; if [[ $s == x64only:* ]]; then x64only=1; s=${s#x64only:}; fi  # build-only checks: no Java 8 rerun (javac 8 has no --release)
  name=$(echo "$s" | tr ' /' '__')
  echo "::group::$s (JDK17 x64, model $(model))"
  bash scripts/$s 2>&1 | tee "build/ci-logs/x64-$name.log"; rc=${PIPESTATUS[0]}
  echo "::endgroup::"
  echo "| \`$s\` | JDK17 model $(model) | $rc | $(grep -c '^PASS' "build/ci-logs/x64-$name.log") |" >> "$summary"
  [[ $rc == 0 ]] || { echo "::error::$s failed on JDK17 (exit $rc)"; failed=1; continue; }
  if [[ -n $x86 && $x64only == 0 ]]; then
    m=$(PATH="$x86/bin:$PATH" model)
    if [[ "$m" != "32" ]]; then echo "::error::x86 runtime reports data model '$m' (need 32)"; failed=1; continue; fi
    echo "::group::$s --run-only (actual Java 8 x86, model $m)"
    PATH="$x86/bin:$PATH" ROMLOGGER_EXPECT_DATA_MODEL=32 bash scripts/$s --run-only 2>&1 | tee "build/ci-logs/x86-$name.log"; rc=${PIPESTATUS[0]}
    echo "::endgroup::"
    echo "| \`$s --run-only\` | Java8 x86 model $m | $rc | $(grep -c '^PASS' "build/ci-logs/x86-$name.log") |" >> "$summary"
    [[ $rc == 0 ]] || { echo "::error::$s failed on Java 8 x86 (exit $rc)"; failed=1; }
  fi
done
exit $failed

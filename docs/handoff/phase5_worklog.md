# Phase V append-only worklog

## 2026-10-19 — V-0 initial checkpoint

- Agent fetched main/clean checkout: `4f80c5e9831ec39368f4e771d829337598eeaa76`; Server: `d8c7a2f3b96ba278d269074dc40cca7741b4155b`. Agent #79 and Server #61 already MERGED; no open PRs in either repository. No retarget/remerge necessary.
- Windows development host DESKTOP-KVHGDS9. Both prescribed checkouts, JDK17 x64 and Zulu8 x86 exist. Existing clean main checkouts preserved; isolated worktree `C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-3\RomRaider_agent`, branch `phase5/provenance-hardening`.
- Latest Agent run39228822854 and Server39229023270 jobs have zero steps: CI NOT RUN (runner not started). No redispatch/billing changes.
- Source inspection confirms UUID-only journal-cursor check, incomplete vault field comparisons, subset-only RLI2 check, and only-later-run duplicate source rejection remain on main.
- RED plan: copied synthetic fixture mutations for cursor device/vehicle/session/path/chain/sequence/snapshot; vault every column/session/meta/schema; extra/missing RLI2 records/receipts/sources/admissions; changing original during staging; existing-target race and child JVM halt boundaries.
- Invariants: preserve v1 bytes/IDs/historical ACK; whole-session fail closed; compare RLC1 chain as RLC1 payload evidence only, never as RLC2 receipt/highwater; freeze original snapshots across classify/import/verify; exact independent output comparison; no overwrite/recovery of partial staging; new-path formal ACK remains disabled.
- Phase IV report now contains completed Windows batch/HTTP results, contrary to the supplied older prompt. These are historical results at other heads and are not counted as Phase V verification.
- Resume: inspect this log, `git status --short --branch`, then `scripts/test-phase5.sh` (to be added). All test data/logs remain ignored under build or new temporary synthetic directories.

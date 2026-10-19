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


## 2026-10-19 — V-0 RED / V-1 and V-2 GREEN / V-3 initial acceptance

- Checkpoint pushed as `00b9340`. RED test commit `d052c56` (tests executed against unchanged baseline implementation): 9 passed, 32 failed, exit1. Log `build/phase5-red.log`, SHA256 `3739c11625af168ea2d0d6638d1e7521abe9c7ae236ed32ec0143776fb5aeebf`. An initial harness failure lacked reflective Windows test classes; corrected before collecting RED. Fixture prefix was restricted to the claimed first physical source and independently encoded legacy metadata.
- Initial corrected implementation: 41/0; expanded suite: 56/0, Windows JDK17 x64. Full regression and final exact-head matrix pending. No production authorization follows from these local results.
- Cursor now compares every owner/path/prefix field, integral sequence and exact legacy metadata snapshot against independently supplied physical RLC2 evidence. Existing pure converter computes RLC1 payload chain/snapshot without translating it to durable receipt/highwater. Same-device/session multiple-vehicle claims collide without changing run IDs. Cross-run UUID reuse rejects both runs in either order.
- Vault validates every typed column, sessions/meta, strict schema (including unknown view/trigger refusal), version/quick/integrity/FK checks. RLI2 independent typed row-set comparison covers all eight tables from the original projection frozen during classification; original growth within staging refuses. Growth before a fresh classification remains legal.
- Staging atomically claims a new private `.rlpc1-work` directory and CREATE_NEW internal files. SQLite writes there only; forced completed files are published via atomic no-replace hardlink creation, followed by removal of the internal name. Final targets are never opened writable. Partial attempts/work directory are retained and retry refuses. Unsupported hardlink publication fails closed.
- Expanded tests include actual child JVM halt at four existing stages, collision at vault/RLI2/marker publication, unchanged competitor bytes, live old Worker lock, hardlink aliases, malformed duplicate cursor rows, extra/missing RLI2 evidence. Runtime.halt is not physical power-loss proof; Windows directory durability remains UNKNOWN.
- Status success renamed `STAGED_LOCAL_HASHES_MATCH_INACTIVE`: only local completed-file consistency, not source authenticity, authorization or full restoration. RLLV1 is record evidence only; RMI1 remains responsible for the complete old table inventory.

## 2026-10-19 — cross-platform validation correction

- Implementation checkpoint `d87108a5acd35721c527de58deb8fce19f5836ef` pushed; draft Agent PR80 attached to this task. Focused56/0 actual x86 (JVM32), load10000 records/10001 receipts actual x86 PASS. Phase IV regression33 legacy checks (plus review/transition/provenance) now passes after restoring the original-specific rejection reason ahead of orphan-cursor diagnostics.
- Fresh Linux extracted archive SHA256 `0e990c66634aceba9955d4141fb28c8985ac73c3dda11d2e205ac91d04d703aa` in `/tmp/romlogger-phase5-d87108a-dDp1ZJ`, with hash-pinned JDBC/SLF4J, exposed `UNSAFE_ACCESS`: remote umask allowed group-write on the freshly created work directory. The safety check refused correctly, but legitimate staging could not run. Fix: POSIX work directory requests owner-only0700 atomically at creation; Windows keeps inheritance from the verified private parent. Preserve first failing log; retry on a new extracted directory/head.
- Initial owned HTTP attempt refused because this new checkout had not generated `build/rlc2-integration-fixture.path` yet. Owned cluster stopped in finally; no shared cluster stop. Re-run after the full batch produces the fixture.
- PR80 Actions runs39232118574/579/549 failed before runner execution; journal run has all four jobs steps0. CI NOT RUN; no merge.
- Full Windows batch started at d87108a before POSIX directory correction; record compilation/source boundary accurately and run final focused tests at the corrected exact head. Never relabel the earlier/mixed working-tree batch as a clean final-head run.

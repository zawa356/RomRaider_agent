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

## 2026-10-19 — additional P0 physical replacement RED → GREEN

- Windows RED on compiled `69691a1` implementation: a fresh synthetic original replaced with byte-identical copied bytes at VAULT_ROWS_WRITTEN was accepted (exit1 assertion), because generic snapshot fileKey is null on Windows. RED log SHA256 `55f9d5b8a5c28b30cc95d25db20eb71e1d2a8bb4378a4a4888973fbdce0750ec`.
- Fix: retain the actual original ReadOpener in the classification report, and re-open/revalidate every physical RLC2 source against its captured marker at each snapshot verification. Existing source codec/native identity must agree; hash-only equality is insufficient. GREEN standalone physical replacement test exits0, log SHA256 `e2005cff182c77c54d282bbcf5ad15a89bb12d0c33b8e6eee486100093b872ec`.
- `69691a1` Linux focused56, phase4, phase3, exact10000 staging and preview/integration large workload/regressions all exit0 in `/tmp/romlogger-phase5-69691a1-j7tvfo`; hashes retained in ignored local console log. Windows corrected POSIX-head focused56/0 on x64/x86. These precede the physical revalidation fix; final focused/platform verification follows at the new head.
- Owned actual FastAPI/PG HTTP35 exits0 after fixture generation, including process stop503/noACK/restart, committed response loss/retry and unchanged Agent ACK bytes. Dedicated Server d8c7a2f migrations twice/check and pytest57 pass (1 warning). No existing service was stopped.
- Operational harness correction: waited for the SFTP upload completion before extraction. An initial premature SHA check refused a partial upload; nothing was extracted on that failed check. Correct complete archive hash verified before all Linux execution.

## 2026-10-19 — retained hash is not sufficient record evidence

- Four additional RED cases on the prior compiled classifier: changed non-ACKed body_json while retaining body_sha256, missing non-ACKed body, changed ACKed captured_at and changed observation sequence. All were accepted (exit1); log SHA256 `30824ee9fb44393c443daffa5a8cac26cfbccb2792eb016cad74dd11ba99728e`.
- Fix compares retained JSON and all canonical field values against the actual projected record in addition to the unchanged canonical hash. An unACKed NULL body is UNKNOWN (`UNACKED_BODY_MISSING`), while ACKed NULL bodies remain legitimate historical rows. Four GREEN cases exit0; log SHA256 `10e7416fb4bf3367b15a14c25319c5a1861967069c21e8d3d481efa8e1380356`.
- The original editing-checkout batch failed127 when Bash read a file being edited during execution. Its earlier results remain historical/mixed only; this is not a product test failure/PASS. A detached frozen checkout is already validating16db2c4. Final code will be frozen in a separate checkout, avoiding source mutation during execution.
- Linux16db2c4 phase5(56+physical-replacement), phase4, phase3, exact10000 staging and preview/integration10000+10sessions all exit0. New body-validation delta will receive final focused and broad matrix at its own head.

## 2026-10-19 — final code frozen, platform milestones

- Final implementation `b4b909cc3111c11e58f35b7600e47bea1d6aadfa`; original implementation/editing checkout now has documentation-only edits. Frozen Windows acceptance checkout `C:\Users\zawa\Documents\Codex\2026-10-19\new-chat-3\phase5-acceptance-final` is clean and remains at that exact detached head.
- Final focused Windows x64 and actual Zulu8 x86:56 main cases +1 byte-identical physical replacement +4 retained-body cases, all exit0. Final exact10000 staging/reverification actual x86 exit0. Final actual owned PG/FastAPI HTTP35 exit0 using the frozen b4b909c candidate and Serverd8c7a2f; only its invocation-owned cluster stopped.
- Final Linux b4b909c:61 focused cases, Phase IV, Phase III, exact10000 staging/reverification, full preview/integration/schema/snapshot/JVM regression and10000+10sessions load all exit0. Source archive SHA256079f6778f891f03533b005ce4489ccd5b278103a89edabdc2d0abee467d4611e. New root `/tmp/romlogger-phase5-b4b909c-8rPW4i`; findmnt reports ext4. Large multi-session load59 checks,11sessions,10100records/10111receipts/22admissions/11bindings. Timings observational only.
- Stopped only the superseded16db2c4 Windows batch owned by this session (verified wrapper PID15820 matching its unique console log,7 descendant processes total); partial synthetic test artifacts retained, no PASS claim for that cancelled run. Final b4b909c batch continues independently. No service/old user Worker stopped.
- Remaining finalization: wait for exact-head full Windows x64/x86 batch, record log hash/counts; publish Agent handoff and Server progress PR; inspect latest CI zero-step status and leave both unmerged.

## 2026-10-19 — final publication-name guard correction

- Independent code review found that internal staging uses a valid dedicated RLI2 filename but final publication could bypass the previous public backend's `.rlc2-integration.db` extension check. RED on b4b909c compiled code actually accepted final `product.db` with a valid synthetic source (exit1); SHA256 `f8eef2fb6a2c06902095251a6bd740642d71306cfee0584b86f1de176ba09e91`.
- Restore the same dedicated-extension requirement at the start of stageIsolated, before any input access/claim/write. Specific code `STAGING_RLI2_EXTENSION_REQUIRED`; test verifies no staging artifacts are created. GREEN exit0; SHA256 `2859fcda58e56988b80984f45e05eb97267b0854c6f356341e5b72b2313df2d6`.
- This is a one-guard delta plus a new negative test. The frozen broad Windows batch remains at b4b909c; do not relabel that whole batch as the new guard head. Re-run the entire focused suite62 cases on Windows x64/x86/Linux at the final guard head, and report both exact heads explicitly. No broader repetition is needed unless the focused results uncover another concern.

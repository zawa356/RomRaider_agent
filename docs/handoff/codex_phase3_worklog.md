# Phase III append-only worklog

## 2026-10-19T11:59:17.0274787+09:00 — III-0 initialization before code

- User Phase III standalone instruction authorizes synthetic implementation, local/remote tests, push/PR and normal merge. Agent main 1dfdcd8f564e2c46cb2651ab76aa4e1705701b83; Server main 3091d6196d3ebb69c568de09b8b4941fe35cad5e, fetched; both clean, open PR0. Branch feat/phase3-deployment-binding. GitHub CLI authenticated; author uses personal noreply.
- Read bootstrap, Phase II report/worklog/evidence, AGENTS, product progress, lane ownership/recovery, RLC2 integration, Server progress/index/wire/approved requirements/design. Server has no AGENTS.md. Current GitHub matches the instruction baseline.
- Actual Windows development paths accessible; Java17 data model64 and actual Zulu8 data model32 independently checked. Python3.12 and existing SSH helper/pins available. Remote connection in progress, not yet PASS.
- Latest Agent run39183584169 and Server39183738536 have failure jobs with steps=[]: CI NOT RUN (runner did not start). No billing change or redispatch.
- Plan: III-1 versioned deployment binding and read-only preflight; III-2 diagnostic cutover; III-3 isolated backup acceptance and reference DB cleanup; III-4 migration rehearsal; III-5 platform/regression evidence and handoff. RED then GREEN, small PRs, preserve exact tested heads and log hashes.
- Safety: synthetic opt-in only; no real car/X270/OpenPort/ECU, production DB migration, automatic Worker stop/restart/cutover, formal ACK, public deploy, lock deletion or forced takeover. Windows directory durability UNKNOWN; physical power loss and real ENOSPC NOT RUN. Local root/path checks do not provide global fencing across clones/hosts/network FS.

## 2026-10-19T12:05:08.7012458+09:00 — III-1 candidate implementation and local acceptance

- Checkpoint6206d5e pushed before code. Pinned VPS connection independently verified Linux JDK17; Docker14 existing containers all Up. No existing service changed.
- RED: DeploymentBindingRedTest against unmodified LaneOwnership acquired same logical session under different roots; AssertionError UNBOUND_ROOT, build/phase3-root-red.log (exit1). This remains a documented lower-level API limit, not silently fixed for old defaults.
- RLDB1 paired root/legacy anchor, canonical identity/ACL/layout checks, read-only JSON CLI and bound RLI2 entry implemented. Windows17 binding18 and actual bound-engine6 checks PASS in build/phase3-binding-x64.log. Real Windows junction created/rejected; unsafe ACL rejected. Precommit binding damage rolls back records and explicit retry succeeds ACK-ineligible.
- Actual x86 focused suite and full Windows regression running. Linux execution pending. Same-DB alternate root refused; coordinated clone/global fencing remains BLOCKED without external single-active assertion. Manifest replacement requires future explicit reviewed rebinding; no repair API.

## 2026-10-19T12:13:35.5303693+09:00 — III-2 diagnostic acceptance / III-1 review

- PR70 https://github.com/zawa356/RomRaider_agent/pull/70 head80507c2. Windows actual x86 binding18/engine6 PASS; Linux fixed head80507c2 binding17/engine6 PASS. Linux initial head6bf2efa refused unsafe fixture permissions (not PASS); new POSIX bindings use CREATE_NEW with0600. Logs retained.
- Cutover diagnostic independently inventories read-only v1 under an existing shared observation lock, class-compares bounded current-build artifact, reports source/cursor/receipt/ACK/latches. Unknown artifact or absent lock requires review. Actual child Worker holding exact lock blocks; test-owned child exit followed by explicit retry succeeds. Focused Windows x64 readiness11 PASS (phase3-cutover-focused-x64.log).
- Development failures retained: shared diagnostic probe raced nonblocking Worker startup; test now waits for owned Worker startup. IDLE next_attempt_ms was incorrectly considered fault; narrowed fault to retry_count/needs_review and ingress latch. Initial artifact fixture required an unused class; corrected tested dependency set. No claim that artifact matches an actual running process.
- Initial full regression reached phase3 after existing x64 suites passed, then failed the evolving readiness fixture (bad review reason). This run is NOT full PASS; final stable-source rerun required. CI PR70 job117870597132 steps=[]; CI NOT RUN.
- Cleanup RED: three preflights plus repeated migration created10 new schema-reference directories, build/phase3-cleanup-red.log, AssertionError REFERENCE_LEAK. Pre-existing reference directories are retained; fix follows in III-3.

## 2026-10-19T12:19:48.0138866+09:00 — III-3/4 isolated backup and rehearsal candidate

- Cleanup RED log phase3-cleanup-red.log: REFERENCE_LEAK new schema dirs10. GREEN phase3-cleanup-green.log: zero new references, all pre-existing references preserved. Added exact finally cleanup only for invocation-owned paths.
- Added read-only RMI1 verifyIsolatedCopy, existing-only RLI2 schema verification (no auto-create), RLBA1 bounded role/hash capture + copied-target inspection CLI, and test-only preflight/owned-copy/inventory/candidate/backup rehearsal.
- Windows x64 backup/rehearsal20 checks PASS in phase3-backup-focused-x64.log: prepared-commit interruption/retry, row/ACK/cursor/receipt fingerprints, original unchanged, complete copied evidence/CLI, missing owner/tampered record/ACK/metadata/WAL/active guard/second importer/manifest-ledger refusal. First fixture used wrong payload_json SQL column; corrected to body_json, failure retained in phase3-backup-x64.log (not PASS).
- No RMI1->RLI2 record conversion, restored deployment activation, cross-DB atomicity or global fence claim. New full after-owner/during-copy/after-commit fault matrix and actual operator cutover/rollback remain incomplete. Stable-source final matrix follows.

## 2026-10-19T12:22:13.1282441+09:00 — final regression found product build boundary defect

- Stable96487fe full batch FAIL at test-ingress-sqlite-receipts -> product-v1 wildcard compile: new backup/rehearsal classes in product.v1 depended on prototype ingress classes absent from that product build. Moved those synthetic-only helpers into prototype.ingress; product package remains independently buildable. This is a real introduced build regression, not an environmental skip. Final matrix restarts on corrected head.
- HTTP independently PASS35 on96487fe with actual invocation-owned PG immediate-stop/restart; existing PG not stopped. Server test result pending collection.
- Linux final archive extraction succeeded, then mkdir lib found tracked existing directory; harness setup failure NOT PASS. Continued only in the same fresh owned directory using explicitly named dependency files; first attempt retained. No existing service touched.

## 2026-10-19T12:30:58.3134767+09:00 — independent review RED: hardlink aliases

- Actual Windows RED phase3-hardlink-red.log: same physical old DB hardlink alias enrolled under two roots/lock paths (HARDLINK_ALIAS AssertionError). Added single-hardlink inspection: POSIX unix:nlink or bounded read-only Windows fsutil list. Actual Windows x64/x86 dedicated GREEN refuses alternate enrollment before metadata writes. Unknown inspection safely refuses; fsutil never mutates sources.
- More serious Phase II migration RED phase3-migration-alias-red.log: target hardlink with matching RMI1 sidecar changed the fresh synthetic original before final digest refusal (SOURCE_MUTATED). No existing/user DB involved. Fixed source/target alias, hardlink and canonical parent checks before guard/writable open. Actual x64/x86 GREEN phase3-migration-alias-green-*.log preserves original bytes. Bounded owner sidecar128 bytes.
- Linux ba82688 had product/PhaseIII/PhaseII/10003-record bound-load PASS; actual Windows x64/x8610003-record bound loads PASS. Those precede the new hardlink fix and are not its platform evidence. Full matrix is repeated where new changes require it.
- One trailing blank line in CutoverReadiness removed: recompiling yields identical class SHA25635db915167f055a94973b8b6074e73134a308168c6634e5c662f92b8285cf53d. Known credential values absent from outgoing diff; generated tracked artifacts0. PyYAML unavailable in system and bundled Python, so YAML parser check NOT RUN; changed workflow lines manually reviewed. PowerShell AST PASS.
- An attempted deletion of old owned build output was rejected by execution approval policy; instead verified its exact non-reparse source/destination under this repo/build and renamed it to preserve the artifacts. No original/test evidence deletion.

## 2026-10-19T12:48:26.9051973+09:00 — next small phase checkpoint: migration fault boundaries

- Frozen stack validation remains2bef02e in independent clean C:\Dev\RomLogger\phase3-release-2bef02e. Linux product/PhaseIII/PhaseII/migration21/10003 bound records PASS; Linux preview 10000+10-session59 PASS. Same-head HTTP35 and bound10003-record Windows64/actual32 PASS. Full isolated Windows batch at final actual32 PhaseIII; no final PASS asserted until process exit.
- Next branch feat/phase3-migration-fault-matrix. Plan: synthetic-only stage hooks before output, after owner creation, during copy, after copy, before commit and after commit; actual child halt and failure-state classification; DB busy and source-change refusal; preserve partial copies/ownership evidence and require explicit retry or operator review. No production restore/ACK/automatic cleanup/Worker stop.
- RLDB1 remains one fixed logical Session/manifest per anchor. Legitimate manifest-file replacement/next Session needs a future reviewed rebind authority; old low-level multi-session load is not acceptance of bound deployment Session transition. Do not mark that product gate complete.

## 2026-10-19T12:55:46.4994372+09:00 — frozen stack merged and new stage-test finding

- Clean fixed checkout2bef02e Windows full acceleration exit0: build/acceleration-20261019-123351.log under phase3-release-2bef02e. Same-head HTTP35, Windows64/actual32 bound10003, Linux suites/migration21/bound10003 and Linux preview10000+10-session59 PASS.
- PR70 normal merge6e0c779501a4a892e61874b93516a48dd416b1cc at2026-10-19T12:54:13+09:00; PR72 normal mergef8b428e2333e0d88ac554ace5df7599a35b965d0 at12:54:21+09:00. PR71's individual merge request rejected shortened expectedHeadOid b737339 (requires full40); its exact commit b737339023c2f1fa39259f7c314166a37c9de49a was already included by PR72 and GitHub automatically marked PR71 MERGED at12:54:23+09:00. Do not call b737339 a separately created merge commit. main f8b428e has zero src/scripts/.github diff versus2bef02e.
- New copy-state CLI runtime RED phase3-copy-status-red.log (unsupported old mode, expected NO_CANDIDATE JSON/exit5); new mode GREEN phase3-copy-status-green.log, source/target/owner unchanged.
- Initial stage test correctly observed OWNED_INTERRUPTED_TARGET_REVIEW_REQUIRED after actual AFTER_COMMIT halt: source uses SQLite WAL, so committed frames can remain in WAL/SHM before connection close/checkpoint. Read-only inspection must not assert committed inventory by ignoring sidecars. Test expectation corrected to accept conservative sidecar review, followed by explicit owned recovery and full verification. Initial failure retained in phase3-stages-x64-initial.log; no production state affected.

## 2026-10-19T13:08:39.9373932+09:00 — migration copy fault implementation checkpoint

- Added synthetic BEFORE_OUTPUT/OWNER_CREATED/COPY_PROGRESS/COPY_COMPLETED/BEFORE_COMMIT/AFTER_COMMIT hooks, bounded forced CREATE_NEW copying, read-only existing-lock copy-state CLI requiring --external-quiescent/--no-apply, and full rehearsal stage delegation. Owner-only/partial copies remain manual; complete/owned-interrupted candidates require explicit retry. No source repair/ACK activation.
- Development Windows64 stage52 PASS and migration21/CLI2 PASS; full rehearsal/backup32 PASS, including before-output/partial-copy/after-commit and first-artifact backup interruption. Final-platform proof is pending the committed stable head; development trial preceded the newest sidecar fix.
- Additional RED phase3-recovery-alias-red.log: owned candidate WAL hardlink changed a different fresh synthetic DB during SQLite recovery (RECOVERY_ALIAS_MUTATED). Added canonical/no-reparse/single-hardlink/regular-file validation for owner and every recovery sidecar before writable open; read-only state now retains/manual-reviews unsafe sidecars. Dedicated Windows64/actual32 GREEN preserves both source and unrelated synthetic DB. Original RED test source retained at build/phase3-recovery-alias-red/source/RecoverySidecarAliasTest.java for replay against2bef02e; no existing DB involved.
- New readiness test credentials are generated at runtime and remain in child environment only. Product default Worker startup/transport/wire/schema are unchanged. Stable new head will run focused/full matrix in isolated checkout and Linux before PR/merge.

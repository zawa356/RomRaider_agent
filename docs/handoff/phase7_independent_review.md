# Phase VII-0 independent review of Phase V/VI

Reviewer: Claude Code (Opus 5.5), fresh session with no prior chat context; a different agent from the Phase V/VI implementer (Codex). No other reviewer agent was used. The Phase VI report and evidence were read as claims to check, not as proof.

## Scope and exact code

| Item | Value |
|---|---|
| Reviewed head | `297126cf2b44029f11c26bc757473d7c9018e8d1` (PR86 `phase6/transition-drive-preflight`); runtime/tests identical to `53f18a27663c0f7f9c8f2398fd3390190f5f5749` |
| Chain check | `git merge-base --is-ancestor` confirms PR80 `phase5/provenance-hardening` and every PR81–85 head are ancestors of PR86. PR bases are unchanged (80→main, 81→80, …, 86→85). |
| Not conflated | Phase VI broad Windows batch (3181 PASS lines, owned HTTP35) belongs to `d3941031af4d22c7317055c6d9439cf10e75e364`. Focused final-head runs belong to `53f18a2`. This review does not relabel either as the other or as a Phase VII result. |
| Read directly | LegacyProvenanceClassifier, Rlc2InputSnapshot, LegacyStagingTargets, LegacyStagedEvidence (role), DeploymentBinding, WindowsDriveGate, OfflineCandidatePreflight, LegacyCandidateGate, DeploymentTransition (verifyActive/abandon/CLI), LaneOwnership, LegacyWorkerGuard, ArtifactProvenance, Rlc2IntegrationStore, Rlc2IntegrationCli |

## Findings

| ID | Severity | Finding | Evidence | Disposition |
|---|---|---|---|---|
| F1 | **Medium (reproduced)** | `OfflineCandidatePreflight.MAIN` approved `Rlc2IntegrationCli` as the known offline entry, but that CLI's only write path is the unbound `Rlc2IntegrationStore.ingest`: Worker guard only, no RLDB/RLDT generation check, no Lane or Publication lock, self-asserted `--quiescent`. A matched preflight therefore did not bind what would be run, and a preflight refusal did not stop the approved entry. | Test-only `Phase7EntryBypassRedTest` at runtime 297126c, Windows JDK17 x64, `scripts/test-phase7-review-red.sh` exit1, 4 PASS/4 RED. Control: preflight WRITER_UNPROVEN/matched and CLI stores. Actual child JVM holds Lane (PID16828) → preflight `LANE_NOT_OBSERVED_IDLE`, CLI exit0 and candidate output changed; same for Publication (PID11848) → `PUBLICATION_NOT_OBSERVED_IDLE`. Log `build/phase7-entry-bypass-red.log` SHA256 cad47faafca458d455cd3f9a971bfad318039c8e3ae962903d54a44cdd28db31. | Cooperative-lock bypass, not production authorization: launch_authorized/ACK stayed false, no Sender. Fixed in VII-1: new `OfflineCandidateEntry` is the only approved main; it writes only through `ingestBoundActive` (holds Worker/Lane/Publication, re-checks generation at every stage). `Rlc2IntegrationCli` itself is unchanged and still callable as a low-level PoC; it is no longer what preflight approves. The RED script stays exit1 by design (it exercises the unchanged old CLI). |
| F2 | Low | Preflight exception mapping turns every non-Refusal failure into `INSPECTION_UNAVAILABLE`. A projection refusal (e.g. `synthetic payload session required`) loses its cause. Fail-closed, but diagnosis evidence is lost. | Observed while building the Phase VII fixture (debug run, not retained as a test). | Integrated readiness (VII-2) records per-axis codes instead of a single collapsed reason. Preflight unchanged. |
| F3 | Low | When an operator-supplied original fails verification, the classifier records `ORIGINAL_UNVERIFIABLE` only in the conflict list; that run's v1 rows are classified `LEGACY_WITHOUT_RLC2_PROVENANCE / NO_ORIGINAL_SUPPLIED`. Not migratable (safe), but the row reason misstates what happened. | Code reading, `LegacyProvenanceClassifier.classify` lines 100–110. | Readiness must surface the conflict list, not the row reason alone. No runtime change (would change RLPC1 output contract). |
| F4 | Low | `DeploymentTransition.fileSha` checks the 128 MiB bound on `length()` and then streams without a running bound; growth after the check is hashed unbounded. | Code reading. | Inputs are snapshot-verified elsewhere; record as a robustness follow-up, no change. |
| F5 | Info (known) | `LegacyWorkerGuard`/`LaneOwnership` lock identity falls back to creation time on Windows (fileKey null). | Code reading. | Already documented Phase V/VI limit; Windows process provenance and identity remain UNKNOWN. |
| F6 | Info | `Rlc2IntegrationCli.main` and `DeploymentTransition.main` use `POSIX_READER`; on Windows the RLC2 reader is test-only JNA. | Code reading. | The Phase VII entry pins the reader in its profile (`POSIX` or `WINDOWS_NATIVE_TEST_ONLY`, the latter loaded only from the hashed, pinned test JAR). Not a product Windows reader. |

## Checklist results (no defect found beyond the table)

- **Snapshot then write**: classifier/stager re-verify `Rlc2InputSnapshot` at each stage and before marker creation; integration store re-verifies in `beforeWrite` and `beforeCommit`. Content-only; noncooperative writer absence remains WRITER_UNPROVEN.
- **Exception handling losing evidence**: staging retains partial outputs; preflight collapses reasons (F2).
- **Old Runner/Writer TOCTOU**: preflight shared observations are released before any writer can start — inherent; the Phase VII entry re-acquires exclusive locks inside the store and re-checks generation (F1 fix). Arbitrary noncooperative/old binaries are still not excluded.
- **Drive check ordering**: `DeploymentBinding.describe` calls `requireLocalDrives` before path inspection; transition plan/commit drive proof precedes chain/lock I/O at 53f18a2. Preflight's first layout access is `verifyActive → describe`. Confirmed by reading.
- **Path aliases**: `DeploymentBinding.safe` rejects non-normal, UNC, reparse, alias and hardlink paths; preflight's own lane-path construction is not UUID-normalized but runs only after `describe` has required canonical UUIDs (benign).
- **Vault completion marker ordering**: vault publish → RLI2 publish → `verifyStaged` → snapshot/guard verify → marker CREATE_NEW last. Confirmed.
- **ACK bypass**: no INSERT/UPDATE of v1 ACK state or Sender call in `prototype/ingress` (grep). Result objects keep `ack_eligible=false`.

## Safety statement

All reproduction used invocation-owned temp fixtures and child JVMs. No existing v1 DB, original, ACK, Worker, Sender, service, ECU/X270/OpenPort was touched. WRITER_UNPROVEN, Windows process provenance and directory durability UNKNOWN, global fencing absent: unchanged by this review.

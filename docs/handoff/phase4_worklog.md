# Phase IV append-only worklog

Times are JST (+09:00). Append only; do not rewrite earlier entries.

## 2026-10-19T15:59+09:00 — IV-0 start checkpoint (before any code edit)

- Agent: Claude Code (Claude Opus 5.5) in the Claude desktop app, on the Windows 11 Pro development VM (10.0.26200, ja-JP, user medium integrity). Earlier phases were implemented by a different agent (Codex); this session reviews them as an independent third party.
- Start main: Agent `2b620c744e900b1ea2a5f614e0047b256c72a77d` (PR #74), Server `bb27cd10ad60cda1a7f4434c3f803df6c4db2a39` (PR #60). Both clean, local main == origin/main, open PRs 0.
- Paths confirmed: `C:\Dev\RomLogger\{RomRaider_agent,RomRaiderServer}`, JDK17 x64 `17.0.20.1`, Zulu8 x86 `1.8.0_504` (win32 client VM), gh authenticated as `zawa356`.
- GitHub Actions: latest Agent runs on PR #73 merge and Server run on PR #60 merge are `failure` in 3-5s with jobs `steps=0` (e.g. Agent run 39207094385). Recorded as **CI NOT RUN (runner not started)**. No billing change, no re-dispatch.
- Work branch `phase4/checkpoint-review`. Gates kept: no X270/OpenPort/ECU, no production DB/service/Worker change, no formal ACK, no fabricated RLC2 provenance, Windows directory durability UNKNOWN.
- Next: IV-0 independent review of DeploymentBinding / LaneOwnership / LegacyWorkerGuard / CutoverReadiness / V1LaneMigration / DurableSpool / Rlc2IntegrationStore / Rlc2InputSnapshot / IsolatedBackupAcceptance / SyntheticMigrationRehearsal.

## 2026-10-19T16:55+09:00 — IV-0 independent review, RED→GREEN

- Read DeploymentBinding/LaneOwnership/LegacyWorkerGuard/CutoverReadiness/V1LaneMigration/Rlc2IntegrationStore/Rlc2InputSnapshot/IsolatedBackupAcceptance/SyntheticMigrationRehearsal plus tests and docs. DurableSpool only pragma/lock sections.
- Probe: Windows Java `fileKey()==null` (JDK17.0.20.1), so RLDB1 Windows identity was creation time only. Linux VPS probe: birth time stable, inode reuse detected via birth time.
- RED (Windows x64): F1 legacy DB replaced keeping creation time accepted; F1b ledger same; F1c delete+recreate (NTFS tunneling) accepted; F2 knownArtifact accepted manifest Class-Path; F2b Launcher-Agent-Class accepted.
- Fix: Windows identity = volume serial + NTFS file ID via bounded read-only `fsutil file queryfileid`; old `null|` records → BINDING_IDENTITY_REENROLL_REQUIRED; manifest attribute allowlist, non-class resources and duplicate entries refused; RMI1 sidecar check NOFOLLOW; fsutil output drained while waiting.
- GREEN: review test 9/0 on JDK17 x64 and Zulu8 x86; Phase III deployment regression exit0 on both (counts unchanged). Full matrix still pending (acceleration batch, Linux, HTTP).
- Findings recorded in docs/handoff/phase4_independent_review.md; evidence in phase4_validation_evidence.md.

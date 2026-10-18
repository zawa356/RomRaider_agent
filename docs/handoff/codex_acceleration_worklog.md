# Codex acceleration worklog — 2026-10-18

## 2026-10-18 11:37 JST — START / Phase A review then B diagnostics

- User GO received after preflight. Agent base `a6e10cc8e01c74e79d18382e64552c488894eef2`; Server base `1dd425808b8611135beb0bb984cc8df212936f1e`.
- Branch `feat/acceleration-diagnostics`; clean initial trees; both open PR lists empty; personal noreply author confirmed. CloudPC MCP unavailable; local Windows and pinned SSH are available.
- Preflight: Windows JDK17 x64 / Zulu8 x86 installed; real SQLite ledger 27 + rotation 43 PASS on Windows x64; isolated PostgreSQL16.15 read-only query PASS. Docker restricted disposable Java container PASS, removed. VPS Ubuntu24.04 SSH/password sudo PASS; Java/SQLite provisioning pending. VPS reinstalled host pin updated with prior pin backup per explicit user authorization.
- Plan: short independent code review of RLC2/ledger/manifest; fix critical findings with RED/GREEN; implement read-only Supervisor diagnostics with stable codes/JSON/CLI and real-DB tests; continue with isolated adapter/E2E, faults and CI.
- Gates: directory durability UNKNOWN on Windows; no production ACK/sending, ECU/X270, original deletion/repair, existing Docker service changes or production DB writes. Runtime.halt is not physical power loss.
- Next: inspect `RotationManifestPrototype`, `WatermarkV2Prototype`, `WatermarkLedgerPrototype`, existing product ingress interfaces; implement diagnostics using read-only SQLite connections (never construct mutating ledger just to inspect).
- Tests after GO: NOT RUN yet. No implementation PR yet.

## 2026-10-18 11:51 JST — Phase B RED: SQLite mode=ro side effects
- Initial diagnostics API/CLI and real SQLite scenarios implemented. Windows x64 RED: read-only SQL connection creates WAL/SHM companion files, violating zero-file-mutation test. No production/original data touched; test fixture only. Fix will require a quiescent SQLite snapshot, reject WAL/journal companions, and use immutable read-only mode; active DB fails closed.
- Phase A targeted review found no critical regression requiring a core fix; prior limitations retained. Next: fix diagnostic read-only DB opening, extend DB-active rejection test, rerun x64/x86/Linux.


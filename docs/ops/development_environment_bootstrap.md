# Development environment bootstrap — start here

Verified 2026-10-19 JST. This document contains paths and non-secret operating rules only.

## First three sources

1. [This bootstrap](https://github.com/zawa356/RomRaider_agent/blob/main/docs/ops/development_environment_bootstrap.md)
2. [Phase III final report](../handoff/codex_phase3_report.md) / [append-only worklog](../handoff/codex_phase3_worklog.md) / [validation evidence](../handoff/phase3_validation_evidence.md)
3. [Agent progress](../product_v1_implementation_progress.md) and [Server progress](https://github.com/zawa356/RomRaiderServer/blob/main/docs/implementation_progress.md)

Read AGENTS.md, latest fetched main/PRs, current contracts and the latest report before edits. Older dated instructions describe history.

## Actual development host and access differences

Windows 11 development VM is separate from the real-car X270. Repositories:
C:\Dev\RomLogger\RomRaider_agent and C:\Dev\RomLogger\RomRaiderServer.
A cloud or other host cannot assume these paths or local credentials exist. Test their existence first; use GitHub/local synthetic work when inaccessible. Do not probe the X270 or ECU.

Proxmox Web URL, VM ID and RDP destination: **unconfirmed / administrator information pending**. No authoritative management-entry document found in current repository. Do not infer endpoints from docs/05_environment.md (X270 record).

## Tools and credential paths

- PowerShell7, Git/Git Bash, gh, Python3.12, SSH.
- JDK17 x64: C:\Program Files\Zulu\zulu-17; actual Java8 x86: C:\Program Files (x86)\Zulu\zulu-8. Assert runtime architecture in tests.
- Credential data: C:\Users\zawa\git\hosts.env. Parse as dotenv data, never execute or print. VPS keys IP/PORT/USERNAME/PASSWORD; Docker keys DOCKER_SSH_HOST/DOCKER_SSH_PORT/DOCKER_SSH_USER/DOCKER_SSH_PASSWORD.
- Existing helper: C:\Dev\tools\romlogger_ssh.py; interpreter C:\Dev\tools\venv-ssh\Scripts\python.exe; pinned host keys C:\Dev\secrets\romlogger_known_hosts.json. Verify existence and implementation before use. Key mismatch stops; never erase all pins/disable verification.
- VPS and Docker SSH verified on 2026-10-19; Linux JDK17 available, 14 existing Docker containers Up. Only fresh isolated synthetic work directories/disposable resources may be used. No existing service/volume/network/DB changes.
- Local isolated PostgreSQL16 test cluster on loopback port55432; helper C:\Dev\tools\romlogger-pg.ps1; Server tests C:\Dev\tools\run-server-tests.ps1; Python C:\Dev\tools\venv-server\Scripts\python.exe. DSNs stay in protected process environment, never logs/arguments.

## Test entrypoints and independent gates

Agent: `pwsh -NoProfile -File scripts/run-acceleration-tests.ps1`.
Optional guarded PG/HTTP and owned-cluster stop/restart: [automation](../acceleration_test_automation.md).
Linux: same Bash scripts in a new dedicated directory with synthetic sources and hash-verified dependencies only.
Root test.sh requires RomRaider.jar; absence means NOT RUN, not success.

GitHub Actions latest observed jobs have empty steps and account-limit/payment annotations; record **CI NOT RUN (runner not started)** and inspect fresh status each session. Local verification can support normal merges of authorized non-production changes; do not change billing or repeatedly dispatch blocked workflows.

Synthetic lock/HTTP tests never enable live ECU ingestion, production migration, formal ACK or original deletion. Windows directory durability UNKNOWN; Runtime.halt is not physical power loss. Keep per-phase commands, failures and resume pointers in the append-only worklog. Never commit secrets, generated data or test credentials.

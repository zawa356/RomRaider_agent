# Phase II append-only worklog

## 2026-10-19 09:34 +09:00 — initialization checkpoint (before code)

- Authorized source: user-supplied RomLogger Phase II standalone instructions dated 2026-10-19. Read AGENTS, documentation index, current progress/history/ADR, Phase I report/worklog, integration v2 contract, acceleration test entrypoints and Server requirements/design/wire/acceptance/progress. Older dated handoffs are historical.
- Actual host: Windows development environment; C:\Dev\RomLogger\RomRaider_agent and RomRaiderServer exist. Both clean main, fetched origin/main: Agent 2a741efd24825fdb453a33934b0f0169ae125337; Server 9274394ec5b093ecd71f44c7eaec146130bb1d32. Open PRs: zero in each. Git author is configured with GitHub noreply email; authenticated GitHub CLI available.
- PowerShell, Git/Bash, gh, Python3.12, SSH, Zulu17 x64 and Zulu8 x86 test paths available. hosts.env and safe password-auth SSH helper exist (contents not emitted); VPS/Docker reachability check pending. No Proxmox/RDP endpoint confirmed. This VM is separate from X270.
- Latest Agent jobs inspected: failure with steps=[]; CI NOT RUN (runner not started). Server latest failure likewise requires annotation inspection. No billing edits or repeat dispatch.
- Gates: synthetic isolated tests only; no ECU/X270/OpenPort, production DB migration, auto-worker installation, formal ACK, original deletion, service restart or history rewrite. Windows directory durability UNKNOWN; physical power loss/real ENOSPC NOT RUN.
- Planned small phases: II-0 bootstrap; II-1 versioned lane contract/RED; II-2 common ownership; II-3 opt-in product integration; II-4 publication guard; II-5 synthetic migration preflight; II-6/7 faults/platform/HTTP/load; report and resume evidence.
- First baseline: pwsh -NoProfile -File scripts/run-acceleration-tests.ps1. Focused scripts will be recorded with RED/GREEN before full matrix. Remote tests use only fresh isolated directories and synthetic inputs; existing Docker services remain untouched.

## 2026-10-19 09:36 +09:00 — II-0 verification
VPS Linux17 reached through pinned helper; Docker14 containers Up. Bootstrap links checked, management endpoints explicitly unconfirmed. Documentation-only diff reviewed; no code/runtime/DB changes. Checkpoint4eafb4a pushed before implementation.

## 2026-10-19 09:49 +09:00 — II-1/2 RED and lock implementation
RED d32c346 test initially lacked the reflected Windows fixture in its focused classpath (harness failure, not RED). Recompiled pre-fix Store into build/phase2-red and reran with existing native fixtures: phase2-conflict-red.log records AssertionError: RLC2 committed while legacy Worker owns its compatibility lock. Fixed Store refuses, then imports3 records after release. New RLLO1 ownership test covers same-JVM and child-JVM exclusion, separate sessions, generation/owner changes, forced process termination, stale tokens and unknown-format preservation. Windows fileKey is null; cooperative creation-time/evidence fallback documented. Actual x86 immediate post-kill retry observed BUSY: test now retries boundedly until the kernel really grants the lock, never treats elapsed time as ownership. No product timeout/takeover added.

## 2026-10-19 10:03 +09:00 — II-3 product candidate integration
Added opt-in constructor/Worker synthetic root, TSV/Journal per-session scopes, DurableSpool write/precommit checks and cooperative RLC2 API with explicit quiescence. Windows x64 focused ownership17/product7/cross-engine scenarios PASS; actual x86 ownership17 PASS. Existing product regression running separately. New default remains disabled; no DB schema, record ID or ACK changes. PR61 contains contract/core; dependent product integration branch retains ongoing work.

## 2026-10-19 09:42 +09:00 — II-4 publication candidate and timestamp correction
The preceding entries labelled09:49 and10:03 were entered with incorrect wall-clock labels; they describe work actually completed before this09:42 checkpoint. Use commit timestamps as authoritative evidence. Cooperative publication6 focused checks PASS: held Writer blocks Reader without writes; Reader blocks Writer before commit; published seal/rotation/Ledger update then extends the run by one admitted Source. No noncooperative/live claim. Server isolated migration upgrade twice/check and pytest57 PASS (server-pytest-20261019-094046.log). Existing Windows full matrix running; final source will be recompiled after remaining changes.

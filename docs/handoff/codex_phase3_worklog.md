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

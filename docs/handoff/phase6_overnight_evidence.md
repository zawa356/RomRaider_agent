# Phase VI validation evidence

Logs are ignored local artifacts; no raw payloads, credentials or test databases are committed. Source heads, platform, exits and SHA256 are appended after execution. Failed harness attempts are not PASS evidence.

## Initial RED and harness corrections

- Exact Phase V runtime plus test-only Phase6WriterTest: Windows17x64 `scripts/test-phase6-writer.sh`, log `build/phase6-writer-red.log`, exit1. Actual child PID2460 holds BEGIN IMMEDIATE without journal; classification still matches content but has no explicit writer_quiescence axis. This RED demonstrates a missing diagnostic, not production authorization.
- Phase V staging/snapshot runtime plus diagnostic delta: `scripts/test-phase6-writer.sh --compile --late-sidecar`, log `build/phase6-late-sidecar-red.log`, exit1. Actual PID8328 creates rollback journal after classification with empty originals; completion was accepted. Child rolled back normally; main v1 hash unchanged. This is the reproduced staging defect.
- First matrix harness attempts failed on test assumptions: switching journal mode was attributed to reader writes; normalize fresh fixture mode before hashing. WAL commit was incorrectly expected to preserve content; correct expectation is body-correspondence refusal after both journal and WAL commits. These runs remain FAIL, not final coverage.

## Persistent gates

Windows directory durability / running-process provenance UNKNOWN. Noncooperative writer absence WRITER_UNPROVEN; readiness BLOCKED. Real SMB, privileged Windows links, physical power loss, real ENOSPC, ECU/OpenPort/X270, production DB/Worker, production migration and new formal ACK NOT RUN/disabled. Root test.sh requires absent RomRaider.jar and is NOT RUN.

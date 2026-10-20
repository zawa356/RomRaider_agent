# Phase VII synthetic-owned offline candidate runbook (isolated test only)

Scope: a development VM or Linux test host, invocation-owned synthetic data only. Never point any path at the real-car X270 deployment, an existing v1 SQLite DB, RomRaider, OpenPort or a production Server. Nothing here enables formal ACK, the product Sender, Worker auto-start or production migration.

## One command

```bash
# Windows Git Bash (JDK17 x64 on PATH) or Linux (umask 077). Builds JARs, runs real supervisor/child JVMs.
bash scripts/test-phase7-e2e.sh
# Actual Zulu8 x86 against the same compiled JARs:
PATH="/c/Program Files (x86)/Zulu/zulu-8/bin:$PATH" ROMLOGGER_EXPECT_DATA_MODEL=32 bash scripts/test-phase7-e2e.sh --run-only
```

Optional real HTTP step (explicit opt-in; owns a fresh PG16 cluster under `build/`, never stops the shared test cluster):

```bash
ROMLOGGER_OWNED_PG_HTTP_ENABLE=1 ROMLOGGER_HTTP_PYTHON=/c/Dev/tools/venv-server/Scripts/python.exe \
ROMLOGGER_OWNED_PG_BIN='C:\Dev\tools\pgsql16\bin' ROMLOGGER_SERVER_PATH='<detached Server checkout>' \
bash scripts/test-phase7-e2e.sh
```

Large session: `ROMLOGGER_PHASE7_LOAD_OBSERVATIONS=10000`.

## What the flow does

1. Generates an owned deployment (RLC2 session with two physical sources, ledger, empty v1 that does not claim the session, empty RLI2 output, RLDB1 generation 0) plus RLSO1 marker and RLOP1 profile pinning the built JARs and the current JVM.
2. `--diagnose` (read-only, BLOCKED) → `--synthetic-owned` stores 3 records / 4 receipts → restart with the same input adds 0.
3. Legitimate rotation: the generation-0 pin refuses; an RLDT1 plan/commit admits only the new Source (generation 1); a new profile pinned to generation 1 stores exactly 1 record / 1 receipt (total 3 sources / 5 receipts / 4 records).
4. A fresh deployment's child JVM is force-killed by its self-reported PID; the result must be atomic (0 or all records); an explicit retry converges.
5. Final diagnose and RLIR1 readiness; `E2E_SUMMARY` prints counts and record/receipt ID set hashes; the HTTP harness receives the candidate path.

## Reading the result

`STORED_SYNTHETIC_OWNED` and `content_axes_complete` are local synthetic facts. `launch_authorized`, `production_authorized`, `ack_eligible` are always false; `writer_quiescence=WRITER_UNPROVEN`; Windows process provenance and directory durability UNKNOWN. A Server test ACK in the HTTP step is never an Agent ACK. Refusal codes are listed in [the entry contract](../interfaces/offline_candidate_entry_v1.md) and [RLIR1](../interfaces/integrated_readiness_v1.md).

## If something refuses

Do not delete, repair or adopt partial files. Keep `owned-runs/`, `*.started`, logs and the candidate DB for review. Re-run only with a new owned deployment or after the cooperative holder named by the code has stopped.

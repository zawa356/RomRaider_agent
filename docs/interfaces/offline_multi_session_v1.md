# Offline multi-session through the entry v1 (RLMS1) — Phase VIII-2

Synthetic acceptance contract for running **many small logical Sessions** through the single
`OfflineCandidateEntry`. Nothing here enables production, launch, a Sender or formal ACK.

## Why a contract is needed

Phase VII proved one logical Session (several physical Sources + rotation) per deployment. RLDB1 binds exactly one
Session per owned root (`deployment.rldb1` is CREATE_NEW and names one device/vehicle/payload/manifest session), and an
RLOP1 profile pins exactly one layout and generation. The Phase VI 11-session load ran at the store API, not through
the entry, and is not reused as evidence here.

## Contract

| Item | Rule |
|---|---|
| Session unit | one owned root (RLSO1 marker) + one RLDB1 binding + one RLOP1 profile + one expected generation + one candidate output |
| Ownership | every root, data root and output belongs to exactly one Session; profiles are not shared or re-targeted (any edited field fails the profile check) |
| Generation | per Session; an RLDT1 transition of one Session never changes another's generation |
| Admission | `SOURCES_CSV` must equal that Session's projection exactly; another Session's Source, manifest or a union is refused **before any child JVM** |
| Overlap | binding a second Session into an existing owned root, or creating an ownership marker for it there, is refused |
| Concurrency | different Sessions use different roots and lock scopes and may run concurrently; the same Session is serialized by its Lane/Publication locks (Phase VII) |
| Restart | re-running a stored Session with identical input adds 0 records/receipts |
| Flags | every result: `state` not authorizing, `launch_authorized=false`, `production_authorized=false`, `ack_eligible=false`, `writer_quiescence=WRITER_UNPROVEN`; every candidate record `ack_eligible=0` |

## Execution flow (synthetic)

1. For each Session: create owned root → bind (RLDB1) → ownership marker (RLSO1) → pin RLOP1 profile (same entry JARs).
2. Optional: `--inventory` of the install directory (RLDI1) and `--diagnose`.
3. `--synthetic-owned PROFILE MANIFEST_SHA256 SOURCES_CSV` → supervisor + one child JVM per Session.
4. Restart = the same command again; expected 0 added.
5. `--readiness` per Session for the RLIR1 report.

## Not covered (explicit)

- Aggregating several Session outputs into one candidate store, or one root holding several Sessions: needs a new
  binding version (RLDB1 is single-Session). NOT_RUN.
- Device/vehicle claim conflicts **inside one shared store** are therefore not reachable through the entry; the
  store-level claim rules (Phase V/VI) still apply and are unchanged.
- Noncooperative writers (WRITER_UNPROVEN), Windows process provenance and directory durability: unchanged UNKNOWN.

Test: `scripts/test-phase8-multisession.sh` (`ROMLOGGER_PHASE8_SESSIONS`, default and minimum 10).

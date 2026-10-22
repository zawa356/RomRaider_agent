# Phase X-A report — Windows CI stability (DRIVE_TYPE_UNPROVEN) and RLDI1 ZIP/JAR audit (Claude Code, 2026-10-22)

Read with: [evidence](phase_xa_ci_stability_evidence.md) · [append-only worklog](phase_xa_ci_stability_worklog.md) ·
[public CI recovery (2026-10-21, with addendum)](public_ci_recovery_2026-10-21.md) · [RLDI1](../interfaces/distribution_inventory_v1.md).

## Where

Draft PR [#104](https://github.com/zawa356/RomRaider_agent/pull/104) `ci/phase-xa-windows-probe` → base
`fix/rldi1-no-temp-write` (#103, `32b139f`). Commits: `d2bc329` measurement/negative tests · `4680f69` ZIP audit RED ·
`c49b8ac` ZIP fix GREEN · `86c680b` Windows fix · final docs commit (evidence file names its SHA). Agent `main` `4f80c5e`
and Server `main` `d8c7a2f` unchanged. **No merge, no Ready-for-review, no rebase/force-push.**

## X-A-1 Windows `DRIVE_TYPE_UNPROVEN` — root cause and fix

**Classification (real logs):** all three Windows failures at `32b139f` were the `WindowsDriveGate` **probe timeout**
(`reply(…,10000,…)`), not fsutil (separate 5 s bound / stack), not a nonzero exit, not a refusal reply: last PASS → error
gaps 10.02 s (ingress `DeploymentBindingTest`), 11.2 s (`Phase7EntryTest`, JDK17), 11.4 s (`Phase7ReviewC2Test`, Zulu8 x86),
each the first probe in a fresh JVM, all at 09:00 UTC.

**Measurement:**

| Where | First (cold) probe | Later probes |
|---|---|---|
| GitHub windows-latest, JDK17 x64 (`d2bc329`, before fix) | 3.3 s, 8.2 s, **9.7 s** | 0.32–0.50 s |
| GitHub, x86 JVM via WOW64 32-bit PowerShell (`86c680b`, measured side by side) | **28.7 s**, 14.4 s, 6.4 s, 0.9 s | — |
| GitHub, x86 JVM via Sysnative 64-bit PowerShell (`86c680b`) | 0.26–0.38 s (after suites) | 0.25–0.39 s |
| Dev VM JDK17 x64 | 2.5–3.9 s | 2.4–3.1 s |
| Dev VM Zulu8 x86, WOW64 → Sysnative | 5.2–7.2 s → 2.5–3.0 s | same reply |

Cause: cold start of PowerShell/.NET plus `Add-Type` (csc compile) on a fresh runner — far worse in the 32-bit (WOW64)
PowerShell that a 32-bit JVM gets through `System32` redirection — against a 10 s bound. Not a hang, not a wrong answer.

**Fix (`86c680b`), nothing weakened:** (1) `PROBE_TIMEOUT_MILLIS` 30 s (≈3× the worst x64 cold start; only the wait bound —
replies are checked exactly as before, a timeout is still `DRIVE_TYPE_UNPROVEN`); (2) from a 32-bit JVM the same fixed
program runs in 64-bit PowerShell via `%SystemRoot%\Sysnative` (exists only for WOW64 processes), test-asserted to give the
same reply; (3) internal `ProbeFailure` kinds (TIMEOUT/EXIT_n/OUTPUT_BOUND/START/STDIN/INTERRUPTED + ms) for diagnostics,
never a result. Unchanged: `GetDriveType`/`QueryDosDevice` twice with consistency, `3|LOCAL` only, subst/alias/remote/
removable/unknown refused, no cache, no `RUNNER_OS` bypass, `TMP=RUNNER_TEMP` kept. A Reflection.Emit variant was tried and
rejected (not equivalent). CI diagnostics run **after** the suites so the suites meet the cold probe themselves.
Residual: a cold start beyond 30 s would still fail closed (`DRIVE_TYPE_UNPROVEN`) — correct behaviour, now observable by
kind and ms.

## X-A-2 RLDI1 ZIP/JAR audit

Independent review of `ZipEntryView` → byte-level fixtures (`PhaseXaZipAuditTest`). On the unfixed parser **15 malformed
archives were accepted** (RED, `4680f69`); after `c49b8ac` all are refused (GREEN) on Linux, Windows x64 and Zulu8 x86:

| Case | Before | After |
|---|---|---|
| local vs central method / flags / CRC / sizes (no descriptor); descriptor with contradicting local CRC | accepted | `JAR_UNREADABLE` |
| DEFLATE longer than declared (truncated view), unterminated (sync-flush only), trailing garbage, non-empty 0-byte | accepted | refused (stream must end exactly) |
| flags 0x40 strong encryption, 0x2000 masked headers | accepted | refused (0x1 already was) |
| non-ASCII name without UTF-8 flag; invalid UTF-8 with flag | accepted | refused (JVM decodes UTF-8) |
| overlapping entry spans (entry inside another's data) | accepted | refused |
| lowercase `meta-inf/manifest.mf` decoy before the real manifest (JDK honours real `Main-Class`) | **RLDI1 MATCHED** | `JAR_MANIFEST_AMBIGUOUS` + `UNAPPROVED_MANIFEST_MAIN` |

Kept (regression): valid STORED/DEFLATED/data-descriptor, valid 0-byte DEFLATE, valid UTF-8 name, real sqlite-jdbc /
slf4j / JNA and a JDK-made JAR read identically to `java.util.jar` (names and bytes), EOCD disk/ZIP64/offset/trailing-byte
refusals, huge declared size bounded, multi-release class finding, A-B-A, no temp write (Phase9InventoryNoWriteTest),
Phase VIII inventory. Not verified / not changed: ZIP64 archives (refused, unchanged), entry-count bound (enforced in
`scanJar` as before; parser itself reads 50 001 tiny entries), JDK behaviour on every malformed case (compared only for the
builder sanity and the manifest decoy). No RLDI2 relaxation: F-IX-1 unchanged.

## X-A-3 CI gate

See evidence for every run. Code head `86c680b`: Ingress, Spool, Phase VII–IX success (all jobs, real steps, x86 jobs incl.);
real-Server E2E **failed on infrastructure** (`pip` IncompleteRead while downloading Server deps) — not counted, re-run
on the final head. Final-head results and repeat runs: evidence file.

## Gates unchanged / owner decisions still open

synthetic_only, launch/production/ACK disabled, `formal_ack=DISABLED`, WRITER_UNPROVEN, `global_old_binary_exclusion=
NOT_PROVEN`, Windows ACL UNKNOWN. Open: RLDI2/F-IX-1 (third-party dependency mains), JNA licence, `FILE_SHARE_DELETE`,
Windows ACL acceptance, and the dev VM's full C: drive / ~9 GB of old `%TEMP%\romlogger-*` fixtures (owner cleanup).

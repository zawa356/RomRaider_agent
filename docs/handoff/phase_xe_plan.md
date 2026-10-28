# Phase X-E plan — Windows CI stability and failure diagnostics (Claude Code, 2026-10-28)

Read with: [append-only worklog](phase_xe_worklog.md) · [evidence](phase_xe_evidence.md) ·
[X-A report (C4 history)](phase_xa_ci_stability_report.md).

## Scope

Independent, CI/diagnostic-only improvement on a fresh branch `phase-xe/ci-diagnostics` from Agent `main` `cfa7543`
(X-D merge). Draft PR only: no push to `main`, no Ready-for-review, no merge, no release/tag/deploy. Server unchanged.

| Item | Observation (input) | Goal |
|---|---|---|
| X-E0 | X-D merged (#107 `1fd3816`, #108 `cfa7543`, Server #67 `139da11`) | Re-verify `main`, PRs and every run/attempt/job |
| X-E1 | ingress run 40429873076 attempt 1, Zulu8 x86 job 121944670338: `V1LaneMigrationTest:26` "injection actually reached prepared migration commit" failed, cause UNKNOWN | Make a prior exception impossible to hide (RED→GREEN, test-only) and classify the CI failure |
| X-E2 | C4: phase8 Windows `DRIVE_TYPE_UNPROVEN` at `Phase8ReadinessCliTest:90`, runs 40300936878 / 40429872972 attempt 1 | Measure, separate test / implementation / runner causes; no timeout extension, retry, relaxation or fallback |
| X-E3 | — | Windows JDK17 x64 + actual Zulu8 x86 + Linux JDK17 regression, CI record of every run/attempt/job, docs |

## Design decisions

1. **X-E1 test-only.** `V1LaneMigrationTest.rejectAtInjection` accepts only the *same instance* the hook threw, and only
   after the hook ran. Anything else fails with `[PRIOR_EXCEPTION|NO_EXCEPTION type= code= at= hook_reached= elapsed_ms=]`.
   `code` is restricted to stable codes (`Refusal` message, fixed-text `IOException` matching `[A-Za-z_ ;-]{1,96}`,
   SQLite error code); the message and cause of any other exception are never printed (they may hold paths). The other
   `reject()` uses (in this test and others) are unchanged. No product change: no product defect was reproduced.
2. **X-E2 observability only.** `WindowsDriveGate.requireFixed` keeps the refusal exactly (`DRIVE_TYPE_UNPROVEN`), and
   now chains the gate's own `ProbeFailure` as cause; `ProbeFailure.toString()` gives `probe=<kind> ms=<n>`, its
   `getMessage()` stays the stable code. A foreign probe `IOException` (arbitrary text) is not chained. Unchanged: 30 s
   bound, no retry, no cache, `3|LOCAL` only, Sysnative for WOW64, no RUNNER_OS bypass.
3. Classification vocabulary: ROOT_CAUSE_PROVEN / HYPOTHESIS / UNKNOWN; reproduction: REPRODUCED / NOT_REPRODUCED.

## Out of scope / unchanged gates

Windows 10 non-car VM (not documented as reachable: NOT_RUN), real Writer, X270/ECU, JNA redistribution, Windows ACL
effective rights, FILE_SHARE_DELETE, native OS minimum: separate open gates, no new owner decision. `synthetic_only=true`,
`state=BLOCKED`, `launch_authorized=false`, `production_authorized=false`, `ack_eligible=false`, `formal_ack=DISABLED`,
`reader_adoption=BLOCKED`, `global_old_binary_exclusion=NOT_PROVEN`, `WRITER_UNPROVEN`; RLDR1/RLDI2 default-off.

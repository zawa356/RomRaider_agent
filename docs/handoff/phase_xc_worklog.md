# Phase X-C worklog (append-only) — Claude Code, 2026-10-25 JST

Rules: append only; never rewrite earlier lines. Times are JST. Each entry names the branch/commit it refers to.

## 2026-10-25 21:50 — X-C0 preflight (read-only)

- Host: Windows 11 dev VM (Claude desktop app, Code tab). The usual `C:\Dev\RomLogger\*` checkouts were **not** used: this
  session cloned both repositories fresh into a session-private folder (`git -c core.longpaths=true clone`; the first plain
  clone failed with "Filename too long" for deep `src/` paths under the long session path — no repository content involved,
  re-cloned with the per-repository long-path setting; no system setting changed).
- Verified `main`: Agent `7fdb4db893828718621ce8965664a89e36b9281a`, Server `3f16a701fb6fe1b0f94780f8dfef1ec38515dd84` —
  equal to the instruction snapshot. Agent PR #81/#92/#104 CLOSED (draft, unmerged, branches kept); #105 MERGED
  2026-10-22T13:47:37Z. Server #62 and #66 MERGED 2026-10-24T11:57Z, #65 CLOSED. No open Agent PR. Post-merge Agent `main`
  CI runs 39790019299 / 39790019276 / 39790019559 / 39790019494 all `success`.
- Tools: JDK17 x64 `C:\Program Files\Zulu\zulu-17`, actual Zulu 8 x86 `C:\Program Files (x86)\Zulu\zulu-8` (first on PATH;
  build steps prepend JDK17 explicitly). `gh` authenticated as zawa356. C: 63 GB free. `%TEMP%\romlogger-*` of unknown
  owners left untouched.
- Dependency JARs fetched through the existing pinned paths: `scripts/test-product-v1-spool.sh --deps-only`
  (sqlite-jdbc 3.46.1.3, slf4j-api/nop 2.0.16: `sha256sum -c` OK) and JNA 5.14.0 into `build/identity-deps`
  (`34ed1e1f…fe8c6` OK). None is tracked.

### Inconsistencies found (X-C0)

1. Agent `AGENTS.md` top entry still said "Draft PR #104 … unmerged; no merge"; bootstrap top said "Top of the Agent stack:
   Draft PR #104"; `docs/00_index.md` top was Phase VI. A fresh session could branch from an old stack branch.
   → fixed by prepending a 2026-10-25 current-state block to all three (history kept verbatim).
2. Server `docs/implementation_progress.md` last section (2026-10-22 追記) says "本 Server PR（#66）は owner の再レビュー待ちで、
   未マージ" and "#81〜#104 は Draft・open のまま". Both became stale on 2026-10-24 (#66 merged; old Agent PRs closed). Server
   is out of scope for code/doc changes in X-C → recorded here and in the X-C report only (owner/next phase).
3. RLDI1 F-IX-1 text (distribution_inventory_v1.md, phase9 report) attributes `UNAPPROVED_MANIFEST_MAIN` to sqlite-jdbc.
   Measured on the pinned JARs: sqlite-jdbc's manifest has **no** `Main-Class`; the manifest main is JNA's
   `Main-Class: com.sun.jna.Native`. sqlite-jdbc contributes the class main `org.sqlite.util.OSInfo` and 4 multi-release
   classes; slf4j-api and slf4j-nop each contribute `META-INF/versions/9/module-info.class`. History not edited; the
   correction is recorded in the RLDI2 design and an X-C note appended to RLDI1.

Branch `phase-xc/rldi2-candidate` created from `7fdb4db`.

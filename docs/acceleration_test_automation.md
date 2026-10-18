# Reproducible acceleration tests (Phase F)

## Windows development VM: one command

```powershell
pwsh -NoProfile -File scripts/run-acceleration-tests.ps1
```

Defaults match the prepared VM's Zulu17 x64, Zulu8 x86 and Git Bash locations. Override
`-Jdk17`, `-Java8x86`, `-BashPath` or `-Python` for another development VM. The script validates
tools, compiles Java8-compatible bytecode with JDK17, asserts actual 64-bit baseline and 32-bit
Zulu8 execution, then runs native identity, RLC2/ledger/rotation, diagnostics/projection,
preview persistence/faults and the existing product/SQLite regression. It restores the calling
process's Java/test environment and writes an ignored timestamped `build/acceleration-*.log`.

For the already isolated local PostgreSQL test cluster, supply its DSN through a protected
process environment (never command arguments or console output), then use:

```powershell
pwsh -NoProfile -File scripts/run-acceleration-tests.ps1 -Postgres `
  -Python C:\Dev\tools\venv-server\Scripts\python.exe `
  -ServerPath C:\Dev\RomLogger\RomRaiderServer
```

`-Postgres` explicitly enables only the guarded preview test. The Python DSN guard still
rejects remote/production databases, encoded names and connection overrides before opening
anything. `-ServerPath` also requires validation by actual Server models, without HTTP. With
no PostgreSQL switch, the script clearly records NOT RUN and never connects to a database.
Linux uses the same Bash test entry points directly; the PowerShell wrapper is Windows-only.

## Artifact and dependency identity

`lib/test-dependencies.sha256` fixes SQLite JDBC3.46.1.3 and SLF4J2.0.16 bytes. Both existing
fetch/test scripts verify identity before using cached or atomically publishing downloaded
jars. A structurally valid wrong JAR is not accepted. `--deps-only` prepares verified test
dependencies without running unrelated tests. JNA5.14.0 keeps its existing separate hash
and remains test-only. Jars/classes/native test dependencies are not committed.

The PostgreSQL test driver's `scripts/requirements-preview-cp312.lock` pins psycopg3.3.6,
binary3.3.6, typing-extensions4.16.0 and tzdata2026.5, with wheel SHA256 for CPython3.12
Windows x64 and manylinux2014 x64. Install into a separate test environment:

```bash
python -m pip install --require-hashes --only-binary=:all: -r scripts/requirements-preview-cp312.lock
```

Other Python/CPU combinations need a separately reviewed lock; source builds or unpinned
transitive packages are not silently allowed. A fresh Windows environment installed this
lock successfully; an intentionally wrong wheel hash was rejected before installation.
A valid SLF4J jar deliberately placed at the SQLite cache path was rejected and replaced
only by the correctly hashed SQLite jar in an isolated scratch fixture.

The new PostgreSQL CI service uses a manifest digest observed from its passing job, fixing
the previously floating postgres:16 image. CI caches follow the Java hash manifest. PR/push
runs share branch concurrency so superseded duplicate runs are cancelled, while main still
executes its complete regression. The actual x86 CI job runs the PowerShell wrapper, not
just a syntax check. Linux/Windows Bash jobs and the private PostgreSQL schema job remain.

CI rejects tracked generated jars/classes/Python bytecode/build output and test-created
tracked-source changes. Lock/hash/PowerShell files have explicit LF checkout rules. Existing
private-Server HTTP E2E is still separately gated; its SKIPPED step is not called a PASS.

## Windows concurrent replacement: safety and progress are separate observations

The complete batch exposed an intermittent existing test failure: MoveFileExW refused a
replacement while another JVM was reading. The retained fixture had a valid old marker,
fully valid next-generation tmp and exactly preserved writer bytes. Local JDK sources confirm
NIO's default delete sharing; the particular transient/external handle causing denial was not
identified. Product publication remains fail-closed, with no retry/cleanup change.

The test now requires either all 25 publications and the quiescent reader's final generation26,
or exact old-marker/expected-staged/original-byte preservation plus reader refusal. The latter
explicitly logs full concurrent publish progress BLOCKED; it is not called a completed
25-generation run. A separate deterministic held-marker/reader scenario always exercises
retained pending-state refusal after both JVMs stop. Native-only builds explicitly compile the
shared scenario source so a stale class cannot hide a newly added assertion.

## Unchanged verification limits

The batch does not exercise ECU/X270, physical power loss, actual ENOSPC, production DBs,
or existing Docker services. Windows directory durability remains UNKNOWN. All new preview
storage stays ACK-ineligible; success of these development tests does not enable sending.

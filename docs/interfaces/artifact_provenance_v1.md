# RLAP1 artifact / process provenance (Phase IV, read-only)

`net.neristal.rrbridge.product.v1.ArtifactProvenance`. RLAP1 is a diagnostic that only reads files. It never stops, restarts or attaches to a process. It never claims code signing, distribution authenticity or administrator-level proof. All JSON reports `production_authorized=false`, `ack_eligible=false` and `signature_verified=false`, and `inspection_success` is reported separately from `process_provenance`.

## Classpath description

`describe(orderedJars, mainClass)` builds an ordered record: `version=RLAP1`, `main_class`, `java_specification`, `data_model`, and for each `entry.NN` its `name` (file name only), `sha256`, `size`, class count and the SHA-256 of the sorted class names.

It refuses with these codes:

- `WILDCARD_CLASSPATH`
- `CLASSPATH_NOT_ABSOLUTE`
- `DIRECTORY_ENTRY_REFUSED`
- `CLASSPATH_ENTRY_MISSING`
- `CLASSPATH_LINK_REFUSED`
- `CLASSPATH_ENTRY_NOT_JAR`
- `CLASSPATH_DUPLICATE_ENTRY`
- `MANIFEST_CLASSPATH_REFUSED` (Class-Path / Launcher-Agent-Class / Boot-Class-Path)
- `JAR_DUPLICATE_ENTRY`
- `DUPLICATE_CLASS` (any class name provided by two entries, which is shadowing)
- `MAIN_CLASS_MISSING`

`compare(expected, observed)` reports the first difference as a code:

- `MAIN_CLASS_MISMATCH`
- `JAVA_VERSION_MISMATCH`
- `DATA_MODEL_MISMATCH`
- `CLASSPATH_ENTRY_COUNT_CHANGED` (a missing dependency or an added JAR)
- `CLASSPATH_ORDER_CHANGED` (same JARs, different order)
- `CLASSPATH_ENTRY_CHANGED`
- `PROVENANCE_FIELD_CHANGED`

## Process evidence levels

| Level | What it shows | What it does not show |
|---|---|---|
| Self report (`selfReport`) | What a JVM says about its classpath, Java runtime, PID and a SHA-256 of its JVM options (options are never printed because they can contain secrets) | Anything about authenticity. Any process can write the same claim |
| Verifier-launched child | The verifier started the exact command itself and checks the child's self report against RLAP1 (used for synthetic tests) | That an independently started Worker is the same |
| Linux same-user `/proc` (`inspectProcess`) | `/proc/PID/cmdline` names exactly the expected `-cp` and main class (`-jar` and `@argfiles` are refused). Every expected JAR is open in the process, and its bytes, read through `/proc/PID/fd`, match RLAP1. A JAR replaced on disk after start shows as `(deleted)` and refuses with `ARTIFACT_REPLACED_AFTER_START`. The on-disk classpath must still match too | Classes loaded from a JAR not yet opened (`ARTIFACT_NOT_OPEN_YET` stays UNKNOWN), the target JVM's own runtime version (the expectation names the verifier's runtime), root-owned processes, containers in other namespaces |
| Windows | `UNKNOWN_PROCESS_PROVENANCE` / `PROCESS_INSPECTION_UNAVAILABLE_ON_THIS_OS`. No OS-wide enumeration is attempted | everything about running processes |

A PID alone, a file name alone, or the presence of an environment variable is never accepted as evidence. A match is reported as `MATCHED_OPEN_ARTIFACTS_LINUX_SAME_USER`. It is an inspection result only, not a production authorization.

## CLI (read-only)

```
java -cp <classes>:lib/* net.neristal.rrbridge.product.v1.ArtifactProvenanceCli --describe MAIN_CLASS /abs/a.jar /abs/b.jar   # prints the RLAP1 record
java -cp <classes>:lib/* net.neristal.rrbridge.product.v1.ArtifactProvenanceCli --inspect-process PID RLAP1_FILE                 # JSON, exit0 inspected / 5 refused / 2 usage
```

The RLAP1 file must be private (the same access rule as RLDB1 records). The CLI does not print command lines or paths.

## Not covered

Code signing, a trusted build pipeline, the authenticity of the distribution channel, administrator-level process inspection, and Windows process inspection are all **NOT IMPLEMENTED / UNKNOWN**. `CutoverReadiness.knownArtifact` keeps comparing only the class bytes of the current build; it now also refuses manifest Class-Path/agent attributes (Phase IV F2).

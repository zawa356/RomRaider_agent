# Phase III validation evidence

最終コードhead `ed8efd6e6195418feffe77224ef61f2116a6db7b`。独立Windows worktreeはdetached clean。実装main `c206282119c424c3dd15c66b5cddfb5713d69fac` と src/scripts/.github 差分0。前段matrixは `2bef02e102664a44908a3662c388f1c959beda49`、その後の新sidecar修正は最終edで独立再試験した。

## 環境・コマンド

Windows11 Pro開発VM（X270ではない）、JDK17 x64 17.0.20.1 / actual Zulu8 x86 1.8.0_504、Java8 bytecode。Linux17 17.0.20.1、SQLite JDBC pinned lib/test-dependencies.sha256、PostgreSQL16.15。原本・失敗fixture・全ログはローカルignored領域に保持。表は実file SHA256でありログ自体はGitへ載せない。

Windows frozen checkout:

```powershell
Set-Location C:\Dev\RomLogger\phase3-release-ed8efd6
pwsh -NoProfile -File scripts/run-acceleration-tests.ps1
$env:ROMLOGGER_BOUND_LOAD_OBSERVATIONS='10000'
$loadCp='build/phase3;lib/*;build/identity-deps/jna-5.14.0.jar'
& 'C:\Program Files\Zulu\zulu-17\bin\java.exe' -cp $loadCp net.neristal.rrbridge.prototype.ingress.Rlc2BoundDeploymentTest
& 'C:\Program Files (x86)\Zulu\zulu-8\bin\java.exe' -cp $loadCp net.neristal.rrbridge.prototype.ingress.Rlc2BoundDeploymentTest
$env:ROMLOGGER_OWNED_PG_HTTP_ENABLE='1'
$env:ROMLOGGER_OWNED_PG_BIN='C:\Dev\tools\pgsql16\bin'
$env:ROMLOGGER_SERVER_PATH='C:\Dev\RomLogger\RomRaiderServer'
& C:\Dev\tools\venv-server\Scripts\python.exe scripts/test-rlc2-owned-postgres-http.py
```

各accepted commandのexit0を実行sessionから確認。大規模追加の最初の手動実行はJNAをclasspathから省きNoClassDefFoundError/exit1、ログを別名で保持。scriptどおりJNAを追加後、同じedのWindows64/実32で各10,003records PASS/exit0。コード変更なし。

VPS pin済helperを介し `/tmp/romlogger-phase3-ed8efd6` のexact git archiveとhash検証済JARだけで実行:

```bash
java -version
sha256sum -c lib/test-dependencies.sha256
bash scripts/test-product-v1-spool.sh
bash scripts/test-phase3-deployment.sh
bash scripts/test-phase2-lanes.sh
ROMLOGGER_BOUND_LOAD_OBSERVATIONS=10000 java -cp "build/phase3:lib/*" net.neristal.rrbridge.prototype.ingress.Rlc2BoundDeploymentTest
ROMLOGGER_LOAD_OBSERVATIONS=10000 ROMLOGGER_LOAD_SESSIONS=10 bash scripts/test-rlc2-preview.sh
```

全chain exit0。Windows binding18（actual junction）、Linux17、readiness11、backup/full rehearsal32、6 child-halt stages52、migration21、CLI2、sidecar alias/cleanup負試験を独立実行。Linux load59は11sessions/10,100records/10,111receipts/22admissionsの旧候補経路。bound load6は10,003records/単一固定logical Session。Linux metricsは単発観測で性能保証ではない。

Server `C:\Dev\tools\run-server-tests.ps1` は既存loopback55432の隔離test DBだけでupgrade二度/checkとpytest57/1warningを実施。DSNはprotected environmentだけ。Server runtime3091d61で実施し、その後Server変更は進捗文書だけ。Agent実HTTP35は別のinvocation-owned PGを作成しimmediate-stop/restart、最後にそのclusterだけ停止。テストACKがAgent ACK/bytesへ反映されないことを検査。

## REDの解釈・限界

UNBOUND_ROOT、REFERENCE_LEAK、hardlink alias、SOURCE_MUTATED、RECOVERY_ALIAS_MUTATEDは専用の新規合成fixtureで実際のAssertionErrorを記録した。初期wrapperはJVMの直後にGet-Contentを実行したためouter PowerShell exit0のものがあり、JVM exit値は独立に採取できていない。worklog初期記載のRED exit1はAssertionErrorによるJVM失敗を指すが、厳密な直接exit値証拠としては扱わない。最終GREEN/full suiteは直接session exit0を確認。source/other DBが変わったREDの失敗fixtureと元REDソースはignored indexで保持し、user/production DBには触れていない。

最初のstage試験はSQL COMMIT後WAL残存をclosed stateと期待して失敗。readonly statusをconservative interrupted reviewとして、explicit owned recovery/full verification後だけ完了と判定する。Runtime.haltは物理電源断ではない。

## CI / 未実行

Agent ed run39198221373 job117885507423はsteps0、account payments/spending limit annotation。CI NOT RUN。workflowへWindows/x86/Linuxの実行口を追加したがCI PASSとはしない。課金変更/redispatchなし。YAML parserローカル依存なしでNOT RUN、変更行手動レビュー、PowerShell AST/diff check実行。

root test.shはRomRaider.jar不在でNOT RUN。旧WindowsRLC1 full56 BLOCKED、privileged symlink fixtures SKIP/NOT RUN。batch optional PostgreSQL preview引数未使用（別PG/実HTTP試験は実施）。Windows directory durability UNKNOWN、物理電源断/実ENOSPC/実車/ECU NOT RUN。global fencing、Session/manifest rebind、RMI1→RLI2 record mapping、本番cutover/restore/正式ACKは未成立・無効。

## 実ログとSHA256

Final local health 2026-10-19T14:58:27+09:00: Java/javaw count0、既存PG55432 accepting connections。残るpostgres parent1360は既存cluster、owned HTTP clusterはfinallyで終了した。Server docs-only PR60 merge `bb27cd10ad60cda1a7f4434c3f803df6c4db2a39` at2026-10-19T14:58:23+09:00。Docker14 same IDs Up/VPS testJVM0。失敗fixtureとfixed checkoutは保持。

| ローカル実file | SHA256 | 実行head | 結果 |
|---|---|---|---|
| `C:/Dev/RomLogger/phase3-release-ed8efd6/build/phase3-final-ed8efd6-regression.log` | `69826cc2a250ec91d2882df39b07e4ca006451efd025ba2c05ceb192ce7c6a38` | ed8efd6 | PASS exit0; full batch outer log |
| `C:/Dev/RomLogger/phase3-release-ed8efd6/build/acceleration-20261019-130956.log` | `f67c119f2ff2df15f48aa7f21c5f407f270d84ebdb72e0363612372decdd7fda` | ed8efd6 | PASS exit0; full batch both Java detailed log |
| `C:/Dev/RomLogger/phase3-release-ed8efd6/build/phase3-final-ed8efd6-http.log` | `4c80823520139375efb0db58de42fd2ee042b7d4158b81b46ef55c9bfd8ec3ae` | ed8efd6 | PASS35 exit0; owned PG stopped |
| `C:/Dev/RomLogger/phase3-release-ed8efd6/build/phase3-final-ed8efd6-bound-large-x64.log` | `b24f684e3e11316cb0b4ede2e62b23535b11d78d5282cc5bfc1146a7e27464f6` | ed8efd6 | PASS6/10003 records exit0 actual64 |
| `C:/Dev/RomLogger/phase3-release-ed8efd6/build/phase3-final-ed8efd6-bound-large-x86.log` | `f4bddd689a11c7897e7cf1f988ef226a991a16303dbc3f3d6465093bf45e372a` | ed8efd6 | PASS6/10003 records exit0 actual32 |
| `C:/Dev/RomLogger/phase3-release-ed8efd6/build/phase3-final-ed8efd6-bound-large-x64-classpath-failure.log` | `34d4e5ee8acb32dafec3021917cf97c010890cb5b93ab0f352f2912d449874ed` | ed8efd6 | NOT PASS: omitted JNA classpath, exit1 |
| `C:/Dev/RomLogger/phase3-release-ed8efd6/build/phase3-final-ed8efd6-bound-large-x86-classpath-failure.log` | `c5bfebae1a14bde79e9613cd210dce88b7965e023f90a79dcdae2b1531e2e92e` | ed8efd6 | NOT PASS: omitted JNA classpath, exit1 |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-final-ed8efd6-linux.log` | `248ad76647704daef9c71954af5f522ef3a7a6f2da39a9f13a7bbb3c25f00d3d` | ed8efd6 | PASS exit0; Linux suites/bound10003/load59 |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-root-red.log` | `7d878c32cf265024dd014a77a5ac14acc432ef320d664c43c0e34930d46630bb` | development; see worklog | RED: UNBOUND_ROOT; lower-level gap retained |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-cleanup-red.log` | `29bfc5e6fd987ad7c63869551b807d4e4f91b28723d8dcdd18a2d881e31fb512` | development; see worklog | RED: REFERENCE_LEAK10 |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-cleanup-green.log` | `b0a0cfd3a608e13ff23733f467d6b0ca8829619f738abbcf57c628ac8ff55e35` | development; see worklog | GREEN: no new references; existing retained |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-hardlink-red.log` | `6bf793144f92b1eb6e7ef1049168b1bfd24f5a91316f8039e108cebad9f997db` | development; see worklog | RED: HARDLINK_ALIAS |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-hardlink-green-x64.log` | `0bd5d8090852e695551ec9ef19d4a8446294907239c21d30268f83fb868aa6f6` | development; see worklog | GREEN64: hardlink refused |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-hardlink-green-x86.log` | `0bd5d8090852e695551ec9ef19d4a8446294907239c21d30268f83fb868aa6f6` | development; see worklog | GREEN32: hardlink refused |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-migration-alias-red.log` | `8ff6796806284437e119837baeb54b8dbec7d78c2151a1a09bee9c4a4bb2375e` | development; see worklog | RED: SOURCE_MUTATED synthetic fixture |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-migration-alias-green-x64.log` | `93689865cf6ee2e3f11493f7e3d1959fac3ca6bae3fe19ec61a89f3189a6f75f` | development; see worklog | GREEN64: original preserved |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-migration-alias-green-x86.log` | `93689865cf6ee2e3f11493f7e3d1959fac3ca6bae3fe19ec61a89f3189a6f75f` | development; see worklog | GREEN32: original preserved |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-recovery-alias-red.log` | `7e4f8c5279b487e92669253977e2a19993ac6c201a4e43987d7bdad89f7288ad` | development; see worklog | RED: RECOVERY_ALIAS_MUTATED synthetic other DB |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-recovery-alias-green-x64.log` | `6f72613c4b6d2b74a86dc17b4eedbf5543f4d93e20f0ccd76f7ffff9b3588543` | development; see worklog | GREEN64: source and other DB preserved |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-recovery-alias-green-x86.log` | `6f72613c4b6d2b74a86dc17b4eedbf5543f4d93e20f0ccd76f7ffff9b3588543` | development; see worklog | GREEN32: source and other DB preserved |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-stages-x64-initial.log` | `c7226caeb539f00d9242b6a26b7a700f4800c70e2dd6d865b422fabfa64b92f3` | development; see worklog | NOT PASS: after-COMMIT WAL expectation corrected |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-final-docker-health-1457.log` | `1c5bbad3d8c93a1edefa5828e6761a49c116f346481411ed1a58a9b491e12a53` | development; see worklog | 14 existing container IDs Up |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-final-vps-test-processes-1457.log` | `13bf7b3039c63bf5a50491fa3cfd8eb4e699d1ba1436315aef9cbe5711530354` | development; see worklog | 0 VPS test JVM |
| `C:/Dev/RomLogger/RomRaider_agent/build/phase3-failed-fixtures-final.json` | `ce5195ef1a67a859325853825fa96e8609146d8463954aaa59e3b4866a133517` | development; see worklog | retained ignored fixture index, not uploaded |
| `C:/Dev/RomLogger/logs/server-migrate-20261019-122005.log` | `42419493a8b29a1104524db90bfbe2811cba1de9b5d10dfcc4d9b83a5a43ea24` | Server3091d61 runtime | PASS migration/check or pytest57 |
| `C:/Dev/RomLogger/logs/server-pytest-20261019-122005.log` | `6002ae1c87708a941443728440cf93ef25820a6284831890fa679dbbe27521aa` | Server3091d61 runtime | PASS migration/check or pytest57 |

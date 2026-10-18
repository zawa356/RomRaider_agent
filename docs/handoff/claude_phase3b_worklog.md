# Claude Phase 3b worklog（追記式）

Claude Code（Claude Desktop / Windows 11 Pro VM）による Phase 3b 以降の作業記録。
過去の Codex 日中記録は [codex_daytime_worklog.md](codex_daytime_worklog.md)、[codex_daytime_report.md](codex_daytime_report.md)。
終了報告は [claude_phase3b_report.md](claude_phase3b_report.md)。

## 2026-10-18 09:40 JST — START / Git checkpoint

- 状態: STARTED（コード編集前のチェックポイント）
- Agent origin/main `8a073ff8e3e51b6ff3f6b2850128b7a96a09ac43`（PR38 merged、main CI [39082551494](https://github.com/zawa356/RomRaider_agent/actions/runs/39082551494) 3jobs success）
- Server origin/main `9048fbe049307191d42c24b9b2024b2a98d826c8`（PoC checks success）
- open PR: Agent 0 / Server 0。両作業ツリーclean（Serverに既知の未追跡 `tests/e2e/agent_v1_snapshot/{build,lib}` のみ。コミットしない）
- 作者: `220358247+zawa356@users.noreply.github.com`
- 作業branch: `claude/phase3b-rlc2-watermark`

### 作業環境

- Windows 11 Pro 10.0.26200 x64（Proxmox QEMU VM）、ja-JP、NTFS、通常ユーザー（Medium integrity）
- JDK 17.0.20.1 x64（`C:\Program Files\Zulu\zulu-17`）でcompile `--release 8`
- Zulu 8 1.8.0_504 x86（`C:\Program Files (x86)\Zulu\zulu-8`、`sun.arch.data.model=32`）で実行試験
- Git Bash 5.3 / gh 2.102 / Python 3.12 / ローカルPostgreSQL 16.15（127.0.0.1:55432、試験専用）
- 開発者モード無効のためローカルWindowsではsymlink fixtureがSKIP（CIでは `ROMLOGGER_REQUIRE_SYMLINK_TESTS=1`）

### 開発目標（Phase 3b、独立PoCのみ）

1. 3b-1: 新marker契約 **RLC2**（version、identity tag、POSIX dev/ino と Windows NTFS volume/file index を区別、UUID/generation/sequence/offset/prefix SHA-256）。旧RLC1は明示拒否し、読み替えない。
2. 3b-2: 確定手順（journal write → sync/FlushFileBuffers → 原本ID・原文再検証 → marker tmp作成 → 同期 → 原子的置換 → directory durability判定 → 別プロセスreader）。各段階で故障注入・別JVM halt。
3. 3b-3: 負試験・互換性（置換/コピー/stale/巻戻り/CRC-valid改変/部分marker/競合/quota/NTFS rename+handle保持/Java17 x64・Zulu8 x86/Linux差異）。
- Windows directory durabilityは保証が確認できない限り **UNKNOWN** を維持。`Runtime.halt` を物理電源断と同等に扱わない。
- 製品writer/Worker/SQLite cursor/ACK/Server IFへは配線しない。

### テスト計画

- 既存回帰: `scripts/test-ingress-journal-prototype.sh`、`test-ingress-sqlite-receipts.sh`、`test-product-v1-spool.sh`、`test-windows-native-identity.sh`（JDK17 x64 + Zulu8 x86 `--run-only`）
- 新規: RLC2 codec/契約試験（OS非依存）、POSIX確定手順試験（Linux CI/VPS）、Windows native確定手順試験（JNA test-only、x64/x86）
- Linux: GitHub Actions ubuntu-latest、可能ならVPS専用作業ディレクトリ

### 次の実行コマンド

```bash
cd /c/Dev/RomLogger/RomRaider_agent
git fetch origin && git status -sb
JAVA_HOME="/c/Program Files/Zulu/zulu-17" PATH="/c/Program Files/Zulu/zulu-17/bin:$PATH" bash scripts/test-ingress-journal-prototype.sh
```

## 2026-10-18 10:40 JST — 環境: VPS / Dockerサーバー SSH（TOFU）

- 接続helper `C:\Dev\tools\romlogger_ssh.py`（paramiko 5.0.0、venv `C:\Dev\tools\venv-ssh`）。hosts.envはデータとして解析しパスワードはメモリのみ。pinは `C:\Dev\secrets\romlogger_known_hosts.json`（ACL: 本人/SYSTEM）。ed25519のみ交渉。
- **Docker**: 提示ed25519がユーザー提供の前回観測値と一致→TOFU登録→認証成功。Ubuntu 22.04.5 / 2 vCPU / RAM 3.8GiB（avail約2.3GiB）/ ディスク空き38GB。Docker 27.3.1・Compose v2.29.7、ユーザーはdocker+sudoグループ（sudoはパスワード要求）。Java/sqlite3/psqlなし。既存 `romraiderserver-*`（db/api/mcp/proxy/voice-demo）、immich、sluicery、portainer等が稼働 → **変更しない**。
- **VPS**: ホスト鍵は観測値と一致しTOFU登録。許可方式は publickey,password。**hosts.envの資格情報でpassword認証失敗（1回のみ試行、ロックアウト回避のため再試行なし）**。VPS = BLOCKED（資格情報確認待ち）。Linux検証はGitHub ActionsとDockerサーバー上の隔離コンテナで代替。
- 要改善: 両サーバーのSSHパスワードが短い（5/8文字）。鍵認証化またはパスワード強化を推奨（無断変更しない）。

## 2026-10-18 11:20 JST — Phase 3b-1/3b-2/3b-3 実装・Windowsローカル試験

- 状態: IMPLEMENTED（独立PoC、製品非接続）。設計 [phase_3b_rlc2_watermark.md](../phase_3b_rlc2_watermark.md)、ADR D-045。
- 変更: `WatermarkV2Prototype.java`（main/prototype）、test `WatermarkV2Scenarios` / `WatermarkV2ContractTest` / `PosixWatermarkV2Test` / `WindowsWatermarkV2Test`、両scriptへ追加。
- 途中の失敗と修正: 並行reader試験が `ok=0 closed=24` で失敗。理由集計で全件 fail-closed（staged残存/読取中変更）＝readerは安全側だが、publishが速く静止状態を観測しないまま停止していた。静止後の最終読取を必須にし、最終gen 26を検証する試験へ修正。
- Windows 11 VM（ja-JP/NTFS）結果:
  - JDK17 x64: `test-ingress-journal-prototype.sh` rc=0 PASS186 SKIP2（symlink、開発者モード無効）、Contract22、POSIX scenarios **NOT RUN**（unix viewなし、fail-closed 1 PASS）
  - JDK17 x64: `test-windows-native-identity.sh` rc=0 PASS179（RLC2 Windows 96）
  - Zulu8 1.8.0_504 x86（data-model32）: `--run-only` rc=0 PASS182（RLC2 Windows 97）
  - Windows directory: `FlushFileBuffers(directory)` は成功を返したが **UNKNOWN** と報告（保証の文書化なし）
- 次: commit/push → PR → Linux CI（POSIX scenarios実行）とWindows/x86 CI確認 → Docker隔離コンテナでLinux追加検証。

## 2026-10-18 11:55 JST — Phase 3b MERGED / main CI

- PR [#39](https://github.com/zawa356/RomRaider_agent/pull/39) MERGED → main `e413fca085d294fb231f89477059a51089f8f31d`。
- PR CI [39084461335](https://github.com/zawa356/RomRaider_agent/actions/runs/39084461335) 全3job success:
  - windows-latest(Server2025, Temurin17 x64): Contract22、RLC2 Windows **96**、POSIX NOT RUN
  - ubuntu-latest: Contract22、RLC2 POSIX **94**（directory=OS_REPORTED_SYNCED）
  - actual Zulu8 1.8.0_504 x86: data-model32、RLC2 Windows **97**
- main CI [39084674087](https://github.com/zawa356/RomRaider_agent/actions/runs/39084674087)（journal 3jobs）・[39084674145](https://github.com/zawa356/RomRaider_agent/actions/runs/39084674145)（spool durability 2jobs）success。
- Linux追加検証（Dockerサーバー隔離コンテナ）: `eclipse-temurin:17-jdk`、`--network none --memory 768m --cpus 1 --pids-limit 256`、名前 `rl-claude-phase3b-linux`（`--rm`）。journal script rc=0 PASS338、RLC2 POSIX 94。既存コンテナ・network・volumeは未変更。作業dir `~/rl-claude-phase3b`。
- Win32文書調査: `MoveFileExW` WRITE_THROUGHの明示保証はcopy+delete経路のみ、同一volume renameのdirectory entry/電源断は記載なし。`FlushFileBuffers`はdirectory handleを文書化していない。→ Windows directory durability **UNKNOWN維持**。

## 2026-10-18 12:10 JST — Phase 3c-1 START / 判断

- 3bのWindows directory durabilityはUNKNOWNで、durabilityゲートは**未成立**。したがって3c全体（製品への統合・ACK）はBLOCKEDのまま。
- ただし3bの反例（stale受理・偽造コピー受理）を閉じる**永続ledgerの独立PoC**は、製品非接続の合成試験として安全に進められる。そこで3c-1として実施し、ACKとの連動はしない。
- branch `claude/phase3c1-rlc2-ledger`（main `e413fca`起点）。

## 2026-10-18 12:40 JST — Phase 3c-1 実装・ローカル試験

- 変更: `WatermarkLedgerPrototype.java`、test `WatermarkLedgerScenarios` / `PosixWatermarkLedgerTest` / `WindowsWatermarkLedgerTest`、`scripts/test-watermark-ledger.sh`、CI（isolated-journalに3c-1 step追加、x86 jobではspool scriptでjar取得→ledger compile→Zulu8で`--run-only`）。
- 途中の修正: sqlite-jdbcの`setAutoCommit(false)`と`BEGIN IMMEDIATE`は二重開始になるため、autocommitのまま明示的にBEGIN IMMEDIATE/COMMIT/ROLLBACKする実装へ変更（コンパイル前に修正）。
- Windows 11 VM: JDK17 x64 ledger **27 PASS** rc=0、Zulu8 x86 **28 PASS** rc=0、POSIX ledger NOT RUN（unix viewなし）。拒否理由がすべて意図した原因（stale/UUID claim/identity/TSV lane/receipt改竄/世代退行）であることをログで確認。並行ingestは `a=added=3 b=added=0`。
- 次: commit/push → PR → CI（Linux POSIX ledger実行）→ merge → main CI。

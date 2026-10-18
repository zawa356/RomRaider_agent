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

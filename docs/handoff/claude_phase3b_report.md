# Claude Phase 3b〜3d-1 開発報告 — 2026-10-18

作業環境はWindows 11 Pro VM（Proxmox、ja-JP、NTFS）上のClaude Codeです。追記式の詳細は [claude_phase3b_worklog.md](claude_phase3b_worklog.md) にあります。

## 結論

3つの小Phaseを独立PoCとして実装・試験・通常mergeした。いずれも**製品writer・Logger・Worker・`v1_*`表・Server ACKへは配線していない**。
ライブジャーナルの完成や電源断耐性は主張しない。

| Phase | 内容 | PR / merge | CI結果（PR head） |
| --- | --- | --- | --- |
| 3b | RLC2 marker（version・identity tag付き、RLC1は読み替えない）、確定手順、7段階の故障注入・別JVM halt、負試験 | [#39](https://github.com/zawa356/RomRaider_agent/pull/39) → `e413fca` | [39084461335](https://github.com/zawa356/RomRaider_agent/actions/runs/39084461335): Win x64 96 / Linux POSIX 94 / Zulu8 x86 97 |
| 3c-1 | 永続expected identity・highwater・原文receiptを同一SQLite transactionで管理（3bの反例2件を解消） | [#40](https://github.com/zawa356/RomRaider_agent/pull/40) → `ae443cb` | [39085178875](https://github.com/zawa356/RomRaider_agent/actions/runs/39085178875): ledger 27 / 27 / 28 |
| 3d-1 | seal、RLM1 rotation manifest、16本quota、manifest順のsession ingest | [#41](https://github.com/zawa356/RomRaider_agent/pull/41) → `512c8b7` | [39085846825](https://github.com/zawa356/RomRaider_agent/actions/runs/39085846825): rotation 43 / 43 / 44 |
| 環境整備 | Windowsのlocale・symlinkに依存した試験の修正（Phase 0-B） | [#38](https://github.com/zawa356/RomRaider_agent/pull/38) → `8a073ff` | main [39082551494](https://github.com/zawa356/RomRaider_agent/actions/runs/39082551494) |
| Server | E2E生成物の.gitignore | [Server #48](https://github.com/zawa356/RomRaiderServer/pull/48) → `6d90eba` | PoC checks success |

各mergeの後、main CIはすべてsuccessを確認した:

- 3b: [39084674087](https://github.com/zawa356/RomRaider_agent/actions/runs/39084674087)
- 3c-1: [39085337766](https://github.com/zawa356/RomRaider_agent/actions/runs/39085337766)
- 3d-1: [39086064638](https://github.com/zawa356/RomRaider_agent/actions/runs/39086064638)
- 各回のspool CIもすべてsuccess

設計は [phase_3b_rlc2_watermark.md](../phase_3b_rlc2_watermark.md)、[phase_3c1_rlc2_ledger.md](../phase_3c1_rlc2_ledger.md)、[phase_3d1_rotation_manifest.md](../phase_3d1_rotation_manifest.md)、ADRは [D-045〜047](../08_decisions.md)。

## ローカル試験（最終main `512c8b7`、Windows 11 VM。GitHub Actionsとは別物）

| 環境 | 試験 | 結果 |
| --- | --- | --- |
| JDK17.0.20.1 x64 | journal / spool / SQLite receipt / native / ledger+rotation | rc=0。PASS 186 / 321 / 343 / 179 / 70 |
| Zulu8 1.8.0_504 x86（data-model32を確認） | native `--run-only` / ledger+rotation `--run-only` / journal系7クラス / spool12クラス+receipt | rc=0。PASS 182 / 72 / 185 / 343 |
| Server（venv、ローカルPostgreSQL 16.15、127.0.0.1:55432、UTC） | migrate upgrade×2・check、pytest全体（認証・ACK・lost ACK・重複・DB障害時ACKなし等） | **57 passed**、exit 0 |
| Linux（Dockerサーバー上の隔離コンテナ: `eclipse-temurin:17-jdk`、`--network none`、768MiB、1CPU、`--rm`） | journal一式 / ledger / ledger+rotation | rc=0。PASS 338（RLC2 POSIX 94を含む）/ 27 / 70 |

SKIP・NOT RUNの理由は既知の2つだけである。

- 開発者モードが無効なため、symlink fixtureがSKIPまたはNOT RUNになる（CIでは `ROMLOGGER_REQUIRE_SYMLINK_TESTS=1` で実行を強制）。
- WindowsにはPOSIXの `unix` viewがないため、POSIX系シナリオはNOT RUNになる（Linux CIとコンテナで実行済み）。

Phase 0-Bで、Agent→FastAPI→実PostgreSQLのE2Eはローカルで PASS 済み。今回はServerのコードを変更していないため、E2Eは再実行していない（NOT RUN）。

## 確定した事項と残る課題

成立したこと（合成試験の範囲）:

- **RLC2:**
  - tag付きidentityを持つ。tagが異なるidentityは比較できない。
  - RLC1は拒否してbytesを保持し、読み替えない。
  - writerの書込記録と完全一致したものだけを確定する。
  - 確定は「所有handleでsync → 同じhandleで再検証 → tmp → 原子的置換」の順で行う。
  - 各段階でIOExceptionを注入しても、別JVMを`Runtime.halt`させても、旧世代を保持するか、tmpを保持してreviewで停止する。
- **3c-1:**
  - 一度claimしたsourceについて、stale markerと偽造コピーを拒否する。
  - receiptとhighwaterは原子的に更新され、二重記録しない。
  - 並行JVMのうち1つだけが記録する。
- **3d-1:**
  - sealしたsegmentは不変として扱う。
  - orphan・巻戻り・改変・quota超過はfail closedにし、何も削除しない。

未成立・要判断（夜間に解除しない）:

1. **Windows directory durabilityはUNKNOWN。** `MoveFileExW` WRITE_THROUGHの文書上の明示保証はcopy+delete経路だけで、directory handleへの`FlushFileBuffers`も文書化されていない。
   OS再起動・物理電源断・実ENOSPCは試験していない（NOT RUN）。`Runtime.halt`は物理電源断ではない。
2. POSIXのidentityはpathを前後でサンプルしたもので、所有handleには結び付いていない（Javaからfstatできない）。
3. ledgerの初回claimはTOFUである。ledger DBやmanifestを一式で書き換えられる管理者への耐改ざんは主張しない（digestは認証ではない）。
4. 失敗が置換後に起きた場合、呼出側には結果が曖昧に見える。再publishの`alreadyCommitted`か、verifyで確認する必要がある。
5. orphan・tmp残存の運用上の解消手順（Supervisorが判断して通知する）、製品`v1_source_claims`やrun/session契約との統合、RLC1からの移行、JNAの製品採用は未承認。
6. 実X270・RomRaiderと同じJVM・実車・ECUはNOT RUN（禁止範囲のため実行していない）。

## 環境・接続

- **VPS:** ホスト鍵は観測値と一致したのでTOFU登録した。password認証は許可されているが、hosts.envの資格情報で**失敗**した。ロックアウトを避けるため1回しか試していない。→ BLOCKED（資格情報の確認待ち）。
- **Dockerサーバー:** TOFU登録して接続できた。既存の `romraiderserver-*`、immich、sluicery、portainer は変更していない。
  - 自分で作ったもの: `~/rl-claude-phase3b`（約46MB）、image `eclipse-temurin:17-jdk`（449MB）。いずれも次回の検証で再利用できる。
  - コンテナは`--rm`で削除済み。
- **要改善:** 両サーバーのSSHパスワードが短い（5文字・8文字）。鍵認証への切替かパスワード強化を推奨する（無断では変更していない）。
- **Windows VMに追加したもの:**
  - `C:\Dev\tools\venv-ssh`（paramiko）と `C:\Dev\tools\romlogger_ssh.py`
  - pin: `C:\Dev\secrets\romlogger_known_hosts.json`
- 秘密値はログ・Git・チャットに出していない。

## 再開手順

```bash
cd /c/Dev/RomLogger/RomRaider_agent
git fetch origin && git status -sb && gh pr list --state open
cat docs/handoff/claude_phase3b_report.md docs/handoff/claude_phase3b_worklog.md
export JAVA_HOME="/c/Program Files/Zulu/zulu-17" PATH="/c/Program Files/Zulu/zulu-17/bin:$PATH"
bash scripts/test-ingress-journal-prototype.sh && bash scripts/test-product-v1-spool.sh
bash scripts/test-windows-native-identity.sh && bash scripts/test-watermark-ledger.sh
pwsh -File C:/Dev/tools/romlogger-pg.ps1 start && pwsh -File C:/Dev/tools/run-server-tests.ps1
```

次の安全な候補:

- ledger・rotationの拒否状態をSupervisorへ通知する診断（read-only）
- 製品`v1_source_claims`との対応関係の設計レビュー（文書のみ）
- Windowsのdirectory durabilityについて、実機での電源断試験計画を作る（実行には承認が必要）

製品への配線、RLC1からの移行、実機での試験は、ユーザーの承認を得てから行う。

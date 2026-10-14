# Phase 4b-2d-2b-3a-writer-binding — native所有handleの独立実験

安全代替小Phase。`src/test/.../WindowsNativeWriterTest`内のprivate fixtureのみ。
原本は自分で作ったTEMPの既存RLJ1/RLI1セット。製品writer/Workerの置換やRLC1 marker
の再解釈はしない。JNAはtest-only build directoryに限定する。

## プロトコルと証明範囲

1. 既存NTFS regular sourceとidentityを要求。元journalをcreate/repairしない。
2. `.writer.lock`のJava FileLock→native journal handle→8MiB位置1-byte exclusive
   `LockFileEx(FAIL_IMMEDIATELY)` guardを取得。guardはEOFを伸ばさない。
3. その**同一native handle**からvolume/file index、全bounded prefix、RLJ1の
   CRC/sequence/UTF8を検証。writerの初期prefix SHAをメモリ保持する。
4. append前に同じhandleで既読prefix/長さ/IDを再検証。native `WriteFile`でframeを
   書き、成功・完全byte数を確認。続けて同じhandleの`FlushFileBuffers`を呼ぶ。
5. flush成功後に再読込して同じID/旧prefix/新sequence/長さと**WriteFileへ渡したnew frame exact bytes**を確認し、メモリ内
   `Proof`候補（ID/seq/offset/hash）を返す。markerやDBへ保存しない。
6. 書込/flush/検証/容量/入力失敗はwriterをlatchし後続appendを拒否。曖昧なbyteを
   truncate/rollback/削除しない。閉鎖時のみ自分のlock/handleを解放。

writerはGENERIC_READ|GENERIC_WRITEで開き、share READ|WRITEとする。
SHARE_DELETEを指定せず、保持中のsource pathname renameをOSで拒否する。
これは製品共有モードの決定ではない。祖先directory rename、volume clone、
非協調的なmapped write、管理者による同時集合変更を完全保護すると主張しない。
初期expected ID/prefixはメモリなので、source一式の再発行を再起動後に識別する
外部永続ledgerは未実装。このfixtureの再openを製品source claimの再承認にしない。

## 合成試験

- native write/flushと別readerのID/hash一致、closed legacy probeのRLJ1互換。
- native handle native guardのold→native/native→old/split→native/native→split/
  native→native別JVM排他。native writer保持中の別JVM-compatible reader。
- rename拒否、quotaで原本保持、部分原本constructor拒否、既読prefix改変拒否、
  malformed UTF16入力拒否、閉鎖後append拒否。
- `WriteFile`直後/flush直後のIOException注入とterminal latch。これは実ENOSPCではない。
- 実native flushのread-only/invalid handle失敗。入力byte/原本/identityを保持。
- 別JVMの`Runtime.halt`をflush前/後に注入。flush後だけ観測ログと閉鎖後の1frame
  再読込を照合。flush前の完全frameが見えても耐久確定と扱わない。
- OVERLAPPED layout20byte(x86)/32byte(x64)を実pointer幅で照合。Zulu8 x86/Java17
  x64実結果はworklogに保存。X270/RomRaider同JVMはNOT RUN。

`bash scripts/test-windows-native-identity.sh`にこのfixtureを追加。
compile JDK17 `--release 8`、同じclassesをZulu8/x86で`--run-only`実行する。

## 継続するゲート

native ID/read/write/flushの**handle binding primitive**が成立しても、cross-platform
watermarkは完成していない。RLC1 fileKeyHashをnative IDへ読み替えない。
新identity tag/versionのformat、staged marker atomic replacement、各発行段階の失敗、
Windows directory同期UNCONFIRMED、永続expected ID/highwater/原文receiptの同一SQLite
transaction、source claimとrotationの規約が残る。3c/3dと製品ライブ配線はBLOCKED。
3bの次候補はこれらprimitiveを使う**新versionの独立marker候補**の設計・負試験。
Windows durable directory条件を満たさない結果はUNKNOWN/BLOCKEDとし、DBへ渡さない。

物理電源断、OS reboot、実ENOSPC、NTFS以外、volume clone、実車はNOT RUN。
成功したFlushFileBuffersとプロセスhaltの試験だけで絶対無損失を保証しない。

一次資料：[FlushFileBuffers](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-flushfilebuffers)、
[LockFileEx](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-lockfileex)。
実行commit/結果/run/次コマンドは[日中worklog](handoff/codex_daytime_worklog.md)。

## 追加レビューによる修正（3a-writer-integrity / D-044）

初回44/45fixtureの成功後、flush直後にnew payloadとCRCを同時改変する負試験を追加。
CRC/sequence/old prefix検査だけではProofを拒否できなかったため、REDを実Windows
x64で再現・保存し、new frameをWriteFileへ渡したbyte[]と完全比較する修正を追加。
この負試験は物理電源断ではなく、原本改変検知のsyntheticケース。CRC-validに書換えた
原本を削除/修復せず保持し、Proofを返さずwriterをlatchする。最新結果はworklog参照。

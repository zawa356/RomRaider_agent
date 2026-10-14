# Phase 4b-2d-2b-3a-compat — native READ / Zulu8 x86独立試験

3bのcross-platform watermarkゲートが未達のため行う、安全な代替小Phase。
本Phaseはwatermarkの仕様変更・製品配線をしない。テスト専用JNA/NTFSでのみ実行。

## 所有read handleとの結び付け

`WindowsBoundReadTest`はnative `CreateFileW(GENERIC_READ, OPEN_EXISTING)`で
開いた**同じhandle**に対して、`GetFileInformationByHandle`と`ReadFile`を呼ぶ。
事前/事後のvolume/index、8MiB上限、prefixのサイズ、外部から渡した期待IDと
期待SHAを検証する。4096-byte chunks、短いread/stall/error/closed handleは拒否。
Unicodeパス、9,000-byte読み取り、途中のpathname rename/copy、同内容別原本、
in-place改変、quota、EOF、別JVMのold/split writer下のREADを合成検証する。

pathnameがコピー先へ入れ替わっても、保持したread handleから返るbytesは旧原本に
結び付く。この性質を証明するfixtureであり、**製品path継続条件を解除しない**。
再確認でpathnameが変われば運用者reviewが必要。複製された期待IDやSHAを再計算する
管理者の耐改ざん保証ではない。in-place改変ではnative IDが変わらない反例もある。

READ結果は **NOT_COMMITTED** 診断。fsync、watermark発行、DB receipt/cursor/ACKを
証明しない。原文hashが一致しても、誰がいつfsyncしたかは分からない。
writer所有Java RandomAccessFileとのnative bindingも未実装。

## 32bit実行の検証

専用Windows Actions jobでJava17 `--release 8`でcompileしたclassesを、別途
setup-javaで取得したZulu8 **x86 JVM**で実行する。環境変数
`ROMLOGGER_EXPECT_DATA_MODEL=32`と実際の`sun.arch.data.model`を照合し、
x64で代用して成功としない。native struct52bytesとx86/x64 pointer/INVALID_HANDLE
判定、JNA nativeロード、別JVM/hardlink/copy/lockを実行する。

```bash
# Windows/Git Bash、compile環境はJDK17
bash scripts/test-ingress-journal-prototype.sh
bash scripts/test-windows-native-identity.sh
# 同じclassesをZulu8/x86へ切替後
ROMLOGGER_EXPECT_DATA_MODEL=32 bash scripts/test-windows-native-identity.sh --run-only
```

GitHub runner上のZulu8 x86成功は**X270/Windows10/RomRaider同一JVMでの成功ではない**。
物理電源断、OS reboot、実disk full、volume clone、ReFS/network FSはNOT RUN。
symlink fixtureが権限により未実行ならNOT RUN行を保持し、実行数をraw logで数える。
Windowsの既存RLC1 full watermark56とdir同期UNCONFIRMEDは継続。

## 次ゲート

read-side primitiveが成立しても3b/3cへ飛ばさない。writer-owned native binding、
Windows directory durability、外部expected identityの永続化と再利用防止、
RLC1互換と新version移行、SQLite原文receipt/CAS/highwaterの原子性が必要。
PoC→本番適用や依存追加は要判断事項。既存writer/Worker/Server IFは非変更。

一次資料：[Microsoft ReadFile](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-readfile)、
[setup-java v4 advanced usage](https://github.com/actions/setup-java/blob/v4/docs/advanced-usage.md)。
実結果とcommit/run/stepは[日中worklog](handoff/codex_daytime_worklog.md)に追記する。

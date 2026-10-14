# Phase 4b-2d-2b-3a — 原本同一性の独立比較

実装は `src/test` の合成fixtureのみ。製品のRLJ1/RLI1/RLC1、writer、Worker、SQLite cursor、ACK、正式Server IFは変更しない。

## 比較・反例

| 案 | 確認できること | 反例・制限 | 判断 |
| --- | --- | --- | --- |
| A hardlink anchor + `Files.isSameFile` | 保持したanchorに対する同内容別inode置換を拒否。rename後も同一inodeを識別 | anchorとsourceを一式複製し、新inode上でhardlink関係を再構築すればtrue。関係だけでは以前の原本との同一性を証明できない。Linux同JVM別handle closeはlegacy guardを失う | 単独採用は否決。既存原本を保持する補助anchorは候補 |
| B native volume serial + file index | NTFSの既存handleから52-byte情報を取得。別JVM/reopen/rename/保持anchorで値が一致、コピーは異なる | NTFS以外は停止。file index再利用、volume clone、OS再起動、Java writer所有handleへのbinding、永続expected identityの原子性は未証明 | **次の独立実験の推奨候補**。製品採用の承認ではない |
| C 原本内UUID/header | 論理source UUIDをバイト列に固定できる | CRC/SHA/UUIDもコピーされるため同内容別原本を識別できない。新headerはRLJ1旧readerとの互換を壊す | 物理原本識別の代替として否決。論理IDの補助のみ |

Aは既存regular/non-symlinkの異なる2パスを事前検査する。全祖先のsymlinkも拒否。
`isSameFile(p,p)`は存在しないパスでもtrueを返すため、同じパスの比較を許可しない。
これはTOCTOU耐性を完成させるものではない。2パスの属性確認と比較の間に管理者が
同時置換できる。raw `isSameFile` はsymlinkを辿るので拒否の事前検査が必要。
anchor欠落時の暗黙再作成はしない。比較fixtureの全保持原本はそのまま残す。

BのテストはJNA **5.14.0 coreのみ**を`build/identity-deps`へ取得し、SHA256
`34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6`を照合する。
JNAのnative extractionもテストJVM内のみ。`lib`/`dist`へ配布しない。
`CreateFileW(FILE_READ_ATTRIBUTES, share READ|WRITE|DELETE, OPEN_EXISTING,
FILE_FLAG_OPEN_REPARSE_POINT)` → `GetFileInformationByHandle` → `CloseHandle`。
既存ファイル以外は作らず、不明FS/取得失敗は例外で停止。無効handleの負試験あり。
共有削除はrename/unlinkの合成試験のためであり、製品の共有モードは未決。

**whole-setコピーのB拒否はコピーの外に保存したexpected IDと比較した結果**。
原本・anchor・期待IDをすべて再発行/改変できる管理者への認証/耐改ざん保証ではない。
コピーされたmarkerはコピー先のnative IDと一致しない条件が必要。UUIDやCRC/hashに
署名としての意味を与えない。ReFSの128-bit IDを64-bit indexへ短縮しない。

## 試験と移行ゲート

`bash scripts/test-ingress-journal-prototype.sh`にA/C比較を追加。
別JVM再起動、source renameと同内容置換、whole-setコピー、anchor欠落、symlink、
同JVM別anchor handle close後の旧writer競合を確認。Linuxでguard喪失の反例を
PASSとして再現するが、排他が維持されたとは報告しない。
既存split-lock試験が両起動順のold/new writer排他、別JVM reader、quotaを担当。
既存watermark試験が部分書込/CRC/hash/連番/原本置換/haltを担当。新比較は
これらの製品への配線を許可するものではない。

Windows追加コマンド：`bash scripts/test-windows-native-identity.sh`。
NTFS/x64 Java17の実結果はworklogに保存。32bit Java8/Zulu8は3a時点NOT RUN、後続[compat](phase_4b_2d_2b_3a_compatibility.md)でactual Zulu8/x86成功。
OS再起動、物理電源断、実ENOSPC、network FS、ReFS、volume cloneはNOT RUN。
Windows directory forceは従来UNCONFIRMEDのまま。

3b/3c/3dのゲートは未達：

1. writerが実際に追記/fsyncした**所有handle**とnative identity取得handleの同一性、
   readerが実際に読み取ったhandleとのbindingが必要。パスの前後照合だけで進めない。
2. expected identity/source UUID/既読highwaterをSQLite原文receiptと同じtransactionで
   保持する設計が必要。anchor保持・file index再利用・source claimを合わせて検証。
3. Windows directory entry永続化がUNCONFIRMED。確認不能時に成功へ昇格させない。
4. RLC1のfileKeyHashをnative IDへ黙って読み替えない。別version/移行契約が必要。
5. 旧writer/reader共存、32bit実行、実機導入・依存追加・形式変更は別ゲート。

したがって3aは比較結果を確定できるが、cross-OS watermarkの成立を宣言しない。
3b以降を無理に進めず、安全な代替小PhaseとしてJava8/x86識別primitiveと
所有handleに結び付いたread-only診断を検証できる。

## 一次資料（2026-10-14確認）

- [Oracle Java8 Files.isSameFile/createLink](https://docs.oracle.com/javase/8/docs/api/java/nio/file/Files.html)：等しいパスのshortcut、リンク/FS/provider依存。
- [Microsoft GetFileInformationByHandle](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getfileinformationbyhandle)：handle情報取得とFS依存。
- [Microsoft BY_HANDLE_FILE_INFORMATION](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/ns-fileapi-by_handle_file_information)：volume/file index、再利用、ReFS制限。
- [JNA5.14.0](https://github.com/java-native-access/jna/tree/5.14.0)：テストbinding候補、LGPL2.1+/Apache2.0 dual license。製品配布選択は未実施。

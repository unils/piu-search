# 材料の欠けの「不具合の疑い」の行に残す例外の先頭のフレームで、行の番号を読めるようにする（architecture.md の「失敗の分け方」）。
# ファイル名は一律に SourceFile に替え、元のファイル名を APK に残さない。名前と行は版ごとの mapping.txt で戻す（release.md）
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile

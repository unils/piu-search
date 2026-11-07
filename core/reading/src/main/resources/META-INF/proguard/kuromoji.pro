# Kuromoji は辞書を Tokenizer.Builder のクラスからの相対の名前（同じパッケージの *.bin）で読む。
# R8 がクラスの名前やパッケージを変えると辞書が見つからなくなるので、名前だけを残す（使わなければ削ってよい）
-keepnames class com.atilika.kuromoji.ipadic.Tokenizer$Builder

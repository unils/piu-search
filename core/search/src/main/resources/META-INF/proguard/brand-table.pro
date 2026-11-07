# BrandTable.loadDefault は、ブランド表（brands.tsv）を BrandTable のクラスからの相対の名前で読む。
# R8 がクラスの名前やパッケージを変えると表が見つからず、索引を作れなくなる（M2-04 に release の APK で見つけた）。
# 名前だけを残す（使わなければ削ってよい）
-keepnames class jp.piu.search.core.search.BrandTable

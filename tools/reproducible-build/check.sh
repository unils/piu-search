#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# 同じコミットから署名なしの release の APK を 2 回組み立て、バイト単位で一致するかを確かめる。
# F-Droid はメンテナの署名を自分で組み立てた APK に貼って比べるので、ZIP の詰め方まで一致しないと配れない。
#
#   tools/reproducible-build/check.sh [コミット] [作業ディレクトリ]
#
# コミットの既定は HEAD（コミットしていない変更は入らない）。作業ディレクトリの既定は build/reproducible-build。
# 2 つの別のディレクトリに git worktree で置き、順に組み立てる（同時に組み立てるとメモリが足りないことがある）。
# 終了コードは、一致で 0、不一致で 1。一致したら作業ディレクトリを消し、不一致なら比べた APK を残す。
# 環境変数 WORKERS_A・WORKERS_B で、それぞれの組み立ての --max-workers を変えられる（既定は 4）。
# 並列の度合いで出力が変わらないかを見るため。
set -euo pipefail

repo=$(git rev-parse --show-toplevel)
rev=$(git -C "$repo" rev-parse --verify "${1:-HEAD}^{commit}")
work=$(realpath -m "${2:-$repo/build/reproducible-build}")
apk=app/build/outputs/apk/release/app-release-unsigned.apk

if [[ -e $work ]]; then
  echo "作業ディレクトリ $work が既にある。消すか別の場所を指定する" >&2
  exit 2
fi
mkdir -p "$work"

keep=1
cleanup() {
  for side in a b; do
    [[ -d $work/$side ]] && git -C "$repo" worktree remove --force "$work/$side"
  done
  git -C "$repo" worktree prune
  if [[ $keep == 0 ]]; then rm -rf "$work"; fi
}
trap cleanup EXIT

build() {
  local side=$1 workers=$2
  git -C "$repo" worktree add --detach --quiet "$work/$side" "$rev"
  # SDK の場所は PC ごとに違い、git の管理外なので写す（ANDROID_HOME でもよい）
  [[ -f $repo/local.properties ]] && cp "$repo/local.properties" "$work/$side/"
  echo "== $side: $rev を組み立てる（--max-workers=$workers）"
  # ビルドキャッシュを使うと、2 回目が 1 回目の出力を取り出すだけになり、比べる意味がなくなる
  (cd "$work/$side" && ./gradlew --no-daemon --no-build-cache --console=plain --quiet \
    "--max-workers=$workers" clean :app:assembleRelease)
  cp "$work/$side/$apk" "$work/$side.apk"
}

build a "${WORKERS_A:-4}"
build b "${WORKERS_B:-4}"

sha_a=$(sha256sum "$work/a.apk" | cut -d' ' -f1)
sha_b=$(sha256sum "$work/b.apk" | cut -d' ' -f1)
echo "a: $sha_a"
echo "b: $sha_b"
if [[ $sha_a == "$sha_b" ]]; then
  echo "一致した"
  keep=0
  exit 0
fi

echo "一致しない。違う項目:"
for side in a b; do
  mkdir "$work/$side.x"
  unzip -q "$work/$side.apk" -d "$work/$side.x"
  (cd "$work/$side.x" && find . -type f -print0 | sort -z | xargs -0 sha256sum) >"$work/$side.sha256"
done
diff "$work/a.sha256" "$work/b.sha256" || true
# 中身が同じでも、項目の並び・圧縮の仕方・余白が違えば APK は一致しない
if diff -q "$work/a.sha256" "$work/b.sha256" >/dev/null; then
  echo "中身は同じ。ZIP の詰め方の違い:"
  diff <(zipinfo -v "$work/a.apk" | tail -n +2) <(zipinfo -v "$work/b.apk" | tail -n +2) | head -40 || true
fi
if command -v diffoscope >/dev/null; then
  diffoscope "$work/a.apk" "$work/b.apk" || true
fi
echo "APK と展開したものは $work に残す（a.apk・b.apk・a.x・b.x）"
exit 1

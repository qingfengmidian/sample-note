#!/usr/bin/env bash
# 校验私仓与开源仓的共享源码是否一致（git blob 哈希级比较）。
# 只比较双方「应当一致」的 tracked 路径；构建产物等未跟踪文件天然不参与。
# 用法：./tools/oss-sync-check.sh   （不一致时退出码 1 并列出差异文件）
set -euo pipefail

PRIVATE_DIR="${PRIVATE_DIR:-/Users/xukk/code/sample-note}"
OSS_DIR="${OSS_DIR:-/Users/xukk/code/sample-note-oss}"

# 双方必须一致的 tracked 路径（多出的/缺少的/内容不同的都会被发现）。
# 不在清单内的：opencode.json、docs/collab/、spike/（仅私仓）；
# settings.gradle.kts、CHANGELOG.md（两仓刻意不同）；开源治理文件（仅开源仓跟踪）。
SHARED_PATHS=(
  app
  buildSrc
  gradle
  gradlew
  gradlew.bat
  gradle.properties
  build.gradle.kts
  .gitignore
  .github/workflows/ci.yml
  AGENTS.md
  docs/需求说明书.md
  docs/软件设计文档.md
  docs/开发计划.md
  docs/测试用例.md
  docs/协同开发规范.md
  docs/文档代码一致性评审.md
  docs/review_round_1.md
  docs/review_round_2.md
  docs/review_round_3.md
)

list_hashes() { # $1=仓库目录；输出 "<模式> <blob哈希> <路径>"，供整行比对
  git -C "$1" ls-files -s -- "${SHARED_PATHS[@]}"
}

A="$(list_hashes "$PRIVATE_DIR" | LC_ALL=C sort)"
B="$(list_hashes "$OSS_DIR" | LC_ALL=C sort)"

if [ "$A" = "$B" ]; then
  echo "OK: 共享源码一致（$(echo "$A" | sed '/^$/d' | wc -l | tr -d ' ') 个文件，blob 哈希级相同）"
  exit 0
fi

echo "FAIL: 私仓与开源仓共享源码不一致："
diff <(echo "$A") <(echo "$B") | grep '^[<>]' | awk '{print $1"\t"$NF}' \
  | sed 's/^< \t/私仓独有或不同: /; s/^> \t/开源仓独有或不同: /'
echo "（同一路径两侧都出现 = 内容不同；只出现一侧 = 一边缺失）"
exit 1

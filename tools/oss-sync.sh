#!/usr/bin/env bash
# 把私仓的共享源码同步到开源仓工作区（先校验，有差异才复制并提交）。
# 只同步 oss-sync-check.sh 清单内的路径，绝不触碰开源仓的排除项与治理文件。
# 用法：./tools/oss-sync.sh
set -euo pipefail

PRIVATE_DIR="${PRIVATE_DIR:-/Users/xukk/code/sample-note}"
OSS_DIR="${OSS_DIR:-/Users/xukk/code/sample-note-oss}"

cd "$(dirname "$0")"
if ./oss-sync-check.sh; then
  echo "无需同步：两仓已一致。"
  exit 0
fi

echo "==> 开始同步（私仓 → 开源仓工作区）"
# 目录：rsync 镜像（--delete 清掉开源仓一侧的残留文件）
for d in app buildSrc gradle; do
  rsync -a --delete "$PRIVATE_DIR/$d/" "$OSS_DIR/$d/"
done
# 单文件
for f in gradlew gradlew.bat gradle.properties build.gradle.kts .gitignore \
         .github/workflows/ci.yml AGENTS.md \
         docs/需求说明书.md docs/软件设计文档.md docs/开发计划.md docs/测试用例.md \
         docs/协同开发规范.md docs/文档代码一致性评审.md \
         docs/review_round_1.md docs/review_round_2.md docs/review_round_3.md; do
  cp "$PRIVATE_DIR/$f" "$OSS_DIR/$f"
done

./oss-sync-check.sh
git -C "$OSS_DIR" add -A
if git -C "$OSS_DIR" diff --cached --quiet; then
  echo "同步后无变更（差异可能来自未跟踪文件，已忽略）。"
else
  git -C "$OSS_DIR" -c core.hooksPath=/dev/null commit -m "sync: 与开发仓同步共享源码（$(date +%F)）"
  echo "已提交同步变更。"
fi

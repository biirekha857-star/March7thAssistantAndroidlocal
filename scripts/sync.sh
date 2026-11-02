#!/usr/bin/env bash
# 一条命令同步到 Gitee 私有仓库。
#
# 用法：
#   ./scripts/sync.sh "改了任务包的参数表单"
#   ./scripts/sync.sh            # 不写说明就用 "sync"
#
# 做的事情：
#   1. 先把别人的改动拉下来（rebase，本地未提交的先暂存）
#   2. 把你的改动全部提交
#   3. 推到 gitee 的 main
#
# 已经配了全局 alias 的人可以直接 `git sync "说明"`，效果一样。

set -e
cd "$(dirname "$0")/.."

MSG="${1:-sync}"

echo "==> 拉取 Gitee 上的改动"
git pull --rebase --autostash gitee main

echo "==> 暂存所有改动"
git add -A

if git diff --cached --quiet; then
    echo "==> 没有新改动，只需要推送"
else
    echo "==> 提交：$MSG"
    git commit -m "$MSG"
fi

echo "==> 推送到 gitee/main"
git push gitee main

echo "==> 完成"

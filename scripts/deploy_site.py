#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 site/ 部署到 Cloudflare Pages。

用法（在仓库根目录执行）：

    python scripts/deploy_site.py             # 上传并验证
    python scripts/deploy_site.py --check     # 只看线上现状，不上传
    python scripts/deploy_site.py --dir site  # 换目录

凭据从 `local.properties` 读（该文件在 .gitignore 里，不会进版本库）：

    maa.cfDeployToken=cfut_xxx      # 用户级 API 令牌，权限 Account/Pages/Edit
    maa.cfAccountId=xxxxxxxx        # 账户 ID
    maa.cfProject=m7a               # Pages 项目名

## 为什么调 wrangler 而不是直接 POST API

Pages 的直接上传接口要求先交一份 `manifest`（路径 → 内容哈希），
但官方 API 文档只写了这个字段"必填"，没写哈希算法、路径格式、以及
文件本身挂在哪个 form 字段下。自己拼 multipart 会得到：

    400  A "manifest" field was expected in the request body

wrangler 内部实现了这套细节，一条命令绕过去。

## 为什么部署完还要回读

`wrangler` 说 Success 只代表文件传完了。真正要确认的是：
线上那份 `index.json` 里是不是你想要的那几个包 —— 少一次回读，
就可能出现"部署成功但用户看不到新包"的情况。
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PROPS = ROOT / "local.properties"
WRANGLER_SPEC = "wrangler@4.42.0"


def read_props(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        k, v = s.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def fail(msg: str) -> int:
    print(f"❌ {msg}")
    return 1


def fetch_index(project: str) -> dict | None:
    url = f"https://{project}.pages.dev/taskpacks/index.json"
    # 必须带 UA：Cloudflare 会直接 403 掉 `Python-urllib/3.x` 这种默认 UA
    req = urllib.request.Request(url, headers={"User-Agent": "m7a-deploy/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode("utf-8"))
    except (urllib.error.URLError, json.JSONDecodeError, TimeoutError) as e:
        print(f"⚠️  读线上索引失败：{e}")
        return None


def show_index(data: dict | None, title: str) -> None:
    if not data:
        return
    packs = data.get("packs", [])
    print(f"\n{title}（updatedAt {data.get('updatedAt', '?')}，{len(packs)} 个包）")
    for p in packs:
        size = p.get("size", 0)
        print(f"   {p.get('id', '?'):38} {p.get('version', '?'):10} {size:>9} B")


def run_wrangler(site: Path, project: str, branch: str, token: str, account: str) -> int:
    npx = shutil.which("npx")
    if not npx:
        return fail("找不到 npx（需要 Node.js）。装了再试，或用网页拖 zip。")

    cmd = [
        npx, "--yes", WRANGLER_SPEC, "pages", "deploy", str(site),
        f"--project-name={project}",
        f"--branch={branch}",
        "--commit-dirty=true",
    ]
    env = {**os.environ, "CLOUDFLARE_API_TOKEN": token, "CLOUDFLARE_ACCOUNT_ID": account}

    print(f"🚀 上传 {site} → 项目 {project}（分支 {branch}）")
    proc = subprocess.run(cmd, env=env)
    return proc.returncode


def main() -> int:
    # 不缓冲：wrangler 是子进程直接继承 stdout，
    # 我们这边的 print 若被缓冲，日志顺序会乱
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except Exception:
        pass

    ap = argparse.ArgumentParser(description="部署 site/ 到 Cloudflare Pages")
    ap.add_argument("--dir", default="site", help="要部署的目录（默认 site）")
    ap.add_argument("--branch", default="main", help="分支名（默认 main = 生产）")
    ap.add_argument("--check", action="store_true", help="只检查线上，不上传")
    ap.add_argument("--props", type=Path, default=DEFAULT_PROPS, help="配置文件路径")
    args = ap.parse_args()

    props = read_props(args.props)
    token = props.get("maa.cfDeployToken", "")
    account = props.get("maa.cfAccountId", "")
    project = props.get("maa.cfProject", "m7a")

    if args.check:
        show_index(fetch_index(project), f"线上现状 · {project}")
        return 0

    if not token:
        return fail(
            "local.properties 里没有 maa.cfDeployToken。\n"
            "   去 https://dash.cloudflare.com/profile/api-tokens 建一个\n"
            "   权限 Account / Cloudflare Pages / Edit，那串 40 位无前缀的字符填进去。"
        )
    if not account:
        return fail("local.properties 里没有 maa.cfAccountId（Cloudflare 账户 ID）。")

    site = (ROOT / args.dir).resolve()
    if not site.is_dir():
        return fail(f"目录不存在：{site}")

    # 部署前先看一眼线上，部署后好对比
    before = fetch_index(project)
    show_index(before, "部署前 · 线上")

    rc = run_wrangler(site, project, args.branch, token, account)
    if rc != 0:
        return fail(f"wrangler 退出码 {rc}，部署没成功。")

    after = fetch_index(project)
    show_index(after, "部署后 · 线上")

    if after is not None:
        local_ids = {
            p.get("id") for p in json.loads(
                (site / "taskpacks" / "index.json").read_text(encoding="utf-8")
            ).get("packs", [])
        }
        live_ids = {p.get("id") for p in after.get("packs", [])}
        if local_ids == live_ids:
            print(f"\n✅ 线上与本地一致（{len(local_ids)} 个包），内容已是最新。")
        else:
            missing = local_ids - live_ids
            extra = live_ids - local_ids
            print("\n⚠️  线上和本地对不上：")
            if missing:
                print(f"   线上缺少：{', '.join(sorted(missing))}")
            if extra:
                print(f"   线上多出：{', '.join(sorted(extra))}")
            print("   等十几秒再跑 --check，若仍不一致说明 CDN 还没刷新。")

    print(f"\n🌐 https://{project}.pages.dev")
    return 0


if __name__ == "__main__":
    sys.exit(main())

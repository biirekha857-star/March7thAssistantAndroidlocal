#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给站点上的任务包索引补指纹并签名。

用法（在仓库根目录执行）：

    python scripts/sign_taskpack_index.py

做三件事：

1. 对 `site/taskpacks/index.json` 里每个 pack，按 `url` 最后一段找本地包文件，
   算 sha256 与 size 写回索引 —— 所以手写索引时**不用自己算 hash**；
2. 打开包里的 `task.json`，核对 `id` 与索引是否一致（不一致直接失败，
   否则装上了配置也对不上）；
3. 对最终的 `index.json` 原始字节做 ECDSA(SHA-256) 签名，写出 `index.json.sig`。

私钥默认取 `../m7a-keystore/taskpack-signing-key.pem`（在仓库外，不入库）。
**私钥丢了只能换密钥 + 发新版 App**，因为公钥是硬编码在 APK 里的。

依赖：openssl 命令行（Git for Windows 自带）。纯 Python 不装第三方包。
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_INDEX = ROOT / "site" / "taskpacks" / "index.json"
DEFAULT_KEY = ROOT.parent / "m7a-keystore" / "taskpack-signing-key.pem"


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_pack_id(path: Path) -> str | None:
    """从 .m7atask（zip）或纯 .json 包里读 task.json 的 id。"""
    if path.suffix.lower() == ".json":
        return json.loads(path.read_text(encoding="utf-8")).get("id")
    with zipfile.ZipFile(path) as z:
        with z.open("task.json") as f:
            return json.loads(f.read().decode("utf-8")).get("id")


def find_openssl() -> str:
    """Windows 上 Python 不一定继承 Git Bash 的 PATH，多个位置都找一下。"""
    for name in ("openssl", "openssl.exe"):
        found = shutil.which(name)
        if found:
            return found
    for guess in (
        r"C:\Program Files\Git\usr\bin\openssl.exe",
        r"C:\Program Files\Git\mingw64\bin\openssl.exe",
        "/usr/bin/openssl",
        "/usr/local/bin/openssl",
    ):
        if Path(guess).is_file():
            return guess
    sys.exit(
        "找不到 openssl。请用 Git Bash 运行，或把 openssl 加进 PATH。\n"
        "（Windows 上一般在 C:\\Program Files\\Git\\usr\\bin\\openssl.exe）"
    )


def sign(content: bytes, key: Path, openssl: str) -> bytes:
    """对字节做 detach 签名，返回 DER 格式签名。"""
    proc = subprocess.run(
        [openssl, "dgst", "-sha256", "-sign", str(key)],
        input=content,
        capture_output=True,
    )
    if proc.returncode != 0:
        sys.exit(f"openssl 签名失败：\n{proc.stderr.decode(errors='replace')}")
    if not proc.stdout:
        sys.exit("openssl 没有输出签名（私钥格式不对？应为 PEM 的 EC 私钥）")
    return proc.stdout


def main() -> int:
    ap = argparse.ArgumentParser(description="给任务包索引补指纹并签名")
    ap.add_argument("--index", type=Path, default=DEFAULT_INDEX, help="索引文件路径")
    ap.add_argument("--key", type=Path, default=DEFAULT_KEY, help="签名私钥路径")
    ap.add_argument(
        "--no-pack-check",
        action="store_true",
        help="不核对本地包（只签名）。包文件放在 CDN/GitHub 上时用",
    )
    args = ap.parse_args()

    if not args.index.is_file():
        print(f"找不到索引：{args.index}", file=sys.stderr)
        print("第一次用的话先建一个：{\"schemaVersion\":1,\"packs\":[]}", file=sys.stderr)
        return 1
    if not args.key.is_file():
        print(f"找不到私钥：{args.key}", file=sys.stderr)
        print("私钥不入库，丢了只能重新生成密钥对并发布新版 App。", file=sys.stderr)
        return 1

    try:
        data = json.loads(args.index.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        print(f"索引不是合法 JSON：{e}", file=sys.stderr)
        return 1

    packs = data.get("packs")
    if not isinstance(packs, list):
        print('索引里没有 packs 数组（应为 "packs": []）', file=sys.stderr)
        return 1

    if data.get("schemaVersion") != 1:
        print(f"schemaVersion 必须是 1，当前是 {data.get('schemaVersion')}", file=sys.stderr)
        return 1

    base_dir = args.index.parent
    errors: list[str] = []
    lines: list[str] = []

    for i, pack in enumerate(packs):
        pid = str(pack.get("id") or "").strip()
        url = str(pack.get("url") or "").strip()

        if not pid:
            errors.append(f"packs[{i}] 缺少 id")
            continue
        if not url.startswith("https://"):
            errors.append(f"{pid}: url 必须是 https://")
        for field in ("name", "author", "version"):
            if not str(pack.get(field) or "").strip():
                errors.append(f"{pid}: 缺少 {field}")

        filename = url.rsplit("/", 1)[-1].split("?")[0]
        local = base_dir / filename

        if not local.is_file():
            if args.no_pack_check:
                if not pack.get("sha256"):
                    errors.append(f"{pid}: 本地没有 {filename}，索引里也没有 sha256")
                else:
                    lines.append(f"· {pid}: 本地无文件，沿用索引 sha256")
                continue
            if pack.get("sha256"):
                lines.append(f"· {pid}: 本地没有 {filename}，沿用索引里的 sha256")
            else:
                errors.append(
                    f"{pid}: 本地没有 {filename}，索引里也没有 sha256 —— 无法校验完整性"
                )
            continue

        digest = sha256_of(local)
        size = local.stat().st_size
        old = str(pack.get("sha256") or "").lower()
        if old and old != digest:
            lines.append(f"· {pid}: sha256 已更新 {old[:12]}… → {digest[:12]}…")
        pack["sha256"] = digest
        pack["size"] = size

        try:
            real_id = read_pack_id(local)
        except Exception as e:  # noqa: BLE001 —— 打包坏了就是打包坏了
            errors.append(f"{pid}: 读不了 {filename} 里的 task.json（{e}）")
            continue
        if real_id != pid:
            errors.append(
                f"{pid}: 索引 id 与包内 task.json 的 id「{real_id}」不一致"
                f"（装上了配置会对不上）"
            )
            continue

        lines.append(f"· {pid}: {filename} · {digest[:12]}… · {size} 字节")

    if errors:
        print("❌ 索引有问题，未签名：", file=sys.stderr)
        for e in errors:
            print(f"   • {e}", file=sys.stderr)
        return 1

    # 写回：indent=2、中文不转义、末尾一个换行 —— 让 diff 好看
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    args.index.write_text(text, encoding="utf-8", newline="\n")

    openssl = find_openssl()
    signature = sign(args.index.read_bytes(), args.key, openssl)
    sig_path = args.index.with_suffix(args.index.suffix + ".sig")
    # newline="\n" 必须有：Windows 默认会写成 CRLF，base64 解码器会报 invalid input
    sig_path.write_text(
        base64.b64encode(signature).decode("ascii") + "\n",
        encoding="ascii",
        newline="\n",
    )

    print(f"✅ 已签名 {len(packs)} 个任务包")
    for line in lines:
        print("  " + line)
    print(f"   索引：{args.index}")
    print(f"   签名：{sig_path}")
    print("\n别忘了部署站点（wrangler pages deploy site），否则线上还是旧的。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

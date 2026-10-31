#!/usr/bin/env python3
"""从 site/update.md 生成 site/update.json。

# 为什么要有这个脚本

客户端读的是 `update.md`（静态文件，人写给人看），但 **0.5.7beta 以前的
版本只会解析 JSON**。要是直接把 update.json 删掉，那些用户就再也收不到
更新提示了 —— 他们会一直停在旧版本，连强制更新都推不到。

所以两份都留在站点上，但只有 md 是手写的，json 由本脚本生成，避免两份
内容对不上。

# 用法

    python3 scripts/update_manifest.py            # 写 site/update.json
    python3 scripts/update_manifest.py --check    # 只校验，不写文件
    python3 scripts/update_manifest.py --stdout   # 打印到终端

发版顺序：改 update.md → 运行本脚本 → 部署 site/ 目录。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UPDATE_MD = ROOT / "site" / "update.md"
UPDATE_JSON = ROOT / "site" / "update.json"

FENCE = "---"

# md 里的 key（小写、去掉下划线和连字符后）→ json 字段名
FIELD_MAP = {
    "versioncode": "versionCode",
    "versionname": "versionName",
    "apkurl": "apkUrl",
    "forceupdate": "forceUpdate",
    "minsupportedversioncode": "minSupportedVersionCode",
    "publishedat": "publishedAt",
}

REQUIRED = ("versionCode", "versionName", "apkUrl")

INT_FIELDS = ("versionCode", "minSupportedVersionCode")
BOOL_FIELDS = ("forceUpdate",)


def normalize_key(raw: str) -> str:
    return re.sub(r"[_\- ]", "", raw.strip().lower())


def parse_bool(raw: str) -> bool:
    return raw.strip().lower() in {"true", "yes", "y", "1", "on"}


def parse(md_text: str) -> tuple[dict, str]:
    """返回 (元信息字典, 正文 changelog)。格式不对就抛 ValueError。"""
    text = md_text.lstrip("\ufeff").replace("\r\n", "\n").replace("\r", "\n")
    lines = text.split("\n")

    start = next((i for i, line in enumerate(lines) if line.strip()), None)
    if start is None:
        raise ValueError("文件是空的")
    if lines[start].strip() != FENCE:
        raise ValueError(f"第 {start + 1} 行应该是 `{FENCE}`，md 必须以 front matter 开头")

    end = next((i for i in range(start + 1, len(lines)) if lines[i].strip() == FENCE), None)
    if end is None:
        raise ValueError("找不到 front matter 的结束标记 `---`")

    meta: dict[str, str] = {}
    for i in range(start + 1, end):
        line = lines[i].strip()
        if not line or line.startswith("#"):
            continue
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        field = FIELD_MAP.get(normalize_key(key))
        if field is None:
            print(f"⚠️  忽略未知字段 `{key.strip()}`（第 {i + 1} 行）", file=sys.stderr)
            continue
        meta[field] = value.strip().strip('"').strip("'")

    missing = [f for f in REQUIRED if not meta.get(f)]
    if missing:
        raise ValueError("front matter 缺少必填字段：" + "、".join(missing))

    data: dict = {}
    for field, value in meta.items():
        if field in INT_FIELDS:
            try:
                data[field] = int(value)
            except ValueError as exc:
                raise ValueError(f"`{field}` 必须是整数，实际是 `{value}`") from exc
        elif field in BOOL_FIELDS:
            data[field] = parse_bool(value)
        else:
            data[field] = value

    data.setdefault("changelog", "\n".join(lines[end + 1:]).strip())
    data.setdefault("forceUpdate", False)
    data.setdefault("minSupportedVersionCode", 0)
    data.setdefault("publishedAt", "")

    return data, data["changelog"]


def dump(data: dict) -> str:
    # 字段顺序固定，diff 才好看
    order = [
        "versionCode",
        "versionName",
        "apkUrl",
        "changelog",
        "forceUpdate",
        "minSupportedVersionCode",
        "publishedAt",
    ]
    ordered = {key: data[key] for key in order if key in data}
    return json.dumps(ordered, ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="从 site/update.md 生成 site/update.json")
    parser.add_argument("--check", action="store_true", help="只校验，不写文件")
    parser.add_argument("--stdout", action="store_true", help="打印结果，不写文件")
    args = parser.parse_args()

    if not UPDATE_MD.exists():
        print(f"❌ 找不到 {UPDATE_MD}", file=sys.stderr)
        return 1

    try:
        data, changelog = parse(UPDATE_MD.read_text(encoding="utf-8"))
    except ValueError as exc:
        print(f"❌ {UPDATE_MD.name} 格式错误：{exc}", file=sys.stderr)
        return 1

    output = dump(data)

    if args.stdout:
        sys.stdout.write(output)
        return 0

    if args.check:
        current = UPDATE_JSON.read_text(encoding="utf-8") if UPDATE_JSON.exists() else ""
        if current.strip() != output.strip():
            print("❌ update.json 和 update.md 不一致，请重新生成", file=sys.stderr)
            return 1
        print(f"✅ 一致：versionCode {data['versionCode']} / {data['versionName']}")
        return 0

    UPDATE_JSON.write_text(output, encoding="utf-8")
    print(
        f"✅ 生成 {UPDATE_JSON.relative_to(ROOT)}："
        f"versionCode {data['versionCode']} / {data['versionName']}"
        f"（changelog {len(changelog)} 字）"
    )
    if data.get("forceUpdate"):
        print("⚠️  forceUpdate = true —— 所有旧版本都会被强制更新")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

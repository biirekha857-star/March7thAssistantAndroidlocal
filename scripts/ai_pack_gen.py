#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用多模态模型生成任务包。

    python scripts/ai_pack_gen.py --desc "从大厅进邮件，领取全部奖励，返回大厅" \
        --images shot1.png shot2.png --out mail.m7atask

    python scripts/ai_pack_gen.py --selftest    # 不联网，验证校验与打包

配置写在 local.properties（该文件在 .gitignore 里）：

    maa.aiBase=https://dashscope.aliyuncs.com/compatible-mode/v1
    maa.aiKey=sk-xxxx
    maa.aiModel=qwen-vl-max

接口按 OpenAI 兼容格式发（`/chat/completions` + `image_url`），
通义、智谱、豆包的兼容模式都能直接用。

## 为什么不生成 waitTemplate

模板类 op 要一个模板图文件，模型给不出来。所以只能用 OCR 文字类
（tapText / waitText）和盲坐标。提示词里明确禁止了那四个 op，
校验阶段也会拦下来 —— 否则生成的包一跑就是「模板文件不存在」。
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import re
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 允许的 op。模板类四个被排除，理由见文件头。
SIMPLE_OPS = {
    "log", "wait", "tap", "longPress", "swipe",
    "waitText", "tapText", "assertText",
    "stop", "waitColor", "swipeUntil",
    "setVar", "ifExpr", "whileExpr", "readNumber", "readText",
}
BRANCH_OPS = {"repeat", "ifText", "ifCount", "assertCount"}

SYSTEM_PROMPT = """你是 M7A 的任务包生成器。根据用户描述和游戏截图，输出一个任务包的 JSON。

硬性要求：
1. 只输出 JSON 本体，不要解释，不要 markdown 代码块。
2. 所有坐标是 0~1 的归一化值（x 从屏幕左 0 到右 1，y 从上 0 到下 1），对应 1920×1080。
3. 只能用这些 op：
   log / wait / tap / longPress / swipe / waitText / tapText / assertText /
   repeat / ifText / ifCount / assertCount / stop / waitColor / swipeUntil /
   setVar / ifExpr / whileExpr / readNumber / readText
   绝对不要用 waitTemplate / tapTemplate / assertTemplate / ifTemplate，
   它们需要模板图文件，你提供不了，生成的包会直接报错。
4. 点击优先用 tapText（先 OCR 找到文字再点它的中心），比盲坐标稳定。
   只有按钮上没有文字时才用 tap，并在前一步 log 里写明这一下点的是什么。
5. 坐标保留 3 位小数（0.523 而不是 0.5）。按你在图上看到的元素边框中心估算，
   不要凑整 —— 凑整在 1920 宽下偏差接近 100 像素，直接点空。
6. 如果某个坐标是估的、拿不准，在 log 里写「需校准：<元素名>」，
   让人用取坐标工具再量一次。宁可标出来，不要装作很准。
7. 步骤之间要有等待：等界面用 waitText 或 wait（800~1500ms），
   不要连续两三个 tap 挨着点。
8. 顶层字段：schemaVersion=1、id（小写字母数字点和横线）、name、author、
   version、description、minAppVersionCode=11、steps。
9. tapText 找不到文字时不会点，所以关键步骤后面跟一个 assertText 自检。

输出示例：

{
  "schemaVersion": 1,
  "id": "com.example.mail",
  "name": "领取邮件",
  "author": "AI",
  "version": "1.0.0",
  "description": "从大厅打开邮件并领取全部奖励",
  "minAppVersionCode": 11,
  "steps": [
    { "op": "log", "text": "打开邮件" },
    { "op": "tapText", "text": "邮件" },
    { "op": "wait", "ms": 1200 },
    { "op": "tapText", "text": "领取全部" },
    { "op": "wait", "ms": 800 },
    { "op": "tap", "x": 0.5, "y": 0.5 },
    { "op": "log", "text": "关闭弹窗" }
  ]
}"""


def read_props(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        k, v = s.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def encode_image(path: Path, max_width: int = 1280) -> str:
    """缩到合适尺寸再 base64。整幅 1920×1080 PNG 直接发会撑爆请求体。"""
    from PIL import Image

    img = Image.open(path).convert("RGB")
    if img.width > max_width:
        h = round(img.height * max_width / img.width)
        img = img.resize((max_width, h), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=85, optimize=True)
    return base64.b64encode(buf.getvalue()).decode()


def strip_code_fence(text: str) -> str:
    text = text.strip()
    m = re.search(r"```(?:json)?\s*(.+?)\s*```", text, re.S)
    if m:
        return m.group(1)
    # 有时候模型会在 JSON 前后加一句「这是生成的任务包」
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        return text[start:end + 1]
    return text


# 与客户端 CustomTaskPackStore.ID_PATTERN 保持一致：允许大写
ID_RE = re.compile(r"^[A-Za-z0-9._-]{3,64}$")


def validate(pack: dict) -> list[str]:
    """结构校验。只查会让客户端直接拒包的问题。"""
    errs: list[str] = []

    if pack.get("schemaVersion") != 1:
        errs.append("schemaVersion 必须是 1")

    pid = pack.get("id", "")
    if not ID_RE.match(pid):
        errs.append(f"id 不合法：{pid!r}（小写字母数字点和横线，至少 3 位）")

    for k in ("name", "author", "version"):
        if not str(pack.get(k, "")).strip():
            errs.append(f"缺少 {k}")

    if int(pack.get("minAppVersionCode") or 0) < 11:
        errs.append("minAppVersionCode 不能小于 11")

    steps = pack.get("steps")
    if not isinstance(steps, list) or not steps:
        errs.append("steps 不能为空")
        return errs

    def walk(items: list, path: str) -> None:
        for i, st in enumerate(items):
            where = f"{path}[{i}]"
            if not isinstance(st, dict):
                errs.append(f"{where} 不是对象")
                continue
            op = st.get("op")
            if op not in SIMPLE_OPS and op not in BRANCH_OPS:
                errs.append(f"{where} 的 op 不受支持：{op!r}")
            if op in ("tap", "longPress"):
                for axis in ("x", "y"):
                    v = st.get(axis)
                    if not isinstance(v, (int, float)) or not (0 <= v <= 1):
                        errs.append(f"{where} 的 {axis} 不在 0~1：{v!r}")
            if op == "swipe":
                for axis in ("fromX", "fromY", "toX", "toY"):
                    v = st.get(axis)
                    if not isinstance(v, (int, float)) or not (0 <= v <= 1):
                        errs.append(f"{where} 的 {axis} 不在 0~1：{v!r}")
            if op in ("tapText", "waitText", "assertText") and not st.get("text"):
                errs.append(f"{where} 缺少 text")
            for key in ("then", "else", "steps"):
                sub = st.get(key)
                if isinstance(sub, list):
                    walk(sub, f"{where}.{key}")

    walk(steps, "steps")
    return errs


def call_model(base: str, key: str, model: str, desc: str, images: list[Path]) -> str:
    content: list[dict] = [{"type": "text", "text": desc}]
    for p in images:
        content.append({
            "type": "image_url",
            "image_url": {"url": "data:image/jpeg;base64," + encode_image(p)},
        })

    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": content},
        ],
        "temperature": 0.2,
    }
    req = urllib.request.Request(
        base.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode(),
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        data = json.load(r)
    return data["choices"][0]["message"]["content"]


def write_pack(pack: dict, out: Path) -> Path:
    out.parent.mkdir(parents=True, exist_ok=True)
    body = json.dumps(pack, ensure_ascii=False, indent=2)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("task.json", body)
    return out


SAMPLE = {
    "schemaVersion": 1,
    "id": "selftest.mail",
    "name": "领取邮件",
    "author": "AI",
    "version": "1.0.0",
    "description": "从大厅打开邮件并领取全部奖励",
    "minAppVersionCode": 11,
    "steps": [
        {"op": "log", "text": "打开邮件"},
        {"op": "tapText", "text": "邮件"},
        {"op": "wait", "ms": 1200},
        {"op": "tapText", "text": "领取全部"},
        {"op": "wait", "ms": 800},
        {"op": "tap", "x": 0.5, "y": 0.5},
    ],
}


def selftest() -> int:
    errs = validate(SAMPLE)
    if errs:
        print("校验未通过：")
        for e in errs:
            print("  -", e)
        return 1

    out = ROOT / "build" / "ai-selftest.m7atask"
    write_pack(SAMPLE, out)
    with zipfile.ZipFile(out) as z:
        back = json.loads(z.read("task.json").decode("utf-8"))

    ok = back == SAMPLE
    print(f"校验通过，已写出 {out}")
    print(f"回读一致：{ok}，steps {len(back['steps'])} 步，体积 {out.stat().st_size} 字节")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="用多模态模型生成任务包")
    ap.add_argument("--desc", help="这一步要做什么，用中文描述")
    ap.add_argument("--images", nargs="*", default=[], help="游戏截图，可多张")
    ap.add_argument("--out", help="输出 .m7atask 路径，默认 <id>.m7atask")
    ap.add_argument("--selftest", action="store_true", help="不联网，验证校验与打包")
    ap.add_argument("--props", type=Path, default=ROOT / "local.properties")
    ap.add_argument("--keep-raw", action="store_true", help="把模型原始回复存成 .txt")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if not args.desc:
        print("缺少 --desc")
        return 1

    props = read_props(args.props)
    base = props.get("maa.aiBase", "")
    key = props.get("maa.aiKey", "")
    model = props.get("maa.aiModel", "")
    if not (base and key and model):
        print("local.properties 缺少 maa.aiBase / maa.aiKey / maa.aiModel")
        print("接口按 OpenAI 兼容格式调用，例如：")
        print("  maa.aiBase=https://dashscope.aliyuncs.com/compatible-mode/v1")
        print("  maa.aiModel=qwen-vl-max")
        return 1

    images = [Path(p) for p in args.images]
    for p in images:
        if not p.is_file():
            print(f"找不到截图：{p}")
            return 1

    print(f"请求 {model}，{len(images)} 张截图…")
    try:
        raw = call_model(base, key, model, args.desc, images)
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, e.read().decode(errors="ignore")[:400])
        return 1
    except Exception as e:
        print("调用失败：", e)
        return 1

    text = strip_code_fence(raw)
    try:
        pack = json.loads(text)
    except json.JSONDecodeError as e:
        print("模型返回的不是合法 JSON：", e)
        print(text[:600])
        if args.keep_raw:
            Path("ai-raw.txt").write_text(raw, encoding="utf-8")
        return 1

    errs = validate(pack)
    if errs:
        print("校验未通过：")
        for e in errs:
            print("  -", e)
        if args.keep_raw:
            Path("ai-raw.json").write_text(json.dumps(pack, ensure_ascii=False, indent=2), encoding="utf-8")
        return 1

    out = Path(args.out) if args.out else Path(f"{pack['id']}.m7atask")
    write_pack(pack, out)
    print(f"已生成 {out}（{len(pack['steps'])} 步，{out.stat().st_size} 字节）")
    print("导入 App 前先看一眼 steps，盲坐标和等待时间通常要手调。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

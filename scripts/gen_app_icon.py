"""用应用内的三月七图标生成 512×512 商店/站点图标。

为什么要这个脚本：`ic_launcher-web.png` 和 `playstore-icon.png` 一直是上游
MAA 的阿米娅挂画图（没跟着桌面图标换），导致网页和商店显示的仍是 MAA 图标。
桌面上真正生效的是 `mipmap-*/ic_launcher.webp`（三月七）。

源图只有 192px，直接放大到 512 会发虚，所以放大后补一次轻度锐化。

注意用 `ic_launcher.webp`（已经和背景色合成为满幅）而不是 `foreground`
—— 后者是自适应图标的前景层，本身带一圈留白，直接贴到画布上会在四边
留出一圈米色边，看起来像"图里套了个框"。
"""

from PIL import Image, ImageFilter

SRC = "app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp"
SIZE = 512

TARGETS = [
    "app/src/main/res/playstore-icon.png",
    "app/src/main/res/ic_launcher-web.png",
    "docs/icon.png",
    "site/icon.png",
]


def build() -> Image.Image:
    src = Image.open(SRC).convert("RGB")
    scaled = src.resize((SIZE, SIZE), Image.LANCZOS)
    # 放大 2.7 倍后补一点锐度，别让线条糊成一片
    return scaled.filter(ImageFilter.UnsharpMask(radius=1.2, percent=65, threshold=2))


def main() -> None:
    icon = build()
    for path in TARGETS:
        icon.save(path, format="PNG", optimize=True)
        print(f"wrote {path} {icon.size[0]}x{icon.size[1]}")


if __name__ == "__main__":
    main()

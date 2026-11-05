# March7thAssistant Android Local

**Honkai: Star Rail · Fully automated daily tasks on Android**

---

## Current Version

**1.0.4**

---

## What's New

**1.0.4** — the coordinate picker goes landscape, and can now measure swipes and regions

**New · coordinate picker: landscape, swipe, region**

- Opens in landscape
- Three modes at the top:
  - Tap: same as before, one tap gives x and y
  - Swipe: drag a line to get fromX / fromY / toX / toY, matching the swipe parameters
  - Region: drag a rectangle to get [x, y, w, h], matching region
- Output uses the same parameter names as the task file, so it pastes straight in

**New · update prompt can skip a version**

- A "Skip this version" action at the bottom of the dialog
- Once used, that version stops prompting
- Not offered when the update is forced

### Core Features

- **Background execution via virtual display** — the game runs on a separate
  virtual display, so you can keep using your phone
- **Trailblaze Power** — Calyx (Golden/Crimson), Stagnant Shadow, Cavern of
  Corrosion, Ornament Extraction, Echo of War
- **Daily Training** — checkbox-driven: dispatch, photo, synthesizer,
  technique, power consumption, claim rewards
- **Forgotten Hall** — Memory of Chaos, floor by floor
- **Start Game** — launches, skips announcements, enters the lobby
  (local or cloud client)
- **Mail / Redeem codes** — one-tap collection, batch code entry
- **Custom tasks** — third-party task packs

### Before You Start

1. **Grant Shizuku or Root permission**
2. **Disable battery optimization** for this app
3. Make sure the game is logged in and sitting in the **lobby**

### ⚠️ Important

- Automation carries **account risk** — evaluate it yourself
- **Do NOT discuss or promote this project in official communities**
- Third-party task packs come from the internet; verifying their legality is up
  to you, and **we accept no liability for any loss**
- When reporting issues, always attach the **log archive**
  (Settings → Logs → Export)

---

## Troubleshooting

**Export logs**: Settings → Logs → Export Log Archive

The archive contains engine logs, timeout screenshots and device info.
**Without logs it is very hard to diagnose anything.**

**Feedback channels**

- [GitHub Issues](https://github.com/biirekha857-star/March7thAssistantAndroidlocal/issues)
- QQ Group: https://qm.qq.com/q/xG9yvDYFgc
- Telegram: https://t.me/+1NJAK_tgLU81NmFl

---

## License

Built on top of [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow),
licensed under **AGPL-3.0**.

Task logic inspired by
[March7thAssistant](https://github.com/moesnow/March7thAssistant).

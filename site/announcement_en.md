# March7thAssistant Android Local

**Honkai: Star Rail · Fully automated daily tasks on Android**

---

## Current Version

**0.5.6beta**

---

## What's New

### Fixed

- **Upscaled and cropped frames, *Tap to Enter* never appears** — when some
  ROMs (e.g. HONOR MagicOS) move the game onto the 1920×1080 virtual display,
  the system's size-compat scale factor (1.1774) is computed but the window
  size is never shrunk to match. The app therefore renders at full size and the
  system scales it again, zooming the canvas from the top-left corner and
  pushing *Tap to Enter* outside the frame, so OCR reads nothing at all.
  When the launch is still stuck after 51 seconds, the app now performs one
  "home-screen round trip" to force a re-layout; recovery takes ~2.2 seconds
- **Display anomalies are no longer silent** — a notification explains what
  happened when the app self-heals. It is **a notice, not an error**: the task
  keeps running to completion
- **Theme description corrected** — turning off *Monet color* actually uses the
  built-in **gold** theme; the old text said blue. Three unreferenced blue
  color schemes were also removed

### UI

- Home title changed from "M7A" to the full name **March7thAssistant**
- Resource loading hint shortened to "Loading resources"

### Core Features

- **Background execution via virtual display** — the game runs on a separate
  virtual display, so you can keep using your phone
- **Trailblaze Power** — Calyx (Golden/Crimson), Stagnant Shadow, Cavern of
  Corrosion, Ornament Extraction, Echo of War
- **Daily Training** — checkbox-driven: dispatch, photo, synthesizer,
  technique, power consumption, claim rewards
- **Start Game** — launches, skips announcements, enters the lobby
  (local or cloud client)
- **Mail** — one-tap attachment collection

### Before You Start

1. **Grant Shizuku or Root permission**
2. **Disable battery optimization** for this app
3. Make sure the game is logged in and sitting in the **lobby**

### ⚠️ Important

- Automation carries **account risk** — evaluate it yourself
- **Do NOT discuss or promote this project in official communities**
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

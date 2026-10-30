# March7thAssistant Android Local

**Honkai: Star Rail · Fully automated daily tasks on Android**

---

## Current Version

**0.5.5beta**

---

## What's New

### Added

- **Cloud game client support** — the *Start Game* task now lets you pick
  either the local client or Cloud Star Rail
  (`com.miHoYo.cloudgames.hkrpg`). The cloud path is separate:
  tap *Start Game* → queue (does not count against the timeout, 60-minute
  cap) → tap *Tap to Enter* → lobby

### Fixed

- **Truncated frames on tall screens** — the capture layer now reads the
  buffer at its real size and rescales it, fixing the case where the
  virtual display is 1920×1080 but frames arrive as 1920×872, cutting off
  *Tap to Enter* and timing out the launch
- **Cloud stream never tapping *Tap to Enter*** — that step is now
  implemented instead of waiting out the timeout

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

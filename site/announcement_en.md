# March7thAssistant Android Local

**Honkai: Star Rail · Fully automated daily tasks on Android**

---

## Current Version

**1.0.3**

---

## What's New

**1.0.3** — bigger task packs work, and launching no longer grabs the wrong game.

⚠️ **Optional update.** But two of these fix things that look like "the app is broken".

**Fixed · task pack limit 20MB → 500MB**

- Packs over 20MB simply wouldn't install. Raising the number alone wasn't enough — unpacking used to read the whole archive into memory and expand it into a second buffer, so a large pack would kill the app outright (not an error, a crash)
- Unpacking now streams to disk, keeping only a few hundred KB in memory. Validation still happens before anything lands on disk. Per-file limit 6MB → 64MB

**Fixed · launching pulled a game that isn't installed**

- The client choice in "Launch game" can get reset to the default official build, while the phone actually has cloud gaming installed — so after a timeout it would dutifully launch `com.miHoYo.hkrpg`, fail, retry, fail again, and the log only ever said "launch timed out"
- It now cross-checks: if the declared package isn't installed but this session's target is, it uses that and says so in the log

**Changed · screen-anomaly repair threshold 50s → 60s**

- Cloud gaming takes a while to set up its stream; 50s occasionally mistook "still loading" for "the picture is broken" and forced a pointless relayout

**Changed · import notice can be dismissed permanently**

- A "don't show this again" checkbox at the bottom of the disclaimer

**Performance**

- Task execution: 6 spots where the same frame was OCR'd twice, or polling was tighter than the work itself
- UI: touch markers no longer recompose the whole screen; the screensaver no longer recomposes 4× per second

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

# March7thAssistant Android Local

**Honkai: Star Rail · Fully automated daily tasks on Android**

---

## Current Version

**1.0.1**

---

## What's New

💡 **This is an optional update** — nothing forces you to install it. But do consider
it: the fixes below are the kind that make the app look broken. Same signing key,
so you can install over the top — task configs and the redeemed code list are preserved.

### Fixed · Couldn't get back to the lobby after farming

- After tapping "Exit stage" the game shows a loading overlay. The old build pressed
  BACK there to "recover" — but in-game BACK never navigates back, it only raises the
  "exit game" dialog
- Worse: that recovery looked for a button called "Cancel", while the dialog's button
  is "Continue game" — so it **never actually dismissed it**: recognised, never tapped,
  yet treated as handled. Six rounds of nothing
- BACK is gone entirely. When no ✕ is found, it now taps the back arrow at the top-left
- The "Restoring screen…" overlay is waited out instead of being tapped through

### Fixed · Mail task failed right after entering the lobby on cloud gaming

- The first tap on the phone icon got swallowed (cloud input latency). The old logic
  only retried if it detected an obstructing dialog — no dialog meant giving up
- It now retries twice unconditionally, 1.2 s apart

### New · Pick your client (4 options)

- Official, Bilibili (`com.miHoYo.hkrpg.bilibili`), Global
  (`com.HoYoverse.hkrpgoversea`) and cloud gaming — four options laid out flat in the
  "Start game" card
- The duplicate "choose client" step in the activation wizard has been removed

### New · Custom task packs

- **One pack, one card**: install three packs and you get three separate cards, each
  with its own toggle and ordering. Previously everything shared a single node
- Packs appear automatically after import
- **Packs can declare parameters**: toggles, number fields and text fields render right
  in the card. Out-of-range values are clamped to the declared bounds and logged
- **Step and time limits removed** (the old 5000 steps / 20 minutes cap is gone)

### New · Shizuku setup guide

- A video guide is one tap away on the privilege screen and in Settings → Launch

### Other

- The bundled announcement is now the project's own (it used to show the upstream
  Arknights announcement when the remote fetch failed)
- A plain-language appendix was added to the task file specification

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

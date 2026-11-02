# March7thAssistant Android Local

**Honkai: Star Rail · Fully automated daily tasks on Android**

---

## Current Version

**1.0.0**

---

## What's New

⚠️ **This is a forced update**: older builds must install 1.0.0 to keep working.
Same signing key, so you can install over the top — task configs and the redeemed
code list are preserved.

### New · Custom tasks (task packs)

- **Settings → Data → Import task pack** — install a `.m7atask` file written by a
  third-party author and run it like any built-in task
- A task file is a **list of actions**, not code: template matching, OCR, colour
  checks, conditionals, loops, variables and arithmetic — 24 primitives in total
- At runtime it only gets "look at the screen" and "tap the screen": it cannot run
  code, install or launch other apps, access the network, or read files outside
  the pack
- Hard budget: at most 5000 steps / 20 minutes per run, nesting up to 4 levels
- **A disclaimer must be read for 8 seconds before import**: packs come from the
  internet, we do not review their safety or legality, and any loss caused by
  using one is on the user; report malicious content via feedback
- Want to write one? See *Task File Specification* in Settings

### New · Forgotten Hall (Memory of Chaos)

- Walks Mobile → Interastral Guide → Divergent Universe loot → Forgotten Hall →
  Memory of Chaos, reads this cycle's progress, then clears floors one by one
- Floors already at 3 stars are skipped; auto-battle is enabled in combat and
  rewards are claimed after settlement
- **No team selection** — it uses whatever team you already configured

### Redeem codes

- The site list is now a set of **permanent** codes, no expiry to worry about
- One-tap redemption still skips codes you have already claimed

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
- **Interastral Guide occasionally undetectable** — OCR ran only once after the
  screen changed, so a fade-in frame failed the check; now a 2-second observation
  window plus a forced re-check every second
- **Daily Training "activity" never read** — the OCR region only covered the
  flame icon, cutting the number off
- **Dispatch falsely reported as "not dispatched"** — the ~800 ms transition
  after tapping could be caught mid-animation; now retried
- **Trailblaze Power "quick refill" toggle never tapped** — template was 1.4×
  larger than on device and the search region sat on the toggle's right edge;
  replaced with an on-device template and a corrected region

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

# March7thAssistant Android Local

**Honkai: Star Rail · Fully automated daily tasks on Android**

---

## Current Version

**1.0.2**

---

## What's New

⚠️ **This is an optional update** — nothing forces you to install it. But do consider it: every item below can leave a task stuck mid-run.

**Fixed · couldn't get back to the lobby after farming**

- The Guide page gained a new tab on the far left (starter rewards), shifting every tab one slot right. The old build tapped "Survival Index" but landed on "Daily Training" — the screen never changed, three retries all missed
- The ✕ on that same page was never recognised either: its shape differs from both bundled templates, so the log said "no ✕ found" while one was clearly on screen, then tapped a back arrow that this page doesn't have
- Both fixed. Tab positions are now **measured** at runtime: tap a slot, check what it landed on, move to the next if wrong — so a future tab reshuffle won't break it again

**Fixed · exiting a stage tapped "Challenge" and re-entered it**

- Exiting tries "Exit stage" then "Exit challenge". On the preparation screen the "Challenge" button matched the fuzzy search — which re-entered the stage, and the run never recovered
- Fuzzy matching now has a length floor: fragments under 3 characters are no longer accepted as a target

**Fixed · reward popup after claiming dispatch rewards wouldn't close**

- That popup's title differs from the activity one, while the check only looked for "Items obtained" — so it decided there was no popup and never tapped
- The popup then covered the ✕, and the task stalled on "can't close the dispatch page"
- Now several marker texts are accepted (including the "tap blank area" hint), and it taps twice — the reward display has two layers

**Fixed · muting didn't work on cloud gaming**

- Cloud gaming and the official client are two different packages; they shared one, so mute and AppOps were applied to a package that isn't installed

**New · pick coordinates from a screenshot (debug mode)**

- No more measuring by hand: Settings → Logs & diagnostics → enable Debug Mode → "Pick coordinates from a screenshot"
- Tap the spot on the captured frame and it gives you the numbers to paste into `task.json`; pick several in a row and copy them all at once

**Upgrade notes**

- Same signing key, installs over the top — task configs, redeemed codes and imported packs are preserved
- If it still says "update available" after installing, you installed an old file; grab it again from the link below

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

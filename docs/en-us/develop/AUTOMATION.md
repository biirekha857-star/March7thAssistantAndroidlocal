# External Automation Integration

This document describes how to trigger MaaMeow to run a specific task profile from external apps (such as MacroDroid or Tasker) via an Intent / `am` command.

## Use Cases

The built-in scheduled task is skipped while the screen is locked. If you need more flexible scheduling, you can combine it with an external automation tool:

- Pair it with unlock/lock actions to build a fully unattended idle-farming flow
- Time-share a single device between MaaMeow and other automation tasks (e.g. OA check-in)
- Control the exact task start time through a third-party scheduler

Typical flow:

```
Timer fires -> Unlock screen -> am start launches the target profile -> MaaMeow runs the task -> Webhook callback -> Lock screen
```

## Prerequisites

| Requirement | Description |
|------|------|
| Run mode | Must be switched to **Background mode** (Settings -> Run mode). Foreground mode does not support external triggers. |
| App state | The app must keep running in the background, with the Shizuku service connected. |
| Execution privilege | The `am` command requires Root. The Shell action in MacroDroid / Tasker must be configured to run as Root. |

## Step 1: Get the Profile ID

Each task profile has a unique ID, which is used to specify the target profile when triggering externally.

1. Open MaaMeow and go to the **Background Task** page
2. Switch to **Profile management** mode (tap the Profile icon to open the management panel)
3. Find the target profile and tap the **edit (pencil) icon**
4. The bottom of the expanded section shows the profile ID. Tap the copy icon on the right to copy the full ID

A Profile ID is a fixed UUID, for example: `3f4a1b2c-xxxx-xxxx-xxxx-xxxxxxxxxxxx`.

## Step 2: Build the `am` command

**Basic usage**

```bash
am start \
  -a com.march7th.hkrpg.action.LAUNCH_PROFILE \
  -n com.march7th.hkrpg/.MainActivity \
  --es extra_profile_id "YOUR_PROFILE_ID"
```

**Force start** (interrupts the currently running task and switches immediately)

```bash
am start \
  -a com.march7th.hkrpg.action.LAUNCH_PROFILE \
  -n com.march7th.hkrpg/.MainActivity \
  --es extra_profile_id "YOUR_PROFILE_ID" \
  --ez extra_force_start true
```

### Parameters

| Parameter | Required | Description |
|------|------|------|
| `extra_profile_id` | Yes | UUID of the target profile |
| `extra_force_start` | No | `true` interrupts the current task before starting; defaults to `false` |

## MacroDroid Example

1. Create a new macro and add the desired trigger (e.g. Timer, NFC, Incoming call)
2. Action -> **Shell Script**, and enter:

   ```
   am start -a com.march7th.hkrpg.action.LAUNCH_PROFILE -n com.march7th.hkrpg/.MainActivity --es extra_profile_id "YOUR_PROFILE_ID"
   ```

3. Enable **Use Root**
4. To auto-lock the screen after the task finishes, combine it with MaaMeow's **Webhook**: set the Webhook URL in MaaMeow settings, and have MacroDroid listen for that callback to perform the lock action

## Tasker Example

1. Create a new task and add Action -> **Code** -> **Run Shell**
2. Enter the command:

   ```
   am start -a com.march7th.hkrpg.action.LAUNCH_PROFILE -n com.march7th.hkrpg/.MainActivity --es extra_profile_id "YOUR_PROFILE_ID"
   ```

3. Enable **Use Root**

## Notes

- After triggering, there is a **30-second countdown**. During this window you can tap "Run now" in the app to skip the wait, or "Cancel" to abort.
- If the app is not in background mode, the trigger is rejected with a Toast and the mode is not switched automatically.
- If a task is already running and `extra_force_start true` is not set, the new trigger is skipped with a "System busy" notice.
- Wait 1-2 seconds after unlocking the screen before issuing the `am` command, so the app has fully woken up.

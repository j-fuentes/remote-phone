# Desktop mode (Samsung DeX / Android desktop)

With **Settings → Desktop mode** on, *Start Mirroring* no longer mirrors the phone screen. It opens a
separate desktop display and streams that instead:

- **Samsung, One UI 8 or later (Android 16):** Samsung DeX starts on it, as with a monitor.
- **Other phones (Android 10+):** the Android desktop / secondary home screen shows up (on Android 16
  with the desktop experience enabled in developer options, full desktop windowing).

The phone itself stays free and can even be locked while the desktop runs. Clients connect exactly
as before; the phone reports `"desktop": true` in its `info` message.

## Why Shizuku

A display that DeX accepts must be *public*, *trusted* and *show system decorations*. Only privileged
callers (shell, system) may create one, which a normal app is not. [Shizuku](https://shizuku.rikka.app/)
runs a small service of ours as the shell user, started on the phone itself through wireless debugging,
no computer and no root needed. It is only used for desktop mode; normal mirroring keeps working
without it, so the project's "no USB debugging" promise holds for everyone who doesn't opt in.

Setup, once:

1. Install Shizuku and start it (Shizuku app → *Start via Wireless debugging*, pairing on the phone).
   After a reboot it needs starting again.
2. RemotePhone → Settings → *Stream a desktop* → *Grant Shizuku access*.
3. Pick a desktop size (1280×720 to 2560×1440), then *Start Mirroring* as usual.

If Shizuku is not running, not authorized or the system refuses the display, the phone shows why
and falls back to mirroring the phone screen.

## How it works

- `desktop/DesktopUserService` runs in Shizuku's shell process. It creates the virtual display with
  the same flags scrcpy's `--new-display` uses, rendering straight into the app's H.264 encoder
  surface (handed over the binder), and injects input events on that display with
  `InputManager.injectInputEvent` + `InputEvent.setDisplayId`.
- `desktop/DesktopSession` (app side) binds that service and turns client commands into events:
  - Web client in desktop mode: real mouse (hover, left/right/middle buttons, drag), wheel
    (vertical and horizontal), Android keycodes with modifiers for shortcuts, and typed text.
  - Phone-protocol commands (the desktop Python client, the footer buttons) are translated:
    tap → click, swipe → drag, long press → held click, scroll → wheel, back/home/recents/notifications
    → their keys, Ctrl+A/C/X/V → key combos.
- Text the virtual keyboard map cannot type (ñ, accents, emoji) falls back to the accessibility
  service's input connection.
- Audio capture is unchanged (MediaProjection), which is why the screen-capture prompt still appears.

## Web client in desktop mode

Mouse: move = pointer, left/right/middle buttons, wheel and two-finger scroll. Keyboard: printable
characters as text (follows the computer's layout), everything else (arrows, Enter, Esc, F-keys,
Tab, shortcuts with Ctrl/Alt/Shift) as key events; on a Mac, Cmd acts as Ctrl. Ctrl/Cmd+V types the
computer's clipboard. Shortcuts the browser keeps for itself (Cmd+W, Ctrl+T, ...) cannot be sent;
fullscreen (F11) helps with some.

## Known limits / to verify on a device

- Only tested against a mock of the phone side; the Android code is built by CI but not yet run on a phone.
- Shizuku in root mode is untested (the service presents itself as the shell package).
- The phone clipboard is not sent back to the computer in desktop mode.
- The on-screen keyboard may pop up on the desktop display when a text field gets focus.
- Desktop size is fixed per session (no resize from the client yet).

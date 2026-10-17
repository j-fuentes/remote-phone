# RemotePhone for GNOME

A GNOME Shell extension (GNOME 45–49) that keeps **connection profiles** for your phones and puts them in
the top bar. Each profile is a switch: on starts the RemotePhone desktop client connected to that phone,
off (or closing the client window) stops it. Several phones can be open at once.

![menu](#) <!-- top bar: phone icon → profiles with switches, "Open client (scan network)", "Profiles…" -->

## Install

1. Install the desktop client so that `remotephone` is on your PATH (`pip install remote-phone`, or
   `pipx install remote-phone`). Running from source? Set the command in *Profiles…* to e.g.
   `/path/to/remote-phone/venv/bin/remotephone`.
2. `./install.sh`, log out and back in, then `gnome-extensions enable remotephone@remotephone.github.io`.

## Profiles

Top bar icon → *Profiles…*: name, phone IP (or host name), port, stream audio, start fullscreen.
Leave the IP empty to let the client scan the network. Profiles live in GSettings
(`org.gnome.shell.extensions.remotephone`).

A profile starts:

```
remotephone --name "<name>" --port <port> --host <ip> [--audio] [--fullscreen]
```

These options are new in the client (`remotephone --help`): with `--host` it skips the scan and connects
at once, `--audio` turns audio on as soon as the phone allows the computer, `--name` goes into the
window title. The phone still asks *Allow* the first time, as usual.

## Notes

- Clients keep running if the extension is disabled (GNOME also disables extensions on the lock screen),
  but the switches then lose track of them.
- Debug: `journalctl -f -o cat /usr/bin/gnome-shell`; preferences: `gnome-extensions prefs remotephone@remotephone.github.io`.

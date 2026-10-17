#!/bin/sh
# Pack and install the RemotePhone GNOME Shell extension for the current user.
set -e
cd "$(dirname "$0")/remotephone@remotephone.github.io"
gnome-extensions pack --force --out-dir=.. \
    --extra-source=profiles.js --extra-source=icons .
gnome-extensions install --force ../remotephone@remotephone.github.io.shell-extension.zip
echo "Installed. Log out and back in (Wayland) or press Alt+F2, r (X11), then:"
echo "  gnome-extensions enable remotephone@remotephone.github.io"

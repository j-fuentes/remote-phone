#!/usr/bin/env python3
"""
RemotePhone Linux Client
Mirror and control your Android phone from your Linux desktop.
"""

import argparse
import sys
import logging
from pathlib import Path

# Default to WARNING — only show errors and important messages
# Use --verbose or -v flag for debug output
_verbose = '--verbose' in sys.argv or '-v' in sys.argv
if _verbose:
    sys.argv = [a for a in sys.argv if a not in ('--verbose', '-v')]

logging.basicConfig(
    level=logging.DEBUG if _verbose else logging.WARNING,
    format='%(asctime)s [%(name)s] %(levelname)s: %(message)s',
    datefmt='%H:%M:%S'
)

from PyQt6.QtWidgets import QApplication
from PyQt6.QtGui import QFont, QIcon
from remotephone.ui.main_window import MainWindow

ASSETS = Path(__file__).resolve().parent / "assets"


def app_icon() -> QIcon:
    """Multi-resolution window/taskbar icon built from the bundled PNGs."""
    icon = QIcon()
    for size in (64, 128, 256, 512):
        png = ASSETS / f"icon-{size}.png"
        if png.exists():
            icon.addFile(str(png))
    return icon


def parse_args(argv):
    """Optional connection settings, used by launchers such as the GNOME extension's profiles.
    Unknown arguments are left for Qt."""
    parser = argparse.ArgumentParser(prog="remotephone", description="Mirror and control your Android phone.")
    parser.add_argument("--host", help="phone IP or host name: skip the network scan and connect at once")
    parser.add_argument("--port", type=int, default=8765, help="phone port (default 8765)")
    parser.add_argument("--name", help="profile name, shown in the window title")
    parser.add_argument("--audio", action="store_true", help="stream the phone's audio once connected")
    parser.add_argument("--fullscreen", action="store_true", help="start fullscreen")
    return parser.parse_known_args(argv)


def main():
    args, qt_args = parse_args(sys.argv[1:])
    app = QApplication(sys.argv[:1] + qt_args)
    app.setApplicationName("RemotePhone")
    app.setOrganizationName("RemotePhone")  # QSettings: client id and trusted phones
    app.setWindowIcon(app_icon())
    app.setStyle("Fusion")

    # Use a clean sans-serif font
    font = QFont("Inter", 10)
    font.setStyleStrategy(QFont.StyleStrategy.PreferAntialias)
    app.setFont(font)

    window = MainWindow(host=args.host, port=args.port, name=args.name, audio=args.audio)
    if args.fullscreen:
        window.showFullScreen()
    else:
        window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()

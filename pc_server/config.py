"""
WireShare PC Server - Configuration Manager
Handles loading and saving user preferences, network ports, PIN code, and hotkeys.
"""

import json
import os
import random
import socket

CONFIG_FILE = os.path.expanduser("~/.wireshare_config.json")


class WireShareConfig:
    def __init__(self):
        self.ws_port = 8765
        self.udp_port = 8766
        self.discovery_port = 8767
        self.hotkey = "<alt>+x"
        self.pin = self.generate_pin()
        self.sensitivity = 1.0
        self.invert_scroll = False
        self.auto_start_tray = True

        # --- Screen Mirror (Feature 2: full PC screen -> phone) ---
        self.mirror_port = 8770
        self.mirror_fps = 25
        self.mirror_jpeg_quality = 65
        self.mirror_max_width = 1600  # frames are downscaled to this width before encoding

        # --- Phone Mirror (Feature 3: phone screen -> PC) ---
        # PC runs a small WS server here; the phone connects to it as a
        # CLIENT (via MediaProjection capture) and pushes its own screen.
        self.phone_mirror_port = 8771

        # --- Extended Display (Feature 1: phone as second monitor) ---
        # Reserved. See extend_display.py - this requires a Windows Indirect
        # Display Driver that does not exist yet; this port/flag exist so the
        # rest of the app has a stable place to wire it in once that driver
        # is built and installed. See extend_display.py's module docstring.
        self.extend_display_port = 8769
        self.extend_display_enabled = False

        self.load()

    @staticmethod
    def generate_pin() -> str:
        """Generate a random 6-digit numeric PIN for secure pairing."""
        return f"{random.randint(100000, 999999)}"

    @staticmethod
    def get_local_ip() -> str:
        """Find the primary local IPv4 address of this machine."""
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("8.8.8.8", 80))
                return s.getsockname()[0]
        except Exception:
            return "127.0.0.1"

    @staticmethod
    def get_hostname() -> str:
        """Get machine hostname."""
        try:
            return socket.gethostname()
        except Exception:
            return "Desktop-PC"

    def to_dict(self) -> dict:
        return {
            "ws_port": self.ws_port,
            "udp_port": self.udp_port,
            "discovery_port": self.discovery_port,
            "hotkey": self.hotkey,
            "pin": self.pin,
            "sensitivity": self.sensitivity,
            "invert_scroll": self.invert_scroll,
            "auto_start_tray": self.auto_start_tray,
            "mirror_port": self.mirror_port,
            "mirror_fps": self.mirror_fps,
            "mirror_jpeg_quality": self.mirror_jpeg_quality,
            "mirror_max_width": self.mirror_max_width,
            "phone_mirror_port": self.phone_mirror_port,
            "extend_display_port": self.extend_display_port,
            "extend_display_enabled": self.extend_display_enabled,
        }

    def load(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.ws_port = data.get("ws_port", self.ws_port)
                    self.udp_port = data.get("udp_port", self.udp_port)
                    self.discovery_port = data.get("discovery_port", self.discovery_port)
                    self.hotkey = data.get("hotkey", self.hotkey)
                    self.pin = str(data.get("pin", self.pin))
                    self.sensitivity = float(data.get("sensitivity", self.sensitivity))
                    self.invert_scroll = bool(
                        data.get("invert_scroll", self.invert_scroll)
                    )
                    self.auto_start_tray = bool(
                        data.get("auto_start_tray", self.auto_start_tray)
                    )
                    self.mirror_port = int(data.get("mirror_port", self.mirror_port))
                    self.mirror_fps = int(data.get("mirror_fps", self.mirror_fps))
                    self.mirror_jpeg_quality = int(
                        data.get("mirror_jpeg_quality", self.mirror_jpeg_quality)
                    )
                    self.mirror_max_width = int(
                        data.get("mirror_max_width", self.mirror_max_width)
                    )
                    self.phone_mirror_port = int(
                        data.get("phone_mirror_port", self.phone_mirror_port)
                    )
                    self.extend_display_port = int(
                        data.get("extend_display_port", self.extend_display_port)
                    )
                    self.extend_display_enabled = bool(
                        data.get("extend_display_enabled", self.extend_display_enabled)
                    )
            except Exception as e:
                print(f"[Config] Error loading config: {e}")

    def save(self):
        try:
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump(self.to_dict(), f, indent=2)
        except Exception as e:
            print(f"[Config] Error saving config: {e}")


# Global config instance
config = WireShareConfig()

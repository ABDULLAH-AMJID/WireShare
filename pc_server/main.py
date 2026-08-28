"""
WireShare PC Server - Application Entry Point
Supports both GUI mode (PyQt6 System Tray & Dashboard) and CLI/Headless mode (--cli or --headless).
"""

import sys
import time
import argparse
from config import config


def run_cli_mode():
    print("=" * 60)
    print("        WireShare KVM PC Server - Headless CLI Mode")
    print("=" * 60)
    print(
        f"Host PC:   {config.get_hostname()} ({config.get_local_ip()})"
    )
    print(f"PIN Code:  {config.pin}")
    print(
        f"Ports:     WebSocket {config.ws_port} | UDP {config.udp_port} | Discovery {config.discovery_port}"
    )
    print(f"Hotkey:    {config.hotkey} (Default: Alt + X to toggle KVM)")
    print("=" * 60)

    from discovery import DiscoveryServer
    from input_hook import InputHookEngine, Mode
    from server import WireShareNetworkServer
    from screen_mirror import ScreenMirrorServer
    from phone_mirror_server import PhoneMirrorServer

    def on_frame(jpeg_bytes):
        pass  # No GUI to display it in headless mode; still accept the stream.

    def on_status_change(connected, device_name):
        print(f"[CLI-Status] Device Connected: {connected} ({device_name})")

    def on_mode_change(mode):
        print(f"[CLI-Mode] Mode changed to: {mode}")
        # BUGFIX: the CLI entry point never told the phone the mode had changed,
        # so the Android app's cursor/state never activated when toggled from
        # the PC side in headless mode. Mirror what gui.py already does.
        network_server.send_mode_change(mode == Mode.PHONE_CONTROL, mode)

    discovery = DiscoveryServer(port=config.discovery_port)
    network_server = WireShareNetworkServer(
        on_client_status_change=on_status_change,
        on_toggle_mode_request=lambda: input_hook.toggle_mode(),
    )
    input_hook = InputHookEngine(
        on_mouse_event=network_server.send_mouse_motion,
        on_keyboard_event=network_server.send_keyboard_event,
        on_mode_change=on_mode_change,
    )
    screen_mirror = ScreenMirrorServer()
    phone_mirror = PhoneMirrorServer(on_frame=on_frame)

    discovery.start()
    network_server.start()
    screen_mirror.start()
    phone_mirror.start()
    input_hook.start()

    print("\n[CLI] Server is running. Press Ctrl+C to stop.\n")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[CLI] Shutting down...")
    finally:
        input_hook.stop()
        discovery.stop()
        network_server.stop()
        screen_mirror.stop()
        phone_mirror.stop()
        print("[CLI] Shutdown complete.")


def run_gui_mode():
    try:
        from PyQt6.QtWidgets import QApplication
        from gui import WireShareMainWindow
    except ImportError as e:
        print(f"[Error] PyQt6 is required for GUI mode ({e}).")
        print("Falling back to CLI/Headless mode...\n")
        run_cli_mode()
        return

    app = QApplication(sys.argv)
    app.setApplicationName("WireShare PC Server")
    window = WireShareMainWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="WireShare PC KVM Server")
    parser.add_argument(
        "--cli",
        "--headless",
        action="store_true",
        help="Run in headless command-line mode without GUI",
    )
    args, unknown = parser.parse_known_args()

    if args.cli:
        run_cli_mode()
    else:
        run_gui_mode()

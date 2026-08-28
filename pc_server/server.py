"""
WireShare PC Server - Hybrid WebSocket & UDP Server (v2.1 Bidirectional)
1. Reliable background asyncio listening (WindowsSelectorEventLoopPolicy).
2. Full support for UDP Type 0x01 (PC->Phone) and Type 0x02 (Phone->PC Joystick/Buttons/Keyboard).
3. Explicit logging and robust exception handling to prevent server crashes.
"""

import asyncio
import json
import socket
import sys
import threading
from typing import Optional
import websockets
from http import HTTPStatus
from pynput import mouse, keyboard
from config import config
from protocol import (
    pack_mouse_packet,
    unpack_mouse_packet,
    build_auth_response,
    build_mode_change,
    build_keyboard_event,
    PACKET_TYPE_MOUSE,
    PACKET_TYPE_REMOTE_PC_MOUSE,
    BTN_LEFT,
    BTN_RIGHT,
    BTN_MIDDLE,
)


class WireShareNetworkServer:
    def __init__(self, on_client_status_change=None, on_toggle_mode_request=None):
        self.ws_port = config.ws_port
        self.udp_port = config.udp_port
        self.on_client_status_change = on_client_status_change
        # Called when the phone asks to flip PC_DESKTOP <-> PHONE_CONTROL.
        self.on_toggle_mode_request = on_toggle_mode_request

        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.thread: Optional[threading.Thread] = None
        self.udp_socket: Optional[socket.socket] = None
        self.udp_listen_thread: Optional[threading.Thread] = None

        self.active_ws_client = None
        self.client_ip: Optional[str] = None
        self.client_name: str = "No Device Connected"
        self.authenticated = False
        self.udp_seq_num = 0

        self.running = False

        # Windows Input Controllers for Mode 2 (Android controlling PC)
        self.mouse_controller = mouse.Controller()
        self.keyboard_controller = keyboard.Controller()
        self.last_remote_buttons = 0

    def start(self):
        if self.running:
            return
        self.running = True
        self.udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.udp_socket.bind(("0.0.0.0", self.udp_port))

        self.thread = threading.Thread(
            target=self._run_asyncio_loop,
            name="WireShare-AsyncServer",
            daemon=True,
        )
        self.thread.start()

        self.udp_listen_thread = threading.Thread(
            target=self._udp_listen_loop,
            name="WireShare-UDP-Listener",
            daemon=True,
        )
        self.udp_listen_thread.start()
        print(
            f"[NetworkServer v2.1] Started WS on port {self.ws_port} & UDP on port {self.udp_port}"
        )

    def _udp_listen_loop(self):
        while self.running and self.udp_socket:
            try:
                data, addr = self.udp_socket.recvfrom(1024)
                unpacked = unpack_mouse_packet(data)
                if not unpacked:
                    continue
                p_type, seq, dx, dy, buttons, sy, sx = unpacked
                if p_type == PACKET_TYPE_REMOTE_PC_MOUSE:
                    self._inject_remote_pc_mouse(dx, dy, buttons, sy, sx)
            except Exception:
                pass

    def _inject_remote_pc_mouse(
        self, dx: int, dy: int, buttons: int, sy: int, sx: int
    ):
        try:
            if dx != 0 or dy != 0:
                self.mouse_controller.move(dx, dy)

            # Left Button
            left_now = (buttons & BTN_LEFT) != 0
            left_old = (self.last_remote_buttons & BTN_LEFT) != 0
            if left_now and not left_old:
                self.mouse_controller.press(mouse.Button.left)
            elif not left_now and left_old:
                self.mouse_controller.release(mouse.Button.left)

            # Right Button
            right_now = (buttons & BTN_RIGHT) != 0
            right_old = (self.last_remote_buttons & BTN_RIGHT) != 0
            if right_now and not right_old:
                self.mouse_controller.click(mouse.Button.right)

            # Middle Button
            mid_now = (buttons & BTN_MIDDLE) != 0
            mid_old = (self.last_remote_buttons & BTN_MIDDLE) != 0
            if mid_now and not mid_old:
                self.mouse_controller.click(mouse.Button.middle)

            # Scroll
            if sy != 0:
                self.mouse_controller.scroll(0, sy)
            if sx != 0:
                self.mouse_controller.scroll(sx, 0)

            self.last_remote_buttons = buttons
        except Exception as e:
            print(f"[NetworkServer] Error injecting remote PC mouse: {e}")

    def _run_asyncio_loop(self):
        if sys.platform == "win32":
            try:
                asyncio.set_event_loop_policy(
                    asyncio.WindowsSelectorEventLoopPolicy()
                )
            except AttributeError:
                pass

        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)

        async def _async_main():
            try:
                async with websockets.serve(
                    self._handle_ws_client,
                    "0.0.0.0",
                    self.ws_port,
                    ping_interval=20,
                    ping_timeout=20,
                ):
                    print(
                        f"[NetworkServer] ✅ WebSocket Server is LIVE and listening on 0.0.0.0:{self.ws_port}"
                    )
                    await asyncio.Future()
            except Exception as e:
                print(
                    f"[NetworkServer] ERROR starting WebSocket server on port {self.ws_port}: {e}"
                )

        try:
            self.loop.run_until_complete(_async_main())
        except Exception as e:
            if self.running:
                print(f"[NetworkServer] Event loop terminated: {e}")

    async def _handle_ws_client(self, websocket, path=None):
        client_address = websocket.remote_address[0]
        print(f"[NetworkServer] New connection from {client_address}")
        try:
            async for message in websocket:
                await self._process_message(websocket, client_address, message)
        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as e:
            print(f"[NetworkServer] Error handling client {client_address}: {e}")
        finally:
            if self.active_ws_client == websocket:
                self.active_ws_client = None
                self.client_ip = None
                self.authenticated = False
                self.client_name = "No Device Connected"
                print(f"[NetworkServer] Client {client_address} disconnected")
                if self.on_client_status_change:
                    self.on_client_status_change(False, "No Device Connected")

    async def _process_message(
        self, websocket, client_address: str, message: str
    ):
        try:
            data = json.loads(message)
            msg_type = data.get("type")

            if msg_type == "AUTH_REQUEST":
                pin = str(data.get("pin", "")).strip()
                client_name = data.get("client_name", "Android Device")
                if pin == str(config.pin):
                    self.active_ws_client = websocket
                    self.client_ip = client_address
                    self.client_name = client_name
                    self.authenticated = True
                    resp = build_auth_response(
                        True, "session_ok", "Authentication successful"
                    )
                    await websocket.send(resp)
                    print(
                        f"[NetworkServer] Client {client_name} ({client_address}) authenticated!"
                    )
                    if self.on_client_status_change:
                        self.on_client_status_change(True, client_name)
                else:
                    resp = build_auth_response(
                        False, "", "Invalid PIN code. Please check PC GUI."
                    )
                    await websocket.send(resp)

            elif msg_type == "REMOTE_KEYBOARD_EVENT":
                action = data.get("action")
                char = data.get("char", "")
                key_code = data.get("key_code", "")

                # Modifier keys must be held (KEY_DOWN -> press, KEY_UP ->
                # release) so combos like Ctrl+Click or Alt+Tab work from the
                # phone's virtual key overlay - a fixed press+release pulse
                # (like the other keys below) can't represent "held while
                # something else happens".
                modifier_map = {
                    "KEY_CTRL": keyboard.Key.ctrl_l,
                    "KEY_ALT": keyboard.Key.alt_l,
                    "KEY_WIN": keyboard.Key.cmd,
                    "KEY_SHIFT": keyboard.Key.shift_l,
                }
                if key_code in modifier_map:
                    pynput_key = modifier_map[key_code]
                    if action == "KEY_DOWN":
                        self.keyboard_controller.press(pynput_key)
                    elif action == "KEY_UP":
                        self.keyboard_controller.release(pynput_key)
                    return

                if action == "KEY_DOWN":
                    if char:
                        self.keyboard_controller.type(char)
                    elif key_code == "KEY_ENTER":
                        self.keyboard_controller.press(keyboard.Key.enter)
                        self.keyboard_controller.release(keyboard.Key.enter)
                    elif key_code == "KEY_BACKSPACE":
                        self.keyboard_controller.press(keyboard.Key.backspace)
                        self.keyboard_controller.release(keyboard.Key.backspace)
                    elif key_code == "KEY_ESCAPE":
                        self.keyboard_controller.press(keyboard.Key.esc)
                        self.keyboard_controller.release(keyboard.Key.esc)
                    elif key_code == "KEY_TAB":
                        self.keyboard_controller.press(keyboard.Key.tab)
                        self.keyboard_controller.release(keyboard.Key.tab)

            elif msg_type == "TOGGLE_MODE_REQUEST":
                # The phone's own toggle button asked to flip modes. Only the
                # already-authenticated, actively-paired client may do this.
                if self.authenticated and websocket is self.active_ws_client:
                    if self.on_toggle_mode_request:
                        self.on_toggle_mode_request()
                    else:
                        print("[NetworkServer] TOGGLE_MODE_REQUEST received but no handler is wired up.")

            elif msg_type == "HEARTBEAT":
                await websocket.send(
                    json.dumps({"type": "HEARTBEAT_ACK", "status": "OK"})
                )

            elif msg_type == "EXTEND_DISPLAY_REQUEST":
                # Feature 1 (phone as a true wireless extended monitor) needs
                # a Windows Indirect Display Driver installed on this PC to
                # create a real virtual monitor - that driver is a separate,
                # signed component this server cannot install or fake at
                # runtime. Report that honestly instead of silently doing
                # nothing, so the phone app can show a real status.
                await websocket.send(
                    json.dumps(
                        {
                            "type": "EXTEND_DISPLAY_RESPONSE",
                            "available": bool(config.extend_display_enabled),
                            "reason": (
                                "OK"
                                if config.extend_display_enabled
                                else "No Indirect Display Driver installed on this PC yet."
                            ),
                        }
                    )
                )
        except json.JSONDecodeError:
            pass
        except Exception as e:
            print(f"[NetworkServer] Message processing error: {e}")

    # --- STREAMING EVENTS FROM INPUT HOOK (PC -> Android) ---

    def send_mouse_motion(
        self,
        dx: int,
        dy: int,
        buttons: int,
        scroll_y: int = 0,
        scroll_x: int = 0,
    ):
        if not self.authenticated or not self.client_ip or not self.udp_socket:
            return
        self.udp_seq_num = (self.udp_seq_num + 1) & 0xFFFFFFFF
        packet = pack_mouse_packet(
            self.udp_seq_num,
            dx,
            dy,
            buttons,
            scroll_y,
            scroll_x,
            PACKET_TYPE_MOUSE,
        )
        try:
            self.udp_socket.sendto(packet, (self.client_ip, self.udp_port))
        except Exception:
            pass

    def send_keyboard_event(
        self,
        action: str,
        key_code: str,
        char: Optional[str] = None,
        modifiers: Optional[list] = None,
    ):
        if not self.authenticated or not self.active_ws_client:
            return
        payload = build_keyboard_event(action, key_code, char, modifiers)
        self._async_send_ws(payload)

    def send_mode_change(self, active: bool, mode_name: str):
        if not self.authenticated or not self.active_ws_client:
            return
        payload = build_mode_change(active, mode_name)
        self._async_send_ws(payload)

    def _async_send_ws(self, payload: str):
        if self.loop and self.active_ws_client:
            asyncio.run_coroutine_threadsafe(
                self._safe_send(payload), self.loop
            )

    async def _safe_send(self, payload: str):
        try:
            if self.active_ws_client:
                await self.active_ws_client.send(payload)
        except Exception:
            pass

    def stop(self):
        self.running = False
        if self.loop:
            self.loop.call_soon_threadsafe(self.loop.stop)
        if self.udp_socket:
            try:
                self.udp_socket.close()
            except Exception:
                pass
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)
        if self.udp_listen_thread and self.udp_listen_thread.is_alive():
            self.udp_listen_thread.join(timeout=1.0)
        print("[NetworkServer] Stopped.")

"""
WireShare PC Server - Automated Protocol Test Client
Simulates an Android client:
1. Discovers server via UDP broadcast (port 8767)
2. Authenticates via WebSocket using PIN code (port 8765)
3. Listens for binary UDP mouse packets (port 8766) and WebSocket keyboard/mode events
"""

import asyncio
import json
import socket
import sys
import threading
import time
from config import config
from protocol import unpack_mouse_packet


class WireShareTestClient:
    def __init__(self, target_ip: str = "127.0.0.1", pin: str = "123456"):
        self.target_ip = target_ip
        self.pin = pin
        self.ws_port = config.ws_port
        self.udp_port = config.udp_port
        self.udp_sock = None
        self.running = False
        self.udp_thread = None

    def discover_server(self, timeout=2.0) -> bool:
        print("[TestClient] Sending UDP broadcast DISCOVER...")
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            s.settimeout(timeout)
            packet = json.dumps(
                {
                    "type": "DISCOVER",
                    "device": "Test-Client-Simulator",
                    "version": "1.0",
                }
            ).encode("utf-8")
            try:
                s.sendto(packet, ("255.255.255.255", config.discovery_port))
                data, addr = s.recvfrom(1024)
                info = json.loads(data.decode("utf-8"))
                print(
                    f"[TestClient] Discovered Server at {addr[0]}: {info}"
                )
                self.target_ip = addr[0]
                self.ws_port = info.get("ws_port", self.ws_port)
                self.udp_port = info.get("udp_port", self.udp_port)
                return True
            except socket.timeout:
                print(
                    "[TestClient] No broadcast response received. Using target_ip: "
                    + self.target_ip
                )
                return False
            except Exception as e:
                print(f"[TestClient] Discovery error: {e}")
                return False

    def start_udp_listener(self):
        self.running = True
        self.udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # Bind to same UDP port or ephemeral port to receive mouse motion
        try:
            self.udp_sock.bind(("0.0.0.0", self.udp_port))
        except Exception:
            self.udp_sock.bind(("0.0.0.0", 0))
        self.udp_sock.settimeout(1.0)

        self.udp_thread = threading.Thread(
            target=self._udp_receive_loop,
            name="TestClient-UDP",
            daemon=True,
        )
        self.udp_thread.start()
        print(f"[TestClient] Listening for UDP mouse packets...")

    def _udp_receive_loop(self):
        while self.running:
            try:
                data, addr = self.udp_sock.recvfrom(1024)
                unpacked = unpack_mouse_packet(data)
                if unpacked:
                    seq, dx, dy, buttons, sy, sx = unpacked
                    print(
                        f"[UDP-Mouse] seq={seq} | dx={dx}, dy={dy} | btn=0x{buttons:02X} | scroll=({sx},{sy})"
                    )
                else:
                    print(
                        f"[UDP-Mouse] Received invalid packet ({len(data)} bytes)"
                    )
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    print(f"[UDP-Mouse] Error: {e}")

    async def run_ws_session(self):
        import websockets

        uri = f"ws://{self.target_ip}:{self.ws_port}"
        print(f"[TestClient] Connecting to WebSocket {uri}...")
        try:
            async with websockets.connect(uri) as ws:
                # 1. Send AUTH_REQUEST
                auth_req = json.dumps(
                    {
                        "type": "AUTH_REQUEST",
                        "client_name": "Test-Client-Simulator",
                        "pin": self.pin,
                    }
                )
                await ws.send(auth_req)
                print(f"[TestClient] Sent AUTH_REQUEST with PIN={self.pin}")

                # 2. Listen for messages
                async for msg in ws:
                    try:
                        data = json.loads(msg)
                        msg_type = data.get("type")
                        print(
                            f"[WS-Event] Received {msg_type}: {json.dumps(data)}"
                        )

                        if msg_type == "AUTH_RESPONSE":
                            if data.get("success"):
                                print(
                                    "[TestClient] Authentication SUCCESSFUL!"
                                )
                            else:
                                print(
                                    "[TestClient] Authentication FAILED: "
                                    + data.get("message", "")
                                )
                                break
                    except Exception as e:
                        print(f"[WS-Event] Error parsing: {e}")
        except Exception as e:
            print(f"[TestClient] WebSocket connection error: {e}")

    def stop(self):
        self.running = False
        if self.udp_sock:
            try:
                self.udp_sock.close()
            except Exception:
                pass
        if self.udp_thread and self.udp_thread.is_alive():
            self.udp_thread.join(timeout=1.0)
        print("[TestClient] Stopped.")


if __name__ == "__main__":
    pin_code = str(config.pin)
    if len(sys.argv) > 1:
        pin_code = sys.argv[1]

    client = WireShareTestClient(target_ip="127.0.0.1", pin=pin_code)
    client.discover_server(timeout=1.0)
    client.start_udp_listener()

    try:
        asyncio.run(client.run_ws_session())
    except KeyboardInterrupt:
        print("\n[TestClient] Exiting...")
    finally:
        client.stop()

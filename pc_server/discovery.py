"""
WireShare PC Server - Zero-Configuration UDP Discovery Service
Listens on UDP port 8767 for DISCOVER packets from Android clients and replies with SERVER_INFO.
"""

import json
import socket
import threading
from config import config
from protocol import build_server_info


class DiscoveryServer:
    def __init__(self, port: int = 8767):
        self.port = port
        self.sock = None
        self.running = False
        self.thread = None

    def start(self):
        if self.running:
            return
        self.running = True
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
        except AttributeError:
            pass  # SO_REUSEPORT not available on Windows
        self.sock.bind(("0.0.0.0", self.port))
        self.sock.settimeout(1.0)

        self.thread = threading.Thread(
            target=self._listen_loop, name="WireShare-Discovery", daemon=True
        )
        self.thread.start()
        print(f"[Discovery] Listening for UDP broadcast on port {self.port}...")

    def _listen_loop(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(1024)
                message = data.decode("utf-8", errors="ignore").strip()
                if not message:
                    continue
                try:
                    payload = json.loads(message)
                    if payload.get("type") == "DISCOVER":
                        print(
                            f"[Discovery] Received DISCOVER from {addr[0]} ({payload.get('device', 'Unknown')})"
                        )
                        reply = build_server_info(
                            hostname=config.get_hostname(),
                            ip=config.get_local_ip(),
                            ws_port=config.ws_port,
                            udp_port=config.udp_port,
                            status="READY",
                        )
                        self.sock.sendto(reply.encode("utf-8"), addr)
                except json.JSONDecodeError:
                    pass
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    print(f"[Discovery] Error: {e}")

    def stop(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)
        print("[Discovery] Stopped.")

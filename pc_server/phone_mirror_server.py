"""
WireShare PC Server - Phone Screen Receiver (Feature 3: phone -> PC mirror)

This is the reverse direction of screen_mirror.py: there, the PC captures its
own screen and streams it to the phone. Here, the ANDROID APP captures its own
screen (using Android's real, well-supported MediaProjection API - see
PhoneScreenCaptureService.kt) and connects to this server AS A CLIENT to push
its frames. The PC's only job here is to receive and display them.

Same handshake and wire format as screen_mirror.py for consistency: first
message is {"type":"AUTH","pin":"..."}, then binary frames of
[0x01 marker byte][raw JPEG bytes].
"""

import asyncio
import json
import threading
from typing import Callable, Optional

import websockets

from config import config


class PhoneMirrorServer:
    def __init__(self, on_frame: Optional[Callable[[bytes], None]] = None):
        self.port = config.phone_mirror_port
        self.on_frame = on_frame
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.thread: Optional[threading.Thread] = None
        self.running = False
        self.connected = False

    def start(self):
        if self.running:
            return
        self.running = True
        self.thread = threading.Thread(
            target=self._run, name="WireShare-PhoneMirror", daemon=True
        )
        self.thread.start()

    def stop(self):
        self.running = False
        if self.loop:
            self.loop.call_soon_threadsafe(self.loop.stop)
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)

    def _run(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)

        async def _main():
            try:
                async with websockets.serve(
                    self._handle_client, "0.0.0.0", self.port, max_size=None
                ):
                    print(f"[PhoneMirror] Listening on 0.0.0.0:{self.port}")
                    await asyncio.Future()
            except Exception as e:
                print(f"[PhoneMirror] Failed to start on port {self.port}: {e}")

        try:
            self.loop.run_until_complete(_main())
        except Exception as e:
            if self.running:
                print(f"[PhoneMirror] Event loop terminated: {e}")

    async def _handle_client(self, websocket, path=None):
        try:
            first = await asyncio.wait_for(websocket.recv(), timeout=10.0)
            data = json.loads(first)
            if data.get("type") != "AUTH" or str(data.get("pin", "")) != str(config.pin):
                await websocket.close(code=4001, reason="Invalid PIN")
                return
        except Exception:
            await websocket.close(code=4000, reason="Auth timeout/invalid")
            return

        self.connected = True
        print("[PhoneMirror] Phone connected, receiving frames.")
        try:
            async for message in websocket:
                if (
                    isinstance(message, (bytes, bytearray))
                    and len(message) > 1
                    and message[0] == 0x01
                ):
                    if self.on_frame:
                        try:
                            self.on_frame(bytes(message[1:]))
                        except Exception as e:
                            print(f"[PhoneMirror] on_frame callback error: {e}")
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self.connected = False
            print("[PhoneMirror] Phone disconnected.")

"""
WireShare PC Server - Protocol Specification & Serialization
Defines binary UDP formats for low-latency mouse motion (PC->Phone 0x01, Phone->PC 0x02)
and JSON schemas for WebSocket control frames.
"""

import json
import struct
from typing import Optional, Tuple

# UDP 20-byte binary packet format:
# < : Little-endian
# 2s : Magic bytes ('WS') (2 bytes)
# B  : Packet Type (1 byte: 0x01 = PC_TO_PHONE, 0x02 = PHONE_TO_PC)
# I  : Sequence Number (4 bytes uint32)
# i  : Delta X (4 bytes int32)
# i  : Delta Y (4 bytes int32)
# B  : Buttons Bitmask (1 byte uint8: bit 0=Left, bit 1=Right, bit 2=Middle)
# b  : Scroll Y (1 byte int8: -10 to +10)
# b  : Scroll X (1 byte int8: -10 to +10)
# 2x : Padding (2 bytes) -> Total = 20 bytes
UDP_MOUSE_STRUCT = struct.Struct("<2sBIiiBbb2x")
MAGIC_BYTES = b"WS"
PACKET_TYPE_MOUSE = 0x01
PACKET_TYPE_REMOTE_PC_MOUSE = 0x02

# Buttons Bitmask Constants
BTN_LEFT = 0x01
BTN_RIGHT = 0x02
BTN_MIDDLE = 0x04

def pack_mouse_packet(
    seq_num: int,
    dx: int,
    dy: int,
    buttons: int = 0,
    scroll_y: int = 0,
    scroll_x: int = 0,
    packet_type: int = PACKET_TYPE_MOUSE,
) -> bytes:
    """
    Serialize mouse motion and button states into a compact 20-byte UDP binary packet.
    """
    return UDP_MOUSE_STRUCT.pack(
        MAGIC_BYTES,
        packet_type & 0xFF,
        seq_num & 0xFFFFFFFF,
        int(dx),
        int(dy),
        buttons & 0xFF,
        max(-128, min(127, int(scroll_y))),
        max(-128, min(127, int(scroll_x))),
    )

def unpack_mouse_packet(
    data: bytes,
) -> Optional[Tuple[int, int, int, int, int, int, int]]:
    """
    Deserialize a 20-byte UDP binary packet.
    Returns (packet_type, seq_num, dx, dy, buttons, scroll_y, scroll_x) or None if invalid.
    """
    if len(data) != UDP_MOUSE_STRUCT.size:
        return None
    try:
        magic, p_type, seq, dx, dy, buttons, sy, sx = UDP_MOUSE_STRUCT.unpack(
            data
        )
        if magic != MAGIC_BYTES:
            return None
        return p_type, seq, dx, dy, buttons, sy, sx
    except struct.error:
        return None

# --- WebSocket JSON Control Frame Builders ---

def build_auth_response(
    success: bool, session_id: str = "", message: str = ""
) -> str:
    return json.dumps(
        {
            "type": "AUTH_RESPONSE",
            "success": success,
            "session_id": session_id,
            "message": message,
        }
    )

def build_mode_change(active: bool, mode: str = "PHONE_CONTROL") -> str:
    return json.dumps(
        {
            "type": "MODE_CHANGE",
            "active": active,
            "mode": mode,
        }
    )

def build_keyboard_event(
    action: str,
    key_code: str,
    char: Optional[str] = None,
    modifiers: Optional[list] = None,
) -> str:
    payload = {
        "type": "KEYBOARD_EVENT",
        "action": action,
        "key_code": key_code,
        "char": char or "",
        "modifiers": modifiers or [],
    }
    return json.dumps(payload)

def build_clipboard_sync(text: str) -> str:
    return json.dumps(
        {
            "type": "CLIPBOARD_SYNC",
            "text": text,
        }
    )

def build_server_info(
    hostname: str, ip: str, ws_port: int, udp_port: int, status: str = "READY"
) -> str:
    return json.dumps(
        {
            "type": "SERVER_INFO",
            "hostname": hostname,
            "ip": ip,
            "ws_port": ws_port,
            "udp_port": udp_port,
            "status": status,
        }
    )

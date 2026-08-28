"""
WireShare PC Server - Raw Input Mouse Delta Capture (Windows Raw Input API)

WHY THIS FILE EXISTS:
The mouse-freeze feature suppresses the real Windows cursor (via a low-level
mouse hook) so it stays fully still while you're driving the phone cursor.
That part works. The bug was in how movement was measured afterwards:
pynput's low-level-hook callback reports the *absolute screen position*
(MSLLHOOKSTRUCT.pt) that Windows computes for the cursor. Once the cursor is
suppressed, the real cursor position never changes - but Windows keeps
recomputing that "would-be" absolute position relative to the frozen cursor
location on every hook call, instead of accumulating your physical movement.
The result: no matter how far you move a trackpad or a mouse, the reported
position barely changes call to call -> tiny/vibrating deltas.

THE FIX:
Read true RELATIVE deltas directly from the HID device via the Windows Raw
Input API (WM_INPUT / RAWMOUSE.lLastX / lLastY). These numbers come straight
from the mouse/trackpad hardware report and are completely independent of
cursor position, suppression, pointer acceleration, or screen-edge clamping -
exactly what's needed for a frozen-cursor "virtual joystick" style control.
Works identically for a laser/optical mouse and a laptop precision trackpad,
since both report standard relative HID mouse movement on Windows.

ALSO used for scroll/wheel deltas (two-finger trackpad scroll included): the
old code read scroll via pynput's on_scroll(), which - like cursor position -
goes through the same suppressed low-level mouse hook and was unreliable
while suppression is active (observed as scrolling always going the same
direction no matter which way you actually swiped). Raw Input wheel reports
(RI_MOUSE_WHEEL / RI_MOUSE_HWHEEL) are read the same way, independent of hook
suppression, fixing that too.
"""

import ctypes
from ctypes import wintypes
import threading
from typing import Callable, Optional

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

# LRESULT is pointer-sized (signed). ctypes.wintypes has no LRESULT, so define it.
LRESULT = ctypes.c_ssize_t

WM_INPUT = 0x00FF
WM_DESTROY = 0x0002
RIDEV_INPUTSINK = 0x00000100
RID_INPUT = 0x10000003
RIM_TYPEMOUSE = 0
MOUSE_MOVE_ABSOLUTE = 0x01
HWND_MESSAGE = -3

# Wheel button-flag bits inside RAWMOUSE.usButtonFlags, and the "one notch"
# unit Windows uses for usButtonData.
RI_MOUSE_WHEEL = 0x0400
RI_MOUSE_HWHEEL = 0x0800
WHEEL_DELTA = 120

WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wintypes.HWND, ctypes.c_uint, wintypes.WPARAM, wintypes.LPARAM)


class WNDCLASSW(ctypes.Structure):
    _fields_ = [
        ("style", ctypes.c_uint),
        ("lpfnWndProc", WNDPROC),
        ("cbClsExtra", ctypes.c_int),
        ("cbWndExtra", ctypes.c_int),
        ("hInstance", wintypes.HINSTANCE),
        ("hIcon", wintypes.HICON),
        ("hCursor", wintypes.HANDLE),
        ("hbrBackground", wintypes.HBRUSH),
        ("lpszMenuName", wintypes.LPCWSTR),
        ("lpszClassName", wintypes.LPCWSTR),
    ]


class RAWINPUTDEVICE(ctypes.Structure):
    _fields_ = [
        ("usUsagePage", wintypes.USHORT),
        ("usUsage", wintypes.USHORT),
        ("dwFlags", wintypes.DWORD),
        ("hwndTarget", wintypes.HWND),
    ]


class RAWINPUTHEADER(ctypes.Structure):
    _fields_ = [
        ("dwType", wintypes.DWORD),
        ("dwSize", wintypes.DWORD),
        ("hDevice", wintypes.HANDLE),
        ("wParam", wintypes.WPARAM),
    ]


class RAWMOUSE(ctypes.Structure):
    _fields_ = [
        ("usFlags", wintypes.USHORT),
        ("usButtonFlags", wintypes.USHORT),
        ("usButtonData", wintypes.USHORT),
        ("ulRawButtons", wintypes.ULONG),
        ("lLastX", ctypes.c_long),
        ("lLastY", ctypes.c_long),
        ("ulExtraInformation", wintypes.ULONG),
    ]


class RAWINPUT(ctypes.Structure):
    _fields_ = [
        ("header", RAWINPUTHEADER),
        ("mouse", RAWMOUSE),
    ]


# --- Explicit argtypes/restypes -----------------------------------------
# Required for correctness on 64-bit Python: without these, ctypes assumes a
# 32-bit `int` return type for handle-returning functions (CreateWindowExW,
# GetModuleHandleW, DefWindowProcW) and silently TRUNCATES 64-bit pointers,
# which crashes or corrupts the window handle on 64-bit Windows.
kernel32.GetModuleHandleW.restype = wintypes.HMODULE
kernel32.GetModuleHandleW.argtypes = [wintypes.LPCWSTR]

user32.RegisterClassW.restype = wintypes.ATOM if hasattr(wintypes, "ATOM") else ctypes.c_ushort
user32.RegisterClassW.argtypes = [ctypes.POINTER(WNDCLASSW)]

user32.CreateWindowExW.restype = wintypes.HWND
user32.CreateWindowExW.argtypes = [
    wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD,
    ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
    wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, wintypes.LPVOID,
]

user32.DefWindowProcW.restype = LRESULT
user32.DefWindowProcW.argtypes = [wintypes.HWND, ctypes.c_uint, wintypes.WPARAM, wintypes.LPARAM]

user32.RegisterRawInputDevices.restype = wintypes.BOOL
user32.RegisterRawInputDevices.argtypes = [ctypes.POINTER(RAWINPUTDEVICE), wintypes.UINT, wintypes.UINT]

user32.GetRawInputData.restype = wintypes.UINT
user32.GetRawInputData.argtypes = [
    wintypes.HANDLE, wintypes.UINT, ctypes.c_void_p, ctypes.POINTER(wintypes.UINT), wintypes.UINT,
]

user32.GetMessageW.restype = wintypes.BOOL
user32.PostMessageW.restype = wintypes.BOOL
user32.PostMessageW.argtypes = [wintypes.HWND, ctypes.c_uint, wintypes.WPARAM, wintypes.LPARAM]


class RawMouseInput:
    """
    Runs a hidden (message-only) window on a dedicated thread and registers it
    for Windows Raw Input mouse reports. Delivers true relative (dx, dy) HID
    deltas via `on_delta`, unaffected by cursor suppression/freezing, and true
    wheel notch deltas via `on_wheel`, unaffected by the same suppression.
    """

    def __init__(
        self,
        on_delta: Callable[[int, int], None],
        on_wheel: Optional[Callable[[float, float], None]] = None,
    ):
        self.on_delta = on_delta
        self.on_wheel = on_wheel
        self._thread: Optional[threading.Thread] = None
        self._hwnd = None
        self._running = False
        self._ready_event = threading.Event()
        self._class_name = "WireShareRawInputWnd"
        self._wndproc_ref = WNDPROC(self._wnd_proc)  # keep alive - GC would break the callback

    def _wnd_proc(self, hwnd, msg, wparam, lparam):
        if msg == WM_INPUT:
            try:
                self._handle_raw_input(lparam)
            except Exception:
                pass
            return 0
        if msg == WM_DESTROY:
            user32.PostQuitMessage(0)
            return 0
        return user32.DefWindowProcW(hwnd, msg, wparam, lparam)

    def _emit_wheel(self, raw_button_data: int, horizontal: bool):
        if not self.on_wheel:
            return
        # usButtonData holds a signed 16-bit value packed into an unsigned
        # USHORT field - undo the two's-complement wrap manually.
        signed = raw_button_data - 0x10000 if raw_button_data >= 0x8000 else raw_button_data
        notches = signed / WHEEL_DELTA
        if horizontal:
            self.on_wheel(0.0, notches)
        else:
            self.on_wheel(notches, 0.0)

    def _handle_raw_input(self, lparam):
        size = wintypes.UINT(0)
        user32.GetRawInputData(
            lparam, RID_INPUT, None, ctypes.byref(size), ctypes.sizeof(RAWINPUTHEADER)
        )
        if size.value == 0:
            return
        buf = ctypes.create_string_buffer(size.value)
        read = user32.GetRawInputData(
            lparam, RID_INPUT, buf, ctypes.byref(size), ctypes.sizeof(RAWINPUTHEADER)
        )
        if read != size.value:
            return
        raw = ctypes.cast(buf, ctypes.POINTER(RAWINPUT)).contents
        if raw.header.dwType != RIM_TYPEMOUSE:
            return

        flags = raw.mouse.usButtonFlags
        # Wheel events (including two-finger trackpad scroll, which Windows'
        # Precision Touchpad driver synthesizes as standard wheel reports)
        # arrive independently of movement, and are read the exact same way
        # regardless of cursor suppression - this is what replaces the old
        # pynput on_scroll() path, which was unreliable while the low-level
        # mouse hook is suppressed (the same class of bug fixed for movement).
        if flags & RI_MOUSE_WHEEL:
            self._emit_wheel(raw.mouse.usButtonData, horizontal=False)
        if flags & RI_MOUSE_HWHEEL:
            self._emit_wheel(raw.mouse.usButtonData, horizontal=True)

        if raw.mouse.usFlags & MOUSE_MOVE_ABSOLUTE:
            # Absolute-mode devices (graphics tablets, RDP virtual mice) aren't
            # what this relative-delta path is for; ignore rather than send
            # garbage deltas that would look like more "vibration".
            return

        dx = raw.mouse.lLastX
        dy = raw.mouse.lLastY
        if dx != 0 or dy != 0:
            self.on_delta(dx, dy)

    def _run(self):
        hinstance = kernel32.GetModuleHandleW(None)

        wndclass = WNDCLASSW()
        wndclass.style = 0
        wndclass.lpfnWndProc = self._wndproc_ref
        wndclass.cbClsExtra = 0
        wndclass.cbWndExtra = 0
        wndclass.hInstance = hinstance
        wndclass.hIcon = None
        wndclass.hCursor = None
        wndclass.hbrBackground = None
        wndclass.lpszMenuName = None
        wndclass.lpszClassName = self._class_name

        user32.RegisterClassW(ctypes.byref(wndclass))

        self._hwnd = user32.CreateWindowExW(
            0, self._class_name, "WireShareRawInput", 0,
            0, 0, 0, 0, HWND_MESSAGE, None, hinstance, None,
        )

        if not self._hwnd:
            print("[RawMouseInput] Failed to create message-only window; raw mouse capture disabled.")
            self._ready_event.set()
            return

        rid = RAWINPUTDEVICE()
        rid.usUsagePage = 0x01  # Generic desktop controls
        rid.usUsage = 0x02      # Mouse
        rid.dwFlags = RIDEV_INPUTSINK  # receive input even while not focused
        rid.hwndTarget = self._hwnd

        if not user32.RegisterRawInputDevices(ctypes.byref(rid), 1, ctypes.sizeof(RAWINPUTDEVICE)):
            print("[RawMouseInput] Failed to register for raw input; mouse movement will not work.")

        self._ready_event.set()

        msg = wintypes.MSG()
        while self._running:
            ret = user32.GetMessageW(ctypes.byref(msg), None, 0, 0)
            if ret == 0 or ret == -1:
                break
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))

    def start(self):
        if self._running:
            return
        self._running = True
        self._ready_event.clear()
        self._thread = threading.Thread(
            target=self._run, name="WireShare-RawInput", daemon=True
        )
        self._thread.start()
        self._ready_event.wait(timeout=2.0)

    def stop(self):
        if not self._running:
            return
        self._running = False
        if self._hwnd:
            try:
                user32.PostMessageW(self._hwnd, WM_DESTROY, 0, 0)
            except Exception:
                pass
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.0)
        self._hwnd = None

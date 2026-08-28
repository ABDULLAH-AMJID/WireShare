"""
WireShare PC Server - Low-Level Windows Input Hook & Trapping Engine (v3.1)

v3.0 fixed the cursor never fully freezing by switching the mouse listener to
suppress=True (blocking Windows from ever seeing the movement/clicks).

v3.1 fixes the follow-on bug that caused: physically moving a mouse/trackpad
barely moved the phone cursor, or made it vibrate/creep in place. Movement was
still being computed from pynput's on_move(x, y), which reports the low-level
hook's *absolute* cursor position. Once the real cursor is suppressed/frozen,
Windows recomputes that absolute position relative to the frozen cursor on
every call instead of accumulating physical movement - so deltas stayed tiny
and jittery no matter how far you moved the mouse/trackpad.

Movement is now read from raw_input.RawMouseInput, which taps the Windows Raw
Input API (WM_INPUT) for true relative HID deltas straight from the hardware -
entirely independent of cursor position/suppression. The pynput mouse listener
is still used (suppress=True) purely to freeze the real cursor and to report
button presses/scroll, which don't have this position-dependent problem.
"""

import time
import threading
from typing import Callable, Optional
from pynput import keyboard, mouse
from config import config
from protocol import BTN_LEFT, BTN_RIGHT, BTN_MIDDLE
from raw_input import RawMouseInput


class Mode:
    PC_DESKTOP = "PC_DESKTOP"
    PHONE_CONTROL = "PHONE_CONTROL"


class InputHookEngine:
    def __init__(
        self,
        on_mouse_event: Callable[[int, int, int, int, int], None],
        on_keyboard_event: Callable[[str, str, Optional[str], list], None],
        on_mode_change: Callable[[str], None],
    ):
        self.mode = Mode.PC_DESKTOP
        self.on_mouse_event = on_mouse_event
        self.on_keyboard_event = on_keyboard_event
        self.on_mode_change = on_mode_change

        self.keyboard_listener: Optional[keyboard.Listener] = None
        self.mouse_listener: Optional[mouse.Listener] = None

        # True relative HID mouse deltas (mouse.lLastX/lLastY via WM_INPUT) and
        # true wheel notches (RI_MOUSE_WHEEL/HWHEEL) - see raw_input.py for why
        # this replaces position- and pynput-scroll-based tracking.
        self.raw_mouse = RawMouseInput(
            on_delta=self._on_raw_mouse_delta,
            on_wheel=self._on_raw_wheel,
        )

        self.buttons_state = 0
        self.active_modifiers = set()

        self.esc_press_times = []
        self.esc_window = 1.5
        self.esc_required = 3

        self.lock = threading.RLock()

    def set_mode(self, new_mode: str):
        with self.lock:
            if self.mode == new_mode:
                return
            self.mode = new_mode
            print(f"[InputHook] Switched to mode: {self.mode}")

            # Recreate the listeners *synchronously* with the correct suppress
            # flags for the new mode. This is fast (a few ms) and removes the
            # race window that existed with the old async-thread approach.
            self._restart_listeners(new_mode == Mode.PHONE_CONTROL)

            if new_mode == Mode.PHONE_CONTROL:
                self.raw_mouse.start()
                print("[InputHook] PC mouse & keyboard fully suppressed - Windows cursor is frozen.")
                print("[InputHook] Raw HID mouse capture active - phone cursor driven by real device deltas.")
            else:
                self.raw_mouse.stop()
                print("[InputHook] PC mouse & keyboard control restored.")

        if self.on_mode_change:
            self.on_mode_change(new_mode)

    def toggle_mode(self):
        new_mode = (
            Mode.PHONE_CONTROL
            if self.mode == Mode.PC_DESKTOP
            else Mode.PC_DESKTOP
        )
        self.set_mode(new_mode)

    def _restart_listeners(self, phone_mode: bool):
        try:
            if self.keyboard_listener:
                self.keyboard_listener.stop()
            if self.mouse_listener:
                self.mouse_listener.stop()
        except Exception:
            pass

        # KEYBOARD: suppress=True in PHONE_CONTROL mode so keystrokes are trapped
        # and don't type into whatever window has focus on Windows.
        self.keyboard_listener = keyboard.Listener(
            on_press=self.on_key_press,
            on_release=self.on_key_release,
            suppress=phone_mode,
        )
        # MOUSE: suppress=True in PHONE_CONTROL mode -> the real Windows cursor
        # never receives the move/click/scroll, so it is fully frozen at exactly
        # wherever it was when the mode switched. NOTE: on_move and on_scroll
        # are deliberately NOT wired up here anymore - movement and wheel/scroll
        # both come from RawMouseInput instead (see class docstring). Suppression
        # still applies to movement/clicks/scroll regardless of which callbacks
        # are registered; only click state is read from this listener now.
        self.mouse_listener = mouse.Listener(
            on_click=self.on_mouse_click,
            suppress=phone_mode,
        )
        self.keyboard_listener.start()
        self.mouse_listener.start()

    # --- KEYBOARD HANDLERS ---

    def _check_emergency_esc(self, key):
        if key == keyboard.Key.esc:
            now = time.time()
            self.esc_press_times.append(now)
            self.esc_press_times = [
                t for t in self.esc_press_times if now - t <= self.esc_window
            ]
            if len(self.esc_press_times) >= self.esc_required:
                print("[InputHook] Emergency ESC sequence triggered! Returning to PC_DESKTOP.")
                self.esc_press_times.clear()
                self.set_mode(Mode.PC_DESKTOP)
                return True
        return False

    def _update_modifiers(self, key, is_down: bool):
        mod_map = {
            keyboard.Key.ctrl: "CTRL",
            keyboard.Key.ctrl_l: "CTRL",
            keyboard.Key.ctrl_r: "CTRL",
            keyboard.Key.alt: "ALT",
            keyboard.Key.alt_l: "ALT",
            keyboard.Key.alt_r: "ALT",
            keyboard.Key.shift: "SHIFT",
            keyboard.Key.shift_l: "SHIFT",
            keyboard.Key.shift_r: "SHIFT",
        }
        mod = mod_map.get(key)
        if mod:
            if is_down:
                self.active_modifiers.add(mod)
            else:
                self.active_modifiers.discard(mod)

    def _is_toggle_chord(self, key) -> bool:
        try:
            if key == keyboard.Key.f8:
                return True
            if "ALT" in self.active_modifiers:
                if hasattr(key, "char") and key.char and key.char.lower() == "x":
                    return True
                if hasattr(key, "name") and key.name and key.name.lower() == "x":
                    return True
                if hasattr(key, "vk") and (key.vk == 88 or key.vk == 120):
                    return True
        except Exception:
            pass
        return False

    def on_key_press(self, key):
        self._update_modifiers(key, True)
        self._check_emergency_esc(key)

        if self._is_toggle_chord(key):
            self.toggle_mode()
            return

        if self.mode == Mode.PHONE_CONTROL:
            key_code = (
                f"KEY_{key.name.upper()}"
                if hasattr(key, "name") and key.name
                else "KEY_CHAR"
            )
            char = (
                key.char
                if hasattr(key, "char") and key.char and key.char.isprintable()
                else ""
            )
            if key == keyboard.Key.space:
                char = " "
            elif key == keyboard.Key.enter:
                char = "\n"
                key_code = "KEY_ENTER"
            elif key == keyboard.Key.backspace:
                key_code = "KEY_BACKSPACE"

            self.on_keyboard_event(
                "KEY_DOWN", key_code, char, list(self.active_modifiers)
            )

    def on_key_release(self, key):
        self._update_modifiers(key, False)
        if self.mode == Mode.PHONE_CONTROL:
            key_code = (
                f"KEY_{key.name.upper()}"
                if hasattr(key, "name") and key.name
                else "KEY_CHAR"
            )
            char = (
                key.char
                if hasattr(key, "char") and key.char and key.char.isprintable()
                else ""
            )
            self.on_keyboard_event(
                "KEY_UP", key_code, char, list(self.active_modifiers)
            )

    # --- MOUSE HANDLERS ---

    def _on_raw_mouse_delta(self, dx: int, dy: int):
        """True relative HID delta from RawMouseInput - see class docstring."""
        if self.mode != Mode.PHONE_CONTROL:
            return

        scaled_dx = int(dx * config.sensitivity)
        scaled_dy = int(dy * config.sensitivity)

        if scaled_dx != 0 or scaled_dy != 0:
            self.on_mouse_event(scaled_dx, scaled_dy, self.buttons_state, 0, 0)

    def on_mouse_click(self, x, y, button, pressed):
        btn_mask = 0
        if button == mouse.Button.left:
            btn_mask = BTN_LEFT
        elif button == mouse.Button.right:
            btn_mask = BTN_RIGHT
        elif button == mouse.Button.middle:
            btn_mask = BTN_MIDDLE

        if pressed:
            self.buttons_state |= btn_mask
        else:
            self.buttons_state &= ~btn_mask

        if self.mode == Mode.PHONE_CONTROL:
            self.on_mouse_event(0, 0, self.buttons_state, 0, 0)

    def _on_raw_wheel(self, notches_y: float, notches_x: float):
        """True wheel/scroll notches from RawMouseInput - see class docstring."""
        if self.mode != Mode.PHONE_CONTROL:
            return

        scroll_y = notches_y * (-1 if config.invert_scroll else 1)
        scroll_x = notches_x

        scroll_y_i = int(round(scroll_y))
        scroll_x_i = int(round(scroll_x))

        if scroll_y_i != 0 or scroll_x_i != 0:
            self.on_mouse_event(0, 0, self.buttons_state, scroll_y_i, scroll_x_i)

    def start(self):
        self._restart_listeners(phone_mode=False)
        print("[InputHook] Keyboard and Mouse hooks started (Alt+X or F8 to toggle).")

    def stop(self):
        self.mode = Mode.PC_DESKTOP
        self.raw_mouse.stop()
        if self.keyboard_listener:
            self.keyboard_listener.stop()
        if self.mouse_listener:
            self.mouse_listener.stop()
        print("[InputHook] Stopped.")


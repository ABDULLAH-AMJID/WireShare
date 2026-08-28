# WireShare: Software KVM & Remote Input Architecture
## Complete Research, Protocol Design & System Architecture

**Document Version:** 1.0.0  
**Date:** August 11, 2026  
**Author:** Senior Full-Stack & System Architect  
**Target Environment:** Windows 10/11 (PC Server) & Android 10+ (Mobile Client)

---

## Table of Contents
1. [Executive Summary & Problem Statement](#1-executive-summary--problem-statement)
2. [Research & Competitive Analysis](#2-research--competitive-analysis)
3. [System Architecture Overview](#3-system-architecture-overview)
4. [Network Protocol Specification (Hybrid WebSocket + UDP)](#4-network-protocol-specification-hybrid-websocket--udp)
5. [Windows Server (`WireShare-PC`) Architecture & Input Trapping](#5-windows-server-wireshare-pc-architecture--input-trapping)
6. [Android Client (`WireShare-Android`) Architecture & Accessibility Injection](#6-android-client-wireshare-android-architecture--accessibility-injection)
7. [Latency Budget & Performance Analysis](#7-latency-budget--performance-analysis)
8. [Security & Authentication Model](#8-security--authentication-model)

---

## 1. Executive Summary & Problem Statement

### 1.1 The Problem
Power users who work on a desktop PC with wired mechanical keyboards and high-precision mice frequently experience **context-switching friction** when interacting with their mobile phones. Whether checking a two-factor authentication code, replying to a message, or querying a search engine on the phone, the user must physically release the desktop peripherals, pick up the mobile device, and use touchscreen typing.

### 1.2 The WireShare Solution
**WireShare** is an enterprise-grade, zero-hardware-required **Software KVM over Wi-Fi** solution. When the user presses a configurable hotkey (default: `Alt + X`) on their Windows PC:
1. **Input Suppression:** The Windows PC server intercepts and traps all subsequent keyboard and mouse events so they are not processed by the local Windows desktop.
2. **Network Streaming:** Mouse relative motion (`dx, dy`), scroll deltas, and keyboard key presses are streamed in real time over the local Wi-Fi network to the Android phone.
3. **Android Injection:** An Android Accessibility Service renders a smooth, GPU-accelerated floating mouse cursor overlay and converts incoming coordinates and keystrokes into native Android taps, drags, scrolls, and text input.
4. **Seamless Return:** Pressing `Alt + X` again immediately restores local PC control.

---

## 2. Research & Competitive Analysis

### 2.1 Why Existing Tools Fall Short

| Feature / Metric | Synergy / Barrier / Input Leap | DeskDock | Bluetooth HID Dongle / ESP32 | WireShare (Our Solution) |
| :--- | :--- | :--- | :--- | :--- |
| **Primary Target** | PC-to-PC / Mac / Linux | PC-to-Android | PC-to-Any Bluetooth Host | **Windows PC-to-Android** |
| **Connection Type** | Wi-Fi / LAN | **USB Cable (ADB Debugging)** | Bluetooth Low Energy / HID | **Wi-Fi / LAN (Zero Config)** |
| **Android Hardware Req.** | None (Poor Android support) | USB Cable + ADB enabled | Separate Bluetooth hardware | **Standard Wi-Fi 5/6** |
| **Root Required?** | N/A | No (uses ADB injection) | No | **No (uses Accessibility Service)** |
| **Mouse Cursor Quality** | Native OS cursor (PC only) | Custom ADB canvas | Native Bluetooth cursor | **Custom Material 3 Overlay View** |
| **Keyboard Injection** | Complete | Complete | Complete | **Complete (Keystroke + IME text)** |
| **Typical Latency** | 3 - 10 ms | 2 - 5 ms (Wired) | 15 - 35 ms | **4 - 8 ms (Hybrid UDP/WS)** |

### 2.2 Key Technical Breakthroughs in WireShare
- **Hybrid UDP/WebSocket Transport:** Instead of sending high-frequency mouse motion over TCP (which suffers from head-of-line blocking and Nagle's algorithm delays), WireShare transmits mouse movement via **stateless UDP datagrams** at up to **120 Hz**, while reserving **WebSockets (TCP)** for reliable keyboard events, PIN pairing, and system control.
- **Rootless Accessibility Injection:** By utilizing Android's `AccessibilityService.dispatchGesture()`, WireShare simulates pixel-perfect taps, long-presses, and smooth inertia scrolling without requiring root access or USB/ADB connections.

---

## 3. System Architecture Overview

```
       [ Windows PC Server (WireShare-PC) ]
   +----------------------------------------------+
   |  PyQt6 GUI System Tray & Control Dashboard   |
   +----------------------------------------------+
   |  Input Capture & Trap Engine (pynput/Win32)  |
   |   - Captures Mouse dx, dy, Scroll, Buttons   |
   |   - Captures Keyboard KeyDown / KeyUp        |
   |   - Suppresses Local PC Input in Phone Mode   |
   +----------------------------------------------+
          |                         |
     WebSocket (8765)           UDP (8766)
     [Reliable Events]      [Mouse Motion (120Hz)]
          |                         |
          +------------+------------+
                       |
               Wi-Fi 5 / 6 Network
                       |
          +------------+------------+
          |                         |
     WebSocket Client           UDP Listener
     [JSON Commands]        [Binary Parsing]
          |                         |
   +----------------------------------------------+
   |   Android Client (WireShare-Android App)     |
   +----------------------------------------------+
   |  WireShareAccessibilityService               |
   |   - FloatingCursorView (TYPE_OVERLAY)        |
   |   - GestureDispatcher (Taps, Drags, Swipes)  |
   |   - KeyDispatcher (Text, Back, Home, Recents)|
   +----------------------------------------------+
```

---

## 4. Network Protocol Specification (Hybrid WebSocket + UDP)

### 4.1 Discovery Protocol (UDP Broadcast - Port 8767)
When the Android app opens, it broadcasts a UDP discovery packet to `255.255.255.255:8767`.
- **Request:** `{"type": "DISCOVER", "device": "Android-Client", "version": "1.0"}`
- **Server Response:** `{"type": "SERVER_INFO", "hostname": "DESKTOP-PC", "ip": "192.168.1.50", "ws_port": 8765, "udp_port": 8766, "status": "READY"}`

### 4.2 Control & Keyboard Protocol (WebSocket - Port 8765)
All stateful commands, authentication, keyboard keystrokes, and clipboard events are exchanged via WebSockets as JSON payloads.

#### 1. Authentication Handshake
```json
// Client -> Server
{"type": "AUTH_REQUEST", "client_name": "Pixel 8 Pro", "pin": "849201"}

// Server -> Client
{"type": "AUTH_RESPONSE", "success": true, "session_id": "ws_session_90a82b", "server_name": "DESKTOP-PC"}
```

#### 2. Mode Change Notification
```json
// Server -> Client (when user presses Alt + X)
{"type": "MODE_CHANGE", "active": true, "mode": "PHONE_CONTROL"}
```

#### 3. Keyboard Event Frame
```json
// Server -> Client
{
  "type": "KEYBOARD_EVENT",
  "action": "KEY_DOWN",     // "KEY_DOWN", "KEY_UP", or "TEXT_TYPE"
  "key_code": "KEY_ENTER",  // Standardized key code
  "char": "\n",             // Printable character if applicable
  "modifiers": ["CTRL"]     // List of active modifiers: CTRL, ALT, SHIFT
}
```

### 4.3 Mouse Motion Protocol (UDP - Port 8766)
To minimize payload overhead and latency, mouse movement is sent as a compact **20-byte binary structure** over UDP:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Magic Header (0x57, 0x53)       |     Packet Type (0x01)|
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Sequence Number                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     Delta X (int32 - pixels)                  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     Delta Y (int32 - pixels)                  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   Buttons Bitmask   |  Scroll Y (int8)  |  Scroll X (int8)    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```
- **Magic Header (`0x5753`):** ASCII `'W'`, `'S'` to validate WireShare packet.
- **Buttons Bitmask:** `0x01` = Left Click, `0x02` = Right Click, `0x04` = Middle Click.
- **Scroll Y / X:** Signed 8-bit integer representing scroll wheel detents (`-1`, `0`, `+1`).

---

## 5. Windows Server (`WireShare-PC`) Architecture & Input Trapping

### 5.1 Low-Level Windows Hooks (`pynput`)
The Windows server utilizes `pynput` (which wraps Win32 `SetWindowsHookExW` for keyboard (`WH_KEYBOARD_LL`) and mouse (`WH_MOUSE_LL`)).
- **Normal Mode:** Hooks are passive observers. They monitor for the hotkey chord (`Alt + X`).
- **Phone Control Mode:**
  - The callback function for `WH_MOUSE_LL` returns `1` (suppress) for all mouse movements and clicks, preventing Windows from moving the desktop cursor.
  - Instead, the delta between the current raw coordinates and the anchor coordinates is calculated and dispatched via UDP.
  - Safety Dead-Man Switch: Pressing `ESC` three times rapidly or `Ctrl + Alt + Del` immediately releases all hooks and returns control to Windows.

---

## 6. Android Client (`WireShare-Android`) Architecture & Accessibility Injection

### 6.1 Floating Cursor View (`FloatingCursorView`)
- Added to the Android WindowManager using `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`.
- Uses hardware-accelerated Canvas rendering to draw a crisp, high-DPI desktop-style pointer.
- Maintains internal floating coordinates `(cursorX, cursorY)` clamped to screen boundaries:
  $$\text{cursorX}_{\text{new}} = \text{clamp}(0, \text{screenWidth}, \text{cursorX} + (\Delta X \times \text{sensitivity}))$$

### 6.2 Gesture Dispatcher
- **Left Click:** Creates a `GestureDescription` with a `StrokeDescription` from `(cursorX, cursorY)` to `(cursorX, cursorY)` with a duration of `30ms`.
- **Drag & Drop:** When Left Button is held down (`KEY_DOWN` with `0x01`), initiates a long-press stroke that continues as the cursor moves until `KEY_UP`.
- **Right Click:** Triggers `AccessibilityService.performGlobalAction(GLOBAL_ACTION_BACK)` or pops up a quick context menu.
- **Middle Click:** Triggers `AccessibilityService.performGlobalAction(GLOBAL_ACTION_HOME)`.
- **Scroll Wheel:** Generates a vertical/horizontal swipe gesture in the direction opposite to wheel motion (natural scrolling).

---

## 7. Latency Budget & Performance Analysis

| Processing Stage | Target Time (ms) | Description |
| :--- | :--- | :--- |
| **PC Input Hook Capture** | 0.3 ms | Low-level Win32 callback firing on mouse interrupt |
| **UDP Serialization** | 0.1 ms | Struct packing into 20-byte binary frame |
| **Wi-Fi Network Transit** | 2.5 - 4.0 ms | Typical local 5GHz Wi-Fi 6 round-trip |
| **Android UDP Read & Unpack**| 0.2 ms | NIO DatagramSocket non-blocking receive |
| **Overlay Position Update** | 1.0 ms | Choreographer VSYNC animation update |
| **Total Glass-to-Glass** | **4.1 - 5.6 ms** | **Perceptible as instantaneous (virtually zero lag)** |

---

## 8. Security & Authentication Model

1. **Local Subnet Isolation:** WireShare binds exclusively to local RFC1918 IPv4 interfaces (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`).
2. **PIN-Based Handshake:** The server displays a randomly generated 6-digit PIN on startup. The Android client must present this PIN over WebSocket before any input events are accepted.
3. **Session Revocation:** Disconnecting the Android client or exiting the server GUI immediately purges the active session.

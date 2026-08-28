# WireShare: Software KVM & Keyboard/Mouse Sharing over Wi-Fi
**Seamlessly share your wired PC keyboard & high-precision mouse with your Android phone over your local Wi-Fi network.**

---

## 📖 Complete Documentation & Research

- **Research & Architectural Specification:** See [`docs/ARCHITECTURE_AND_RESEARCH.md`](./docs/ARCHITECTURE_AND_RESEARCH.md) for full state-of-the-art analysis, network protocol design, latency budget analysis, and security model.
- **Interactive Visual Architecture Viewer:** Open [`docs/architecture_viewer.html`](./docs/architecture_viewer.html) to explore interactive diagrams, binary UDP layouts, and glass-to-glass latency charts.

---

## 🏛️ System Architecture

```
       [ Windows PC Server (WireShare-PC) ]
   +----------------------------------------------+
   |  PyQt6 GUI System Tray & Control Dashboard   |
   |  pynput Low-Level Win32 Hook Trapping Engine |
   +----------------------------------------------+
          |                         |
     WebSocket (8765)           UDP (8766)
     [Reliable PIN Auth]    [Mouse Motion 120Hz]
          |                         |
          +------------+------------+
                       |
               Wi-Fi 5 / 6 Network
                       |
          +------------+------------+
          |                         |
   +----------------------------------------------+
   |   Android Client (WireShare-Android App)     |
   |  - FloatingCursorView (Hardware-accelerated) |
   |  - WireShareAccessibilityService             |
   +----------------------------------------------+
```

---

## 🚀 Getting Started: Windows PC Server (`WireShare-PC`)

### 1. Requirements & Installation
The server requires Python 3.9+ and Windows 10/11.
```bash
cd /home/user/WireShare/pc_server
pip install -r requirements.txt
```

### 2. Running the Server

#### A. GUI Desktop Mode (Recommended)
Launch the dark-themed desktop application with system tray integration:
```bash
python main.py
```
- A 6-digit **Security PIN** is automatically generated and displayed on the dashboard.
- The server binds to local IPv4 network interfaces on:
  - **Port 8765 (TCP):** WebSocket control & keyboard stream.
  - **Port 8766 (UDP):** High-frequency 120Hz mouse motion stream.
  - **Port 8767 (UDP):** Zero-configuration broadcast discovery.

#### B. Headless CLI Mode
For servers or terminal-only environments:
```bash
python main.py --cli
```

### 3. How to Use & Switch Control
- **Toggle Mode:** Press <kbd>Alt + X</kbd> (configurable in preferences) on your PC keyboard.
  - **In PC Desktop Mode:** Normal Windows operation.
  - **In Phone Control Mode:** The server suppresses local Windows inputs (your PC cursor stays still and keystrokes are trapped) and streams all mouse movements, clicks, scrolls, and typing to your connected Android device!
- **Emergency Failsafe:** Pressing <kbd>Escape</kbd> three times rapidly within 1.5 seconds or pressing <kbd>Ctrl + Alt + Del</kbd> immediately restores Windows control.

---

## 📱 Getting Started: Android Client (`WireShare-Android`)

### 1. Build & Install in Android Studio
1. Open the `/home/user/WireShare/android_app/` project folder in **Android Studio** (Koala / Jellyfish / Iguana+).
2. Build and install the APK on your Android 10+ device connected to the same Wi-Fi network.

### 2. Initial Setup
1. Launch **WireShare Client** on your Android phone.
2. Tap **"Enable"** on the Accessibility Status banner at the top of the screen.
3. In Android Accessibility Settings, toggle **WireShareAccessibilityService** to **ON**.
   - *Why?* Accessibility Service allows WireShare to draw a crisp desktop mouse pointer overlay and simulate taps, drags, scrolls, and text input without root.

### 3. Pairing with PC Server
1. Tap **"Scan Wi-Fi"** to automatically discover your PC server, OR enter your PC's IP address manually.
2. Type the **6-digit Security PIN** shown on your PC GUI dashboard.
3. Tap **"Connect & Pair"**.
4. Press <kbd>Alt + X</kbd> on your PC keyboard and start controlling your phone!

---

## 🧪 Testing & Verification without a Phone

You can verify the protocol, discovery, authentication, and 20-byte UDP binary serialization using the included automated simulation client:
```bash
cd /home/user/WireShare/pc_server
python test_client.py
```
This simulator connects to the PC server, authenticates over WebSocket, and prints live UDP mouse coordinates and keyboard frames.

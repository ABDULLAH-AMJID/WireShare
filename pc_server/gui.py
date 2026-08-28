"""
WireShare PC Server - Modern PyQt6 Desktop GUI & System Tray Manager
Provides a dark-themed status dashboard, PIN pairing display, sensitivity controls, and system tray integration.
"""

import sys
from typing import Optional
from PyQt6.QtCore import Qt, pyqtSignal, QObject
from PyQt6.QtGui import QIcon, QFont, QAction, QColor, QPalette
from PyQt6.QtWidgets import (
    QApplication,
    QMainWindow,
    QWidget,
    QVBoxLayout,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QGroupBox,
    QSlider,
    QCheckBox,
    QLineEdit,
    QSystemTrayIcon,
    QMenu,
    QTextEdit,
    QFrame,
    QMessageBox,
)

from config import config
from discovery import DiscoveryServer
from input_hook import InputHookEngine, Mode
from server import WireShareNetworkServer
from screen_mirror import ScreenMirrorServer
from phone_mirror_server import PhoneMirrorServer


class SignalBridge(QObject):
    log_signal = pyqtSignal(str)
    mode_signal = pyqtSignal(str)
    client_status_signal = pyqtSignal(bool, str)
    pin_changed_signal = pyqtSignal(str)
    phone_frame_signal = pyqtSignal(bytes)


class PhoneScreenDialog(QWidget):
    """Small always-available window showing the phone's mirrored screen."""

    def __init__(self, parent=None):
        super().__init__(parent, Qt.WindowType.Window)
        self.setWindowTitle("WireShare - Phone Screen")
        self.resize(360, 720)
        self.setStyleSheet("background-color: #0b1120;")

        layout = QVBoxLayout(self)
        self.image_label = QLabel("Waiting for phone to start sharing its screen...")
        self.image_label.setStyleSheet("color: #94a3b8;")
        self.image_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.image_label.setScaledContents(False)
        layout.addWidget(self.image_label)

    def update_frame(self, jpeg_bytes: bytes):
        from PyQt6.QtGui import QPixmap

        pixmap = QPixmap()
        if pixmap.loadFromData(jpeg_bytes, "JPG"):
            scaled = pixmap.scaled(
                self.image_label.size(),
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation,
            )
            self.image_label.setText("")
            self.image_label.setPixmap(scaled)


class WireShareMainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.bridge = SignalBridge()

        # Initialize core components
        self.discovery = DiscoveryServer(port=config.discovery_port)
        self.network_server = WireShareNetworkServer(
            on_client_status_change=self.on_network_client_status,
            # self.input_hook is assigned right below; by the time this callback
            # actually fires (a real TOGGLE_MODE_REQUEST from the phone) it will exist.
            on_toggle_mode_request=lambda: self.input_hook.toggle_mode(),
        )
        self.input_hook = InputHookEngine(
            on_mouse_event=self.network_server.send_mouse_motion,
            on_keyboard_event=self.network_server.send_keyboard_event,
            on_mode_change=self.on_hook_mode_change,
        )
        # Feature 2: full-screen mirror. Runs its own small WS server on a
        # separate port (config.mirror_port) - the phone connects to it only
        # when the user opens the "Screen Mirror" view; gestures/clicks/keys
        # still go over the existing control channel (self.network_server).
        self.screen_mirror = ScreenMirrorServer()

        # Feature 3: phone -> PC mirror. Reverse direction - the phone
        # captures its own screen (MediaProjection) and pushes frames here.
        self.phone_mirror = PhoneMirrorServer(on_frame=self.on_phone_frame)
        self.phone_screen_dialog: Optional[PhoneScreenDialog] = None

        self.init_ui()
        self.init_tray()
        self.connect_signals()

        # Start servers
        self.discovery.start()
        self.network_server.start()
        self.screen_mirror.start()
        self.phone_mirror.start()
        self.input_hook.start()
        self.mirror_status_label.setText(self._mirror_status_text())

        self.log_message(
            f"WireShare Server Started on IP {config.get_local_ip()} | PIN: {config.pin}"
        )

    def _mirror_status_text(self) -> str:
        try:
            from screen_mirror import MSS_AVAILABLE
        except Exception:
            MSS_AVAILABLE = False
        if not MSS_AVAILABLE:
            pc_to_phone = "🖥️ PC->Phone Mirror: ❌ Disabled - run: pip install mss Pillow"
        elif self.screen_mirror.running:
            pc_to_phone = f"🖥️ PC->Phone Mirror: 🟢 Listening on port {config.mirror_port}"
        else:
            pc_to_phone = "🖥️ PC->Phone Mirror: ⚪ Not started"

        if self.phone_mirror.running:
            phone_to_pc = f"📱 Phone->PC Mirror: 🟢 Listening on port {config.phone_mirror_port}"
        else:
            phone_to_pc = "📱 Phone->PC Mirror: ⚪ Not started"

        return f"{pc_to_phone}   |   {phone_to_pc}"

    def init_ui(self):
        self.setWindowTitle("WireShare - PC KVM Server")
        self.resize(680, 600)
        self.setStyleSheet(
            """
            QMainWindow {
                background-color: #0f172a;
            }
            QLabel {
                color: #f8fafc;
                font-family: 'Segoe UI', sans-serif;
            }
            QGroupBox {
                border: 1px solid #334155;
                border-radius: 8px;
                margin-top: 10px;
                padding-top: 15px;
                color: #38bdf8;
                font-weight: bold;
            }
            QPushButton {
                background-color: #1e293b;
                color: #f8fafc;
                border: 1px solid #334155;
                padding: 8px 16px;
                border-radius: 6px;
                font-weight: 600;
            }
            QPushButton:hover {
                background-color: #334155;
                border-color: #38bdf8;
            }
            QLineEdit {
                background-color: #1e293b;
                color: #f8fafc;
                border: 1px solid #334155;
                padding: 6px;
                border-radius: 4px;
            }
            QTextEdit {
                background-color: #0b1120;
                color: #94a3b8;
                border: 1px solid #334155;
                border-radius: 6px;
                font-family: 'Consolas', monospace;
                font-size: 12px;
            }
            QCheckBox {
                color: #f8fafc;
            }
        """
        )

        main_widget = QWidget()
        self.setCentralWidget(main_widget)
        main_layout = QVBoxLayout(main_widget)
        main_layout.setSpacing(15)

        # --- STATUS HEADER ---
        header_layout = QHBoxLayout()
        title_label = QLabel("WireShare KVM Server")
        title_label.setFont(QFont("Segoe UI", 18, QFont.Weight.Bold))
        title_label.setStyleSheet("color: #38bdf8;")

        self.mode_badge = QLabel("Mode: 💻 PC DESKTOP")
        self.mode_badge.setStyleSheet(
            "background-color: #064e3b; color: #4ade80; padding: 6px 12px; border-radius: 6px; font-weight: bold;"
        )

        header_layout.addWidget(title_label)
        header_layout.addStretch()
        header_layout.addWidget(self.mode_badge)
        main_layout.addLayout(header_layout)

        # --- PAIRING & CONNECTION GROUP ---
        conn_group = QGroupBox("Pairing & Connection Status")
        conn_layout = QVBoxLayout()

        info_layout = QHBoxLayout()
        ip_label = QLabel(
            f"<b>PC Name:</b> {config.get_hostname()} &nbsp;&nbsp;|&nbsp;&nbsp; <b>IP Address:</b> {config.get_local_ip()}"
        )
        self.client_status_label = QLabel("<b>Device:</b> 🔴 Not Connected")
        self.client_status_label.setStyleSheet("color: #fbbf24;")

        self.mirror_status_label = QLabel(self._mirror_status_text())
        self.mirror_status_label.setStyleSheet("color: #94a3b8;")

        info_layout.addWidget(ip_label)
        info_layout.addStretch()
        info_layout.addWidget(self.client_status_label)
        conn_layout.addLayout(info_layout)

        mirror_layout = QHBoxLayout()
        mirror_layout.addWidget(self.mirror_status_label)
        mirror_layout.addStretch()
        conn_layout.addLayout(mirror_layout)

        pin_layout = QHBoxLayout()
        pin_title = QLabel("Security PIN Code:")
        pin_title.setFont(QFont("Segoe UI", 12))

        self.pin_label = QLabel(str(config.pin))
        self.pin_label.setFont(QFont("Consolas", 22, QFont.Weight.Bold))
        self.pin_label.setStyleSheet(
            "color: #c084fc; background-color: #1e293b; padding: 4px 14px; border-radius: 6px; border: 1px solid #334155;"
        )

        regen_btn = QPushButton("Regenerate PIN")
        regen_btn.clicked.connect(self.regenerate_pin)

        pin_layout.addWidget(pin_title)
        pin_layout.addWidget(self.pin_label)
        pin_layout.addWidget(regen_btn)
        pin_layout.addStretch()
        conn_layout.addLayout(pin_layout)

        conn_group.setLayout(conn_layout)
        main_layout.addWidget(conn_group)

        # --- PREFERENCES GROUP ---
        pref_group = QGroupBox("KVM & Mouse Settings")
        pref_layout = QVBoxLayout()

        hotkey_layout = QHBoxLayout()
        hotkey_label = QLabel("Toggle Mode Hotkey:")
        self.hotkey_input = QLineEdit(config.hotkey)
        self.hotkey_input.setFixedWidth(120)
        hotkey_layout.addWidget(hotkey_label)
        hotkey_layout.addWidget(self.hotkey_input)
        hotkey_layout.addStretch()
        pref_layout.addLayout(hotkey_layout)

        sens_layout = QHBoxLayout()
        sens_label = QLabel("Mouse Sensitivity (DPI Scaling):")
        self.sens_slider = QSlider(Qt.Orientation.Horizontal)
        self.sens_slider.setRange(5, 30)  # 0.5 to 3.0
        self.sens_slider.setValue(int(config.sensitivity * 10))
        self.sens_val_label = QLabel(f"{config.sensitivity:.1f}x")
        self.sens_slider.valueChanged.connect(self.on_sens_changed)

        sens_layout.addWidget(sens_label)
        sens_layout.addWidget(self.sens_slider)
        sens_layout.addWidget(self.sens_val_label)
        pref_layout.addLayout(sens_layout)

        self.invert_scroll_cb = QCheckBox("Invert Scroll Direction")
        self.invert_scroll_cb.setChecked(config.invert_scroll)
        pref_layout.addWidget(self.invert_scroll_cb)

        save_btn = QPushButton("Save Preferences")
        save_btn.clicked.connect(self.save_preferences)
        pref_layout.addWidget(save_btn, alignment=Qt.AlignmentFlag.AlignRight)

        pref_group.setLayout(pref_layout)
        main_layout.addWidget(pref_group)

        # --- ACTIVITY LOG ---
        log_label = QLabel("Live Server Logs & Events")
        log_label.setStyleSheet("color: #94a3b8; font-weight: 600;")
        main_layout.addWidget(log_label)

        self.log_text = QTextEdit()
        self.log_text.setReadOnly(True)
        main_layout.addWidget(self.log_text)

        # Bottom Actions
        btn_layout = QHBoxLayout()
        toggle_btn = QPushButton("Manual Toggle KVM Mode (Alt + X)")
        toggle_btn.clicked.connect(self.input_hook.toggle_mode)
        toggle_btn.setStyleSheet(
            "background-color: #0284c7; color: white; font-weight: bold;"
        )

        view_phone_btn = QPushButton("📱 View Phone Screen")
        view_phone_btn.clicked.connect(self.show_phone_screen_dialog)
        view_phone_btn.setStyleSheet(
            "background-color: #7c3aed; color: white; font-weight: bold;"
        )

        exit_btn = QPushButton("Shutdown & Exit")
        exit_btn.clicked.connect(self.close_app)

        btn_layout.addWidget(toggle_btn)
        btn_layout.addWidget(view_phone_btn)
        btn_layout.addStretch()
        btn_layout.addWidget(exit_btn)
        main_layout.addLayout(btn_layout)

    def show_phone_screen_dialog(self):
        if self.phone_screen_dialog is None:
            self.phone_screen_dialog = PhoneScreenDialog(self)
        self.phone_screen_dialog.show()
        self.phone_screen_dialog.raise_()
        self.phone_screen_dialog.activateWindow()

    def on_phone_frame(self, jpeg_bytes: bytes):
        # Called from the PhoneMirrorServer's asyncio thread - never touch Qt
        # widgets directly from there, hop back to the Qt thread via signal.
        self.bridge.phone_frame_signal.emit(jpeg_bytes)

    def connect_signals(self):
        self.bridge.log_signal.connect(self._append_log)
        self.bridge.mode_signal.connect(self._update_mode_ui)
        self.bridge.client_status_signal.connect(self._update_client_ui)
        self.bridge.pin_changed_signal.connect(self._update_pin_ui)
        self.bridge.phone_frame_signal.connect(self._update_phone_frame)

    def _update_phone_frame(self, jpeg_bytes: bytes):
        if self.phone_screen_dialog is None:
            self.phone_screen_dialog = PhoneScreenDialog(self)
        self.phone_screen_dialog.update_frame(jpeg_bytes)
        if not self.phone_screen_dialog.isVisible():
            self.phone_screen_dialog.show()

    def init_tray(self):
        if not QSystemTrayIcon.isSystemTrayAvailable():
            return
        self.tray_icon = QSystemTrayIcon(self)
        # Create a simple colored pixmap icon if no ico file
        from PyQt6.QtGui import QPixmap, QPainter, QColor

        pix = QPixmap(32, 32)
        pix.fill(Qt.GlobalColor.transparent)
        painter = QPainter(pix)
        painter.setBrush(QColor("#38bdf8"))
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawEllipse(2, 2, 28, 28)
        painter.end()

        self.tray_icon.setIcon(QIcon(pix))
        self.tray_icon.setToolTip("WireShare KVM Server - Desktop Ready")

        menu = QMenu()
        show_action = QAction("Show WireShare Dashboard", self)
        show_action.triggered.connect(self.showNormal)
        menu.addAction(show_action)

        toggle_action = QAction("Toggle KVM Mode (Alt + X)", self)
        toggle_action.triggered.connect(self.input_hook.toggle_mode)
        menu.addAction(toggle_action)

        menu.addSeparator()
        exit_action = QAction("Exit WireShare", self)
        exit_action.triggered.connect(self.close_app)
        menu.addAction(exit_action)

        self.tray_icon.setContextMenu(menu)
        self.tray_icon.show()

    def log_message(self, text: str):
        self.bridge.log_signal.emit(text)

    def _append_log(self, text: str):
        from datetime import datetime

        ts = datetime.now().strftime("%H:%M:%S")
        self.log_text.append(f"[{ts}] {text}")

    def on_hook_mode_change(self, mode: str):
        self.bridge.mode_signal.emit(mode)
        active = mode == Mode.PHONE_CONTROL
        self.network_server.send_mode_change(active, mode)

    def _update_mode_ui(self, mode: str):
        if mode == Mode.PHONE_CONTROL:
            self.mode_badge.setText("Mode: 📱 PHONE CONTROL")
            self.mode_badge.setStyleSheet(
                "background-color: #1e3a8a; color: #60a5fa; padding: 6px 12px; border-radius: 6px; font-weight: bold;"
            )
            self.log_message(
                "Mode toggled to PHONE CONTROL -> Input streamed to phone."
            )
        else:
            self.mode_badge.setText("Mode: 💻 PC DESKTOP")
            self.mode_badge.setStyleSheet(
                "background-color: #064e3b; color: #4ade80; padding: 6px 12px; border-radius: 6px; font-weight: bold;"
            )
            self.log_message(
                "Mode toggled to PC DESKTOP -> Local Windows control restored."
            )

    def on_network_client_status(self, connected: bool, device_name: str):
        self.bridge.client_status_signal.emit(connected, device_name)

    def _update_client_ui(self, connected: bool, device_name: str):
        if connected:
            self.client_status_label.setText(
                f"<b>Device:</b> 🟢 Connected ({device_name})"
            )
            self.client_status_label.setStyleSheet("color: #4ade80;")
            self.log_message(
                f"Client '{device_name}' paired and authenticated over network."
            )
        else:
            self.client_status_label.setText(
                "<b>Device:</b> 🔴 Not Connected"
            )
            self.client_status_label.setStyleSheet("color: #fbbf24;")
            self.log_message("Client disconnected.")

    def _update_pin_ui(self, pin_str: str):
        self.pin_label.setText(pin_str)

    def regenerate_pin(self):
        config.pin = config.generate_pin()
        config.save()
        self.bridge.pin_changed_signal.emit(str(config.pin))
        self.log_message(f"Regenerated security PIN code: {config.pin}")

    def on_sens_changed(self, value: int):
        sens = value / 10.0
        self.sens_val_label.setText(f"{sens:.1f}x")

    def save_preferences(self):
        config.hotkey = self.hotkey_input.text().strip()
        config.sensitivity = self.sens_slider.value() / 10.0
        config.invert_scroll = self.invert_scroll_cb.isChecked()
        config.save()
        self.log_message("Saved KVM preferences to disk.")

    def close_app(self):
        self.log_message("Shutting down WireShare servers...")
        self.input_hook.stop()
        self.discovery.stop()
        self.network_server.stop()
        self.screen_mirror.stop()
        self.phone_mirror.stop()
        if hasattr(self, "tray_icon") and self.tray_icon:
            self.tray_icon.hide()
        QApplication.quit()

    def closeEvent(self, event):
        # Minimize to tray instead of quitting if auto_start_tray is active
        if hasattr(self, "tray_icon") and self.tray_icon.isVisible():
            self.hide()
            self.tray_icon.showMessage(
                "WireShare Running",
                "WireShare KVM server is still running in the system tray.",
                QSystemTrayIcon.MessageIcon.Information,
                2000,
            )
            event.ignore()
        else:
            self.close_app()

package com.wireshare.client.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wireshare.client.data.model.ConnectionState
import com.wireshare.client.data.model.KeyboardEvent
import com.wireshare.client.data.model.MouseEvent
import com.wireshare.client.network.WireShareClient
import kotlinx.coroutines.*

class WireShareAccessibilityService : AccessibilityService(), Choreographer.FrameCallback {

    private val TAG = "WireShareAccessibility"
    private var windowManager: WindowManager? = null
    private var cursorView: FloatingCursorView? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isOverlayAdded = false
    private var lastButtonState = 0
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var isDragging = false
    var sensitivity: Float = 1.2f

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var powerManager: PowerManager? = null

    private var targetX: Float = 500f
    private var targetY: Float = 1000f
    private var isFrameCallbackRunning = false

    companion object {
        var instance: WireShareAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "WireShareAccessibilityService connected!")
        initCursorOverlay()
        initPowerLocks()
        observeNetworkEvents()
        observeConnectionState()
        startVsyncLoop()
    }

    private fun startVsyncLoop() {
        if (!isFrameCallbackRunning) {
            isFrameCallbackRunning = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        val cv = cursorView
        if (cv != null && cv.visibility == View.VISIBLE) {
            val currentX = cv.cursorX
            val currentY = cv.cursorY
            val diffX = targetX - currentX
            val diffY = targetY - currentY

            if (Math.abs(diffX) > 0.5f || Math.abs(diffY) > 0.5f) {
                val nextX = currentX + (diffX * 0.45f)
                val nextY = currentY + (diffY * 0.45f)
                cv.setPositionSmooth(nextX, nextY)
            }
        }
        if (isFrameCallbackRunning) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun initPowerLocks() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = wifiManager.createWifiLock(lockMode, "WireShare::WifiLock").apply {
                setReferenceCounted(false)
            }

            powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "WireShare::WakeLock"
            )?.apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating wake locks: ${e.message}")
        }
    }

    private fun updateLocks(connected: Boolean) {
        try {
            if (connected) {
                if (wifiLock?.isHeld != true) wifiLock?.acquire()
                if (wakeLock?.isHeld != true) wakeLock?.acquire(24 * 60 * 60 * 1000L)
            } else {
                if (wifiLock?.isHeld == true) wifiLock?.release()
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating locks: ${e.message}")
        }
    }

    private fun wakeUpPhoneScreen() {
        try {
            val pm = powerManager ?: return
            @Suppress("DEPRECATION")
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "WireShare::ScreenWakeup"
            )
            screenLock.acquire(3000L)
            Log.d(TAG, "Phone screen woke up automatically via ACQUIRE_CAUSES_WAKEUP!")
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen: ${e.message}")
        }
    }

    private fun initCursorOverlay() {
        if (isOverlayAdded) return
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            cursorView = FloatingCursorView(this)

            val params = WindowManager.LayoutParams(
                72,
                72,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 500
                y = 1000
            }

            cursorView?.initScreenDimensions(windowManager!!, params)
            windowManager?.addView(cursorView, params)
            isOverlayAdded = true
            cursorView?.setCursorVisible(false)
            targetX = 500f
            targetY = 1000f
            Log.d(TAG, "Floating cursor overlay view attached successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating cursor overlay: ${e.message}")
        }
    }

    private fun observeConnectionState() {
        scope.launch {
            WireShareClient.connectionState.collect { state ->
                val isConnected = (state == ConnectionState.CONNECTED || state == ConnectionState.MODE_ACTIVE)
                updateLocks(isConnected)

                if (state == ConnectionState.MODE_ACTIVE) {
                    wakeUpPhoneScreen()
                    val center = cursorView?.recenterCursor()
                    if (center != null) {
                        targetX = center.first
                        targetY = center.second
                    }
                    cursorView?.setCursorVisible(true)
                } else {
                    cursorView?.setCursorVisible(false)
                }
            }
        }
    }

    private fun observeNetworkEvents() {
        scope.launch {
            WireShareClient.mouseEvents.collect { event ->
                handleMouseEvent(event)
            }
        }
        scope.launch {
            WireShareClient.keyboardEvents.collect { event ->
                handleKeyboardEvent(event)
            }
        }
    }

    private fun handleMouseEvent(event: MouseEvent) {
        if (powerManager?.isInteractive == false) {
            wakeUpPhoneScreen()
            return
        }

        val cv = cursorView ?: return
        val newPos = cv.calculateNextTarget(targetX, targetY, event.dx, event.dy, sensitivity)
        targetX = newPos.first
        targetY = newPos.second

        if (cv.visibility != View.VISIBLE) {
            cv.setCursorVisible(true)
        }

        val leftDown = (event.buttons and MouseEvent.BTN_LEFT) != 0
        val wasLeftDown = (lastButtonState and MouseEvent.BTN_LEFT) != 0

        if (leftDown && !wasLeftDown) {
            dragStartX = targetX
            dragStartY = targetY
            isDragging = true
        } else if (!leftDown && wasLeftDown) {
            if (isDragging) {
                val dist = Math.hypot((targetX - dragStartX).toDouble(), (targetY - dragStartY).toDouble())
                if (dist < 20.0) {
                    performSingleTap(targetX, targetY)
                } else {
                    performSwipeGesture(dragStartX, dragStartY, targetX, targetY, 250L)
                }
                isDragging = false
            }
        }

        val rightDown = (event.buttons and MouseEvent.BTN_RIGHT) != 0
        val wasRightDown = (lastButtonState and MouseEvent.BTN_RIGHT) != 0
        if (rightDown && !wasRightDown) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        val midDown = (event.buttons and MouseEvent.BTN_MIDDLE) != 0
        val wasMidDown = (lastButtonState and MouseEvent.BTN_MIDDLE) != 0
        if (midDown && !wasMidDown) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        if (event.scrollY != 0) {
            // BUGFIX (direction): previously this always ended the swipe at
            // targetY regardless of scroll sign, and the swipe direction was
            // inverted from natural wheel semantics. A positive scrollY (wheel/
            // trackpad scrolled "up", i.e. reveal content above) must swipe the
            // finger DOWNWARD (start above the cursor, end below it) - dragging
            // the page down like a natural touch scroll. Negative scrollY does
            // the opposite. This now matches the arrow-key scroll gestures below.
            val scrollDistance = 300f
            val startY = if (event.scrollY > 0) targetY - scrollDistance else targetY + scrollDistance
            val endY = if (event.scrollY > 0) targetY + scrollDistance else targetY - scrollDistance
            performSwipeGesture(targetX, startY, targetX, endY, 150L)
        }

        if (event.scrollX != 0) {
            val scrollDistance = 300f
            val startX = if (event.scrollX > 0) targetX + scrollDistance else targetX - scrollDistance
            val endX = if (event.scrollX > 0) targetX - scrollDistance else targetX + scrollDistance
            performSwipeGesture(startX, targetY, endX, targetY, 150L)
        }

        lastButtonState = event.buttons
    }

    private fun performSingleTap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 40)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    private fun performSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    private fun handleKeyboardEvent(event: KeyboardEvent) {
        if (powerManager?.isInteractive == false) {
            wakeUpPhoneScreen()
            return
        }
        if (event.action != "KEY_DOWN") return

        when (event.keyCode) {
            "KEY_ENTER" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
            "KEY_BACKSPACE" -> {
                deleteLastCharacterInFocusedNode()
                return
            }
            "KEY_ESCAPE" -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
            // BUGFIX (root cause): arrow keys had NO case here at all, so they
            // silently fell through to the printable-char check below - and
            // since arrow keys never carry a char, nothing happened whatsoever.
            // Now: inside an editable text field, arrows move the text cursor;
            // otherwise they scroll the current screen/page.
            "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT" -> {
                handleArrowKey(event.keyCode)
                return
            }
        }

        if (event.char.isNotEmpty() && event.char[0].isPrintable()) {
            appendTextToFocusedNode(event.char)
        }
    }

    private fun handleArrowKey(keyCode: String) {
        try {
            val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null && focusedNode.isEditable) {
                val moved = moveCursorInEditableNode(focusedNode, keyCode)
                focusedNode.recycle()
                if (moved) return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move cursor in focused node: ${e.message}")
        }
        // Not in an editable field (or vertical movement in a single-line
        // field, where "up/down" has no text meaning) -> scroll the screen
        // at the current cursor position instead, so arrow keys are always
        // useful for reading a long page/website.
        performArrowScrollGesture(keyCode)
    }

    /**
     * Moves the text caret one character (LEFT/RIGHT) or one line (UP/DOWN)
     * inside the focused editable node. Returns false if there was nothing
     * sensible to do (e.g. UP/DOWN in a single-line field with no newlines),
     * so the caller can fall back to scrolling the screen instead.
     */
    private fun moveCursorInEditableNode(node: AccessibilityNodeInfo, keyCode: String): Boolean {
        val text = getActualEditableText(node)
        if (text.isEmpty()) return false

        val selEnd = if (node.textSelectionEnd >= 0) node.textSelectionEnd else text.length
        var newPos = selEnd

        when (keyCode) {
            "KEY_LEFT" -> newPos = (selEnd - 1).coerceAtLeast(0)
            "KEY_RIGHT" -> newPos = (selEnd + 1).coerceAtMost(text.length)
            "KEY_UP", "KEY_DOWN" -> {
                if (!text.contains('\n')) return false
                val lineStart = text.lastIndexOf('\n', (selEnd - 1).coerceAtLeast(0)).let {
                    if (it == -1) 0 else it + 1
                }
                val column = selEnd - lineStart
                newPos = if (keyCode == "KEY_UP") {
                    if (lineStart == 0) return false
                    val prevLineStart = text.lastIndexOf('\n', lineStart - 2).let {
                        if (it == -1) 0 else it + 1
                    }
                    (prevLineStart + column).coerceAtMost(lineStart - 1)
                } else {
                    val lineEnd = text.indexOf('\n', selEnd).let { if (it == -1) text.length else it }
                    if (lineEnd == text.length) return false
                    val nextLineStart = lineEnd + 1
                    val nextLineEnd = text.indexOf('\n', nextLineStart).let { if (it == -1) text.length else it }
                    (nextLineStart + column).coerceAtMost(nextLineEnd)
                }
            }
        }

        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newPos)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newPos)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
    }

    /**
     * Scrolls the visible screen at the current cursor position - used for
     * arrow keys outside a text field (long pages, websites, feeds, etc.).
     * Direction matches standard "arrow key scrolls like a swipe" semantics:
     * UP/DOWN reveal content above/below, LEFT/RIGHT reveal content to the
     * side - same mapping used for wheel/trackpad scroll above.
     */
    private fun performArrowScrollGesture(keyCode: String) {
        val distance = 350f
        when (keyCode) {
            "KEY_UP" -> performSwipeGesture(targetX, targetY - distance, targetX, targetY + distance, 200L)
            "KEY_DOWN" -> performSwipeGesture(targetX, targetY + distance, targetX, targetY - distance, 200L)
            "KEY_LEFT" -> performSwipeGesture(targetX - distance, targetY, targetX + distance, targetY, 200L)
            "KEY_RIGHT" -> performSwipeGesture(targetX + distance, targetY, targetX - distance, targetY, 200L)
        }
    }

    private fun getActualEditableText(node: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) {
            return ""
        }
        val text = node.text?.toString() ?: ""
        val hint = node.hintText?.toString() ?: ""
        if (text.isNotEmpty() && text == hint) {
            return ""
        }
        return text
    }

    private fun appendTextToFocusedNode(text: String) {
        try {
            val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null && focusedNode.isEditable) {
                val currentText = getActualEditableText(focusedNode)
                val newText = currentText + text
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                focusedNode.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set text on focused node: ${e.message}")
        }
    }

    private fun deleteLastCharacterInFocusedNode() {
        try {
            val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null && focusedNode.isEditable) {
                val currentText = getActualEditableText(focusedNode)
                if (currentText.isNotEmpty()) {
                    val newText = currentText.substring(0, currentText.length - 1)
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                    }
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
                focusedNode.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backspace in focused node: ${e.message}")
        }
    }

    private fun Char.isPrintable(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return (!Character.isISOControl(this)) &&
                block != null &&
                block != Character.UnicodeBlock.SPECIALS
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isFrameCallbackRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        updateLocks(false)
        scope.cancel()
        if (isOverlayAdded && cursorView != null) {
            try {
                windowManager?.removeView(cursorView)
            } catch (e: Exception) {
                // Ignore
            }
        }
        isOverlayAdded = false
        Log.d(TAG, "WireShareAccessibilityService destroyed.")
    }
}

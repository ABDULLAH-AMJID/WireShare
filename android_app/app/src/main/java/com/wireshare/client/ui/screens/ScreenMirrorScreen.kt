package com.wireshare.client.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wireshare.client.data.model.MouseEvent
import com.wireshare.client.network.ScreenMirrorClient
import com.wireshare.client.network.WireShareClient
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Feature 2: full PC screen mirrored to the phone, with touch gestures mapped
 * to mouse/keyboard control. See screen_mirror.py on the PC side for what
 * this actually is (mss+JPEG over WebSocket, not WebRTC/H.264 - see that
 * file's docstring for why, and the upgrade path if you want to push further).
 *
 * Gesture mapping (as specified):
 *   1-finger drag -> mouse move (relative)
 *   1-finger tap  -> left click
 *   2-finger tap  -> right click
 *   2-finger drag -> scroll
 *   pinch         -> LOCAL viewport zoom only (does not affect the PC)
 */
@Composable
fun ScreenMirrorScreen() {
    val frame by ScreenMirrorClient.latestFrame.collectAsState()
    val isConnected by ScreenMirrorClient.isConnected.collectAsState()
    val errorMessage by ScreenMirrorClient.errorMessage.collectAsState()

    var zoom by remember { mutableFloatStateOf(1f) }
    var ctrlHeld by remember { mutableStateOf(false) }
    var altHeld by remember { mutableStateOf(false) }
    var winHeld by remember { mutableStateOf(false) }
    var shiftHeld by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val ip = WireShareClient.currentServerIp()
        val pin = WireShareClient.currentPin()
        try {
            if (ip.isNotBlank()) {
                ScreenMirrorClient.connect(ip, mirrorPort = 8770, pin = pin)
            }
        } catch (e: Exception) {
            // Never let a bad connect attempt crash the screen - the UI
            // below already shows "Mirror unavailable" from errorMessage.
        }
        onDispose {
            try {
                ScreenMirrorClient.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val bmp = frame
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "PC Screen",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(scaleX = zoom, scaleY = zoom)
                        .pointerInput(Unit) {
                            mirrorGestureDetector(
                                onMove = { dx, dy ->
                                    WireShareClient.sendRemotePcMouse(dx, dy, buttons = 0)
                                },
                                onLeftClick = {
                                    WireShareClient.sendRemotePcClick(MouseEvent.BTN_LEFT)
                                },
                                onRightClick = {
                                    WireShareClient.sendRemotePcClick(MouseEvent.BTN_RIGHT)
                                },
                                onScroll = { scrollY, scrollX ->
                                    WireShareClient.sendRemotePcMouse(
                                        0, 0, buttons = 0, scrollY = scrollY, scrollX = scrollX
                                    )
                                },
                                onPinchZoom = { factor ->
                                    zoom = (zoom * factor).coerceIn(1f, 3f)
                                }
                            )
                        }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (errorMessage != null)
                                "Mirror unavailable: $errorMessage"
                            else if (!isConnected)
                                "Connecting to PC screen..."
                            else
                                "Waiting for first frame...",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Virtual key overlay - Ctrl/Alt/Win/Shift are HELD while pressed
        // (so Ctrl+Click / Alt+Tab-style combos work); Esc/Tab are one-shot.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111827))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VirtualHoldKey("Ctrl", ctrlHeld, modifier = Modifier.weight(1f)) { held ->
                ctrlHeld = held
                WireShareClient.sendRemotePcKeyboardAction(
                    if (held) "KEY_DOWN" else "KEY_UP", "KEY_CTRL"
                )
            }
            VirtualHoldKey("Alt", altHeld, modifier = Modifier.weight(1f)) { held ->
                altHeld = held
                WireShareClient.sendRemotePcKeyboardAction(
                    if (held) "KEY_DOWN" else "KEY_UP", "KEY_ALT"
                )
            }
            VirtualHoldKey("Win", winHeld, modifier = Modifier.weight(1f)) { held ->
                winHeld = held
                WireShareClient.sendRemotePcKeyboardAction(
                    if (held) "KEY_DOWN" else "KEY_UP", "KEY_WIN"
                )
            }
            VirtualHoldKey("Shift", shiftHeld, modifier = Modifier.weight(1f)) { held ->
                shiftHeld = held
                WireShareClient.sendRemotePcKeyboardAction(
                    if (held) "KEY_DOWN" else "KEY_UP", "KEY_SHIFT"
                )
            }
            VirtualTapKey("Esc", modifier = Modifier.weight(1f)) {
                WireShareClient.sendRemotePcKeyboard(keyCode = "KEY_ESCAPE")
            }
            VirtualTapKey("Tab", modifier = Modifier.weight(1f)) {
                WireShareClient.sendRemotePcKeyboard(keyCode = "KEY_TAB")
            }
        }
    }
}

/**
 * Manual replacement for foundation's internal `waitForUpOrCancel()` (not a
 * stable public API in this Compose version, hence the unresolved-reference
 * build error). Loops raw pointer events for the given pointer id until it's
 * released; returns true on a normal release, false if the gesture was
 * canceled (e.g. a parent scroll container stole the touch).
 */
private suspend fun AwaitPointerEventScope.waitForRelease(pointerId: PointerId): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return false
        if (!change.pressed) {
            return !change.isConsumed
        }
    }
}

@Composable
private fun VirtualHoldKey(
    label: String,
    held: Boolean,
    modifier: Modifier = Modifier,
    onHoldChanged: (Boolean) -> Unit
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .background(
                if (held) Color(0xFF38BDF8) else Color(0xFF334155),
                shape = RoundedCornerShape(8.dp)
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onHoldChanged(true)
                    waitForRelease(down.id)
                    onHoldChanged(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun VirtualTapKey(label: String, modifier: Modifier = Modifier, onTap: () -> Unit) {
    Box(
        modifier = modifier
            .height(44.dp)
            .background(Color(0xFF334155), shape = RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val releasedNormally = waitForRelease(down.id)
                    if (releasedNormally) onTap()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

/**
 * Custom multitouch gesture recognizer (Compose's built-in detectDragGestures/
 * detectTapGestures don't distinguish finger count, which the spec explicitly
 * needs: 1-finger vs 2-finger drag/tap mean different things here).
 *
 * Tracks the live pointer count each frame and routes accordingly:
 *  - 1 pointer, moved beyond touch slop  -> onMove(dx, dy) repeatedly
 *  - 1 pointer, released without moving  -> onLeftClick()
 *  - 2 pointers, distance stable, moved  -> onScroll(dy, dx)
 *  - 2 pointers, distance changing a lot -> onPinchZoom(factor)
 *  - 2 pointers, released without moving -> onRightClick()
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.mirrorGestureDetector(
    onMove: (Int, Int) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onScroll: (Int, Int) -> Unit,
    onPinchZoom: (Float) -> Unit,
) {
    val touchSlop = 12f

    awaitEachGesture {
        var totalMovement = 0f
        var lastCentroid: androidx.compose.ui.geometry.Offset? = null
        var lastSpan: Float? = null
        var sawTwoFingers = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressed = event.changes.filter { it.pressed }

            if (pressed.isEmpty()) break

            if (pressed.size == 1) {
                val change = pressed[0]
                val delta = change.positionChange()
                totalMovement += hypot(delta.x, delta.y)
                if (totalMovement > touchSlop) {
                    onMove(delta.x.toInt(), delta.y.toInt())
                    change.consume()
                }
            } else {
                sawTwoFingers = true
                val p1 = pressed[0].position
                val p2 = pressed[1].position
                val centroid = androidx.compose.ui.geometry.Offset(
                    (p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f
                )
                val span = hypot((p1.x - p2.x), (p1.y - p2.y))

                if (lastCentroid != null && lastSpan != null) {
                    val prevCentroid = lastCentroid
                    val prevSpan = lastSpan
                    val spanDelta = abs(span - prevSpan)
                    val centroidDelta = androidx.compose.ui.geometry.Offset(
                        centroid.x - prevCentroid.x, centroid.y - prevCentroid.y
                    )
                    totalMovement += hypot(centroidDelta.x, centroidDelta.y)

                    if (spanDelta > touchSlop * 1.5f) {
                        // Fingers moving apart/together faster than they're
                        // translating together -> treat as pinch, not scroll.
                        val factor = if (prevSpan > 0f) span / prevSpan else 1f
                        onPinchZoom(factor)
                    } else if (totalMovement > touchSlop) {
                        onScroll(centroidDelta.y.toInt() / 4, centroidDelta.x.toInt() / 4)
                    }
                }
                lastCentroid = centroid
                lastSpan = span
                pressed.forEach { it.consume() }
            }
        }

        // Gesture ended - decide tap vs right-click-tap based on whether any
        // real movement happened and how many fingers were involved.
        if (totalMovement <= touchSlop) {
            if (sawTwoFingers) onRightClick() else onLeftClick()
        }
    }
}

package com.wireshare.client.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wireshare.client.data.model.MouseEvent
import com.wireshare.client.network.WireShareClient

@Composable
fun PcControllerScreen() {
    var isLeftPressed by remember { mutableStateOf(false) }
    var isRightPressed by remember { mutableStateOf(false) }
    var isMiddlePressed by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }
    var showKeyboard by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
// Joystick / Touchpad Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "🎮 Virtual Joystick & Touchpad",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Slide thumb here to move Windows PC cursor",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

// Interactive Joystick Surface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF0B1120), shape = RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dx = dragAmount.x.toInt()
                                val dy = dragAmount.y.toInt()
                                var buttons = 0
                                if (isLeftPressed) buttons = buttons or MouseEvent.BTN_LEFT
                                if (isRightPressed) buttons = buttons or MouseEvent.BTN_RIGHT
                                if (isMiddlePressed) buttons = buttons or MouseEvent.BTN_MIDDLE

                                WireShareClient.sendRemotePcMouse(
                                    dx = dx,
                                    dy = dy,
                                    buttons = buttons,
                                    scrollY = 0
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "DRAG TO MOVE CURSOR",
                        color = Color(0xFF334155),
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                }
            }
        }

// Dedicated Mouse Buttons Bar (Left, Middle Rolling Scroll Strip, Right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
// Left Click Button
            // BUGFIX: previously a plain Button with onClick, which always sent a
            // fixed press+release pulse and never actually updated isLeftPressed -
            // so the "pressed" color never lit up, and holding this button down while
            // dragging on the touchpad above (for click-and-drag / text selection)
            // was impossible, since the button state and the drag state were never
            // linked. This now tracks real press/release so holding + dragging works.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (isLeftPressed) Color(0xFF38BDF8) else Color(0xFF334155),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isLeftPressed = true
                                WireShareClient.sendRemotePcMouse(0, 0, buttons = MouseEvent.BTN_LEFT)
                                tryAwaitRelease()
                                isLeftPressed = false
                                WireShareClient.sendRemotePcMouse(0, 0, buttons = 0)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("LEFT CLICK", color = Color.White, fontWeight = FontWeight.Bold)
            }

// Scroll Strip
            Card(
                modifier = Modifier
                    .width(70.dp)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val scrollY = (-dragAmount.y / 5f).toInt()
                            if (scrollY != 0) {
                                WireShareClient.sendRemotePcMouse(0, 0, 0, scrollY)
                            }
                        }
                    },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📜\nSCROLL", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

// Right Click Button
            Button(
                onClick = {
                    WireShareClient.sendRemotePcClick(MouseEvent.BTN_RIGHT)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRightPressed) Color(0xFFC084FC) else Color(0xFF334155)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("RIGHT CLICK", fontWeight = FontWeight.Bold)
            }
        }

// Built-in Virtual Soft Keyboard Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⌨️ Built-in Windows PC Keyboard",
                        color = Color(0xFF4ADE80),
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = {
                            // BUGFIX: this used to call sendRemotePcKeyboard("KEY_ENTER")
                            // which filled the `char` parameter (positional arg), so the
                            // PC literally typed the text "KEY_ENTER" instead of pressing
                            // the Enter key. Must pass it as the named keyCode parameter.
                            WireShareClient.sendRemotePcKeyboard(keyCode = "KEY_ENTER")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF064E3B))
                    ) {
                        Text("Send Enter")
                    }
                }

                OutlinedTextField(
                    value = typedText,
                    onValueChange = { newVal ->
                        val lengthDiff = newVal.length - typedText.length
                        if (lengthDiff == 1) {
// User typed a single character -> send to PC!
                            val char = newVal.last().toString()
                            WireShareClient.sendRemotePcKeyboard(char = char)
                        } else if (lengthDiff < 0) {
// User pressed Backspace!
                            WireShareClient.sendRemotePcKeyboard(keyCode = "KEY_BACKSPACE")
                        }
                        typedText = newVal
                    },
                    label = { Text("Type here to send text directly to Windows PC") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false
                )
            }
        }
    }
}

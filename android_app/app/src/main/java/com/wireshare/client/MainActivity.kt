package com.wireshare.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wireshare.client.ui.screens.MainScreen
import com.wireshare.client.ui.theme.WireShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WireShareTheme {
                MainScreen()
            }
        }
    }
}

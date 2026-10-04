package com.chockXlate.teachablevoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.chockXlate.teachablevoice.ui.screens.MainDashboardScreen
import com.chockXlate.teachablevoice.ui.theme.ColorBgBase
import com.chockXlate.teachablevoice.ui.theme.TeachableVoiceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TeachableVoiceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ColorBgBase
                ) {
                    MainDashboardScreen()
                }
            }
        }
    }
}

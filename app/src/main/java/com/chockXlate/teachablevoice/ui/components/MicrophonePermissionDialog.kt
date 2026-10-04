package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chockXlate.teachablevoice.ui.theme.*

/**
 * Dialog shown when microphone permission is required or has been denied.
 * Preserves the exact existing visual language and ensures text input remains available.
 */
@Composable
fun MicrophonePermissionDialog(
    isPermanentlyDenied: Boolean = false,
    onGrantOrSettingsClicked: () -> Unit,
    onDismissOrTextFallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onDismissOrTextFallback,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Column(
            modifier = modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .clip(RadiusLg)
                .background(ColorBgSurfaceElevated)
                .border(1.dp, ColorBorderMedium, RadiusLg)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isPermanentlyDenied) "Microphone Permission Required" else "Enable Microphone",
                color = ColorTextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isPermanentlyDenied) {
                    "Microphone access is required to speak commands.\n\nPlease enable microphone permissions in App Settings, or continue using text input."
                } else {
                    "Teachable Voice Automation uses the microphone for hands-free voice commands.\n\nYou can always continue using typed text input."
                },
                color = ColorTextSecondary,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            PrimaryButton(
                label = if (isPermanentlyDenied) "Open App Settings" else "Grant Permission",
                modifier = Modifier.fillMaxWidth(),
                onClick = onGrantOrSettingsClicked
            )

            Spacer(modifier = Modifier.height(8.dp))

            SecondaryButton(
                label = "Use Text Input",
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismissOrTextFallback
            )
        }
    }
}

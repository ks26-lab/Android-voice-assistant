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

@Composable
fun AccessibilityPermissionDialog(
    onEnableClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onCancelClicked,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false
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
                text = "Accessibility Service is Off",
                color = ColorTextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Teachable Voice Automation requires Accessibility access to observe and automate Android UI interactions.\n\nPlease enable the service in Accessibility Settings.",
                color = ColorTextSecondary,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            PrimaryButton(
                label = "Enable Accessibility",
                modifier = Modifier.fillMaxWidth(),
                onClick = onEnableClicked
            )

            Spacer(modifier = Modifier.height(8.dp))

            SecondaryButton(
                label = "Cancel",
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancelClicked
            )
        }
    }
}

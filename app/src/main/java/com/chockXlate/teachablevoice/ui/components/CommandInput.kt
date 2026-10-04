package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun CommandInput(
    placeholder: String = "Search for headphones",
    value: String = "",
    onValueChange: ((String) -> Unit)? = null,
    onSubmit: ((String) -> Unit)? = null,
    onVoiceClick: (() -> Unit)? = null,
    isListening: Boolean = false,
    statusMessage: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Command")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RadiusMd)
                .background(ColorBgInput)
                .border(
                    1.dp,
                    if (isListening) ColorAccentPrimary else ColorBorderSubtle,
                    RadiusMd
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && onValueChange != null) {
                    Text(
                        text = if (isListening) "Listening… Speak now" else placeholder,
                        color = if (isListening) ColorAccentPrimary else ColorTextMuted,
                        fontSize = 13.5.sp
                    )
                } else if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = ColorTextMuted,
                        fontSize = 13.5.sp
                    )
                }

                if (onValueChange != null) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Command text input" },
                        textStyle = TextStyle(
                            color = ColorTextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(ColorAccentPrimary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            if (value.isNotBlank()) {
                                onSubmit?.invoke(value)
                            }
                        })
                    )
                } else {
                    Text(
                        text = value,
                        color = ColorTextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Submit button if text is present and onValueChange is active
            if (onValueChange != null && value.isNotBlank() && onSubmit != null) {
                Box(
                    modifier = Modifier
                        .clip(RadiusSm)
                        .background(ColorAccentPrimary)
                        .clickable { onSubmit(value) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .semantics { contentDescription = "Execute command" },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Run",
                        color = ColorTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.padding(start = 6.dp))
            }

            // Voice mic icon button
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isListening) ColorAccentSubtle else ColorBgSurfaceSubtle)
                    .clickable { onVoiceClick?.invoke() }
                    .padding(6.dp)
                    .semantics { contentDescription = if (isListening) "Stop voice input" else "Start voice input" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🎤",
                    fontSize = 14.sp
                )
            }
        }

        if (!statusMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusMessage,
                color = if (statusMessage.contains("unavailable", ignoreCase = true) || statusMessage.contains("denied", ignoreCase = true) || statusMessage.contains("error", ignoreCase = true)) ColorStatusError else ColorTextSecondary,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

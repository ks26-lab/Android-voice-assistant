package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun AppHeader(
    title: String = "Teachable Voice Automation",
    subtitle: String = "One-Shot Semantic Workflow Learning",
    status: String = "READY",
    statusVariant: StatusVariant = StatusVariant.READY,
    onOpenSkills: (() -> Unit)? = null,
    onOpenActivity: (() -> Unit)? = null,
    onToggleTheme: (() -> Unit)? = null,
    isDarkMode: Boolean = true,
    activeEventsCount: Int = 0,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = title,
                    color = ColorTextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.01).sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = ColorTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onToggleTheme != null) {
                    Box(
                        modifier = Modifier
                            .clip(RadiusSm)
                            .background(ColorBgSurfaceSubtle)
                            .border(1.dp, ColorBorderSubtle, RadiusSm)
                            .clickable { onToggleTheme() }
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Toggle theme" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isDarkMode) "🌙" else "☀️",
                            fontSize = 11.sp
                        )
                    }
                }
                if (onOpenSkills != null) {
                    Box(
                        modifier = Modifier
                            .clip(RadiusSm)
                            .background(ColorBgSurfaceSubtle)
                            .border(1.dp, ColorBorderSubtle, RadiusSm)
                            .clickable { onOpenSkills() }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Open skills" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Skills",
                            color = ColorTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (onOpenActivity != null) {
                    val hasActive = activeEventsCount > 0
                    Box(
                        modifier = Modifier
                            .clip(RadiusSm)
                            .background(if (hasActive) ColorAccentSubtle else ColorBgSurfaceSubtle)
                            .border(1.dp, if (hasActive) ColorAccentGlow else ColorBorderSubtle, RadiusSm)
                            .clickable { onOpenActivity() }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                            .semantics { contentDescription = "Open activity" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (hasActive) "Activity ($activeEventsCount)" else "Activity",
                            color = if (hasActive) ColorAccentHover else ColorTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                StatusIndicator(
                    status = status,
                    variant = statusVariant
                )
            }
        }
        Divider(
            color = ColorBorderSubtle,
            thickness = 1.dp
        )
    }
}

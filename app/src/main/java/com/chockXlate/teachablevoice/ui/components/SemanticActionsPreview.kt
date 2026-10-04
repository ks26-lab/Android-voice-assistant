package com.chockXlate.teachablevoice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.mock.SemanticActionData
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun SemanticActionsPreview(
    actions: List<SemanticActionData>,
    modifier: Modifier = Modifier
) {
    if (actions.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Semantic actions detected",
            badge = "${actions.size} steps"
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RadiusMd)
                .background(ColorBgSurface)
                .border(1.dp, ColorBorderSubtle, RadiusMd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            actions.forEach { act ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RadiusXs)
                        .background(ColorBgSurfaceElevated)
                        .border(1.dp, ColorBorderSubtle, RadiusXs)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${act.step}.",
                            color = ColorTextMuted,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = act.type,
                            color = ColorTextAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = act.target,
                            color = ColorTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    if (act.value != null) {
                        Box(
                            modifier = Modifier
                                .clip(RadiusXs)
                                .background(ColorStatusReadyBg)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = act.value,
                                color = Color(0xFF6EE7B7),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

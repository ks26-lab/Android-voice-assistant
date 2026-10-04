package com.samsung.prism.uidemo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.prism.uidemo.ui.theme.*

@Composable
fun CurrentStateCard(
    statusBadge: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    statusVariant: String = "ready"
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(12.dp))
            .border(1.dp, BorderMedium, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "CURRENT STATE",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            StatusIndicator(
                status = statusBadge,
                variant = statusVariant
            )
        }
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = description,
            color = TextMuted,
            fontSize = 12.5.sp,
            lineHeight = 17.sp
        )
    }
}

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chockXlate.teachablevoice.ui.mock.LogCategory
import com.chockXlate.teachablevoice.ui.mock.LogEntry
import com.chockXlate.teachablevoice.ui.theme.*

@Composable
fun TechnicalLogCard(
    events: List<LogEntry>,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "Technical Log")

        val latestEvent = events.lastOrNull()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RadiusMd)
                .background(ColorBgCode)
                .border(1.dp, ColorBorderSubtle, RadiusMd)
                .padding(12.dp)
        ) {
            if (isCollapsed) {
                // Collapsed View
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "LATEST EVENT: ",
                            color = ColorTextMuted,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = latestEvent?.event ?: "SYSTEM_READY",
                            color = ColorTextAccent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "[ Show details ]",
                        color = ColorTextAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RadiusXs)
                            .clickable { onToggleCollapse() }
                            .padding(4.dp)
                    )
                }
            } else {
                // Expanded View
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "TECHNICAL LOG",
                            color = ColorTextMuted,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.06.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RadiusXs)
                                .background(ColorBgSurfaceElevated)
                                .border(1.dp, ColorBorderSubtle, RadiusXs)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${events.size} events",
                                color = ColorTextMuted,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Clear",
                            color = ColorTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RadiusXs)
                                .clickable { onClear() }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Text(
                            text = "[ Hide details ]",
                            color = ColorTextAccent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RadiusXs)
                                .clickable { onToggleCollapse() }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                Divider(color = Color(0x14FFFFFF), thickness = 1.dp)
                Spacer(modifier = Modifier.height(6.dp))

                // Scrollable Event Stream
                val listState = rememberLazyListState()
                LaunchedEffect(events.size) {
                    if (events.isNotEmpty()) {
                        listState.animateScrollToItem(events.size - 1)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(events) { index, item ->
                        val isLatest = index == events.size - 1
                        LogItemRow(item = item, isLatest = isLatest)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItemRow(item: LogEntry, isLatest: Boolean) {
    val (catBg, catText) = when (item.category) {
        LogCategory.STATE, LogCategory.SYSTEM -> Pair(Color(0x10FFFFFF), ColorTextSecondary)
        LogCategory.TEACH, LogCategory.TRACE, LogCategory.NORMALIZE, LogCategory.LEARN, LogCategory.RECOVERY, LogCategory.ACTION ->
            Pair(Color(0x267C5CFF), Color(0xFFA78BFA))
        LogCategory.VALIDATE, LogCategory.SKILL, LogCategory.VERIFY, LogCategory.RUNTIME ->
            Pair(Color(0x2410B981), Color(0xFF6EE7B7))
        LogCategory.WAITING -> Pair(Color(0x2EF59E0B), Color(0xFFFBBF24))
        LogCategory.SAFETY -> Pair(Color(0x38F59E0B), Color(0xFFF59E0B))
        LogCategory.ERROR -> Pair(Color(0x2EEF4444), Color(0xFFF87171))
    }

    val rowBg = if (isLatest) Color(0x147C5CFF) else Color(0x05FFFFFF)
    val borderLeftColor = if (isLatest) ColorAccentPrimary else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RadiusXs)
            .background(rowBg)
            .border(1.dp, borderLeftColor, RadiusXs)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = item.time,
            color = ColorTextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(48.dp)
        )
        Box(
            modifier = Modifier
                .clip(RadiusXs)
                .background(catBg)
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                text = item.category.displayName,
                color = catText,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.event,
                    color = ColorTextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
                if (isLatest) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RadiusXs)
                            .background(Color(0x407C5CFF))
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "LATEST",
                            color = Color(0xFFC4B5FD),
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (item.detail != null) {
                Text(
                    text = item.detail,
                    color = ColorTextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

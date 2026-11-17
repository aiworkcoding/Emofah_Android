package com.emofah.agencies.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emofah.agencies.reporting.DataFreshnessStatus
import com.emofah.agencies.reporting.FreshnessLevel

@Composable
fun DataFreshnessIndicatorCard(
    status: DataFreshnessStatus,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (badgeColor, textColor, icon) = when (status.freshnessLevel) {
        FreshnessLevel.FRESH -> Triple(Color(0xFFDCFCE7), Color(0xFF166534), Icons.Outlined.CheckCircle)
        FreshnessLevel.AGING -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), Icons.Outlined.Info)
        FreshnessLevel.STALE -> Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), Icons.Outlined.Warning)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    color = badgeColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(20.dp)
                    )
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = when (status.freshnessLevel) {
                                FreshnessLevel.FRESH -> "Data Up-to-Date"
                                FreshnessLevel.AGING -> "Catalogue Aging"
                                FreshnessLevel.STALE -> "Data Stale / Offline"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = textColor
                        )
                        if (!status.isOnline) {
                            Surface(
                                color = Color(0xFFF1F5F9),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "OFFLINE",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    color = Color.DarkGray
                                )
                            }
                        }
                    }
                    Text(
                        text = "Catalogue updated ${status.formattedCatalogueAge} · Last sync: ${status.formattedLastSync}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            TextButton(onClick = onRefreshClick) {
                Text("Refresh", fontSize = 12.sp)
            }
        }
    }
}

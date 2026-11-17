package com.emofah.agencies.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.recognition.CloudFallbackState
import com.emofah.agencies.recognition.MatchConfidenceStatus
import com.emofah.agencies.recognition.RecognitionCandidate
import com.emofah.agencies.recognition.RecognitionOutcome
import com.emofah.agencies.recognition.RecognitionSelectionOrigin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 1.3 + Stage 1.4 (Actions 1.3.3 & 1.4.1):
 * Displays on-device recognition candidates (and optional cloud-assisted suggestions)
 * with product photo, name, brand, variant/size, catalogue selling price, stock freshness,
 * and human confirmation controls without exposing raw technical model scores.
 */
@Composable
fun RecognitionResultScreen(
    isLoading: Boolean,
    isCheckingCloud: Boolean = false,
    isDeviceOnline: Boolean = true,
    outcome: RecognitionOutcome?,
    fallbackProducts: List<ProductIdentity>,
    onConfirmProduct: (ProductIdentity, RecognitionSelectionOrigin, Boolean) -> Unit,
    onRetryCloudMatch: () -> Unit = {},
    onRetakePhoto: () -> Unit,
    onUnidentifiedRoute: (preservedPhotoPath: String, extractedOcr: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isLoading || outcome == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            CircularProgressIndicator()
            Text(
                text = "Checking local catalogue...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Matching packaging text and visual features offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    var selectedId by remember(outcome.captureId, outcome.candidates.firstOrNull()?.product?.id) {
        mutableStateOf(outcome.candidates.firstOrNull()?.product?.id)
    }
    var manualSearchQuery by remember(outcome.captureId) { mutableStateOf("") }
    var allowImageLearning by remember(outcome.captureId) { mutableStateOf(true) }

    val selectedCandidate = outcome.candidates.firstOrNull { it.product.id == selectedId }
    val manualMatches = remember(manualSearchQuery, fallbackProducts) {
        val q = manualSearchQuery.trim().lowercase()
        if (q.length < 2) emptyList()
        else fallbackProducts.filter {
            it.name.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                (it.variantLabel?.lowercase()?.contains(q) == true)
        }.take(6)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Match Status Header (Understandable plain language, no raw scores)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when (outcome.status) {
                    MatchConfidenceStatus.STRONG_MATCH -> MaterialTheme.colorScheme.primaryContainer
                    MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES -> MaterialTheme.colorScheme.secondaryContainer
                    MatchConfidenceStatus.UNIDENTIFIED -> MaterialTheme.colorScheme.errorContainer
                },
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (outcome.status) {
                        MatchConfidenceStatus.STRONG_MATCH -> Icons.Outlined.CheckCircle
                        MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES -> Icons.Outlined.HelpOutline
                        MatchConfidenceStatus.UNIDENTIFIED -> Icons.Outlined.HelpOutline
                    },
                    contentDescription = null,
                )
                Column {
                    Text(
                        text = when (outcome.status) {
                            MatchConfidenceStatus.STRONG_MATCH -> "Likely Product Match"
                            MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES ->
                                if (outcome.usedCloudAssist) "Cloud-Assisted Match — Please Confirm"
                                else "Choose Exact Product"
                            MatchConfidenceStatus.UNIDENTIFIED -> "Product Not Identified"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = outcome.statusLabel,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Stage 1.4 Cloud Fallback Status & Explicit Trigger (never blocks local sale/search)
        if (isCheckingCloud) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudQueue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Checking online catalogue recognition...",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        } else if (outcome.status != MatchConfidenceStatus.STRONG_MATCH || outcome.usedCloudAssist) {
            val canShowCloudButton = outcome.status != MatchConfidenceStatus.STRONG_MATCH &&
                isDeviceOnline &&
                outcome.cloudFallbackState != CloudFallbackState.MATCHES_FOUND &&
                outcome.cloudFallbackState != CloudFallbackState.CACHE_HIT &&
                outcome.cloudFallbackState != CloudFallbackState.BUDGET_LIMIT_REACHED

            if (outcome.cloudNote != null || canShowCloudButton) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = when (outcome.cloudFallbackState) {
                                    CloudFallbackState.MATCHES_FOUND,
                                    CloudFallbackState.CACHE_HIT -> Icons.Outlined.CloudDone
                                    CloudFallbackState.OFFLINE_SKIPPED,
                                    CloudFallbackState.UNAVAILABLE_OR_FAILED -> Icons.Outlined.CloudOff
                                    else -> Icons.Outlined.CloudQueue
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = outcome.cloudNote ?: if (!isDeviceOnline) {
                                    "Offline mode — local catalogue & manual search remain available."
                                } else {
                                    "Need help identifying this item?"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (canShowCloudButton) {
                            OutlinedButton(
                                onClick = onRetryCloudMatch,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CloudQueue,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Try Cloud Match")
                            }
                        }
                    }
                }
            }
        }

        // Candidates List
        if (outcome.candidates.isNotEmpty()) {
            outcome.candidates.forEachIndexed { index, candidate ->
                CandidateCard(
                    candidate = candidate,
                    isBestCandidate = index == 0,
                    isSelected = candidate.product.id == selectedId,
                    onClick = { selectedId = candidate.product.id },
                )
            }

            if (selectedCandidate != null) {
                Button(
                    onClick = {
                        onConfirmProduct(
                            selectedCandidate.product,
                            RecognitionSelectionOrigin.CANDIDATE,
                            allowImageLearning,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    Text(
                        text = "Confirm: ${selectedCandidate.product.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }

        // Stage 1.6: Explicit permission toggle to associate captured photo & packaging clue
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { allowImageLearning = !allowImageLearning }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = allowImageLearning,
                onCheckedChange = { allowImageLearning = it },
            )
            Text(
                text = "Save captured photo & packaging clue after I confirm (improves future offline scans)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Manual Local Search Fallback (helpful when OCR/visual is partial)
        OutlinedTextField(
            value = manualSearchQuery,
            onValueChange = { manualSearchQuery = it },
            label = { Text("Or find product manually in local catalogue") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        manualMatches.forEach { product ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onConfirmProduct(
                            product,
                            RecognitionSelectionOrigin.MANUAL_SEARCH,
                            allowImageLearning,
                        )
                    },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = product.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${product.category} · Stock: ${product.stockQty}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Ksh ${product.effectiveSellPrice.toInt()}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Retake & Unidentified Product Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onRetakePhoto,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retake")
            }

            FilledTonalButton(
                onClick = {
                    onUnidentifiedRoute(outcome.preservedPhotoPath, outcome.extractedOcrText)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Icon(Icons.Outlined.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Not in List")
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: RecognitionCandidate,
    isBestCandidate: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val product = candidate.product
    val bitmap = remember(candidate.cachedPhotoPath) {
        candidate.cachedPhotoPath?.let { BitmapFactory.decodeFile(it) }
    }
    val stockQty = candidate.stockProjection?.provisionalAvailableQty ?: product.stockQty
    val freshnessLabel = remember(candidate.stockProjection) {
        val proj = candidate.stockProjection
        if (proj == null) {
            "Cached stock"
        } else {
            val formattedTime = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(proj.serverUpdatedAtEpochMs))
            if (proj.pendingDeltaQty != 0) {
                "Provisional (offline changes pending · synced $formattedTime)"
            } else {
                "Server synced $formattedTime"
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp),
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = product.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Inventory2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (isBestCandidate) {
                    Text(
                        text = "Top Suggestion",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                val subtitle = listOfNotNull(
                    product.brandHint,
                    product.variantLabel,
                    product.category,
                ).joinToString(" · ")
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Stock: $stockQty · $freshnessLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "Ksh ${product.effectiveSellPrice.toInt()}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

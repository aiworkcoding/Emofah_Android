package com.emofah.agencies.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.recognition.CloudFallbackState
import com.emofah.agencies.recognition.MatchConfidenceStatus
import com.emofah.agencies.recognition.RecognitionCandidate
import com.emofah.agencies.recognition.RecognitionOutcome
import com.emofah.agencies.recognition.RecognitionSelectionOrigin
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahEmptyState
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahInteractiveCard
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahLocalImage
import com.emofah.agencies.ui.components.EmofahSearchField
import com.emofah.agencies.ui.components.EmofahSectionHeader
import com.emofah.agencies.ui.components.EmofahSkeletonCard
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Refined Recognition Result screen with smart skeleton loading,
 * async thumbnails via EmofahLocalImage, and virtualized LazyColumn lists.
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
    val colors = MaterialTheme.emofahColors

    if (isLoading || outcome == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EmofahFeedbackBanner(
                title = "Checking local catalogue...",
                message = "Matching packaging text and visual features offline.",
                tone = EmofahBadgeTone.INFO,
            )
            EmofahSkeletonCard()
            EmofahSkeletonCard()
            EmofahSkeletonCard()
        }
        return
    }

    var selectedId by remember(outcome.captureId, outcome.candidates.firstOrNull()?.product?.id) {
        mutableStateOf(outcome.candidates.firstOrNull()?.product?.id)
    }
    var manualSearchQuery by remember(outcome.captureId) { mutableStateOf("") }
    var allowImageLearning by remember(outcome.captureId) { mutableStateOf(true) }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val selectedCandidate = outcome.candidates.firstOrNull { it.product.id == selectedId }
    val manualMatches = remember(manualSearchQuery, fallbackProducts) {
        val q = manualSearchQuery.trim().lowercase()
        if (q.length < 2) emptyList()
        else fallbackProducts.filter {
            it.name.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                (it.variantLabel?.lowercase()?.contains(q) == true)
        }.take(12)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Match Status Header
        item(key = "status_banner") {
            val (bannerTitle, bannerTone) = when (outcome.status) {
                MatchConfidenceStatus.STRONG_MATCH -> "Likely Product Match" to EmofahBadgeTone.SUCCESS
                MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES ->
                    (if (outcome.usedCloudAssist) "Cloud-Assisted Match — Please Confirm" else "Choose Exact Product") to EmofahBadgeTone.INFO
                MatchConfidenceStatus.UNIDENTIFIED -> "Product Not Identified" to EmofahBadgeTone.DANGER
            }
            EmofahFeedbackBanner(
                title = bannerTitle,
                message = outcome.statusLabel,
                tone = bannerTone,
            )
        }

        // Cloud Fallback Status Card
        if (isCheckingCloud) {
            item(key = "cloud_loading") {
                EmofahCard(contentPadding = PaddingValues(14.dp)) {
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
                item(key = "cloud_status") {
                    EmofahCard(contentPadding = PaddingValues(14.dp)) {
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
                                    "Offline mode — local catalogue & manual search remain ready."
                                } else {
                                    "Need help identifying this item?"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                            )
                        }
                        if (canShowCloudButton) {
                            OutlinedButton(
                                onClick = onRetryCloudMatch,
                                shape = RoundedCornerShape(12.dp),
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
            item(key = "candidates_header") {
                EmofahSectionHeader(
                    title = "Matches Found (${outcome.candidates.size})",
                    subtitle = "Tap a product card, then confirm below",
                )
            }
            items(outcome.candidates, key = { it.product.id }) { candidate ->
                val isBest = outcome.candidates.firstOrNull()?.product?.id == candidate.product.id
                CandidateCard(
                    candidate = candidate,
                    isBestCandidate = isBest,
                    isSelected = candidate.product.id == selectedId,
                    timeFormat = timeFormat,
                    onClick = { selectedId = candidate.product.id },
                )
            }
            if (selectedCandidate != null) {
                item(key = "confirm_cta") {
                    EmofahLoadingButton(
                        text = "Confirm: ${selectedCandidate.product.name}",
                        onClick = {
                            onConfirmProduct(
                                selectedCandidate.product,
                                RecognitionSelectionOrigin.CANDIDATE,
                                allowImageLearning,
                            )
                        },
                        height = 54.dp,
                    )
                }
            }
        } else {
            item(key = "no_candidates_empty") {
                EmofahEmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = "No Automatic Match",
                    message = "Search manually below or register this product if it is new to your shop.",
                )
            }
        }

        // Packaging Learning Toggle
        item(key = "image_learning_toggle") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceCard,
                border = BorderStroke(1.dp, colors.borderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { allowImageLearning = !allowImageLearning },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = allowImageLearning,
                        onCheckedChange = { allowImageLearning = it },
                    )
                    Text(
                        text = "Save photo & packaging clue after I confirm (improves future offline scans)",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                }
            }
        }

        // Manual Search Fallback
        item(key = "manual_search_input") {
            EmofahSearchField(
                value = manualSearchQuery,
                onValueChange = { manualSearchQuery = it },
                placeholder = "Find product manually in local catalogue",
            )
        }

        items(manualMatches, key = { "manual_${it.id}" }) { product ->
            EmofahInteractiveCard(
                onClick = {
                    onConfirmProduct(
                        product,
                        RecognitionSelectionOrigin.MANUAL_SEARCH,
                        allowImageLearning,
                    )
                },
                contentPadding = PaddingValues(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                            color = colors.textMuted,
                        )
                    }
                    Text(
                        text = "Ksh ${product.effectiveSellPrice.toInt()}",
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Retake & Register Actions
        item(key = "footer_actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onRetakePhoto,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retake")
                }

                FilledTonalButton(
                    onClick = {
                        onUnidentifiedRoute(outcome.preservedPhotoPath, outcome.extractedOcrText)
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                ) {
                    Icon(Icons.Outlined.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Not in List")
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: RecognitionCandidate,
    isBestCandidate: Boolean,
    isSelected: Boolean,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.emofahColors
    val product = candidate.product
    val stockQty = candidate.stockProjection?.provisionalAvailableQty ?: product.stockQty
    val freshnessLabel = remember(candidate.stockProjection, timeFormat) {
        val proj = candidate.stockProjection
        if (proj == null) {
            "Cached stock"
        } else {
            val formattedTime = timeFormat.format(Date(proj.serverUpdatedAtEpochMs))
            if (proj.pendingDeltaQty != 0) {
                "Est. (pending sync · $formattedTime)"
            } else {
                "Synced $formattedTime"
            }
        }
    }

    EmofahInteractiveCard(
        onClick = onClick,
        isSelected = isSelected,
        contentPadding = PaddingValues(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EmofahLocalImage(
                path = candidate.cachedPhotoPath,
                contentDescription = product.name,
                fallbackIcon = Icons.Outlined.Inventory2,
                maxDimensionPx = 256,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (isBestCandidate) {
                    EmofahStatusBadge(
                        text = "Top Suggestion",
                        tone = EmofahBadgeTone.INFO,
                    )
                }
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    product.brandHint,
                    product.variantLabel,
                    product.category,
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "Stock: $stockQty · $freshnessLabel",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.textMuted,
                )
            }

            Text(
                text = "Ksh ${product.effectiveSellPrice.toInt()}",
                style = MaterialTheme.typography.titleSmall.tabular(),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

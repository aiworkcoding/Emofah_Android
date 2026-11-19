package com.emofah.agencies.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular

enum class EmofahBadgeTone {
    NEUTRAL,
    INFO,
    SUCCESS,
    WARNING,
    DANGER,
}

@Composable
fun EmofahGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    shadowElevation: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.emofahColors
    Surface(
        modifier = modifier,
        shape = shape,
        color = colors.glassSurface,
        shadowElevation = shadowElevation,
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    if (colors.isDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.9f),
                    colors.borderSubtle,
                ),
            ),
        ),
        content = content,
    )
}

@Composable
fun EmofahCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    containerColor: Color = MaterialTheme.emofahColors.surfaceCard,
    borderColor: Color = MaterialTheme.emofahColors.borderSubtle,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = containerColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun EmofahInteractiveCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSelected: Boolean = false,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color = if (isSelected) {
        MaterialTheme.emofahColors.infoContainer
    } else {
        MaterialTheme.emofahColors.surfaceCard
    },
    borderColor: Color = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.emofahColors.borderSubtle
    },
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .emofahPressScale(interactionSource)
            .clip(shape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
            ),
        shape = shape,
        color = containerColor,
        shadowElevation = if (isSelected) 2.dp else 1.dp,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun EmofahSectionHeader(
    title: String,
    subtitle: String? = null,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
        if (trailingActionLabel != null && onTrailingAction != null) {
            TextButton(onClick = onTrailingAction) {
                Text(
                    text = trailingActionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun EmofahStatusBadge(
    text: String,
    tone: EmofahBadgeTone = EmofahBadgeTone.NEUTRAL,
    modifier: Modifier = Modifier,
    leadingDot: Boolean = false,
) {
    val colors = MaterialTheme.emofahColors
    val (bg, fg) = when (tone) {
        EmofahBadgeTone.NEUTRAL -> colors.surfaceMuted to colors.textMuted
        EmofahBadgeTone.INFO -> colors.infoContainer to colors.info
        EmofahBadgeTone.SUCCESS -> colors.successContainer to colors.success
        EmofahBadgeTone.WARNING -> colors.warningContainer to colors.warning
        EmofahBadgeTone.DANGER -> colors.dangerContainer to colors.danger
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (leadingDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(fg),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EmofahMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    tone: EmofahBadgeTone = EmofahBadgeTone.NEUTRAL,
) {
    val colors = MaterialTheme.emofahColors
    val (bg, accent) = when (tone) {
        EmofahBadgeTone.NEUTRAL -> colors.surfaceCard to colors.textPrimary
        EmofahBadgeTone.INFO -> colors.infoContainer to colors.info
        EmofahBadgeTone.SUCCESS -> colors.successContainer to colors.success
        EmofahBadgeTone.WARNING -> colors.warningContainer to colors.warning
        EmofahBadgeTone.DANGER -> colors.dangerContainer to colors.danger
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.tabular(),
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!sublabel.isNullOrBlank()) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun EmofahSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Clear",
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceCard,
            unfocusedContainerColor = colors.surfaceCard,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = colors.borderSubtle,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun EmofahLoadingButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    loadingText: String = text,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 52.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = {
            if (!isLoading && enabled) {
                onClick()
            }
        },
        enabled = enabled && !isLoading,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .emofahPressScale(interactionSource),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = loadingText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EmofahFeedbackBanner(
    title: String,
    message: String? = null,
    tone: EmofahBadgeTone = EmofahBadgeTone.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    val (bg, fg, icon) = when (tone) {
        EmofahBadgeTone.SUCCESS -> Triple(colors.successContainer, colors.success, Icons.Outlined.CheckCircle)
        EmofahBadgeTone.WARNING -> Triple(colors.warningContainer, colors.warning, Icons.Outlined.WarningAmber)
        EmofahBadgeTone.DANGER -> Triple(colors.dangerContainer, colors.danger, Icons.Outlined.ErrorOutline)
        EmofahBadgeTone.INFO -> Triple(colors.infoContainer, colors.info, Icons.Outlined.Info)
        EmofahBadgeTone.NEUTRAL -> Triple(colors.surfaceMuted, colors.textPrimary, Icons.Outlined.Info)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.22f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
                if (!message.isNullOrBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, fg.copy(alpha = 0.45f)),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = fg,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
fun EmofahEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.emofahColors
    EmofahCard(
        modifier = modifier,
        containerColor = colors.surfaceCard,
        contentPadding = PaddingValues(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceMuted,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(2.dp))
                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun EmofahSkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    val reducedMotion = LocalReducedMotion.current
    val colors = MaterialTheme.emofahColors
    val alpha = if (reducedMotion) {
        0.55f
    } else {
        val transition = rememberInfiniteTransition(label = "skeletonPulse")
        val animated by transition.animateFloat(
            initialValue = 0.42f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 820),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeletonAlpha",
        )
        animated
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceMuted.copy(alpha = alpha)),
    )
}

@Composable
fun EmofahSkeletonCard(
    modifier: Modifier = Modifier,
) {
    EmofahCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        EmofahSkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .height(16.dp),
        )
        EmofahSkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(12.dp),
        )
        EmofahSkeletonBox(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(12.dp),
        )
    }
}

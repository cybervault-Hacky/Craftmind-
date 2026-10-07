package com.craftmind.app.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Screen states (Phase 15 design system, §17).
 *
 * Every major screen expresses the same seven situations with the same components, so a user who has seen one empty
 * state recognises all of them: [CraftMindLoadingState], [CraftMindEmptyState], [CraftMindErrorState],
 * [CraftMindUnavailableState], [CraftMindSuccessState], [CraftMindNotice], and [CraftMindExpandableSection] for
 * diagnostics.
 *
 * Two rules are structural:
 *
 * * Nothing here fabricates progress. [CraftMindLoadingState] shows an indeterminate indicator plus a sentence that
 *   names what the app is really doing; there is no percentage and no simulated motion.
 * * Errors are written for people. Diagnostics — reason codes, adapter ids, bridge failures — are collapsed behind
 *   [CraftMindExpandableSection] so they remain reachable without ever being the first thing a user reads.
 */

/** Real work in flight. [message] names the actual stage the app is in. */
@Composable
fun CraftMindLoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    CraftMindCard(modifier = modifier, contentPadding = CraftMindLayout.xl) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(CraftMindLayout.progressMedium)
                    .semantics { contentDescription = message },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = message,
                style = CraftMindType.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Nothing to show yet, and what to do about it. */
@Composable
fun CraftMindEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    CraftMindCard(modifier = modifier, contentPadding = CraftMindLayout.xxl) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CraftMindLayout.md, Alignment.CenterVertically),
        ) {
            icon?.let {
                Box(
                    modifier = Modifier
                        .size(CraftMindLayout.iconFeature)
                        .background(MaterialTheme.colorScheme.primaryContainer, CraftMindShapes.circle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(CraftMindLayout.iconXl),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = title,
                style = CraftMindType.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = CraftMindType.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                CraftMindPrimaryButton(
                    text = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.padding(top = CraftMindLayout.sm),
                    fullWidth = false,
                )
            }
        }
    }
}

/**
 * Something failed. [message] is the human explanation; [diagnostics] are the technical lines a user may expand.
 * A retry action is offered only when retrying can actually help.
 */
@Composable
fun CraftMindErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    diagnostics: List<String> = emptyList(),
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    CraftMindCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.md)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(CraftMindLayout.iconLg),
                    tint = MaterialTheme.colorScheme.error,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xs),
                ) {
                    Text(
                        text = title,
                        style = CraftMindType.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = message,
                        style = CraftMindType.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (diagnostics.isNotEmpty()) {
                var diagnosticsExpanded by remember { mutableStateOf(false) }
                CraftMindExpandableSection(
                    title = "Technical detail",
                    summary = "${diagnostics.size} diagnostic line(s)",
                    expanded = diagnosticsExpanded,
                    onToggle = { diagnosticsExpanded = !diagnosticsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CraftMindDetailLines(diagnostics)
                }
            }
            if (retryLabel != null || onDismiss != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm, Alignment.End),
                ) {
                    onDismiss?.let { CraftMindTertiaryButton(text = "Dismiss", onClick = it) }
                    if (retryLabel != null && onRetry != null) {
                        CraftMindSecondaryButton(text = retryLabel, onClick = onRetry)
                    }
                }
            }
        }
    }
}

/**
 * An action exists but its precondition is not met — no bridge paired, no provider key, unsupported runtime.
 *
 * This is the component §7 requires instead of a context-free disabled button: it states the precondition and offers
 * the one action that can satisfy it.
 */
@Composable
fun CraftMindUnavailableState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    reasonLines: List<String> = emptyList(),
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    CraftMindCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.md)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(CraftMindLayout.iconLg),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xs),
                ) {
                    Text(
                        text = title,
                        style = CraftMindType.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = message,
                        style = CraftMindType.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (reasonLines.isNotEmpty()) {
                CraftMindDetailLines(reasonLines)
            }
            if (actionLabel != null && onAction != null) {
                CraftMindSecondaryButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

/** A real success: saved, accepted, executed, verified. */
@Composable
fun CraftMindSuccessState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    CraftMindCard(modifier = modifier, emphasized = true) {
        Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.md)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(CraftMindLayout.iconLg),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xs),
                ) {
                    Text(text = title, style = CraftMindType.titleMedium)
                    Text(text = message, style = CraftMindType.bodyMedium)
                }
            }
            if (actionLabel != null && onAction != null) {
                CraftMindPrimaryButton(text = actionLabel, onClick = onAction, fullWidth = false)
            }
        }
    }
}

/**
 * An inline notice tied to real state: a validation problem, a cancelled request, a provider message, a saved key.
 * [tone] picks the semantic colour; the icon and the text both carry the meaning so it never relies on colour alone.
 */
@Composable
fun CraftMindNotice(
    message: String,
    modifier: Modifier = Modifier,
    tone: CraftMindTone = CraftMindTone.INFORMATIVE,
    title: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = LocalCraftMindColors.current.toneColors(tone)
    val noticeIcon = icon ?: when (tone) {
        CraftMindTone.NEGATIVE -> Icons.Default.Warning
        CraftMindTone.CAUTION -> Icons.Default.Warning
        CraftMindTone.POSITIVE -> Icons.Default.CheckCircle
        else -> Icons.Default.Info
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CraftMindShapes.md,
        color = craftMindColor(colors.container),
        contentColor = craftMindColor(colors.onContainer),
        border = BorderStroke(
            CraftMindLayout.hairline,
            craftMindColor(colors.accent).copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = CraftMindLayout.lg,
                top = CraftMindLayout.md,
                end = CraftMindLayout.sm,
                bottom = CraftMindLayout.md,
            ),
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = noticeIcon,
                contentDescription = null,
                modifier = Modifier.size(CraftMindLayout.iconMd),
                tint = craftMindColor(colors.accent),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xs),
            ) {
                title?.let { Text(text = it, style = CraftMindType.titleSmall) }
                Text(text = message, style = CraftMindType.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    CraftMindTertiaryButton(text = actionLabel, onClick = onAction)
                }
            }
            onDismiss?.let {
                CraftMindIconButton(
                    icon = Icons.Default.Close,
                    description = title?.let { value -> "Dismiss $value" } ?: "Dismiss message",
                    onClick = it,
                )
            }
        }
    }
}

/**
 * Progressive disclosure.
 *
 * The honest summary is always visible; the technical detail is one tap away. Callers own [expanded] so the state can
 * be remembered per screen. When the caller passes a constant [expanded] with an empty [onToggle] the section behaves
 * as a read-only detail well.
 */
@Composable
fun CraftMindExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = LocalCraftMindMotion.current
    val enter = if (motion.animates) {
        fadeIn(tween(motion.base)) + expandVertically(tween(motion.base))
    } else {
        EnterTransition.None
    }
    val exit = if (motion.animates) {
        fadeOut(tween(motion.fast)) + shrinkVertically(tween(motion.fast))
    } else {
        ExitTransition.None
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = CraftMindLayout.minTouchTarget)
                .clickable(onClick = onToggle)
                .semantics {
                    contentDescription = if (expanded) "Hide $title" else "Show $title"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(CraftMindLayout.iconMd),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xxs),
            ) {
                Text(text = title, style = CraftMindType.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                summary?.let {
                    Text(
                        text = it,
                        style = CraftMindType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        AnimatedVisibility(visible = expanded, enter = enter, exit = exit) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = CraftMindLayout.xl, top = CraftMindLayout.sm),
                verticalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
                content = content,
            )
        }
    }
}

/** Dense technical lines in a recessed well: reason codes, adapter ids, evidence lines, diagnostics. */
@Composable
fun CraftMindDetailLines(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    CraftMindInsetPanel(modifier = modifier, contentPadding = CraftMindLayout.md) {
        lines.forEach { line ->
            Text(
                text = line,
                style = CraftMindType.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

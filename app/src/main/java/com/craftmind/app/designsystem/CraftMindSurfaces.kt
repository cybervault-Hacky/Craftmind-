package com.craftmind.app.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Structural components of the CraftMind design system (Phase 15).
 *
 * These are the only surfaces screens may build on. Later phases add screens by composing them, never by
 * re-implementing a card or a header, so every surface in the app keeps the same rhythm, hairline border language,
 * corner radius, and reading measure.
 */

/**
 * A full screen: consistent margins, a capped reading measure, and one accessible title.
 *
 * Every CraftMind screen uses this, which is what guarantees §4's promise that each screen has a clear title, a
 * stated purpose, and predictable structure. [actions] is the single place a screen-level secondary action may live;
 * the primary action belongs in the content so it stays obvious.
 *
 * @param scrolling false for screens that manage their own scrolling (a lazy list, for example).
 */
@Composable
fun CraftMindScreen(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    scrolling: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= CraftMindLayout.mediumBreakpoint
        val horizontalMargin = if (wide) CraftMindLayout.screenMarginWide else CraftMindLayout.screenMargin
        val scrollModifier = if (scrolling) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(scrollModifier)
                .padding(
                    horizontal = horizontalMargin,
                    vertical = CraftMindLayout.screenMarginVertical,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = CraftMindLayout.screenMaxWidth)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xl),
            ) {
                CraftMindScreenHeader(
                    title = title,
                    eyebrow = eyebrow,
                    subtitle = subtitle,
                    actions = actions,
                )
                content()
            }
        }
    }
}

/** Screen title block: optional eyebrow, one `heading` for accessibility, purpose line, optional actions. */
@Composable
fun CraftMindScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xxs)) {
            eyebrow?.let { CraftMindEyebrow(it) }
            Text(
                text = title,
                style = CraftMindType.headline,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
        }
        subtitle?.let {
            Text(
                text = it,
                style = CraftMindType.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = CraftMindLayout.contentMaxWidth),
            )
        }
        actions?.let { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = CraftMindLayout.xs),
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) { row() }
        }
    }
}

/** Uppercase tracked section label. Never used for reading copy, only to name a region. */
@Composable
fun CraftMindEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text.uppercase(),
        style = CraftMindType.eyebrow,
        color = color,
        modifier = modifier,
    )
}

/**
 * The standard card: white/dark surface, hairline border, low elevation, medium radius, generous inset.
 *
 * @param emphasized lifts the card one step and tints it with the brand container; at most one per screen region so
 *   the primary content stays obvious.
 */
@Composable
fun CraftMindCard(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    shape: Shape = CraftMindShapes.md,
    contentPadding: Dp = CraftMindLayout.cardInset,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = if (emphasized) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (emphasized) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            CraftMindLayout.hairline,
            if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = if (emphasized) CraftMindLayout.raised else CraftMindLayout.cardElevation,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
            content = content,
        )
    }
}

/**
 * A recessed panel for technical detail, diagnostics, and quoted values.
 *
 * Depth here is expressed by lightness, never by shadow or glow: the panel sits *below* its card so dense technical
 * information reads as supporting material rather than competing with the summary above it.
 */
@Composable
fun CraftMindInsetPanel(
    modifier: Modifier = Modifier,
    contentPadding: Dp = CraftMindLayout.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CraftMindShapes.sm,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
            content = content,
        )
    }
}

/** Card or panel title block: eyebrow, title, optional supporting line. */
@Composable
fun CraftMindSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xxs),
        ) {
            eyebrow?.let { CraftMindEyebrow(it) }
            Text(
                text = title,
                style = CraftMindType.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading(level = 2) },
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = CraftMindType.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.let { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
            ) { row() }
        }
    }
}

/** A labelled fact row: metadata on the left, value on the right, wrapping rather than truncating. */
@Composable
fun CraftMindKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = CraftMindType.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 104.dp, max = 176.dp),
        )
        Text(
            text = value,
            style = CraftMindType.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start,
        )
    }
}

/** A hairline separator. Separation is primarily carried by spacing and surface tone; dividers are the last resort. */
@Composable
fun CraftMindDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = CraftMindLayout.hairline,
        color = color,
    )
}

/**
 * The CraftMind brand mark: two offset squares on a brand-coloured tile.
 *
 * It is an abstract nod to stacked blocks, drawn only from design-system colours. It deliberately is not a pixel-art
 * logo, not a texture, and not derived from any game asset.
 */
@Composable
fun CraftMindBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = CraftMindLayout.brandMark,
) {
    Box(
        modifier = modifier.size(size).background(MaterialTheme.colorScheme.primary, CraftMindShapes.sm),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(size / 14f),
        ) {
            Box(
                Modifier
                    .size(size / 3.2f)
                    .background(MaterialTheme.colorScheme.onPrimary, CraftMindShapes.xs),
            )
            Box(
                Modifier
                    .size(size / 4.8f)
                    .background(
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
                        CraftMindShapes.xs,
                    ),
            )
        }
    }
}

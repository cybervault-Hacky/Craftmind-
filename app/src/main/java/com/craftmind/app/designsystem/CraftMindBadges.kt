package com.craftmind.app.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/**
 * Status and selection indicators (Phase 15 design system).
 *
 * A badge states a fact the app actually holds — "Certified", "Incompatible", "Version 3", "Experimental" — and takes
 * its colour from a semantic [CraftMindTone], never from an arbitrary palette pick. Badges are always text: colour is
 * a reinforcement for sighted users, never the only carrier of meaning, and a decorative dot is only ever shown next
 * to a text badge.
 */

/** A pill stating one real status. */
@Composable
fun CraftMindStatusBadge(
    label: String,
    modifier: Modifier = Modifier,
    tone: CraftMindTone = CraftMindTone.NEUTRAL,
    icon: ImageVector? = null,
) {
    val colors = LocalCraftMindColors.current.toneColors(tone)
    Surface(
        modifier = modifier.widthIn(max = CraftMindLayout.contentMaxWidth),
        shape = CraftMindShapes.pill,
        color = craftMindColor(colors.container),
        contentColor = craftMindColor(colors.onContainer),
        border = BorderStroke(CraftMindLayout.hairline, craftMindColor(colors.accent).copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = CraftMindLayout.md,
                vertical = CraftMindLayout.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.xs),
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(CraftMindLayout.iconXs),
                    tint = craftMindColor(colors.accent),
                )
            }
            Text(
                text = label,
                style = CraftMindType.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A quiet metadata chip: version numbers, counts, source types. Not interactive, not a status. */
@Composable
fun CraftMindMetaChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = CraftMindShapes.xs,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = CraftMindLayout.sm,
                vertical = CraftMindLayout.xxs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.xs),
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(CraftMindLayout.iconXs),
                )
            }
            Text(text = label, style = CraftMindType.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A single-choice selection chip, used for input modes and filters.
 *
 * The visible pill is compact; the touch target is padded out to the 48dp minimum, which is why the outer box exists.
 * Selection is exposed to accessibility services through [Role.RadioButton] and the `selected` semantic property.
 */
@Composable
fun CraftMindSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = CraftMindLayout.minTouchTarget)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = CraftMindLayout.compactTouchPadding)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            shape = CraftMindShapes.sm,
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            border = BorderStroke(
                if (selected) CraftMindLayout.emphasis else CraftMindLayout.hairline,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .height(CraftMindLayout.compactHeight)
                    .padding(horizontal = CraftMindLayout.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
            ) {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(CraftMindLayout.iconSm),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(text = label, style = CraftMindType.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A decorative state dot. It only ever accompanies a text badge — never replaces one — so it is marked as not
 * important for accessibility by the caller's surrounding semantics.
 */
@Composable
fun CraftMindStatusDot(
    tone: CraftMindTone,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCraftMindColors.current.toneColors(tone)
    Box(
        modifier = modifier
            .size(CraftMindLayout.sm)
            .background(craftMindColor(colors.accent), CraftMindShapes.circle),
    )
}

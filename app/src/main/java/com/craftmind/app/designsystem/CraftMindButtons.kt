package com.craftmind.app.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Button system (Phase 15 design system).
 *
 * Four emphasis levels and nothing else, so the primary action is always unambiguous:
 *
 * 1. [CraftMindPrimaryButton] — the one thing this screen is for. At most one per screen region, filled brand colour,
 *    the tallest control in the system.
 * 2. [CraftMindSecondaryButton] — a real alternative action, outlined, same height as standard controls.
 * 3. [CraftMindTertiaryButton] — low-emphasis navigation or a supplementary action.
 * 4. [CraftMindDestructiveButton] — irreversible actions (discard, revoke, forget), labelled in the error token and
 *    always paired with a confirmation dialog by the caller.
 *
 * [loading] must reflect a coroutine that is actually running; the indicator is a real in-flight signal, never
 * decoration. A disabled button explains itself through the copy beside it — screens pair every disabled state with
 * a [CraftMindNotice] or an [CraftMindUnavailableState] that says why.
 */

@Composable
fun CraftMindPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    fullWidth: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(CraftMindLayout.controlHeightLarge),
        shape = CraftMindShapes.sm,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = CraftMindLayout.controls,
            pressedElevation = CraftMindLayout.raised,
            focusedElevation = CraftMindLayout.controls,
            disabledElevation = CraftMindLayout.flat,
        ),
        contentPadding = PaddingValues(horizontal = CraftMindLayout.xl),
    ) {
        CraftMindButtonContent(text = text, icon = icon, loading = loading, style = CraftMindType.labelLarge)
    }
}

@Composable
fun CraftMindSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    fullWidth: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(CraftMindLayout.controlHeight),
        shape = CraftMindShapes.sm,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = BorderStroke(CraftMindLayout.hairline, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = CraftMindLayout.lg),
    ) {
        CraftMindButtonContent(text = text, icon = icon, loading = loading, style = CraftMindType.labelLarge)
    }
}

@Composable
fun CraftMindTertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(CraftMindLayout.controlHeight),
        shape = CraftMindShapes.sm,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = CraftMindLayout.md),
    ) {
        CraftMindButtonContent(text = text, icon = icon, loading = loading, style = CraftMindType.labelLarge)
    }
}

@Composable
fun CraftMindDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(CraftMindLayout.controlHeight),
        shape = CraftMindShapes.sm,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = CraftMindLayout.md),
    ) {
        CraftMindButtonContent(text = text, icon = null, loading = loading, style = CraftMindType.labelLarge)
    }
}

/**
 * Icon-only action. [description] is mandatory: an icon button with no accessible name is a defect, so the design
 * system does not offer a default.
 */
@Composable
fun CraftMindIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = CraftMindLayout.minTouchTarget,
                minHeight = CraftMindLayout.minTouchTarget,
            )
            .clickable(enabled = enabled, onClick = onClick, onClickLabel = description),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(CraftMindLayout.iconLg),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
        )
    }
}

@Composable
private fun CraftMindButtonContent(
    text: String,
    icon: ImageVector?,
    loading: Boolean,
    style: TextStyle,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm, Alignment.CenterHorizontally),
    ) {
        when {
            // Inherits the button's content colour, so the spinner stays legible on filled and outlined buttons.
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(CraftMindLayout.progressSmall),
                strokeWidth = 2.dp,
            )

            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(CraftMindLayout.iconSm),
            )
        }
        Text(text = text, style = style)
    }
}

/**
 * Accessible modifier for any interactive element that must expose a semantic description distinct from its visible
 * text (icon rows, chips with supporting detail, thumbnail actions).
 */
fun Modifier.craftMindDescription(description: String): Modifier =
    semantics { contentDescription = description }

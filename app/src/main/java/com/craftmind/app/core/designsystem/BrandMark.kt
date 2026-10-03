package com.craftmind.app.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import com.craftmind.app.R
import androidx.compose.material3.MaterialTheme

@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = ComponentSize.brandMark,
) {
    val background = MaterialTheme.colorScheme.primaryContainer
    val blocks = MaterialTheme.colorScheme.onPrimaryContainer
    val description = stringResource(R.string.brand_mark_description)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(Corners.medium))
            .background(background)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(size)) {
            drawCraftBlocks(blocks)
        }
    }
}

@Composable
fun BlockGlyph(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier) {
        val side = size.minDimension
        val cell = side * 0.22f
        val gap = side * 0.065f
        val left = (size.width - (cell * 3f + gap * 2f)) / 2f
        val top = (size.height - (cell * 3f + gap * 2f)) / 2f
        val pattern = listOf(
            1 to 0,
            0 to 1, 1 to 1, 2 to 1,
            1 to 2,
        )
        pattern.forEach { (column, row) ->
            drawRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = left + column * (cell + gap),
                    y = top + row * (cell + gap),
                ),
                size = androidx.compose.ui.geometry.Size(cell, cell),
            )
        }
    }
}

private fun DrawScope.drawCraftBlocks(color: Color) {
    val cell = size.minDimension * 0.19f
    val gap = size.minDimension * 0.055f
    val left = (size.width - (cell * 3f + gap * 2f)) / 2f
    val top = (size.height - (cell * 3f + gap * 2f)) / 2f
    val cells = listOf(
        1 to 0,
        0 to 1, 1 to 1, 2 to 1,
        1 to 2,
    )
    cells.forEachIndexed { index, (column, row) ->
        val origin = androidx.compose.ui.geometry.Offset(
            left + column * (cell + gap),
            top + row * (cell + gap),
        )
        drawRect(color, origin, androidx.compose.ui.geometry.Size(cell, cell))
        if (index == 2) {
            drawRect(
                color = color.copy(alpha = 0.42f),
                topLeft = origin,
                size = androidx.compose.ui.geometry.Size(cell, cell),
                style = Stroke(width = ComponentSize.border.toPx()),
            )
        }
    }
}

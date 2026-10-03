package com.craftmind.app.presentation.builds

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craftmind.app.R
import com.craftmind.app.core.designsystem.BlockGlyph
import com.craftmind.app.core.designsystem.BrandMark
import com.craftmind.app.core.designsystem.ComponentSize
import com.craftmind.app.core.designsystem.Corners
import com.craftmind.app.core.designsystem.IconSize
import com.craftmind.app.core.designsystem.LayoutBreakpoint
import com.craftmind.app.core.designsystem.Space
import com.craftmind.app.domain.model.BuildResult

@Composable
fun BuildsScreen(
    history: List<BuildResult>,
    onStartBuild: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = Space.contentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(
                    horizontal = if (maxWidth >= LayoutBreakpoint.navigationRail) Space.xxxl else Space.pageHorizontal,
                    vertical = Space.xl,
                ),
            verticalArrangement = Arrangement.spacedBy(Space.xxl),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                BrandMark(size = ComponentSize.brandMark)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.builds_eyebrow),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.1.sp,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(
                    text = stringResource(R.string.builds_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.builds_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (history.isEmpty()) {
                EmptyBuilds(onStartBuild = onStartBuild)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    history.forEach { result -> BuildPlanCard(result) }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Corners.medium),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                ) {
                    Row(
                        modifier = Modifier.padding(Space.md),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(IconSize.medium),
                        )
                        Text(
                            text = stringResource(R.string.builds_session_only_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyBuilds(onStartBuild: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Corners.extraLarge),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        border = BorderStroke(
            ComponentSize.border,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.xxl, vertical = Space.huge),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Surface(
                    modifier = Modifier.size(ComponentSize.emptyStateMark),
                    shape = RoundedCornerShape(Corners.large),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        BlockGlyph(Modifier.size(IconSize.display))
                    }
                }
                Text(
                    text = stringResource(R.string.builds_empty_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.builds_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 23.sp,
                )
                Button(
                    onClick = onStartBuild,
                    modifier = Modifier.padding(top = Space.xs),
                    shape = RoundedCornerShape(Corners.medium),
                ) {
                    Text(stringResource(R.string.start_a_build))
                    Spacer(Modifier.size(Space.xs))
                    Icon(
                        imageVector = Icons.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.medium),
                    )
                }
            }
        }
    }
}

@Composable
private fun BuildPlanCard(result: BuildResult) {
    val plan = result.plan
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Corners.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            ComponentSize.border,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = plan.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = plan.style,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.plan_dimensions_and_operations,
                    plan.dimensions.width,
                    plan.dimensions.length,
                    plan.dimensions.height,
                    plan.operations.size,
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    R.string.plan_materials,
                    plan.materials.joinToString { "${it.blockIdentifier} × ${it.count}" },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.plan_review_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

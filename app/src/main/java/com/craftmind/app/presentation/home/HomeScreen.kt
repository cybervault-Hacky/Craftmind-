package com.craftmind.app.presentation.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.InsertLink
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.craftmind.app.domain.media.ImageReferenceRepository
import com.craftmind.app.domain.model.BuildResult
import com.craftmind.app.presentation.builder.BuilderComposer
import com.craftmind.app.presentation.builder.BuilderUiState

@Composable
fun HomeScreen(
    state: BuilderUiState,
    imageReferenceRepository: ImageReferenceRepository,
    onPromptChanged: (String) -> Unit,
    onChooseImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onUrlEditorVisibilityChanged: (Boolean) -> Unit,
    onUrlDraftChanged: (String) -> Unit,
    onAddUrlReference: () -> Unit,
    onRemoveUrlReference: () -> Unit,
    onBuildPressed: () -> Unit,
    onCancelGeneration: () -> Unit,
    onRetryGeneration: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissSubmissionNotice: () -> Unit,
    onOpenBuilds: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= LayoutBreakpoint.twoColumnBuilder
        val horizontalInset = if (maxWidth >= LayoutBreakpoint.navigationRail) Space.xxxl else Space.pageHorizontal
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = Space.contentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = horizontalInset, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xxl),
        ) {
            BrandHeader()

            if (wideLayout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.xxxl),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.86f)
                            .padding(top = Space.sm),
                        verticalArrangement = Arrangement.spacedBy(Space.xl),
                    ) {
                        HomeHero(wide = true)
                        CapabilityPills()
                    }
                    BuilderComposer(
                        state = state,
                        imageReferenceRepository = imageReferenceRepository,
                        onPromptChanged = onPromptChanged,
                        onChooseImage = onChooseImage,
                        onRemoveImage = onRemoveImage,
                        onUrlEditorVisibilityChanged = onUrlEditorVisibilityChanged,
                        onUrlDraftChanged = onUrlDraftChanged,
                        onAddUrlReference = onAddUrlReference,
                        onRemoveUrlReference = onRemoveUrlReference,
                        onBuildPressed = onBuildPressed,
                        onCancelGeneration = onCancelGeneration,
                        onRetryGeneration = onRetryGeneration,
                        onOpenSettings = onOpenSettings,
                        onOpenBuilds = onOpenBuilds,
                        onDismissSubmissionNotice = onDismissSubmissionNotice,
                        modifier = Modifier.weight(1.14f),
                    )
                }
            } else {
                HomeHero(wide = false)
                BuilderComposer(
                    state = state,
                    imageReferenceRepository = imageReferenceRepository,
                    onPromptChanged = onPromptChanged,
                    onChooseImage = onChooseImage,
                    onRemoveImage = onRemoveImage,
                    onUrlEditorVisibilityChanged = onUrlEditorVisibilityChanged,
                    onUrlDraftChanged = onUrlDraftChanged,
                    onAddUrlReference = onAddUrlReference,
                    onRemoveUrlReference = onRemoveUrlReference,
                    onBuildPressed = onBuildPressed,
                    onCancelGeneration = onCancelGeneration,
                    onRetryGeneration = onRetryGeneration,
                    onOpenSettings = onOpenSettings,
                    onOpenBuilds = onOpenBuilds,
                    onDismissSubmissionNotice = onDismissSubmissionNotice,
                )
            }

            RecentBuildsPreview(history = state.buildHistory, onOpenBuilds = onOpenBuilds)
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
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
                    text = stringResource(R.string.brand_tagline),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.1.sp,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(Corners.pill),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(ComponentSize.phaseIndicator)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    text = stringResource(R.string.phase_status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}

@Composable
private fun HomeHero(wide: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            BlockGlyph(Modifier.size(IconSize.small))
            Text(
                text = stringResource(R.string.home_eyebrow),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.1.sp,
            )
        }
        Text(
            text = stringResource(R.string.home_title),
            style = if (wide) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            lineHeight = if (wide) 48.sp else 38.sp,
        )
        Text(
            text = stringResource(R.string.home_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 25.sp,
            modifier = Modifier.widthIn(max = if (wide) 440.dp else 620.dp),
        )
    }
}

@Composable
private fun CapabilityPills() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CapabilityPill(
            icon = Icons.Outlined.TextFields,
            label = stringResource(R.string.capability_text),
        )
        CapabilityPill(
            icon = Icons.Outlined.AddPhotoAlternate,
            label = stringResource(R.string.capability_image),
        )
        CapabilityPill(
            icon = Icons.Outlined.InsertLink,
            label = stringResource(R.string.capability_url),
        )
    }
}

@Composable
private fun CapabilityPill(
    icon: ImageVector,
    label: String,
) {
    Surface(
        shape = RoundedCornerShape(Corners.pill),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(IconSize.small),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.6.sp,
            )
        }
    }
}

@Composable
private fun RecentBuildsPreview(history: List<BuildResult>, onOpenBuilds: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.recent_builds_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(
                onClick = onOpenBuilds,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(stringResource(R.string.see_all_builds))
                Spacer(Modifier.width(Space.xs))
                Icon(
                    imageVector = Icons.Outlined.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.small),
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Corners.large),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
            border = androidx.compose.foundation.BorderStroke(
                ComponentSize.border,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(Space.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Surface(
                    modifier = Modifier.size(ComponentSize.smallIconContainer),
                    shape = RoundedCornerShape(Corners.medium),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        BlockGlyph(Modifier.size(IconSize.medium))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    val latest = history.firstOrNull()
                    Text(
                        text = latest?.plan?.title ?: stringResource(R.string.recent_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (latest == null) {
                            stringResource(R.string.recent_empty_body)
                        } else {
                            stringResource(
                                R.string.recent_plan_summary,
                                latest.plan.dimensions.width,
                                latest.plan.dimensions.length,
                                latest.plan.dimensions.height,
                                latest.plan.operations.size,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

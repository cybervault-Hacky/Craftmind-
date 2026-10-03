package com.craftmind.app.presentation.builder

import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.InsertLink
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.craftmind.app.R
import com.craftmind.app.core.designsystem.ComponentSize
import com.craftmind.app.core.designsystem.Corners
import com.craftmind.app.core.designsystem.Elevation
import com.craftmind.app.core.designsystem.IconSize
import com.craftmind.app.core.designsystem.MotionDuration
import com.craftmind.app.core.designsystem.Space
import com.craftmind.app.domain.media.ImageReferenceRepository
import com.craftmind.app.domain.media.ImageValidationError
import com.craftmind.app.domain.model.BuildErrorCode
import com.craftmind.app.domain.model.BuildPlanValidationIssue
import com.craftmind.app.domain.model.BuildResult
import com.craftmind.app.domain.model.ImageReference
import com.craftmind.app.domain.model.UrlReference
import com.craftmind.app.domain.validation.UrlValidationError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BuilderComposer(
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
    onOpenBuilds: () -> Unit,
    onDismissSubmissionNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var promptFocused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (promptFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
        },
        animationSpec = tween(MotionDuration.standardMillis),
        label = "builder-focus-border",
    )

    val promptCounterDescription = stringResource(
        R.string.prompt_counter_description,
        state.prompt.length,
        com.craftmind.app.domain.validation.BuilderInputValidator.MAX_PROMPT_LENGTH,
    )
    val buildButtonDescription = stringResource(R.string.build_button_accessibility)
    val cancelButtonDescription = stringResource(R.string.cancel_generation)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(Corners.large),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(ComponentSize.border, borderColor),
        tonalElevation = Elevation.subtle,
    ) {
        Column(
            modifier = Modifier.padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.home_input_heading),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.prompt_counter,
                        state.prompt.length,
                        com.craftmind.app.domain.validation.BuilderInputValidator.MAX_PROMPT_LENGTH,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = promptCounterDescription
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.home_input_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextField(
                value = state.prompt,
                onValueChange = onPromptChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ComponentSize.promptMinHeight, max = ComponentSize.promptMaxHeight)
                    .testTag("builder-prompt")
                    .onFocusChanged { promptFocused = it.isFocused },
                label = { Text(stringResource(R.string.prompt_label)) },
                placeholder = {
                    Text(
                        text = stringResource(R.string.prompt_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
                    )
                },
                minLines = 4,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )

            AnimatedVisibility(
                visible = state.inputError != null,
                enter = fadeIn(tween(MotionDuration.quickMillis)) + expandVertically(),
                exit = fadeOut(tween(MotionDuration.quickMillis)) + shrinkVertically(),
            ) {
                state.inputError?.let { error ->
                    ErrorLine(
                        message = when (error) {
                            BuilderInputError.MissingInput -> stringResource(R.string.error_missing_input)
                            BuilderInputError.PromptTooLong -> stringResource(R.string.error_prompt_too_long)
                            BuilderInputError.ImageSelectionInProgress -> stringResource(R.string.error_image_pending)
                            BuilderInputError.UrlReferenceUnconfirmed -> stringResource(R.string.error_url_unconfirmed)
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                ComposerAction(
                    icon = Icons.Outlined.AddPhotoAlternate,
                    text = stringResource(
                        if (state.image == null) R.string.add_image else R.string.replace_image,
                    ),
                    onClick = onChooseImage,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                ComposerAction(
                    icon = Icons.Outlined.Link,
                    text = if (state.url == null) {
                        stringResource(R.string.add_reference)
                    } else {
                        stringResource(R.string.url_replace_reference)
                    },
                    onClick = { onUrlEditorVisibilityChanged(!state.isUrlEditorVisible) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                )
                if (state.isInspectingImage) {
                    Spacer(Modifier.weight(1f))
                    CircularProgressIndicator(
                        modifier = Modifier.size(IconSize.small),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.checking_image),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = state.image != null,
                enter = fadeIn(tween(MotionDuration.standardMillis)) + expandVertically(),
                exit = fadeOut(tween(MotionDuration.quickMillis)) + shrinkVertically(),
            ) {
                state.image?.let { image ->
                    ImageReferencePreview(
                        image = image,
                        repository = imageReferenceRepository,
                        onRemove = onRemoveImage,
                    )
                }
            }

            AnimatedVisibility(
                visible = state.imageError != null,
                enter = fadeIn(tween(MotionDuration.quickMillis)) + expandVertically(),
                exit = fadeOut(tween(MotionDuration.quickMillis)) + shrinkVertically(),
            ) {
                state.imageError?.let { ErrorLine(imageErrorMessage(it)) }
            }

            AnimatedVisibility(
                visible = state.url != null,
                enter = fadeIn(tween(MotionDuration.standardMillis)) + expandVertically(),
                exit = fadeOut(tween(MotionDuration.quickMillis)) + shrinkVertically(),
            ) {
                state.url?.let { UrlReferencePreview(it, onRemoveUrlReference) }
            }

            AnimatedVisibility(
                visible = state.isUrlEditorVisible,
                enter = fadeIn(tween(MotionDuration.standardMillis)) + expandVertically(),
                exit = fadeOut(tween(MotionDuration.quickMillis)) + shrinkVertically(),
            ) {
                UrlReferenceEditor(
                    value = state.urlDraft,
                    error = state.urlError,
                    isReplacement = state.url != null,
                    onValueChanged = onUrlDraftChanged,
                    onAdd = onAddUrlReference,
                    onCancel = { onUrlEditorVisibilityChanged(false) },
                )
            }

            if (state.urlError != null && !state.isUrlEditorVisible) {
                ErrorLine(urlErrorMessage(state.urlError))
            }

            val interactionSource = remember { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()
            val scale by animateFloatAsState(
                targetValue = if (hovered) 1.012f else 1f,
                animationSpec = tween(MotionDuration.quickMillis),
                label = "build-button-hover",
            )
            val isWorking = state.submission == BuilderSubmissionState.Validating ||
                state.submission == BuilderSubmissionState.Generating ||
                state.submission == BuilderSubmissionState.ValidatingPlan
            Button(
                onClick = if (isWorking) onCancelGeneration else onBuildPressed,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = ComponentSize.buttonHeight)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .hoverable(interactionSource)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .semantics {
                        contentDescription = if (isWorking) {
                            cancelButtonDescription
                        } else {
                            buildButtonDescription
                        }
                    },
                interactionSource = interactionSource,
                shape = RoundedCornerShape(Corners.medium),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(
                    text = stringResource(if (isWorking) R.string.cancel_generation else R.string.build_button),
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.size(Space.xs))
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(IconSize.medium),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.medium),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.small),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(Space.xs))
                Text(
                    text = stringResource(R.string.local_only_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = state.submission !is BuilderSubmissionState.Idle,
                enter = fadeIn(tween(MotionDuration.standardMillis)) + expandVertically(),
                exit = fadeOut(tween(MotionDuration.quickMillis)) + shrinkVertically(),
            ) {
                when (val submission = state.submission) {
                    BuilderSubmissionState.Idle -> Unit
                    BuilderSubmissionState.Validating -> BuildWorkingNotice(
                        title = stringResource(R.string.build_validating_request),
                        onCancel = onCancelGeneration,
                    )
                    BuilderSubmissionState.Generating -> BuildWorkingNotice(
                        title = stringResource(R.string.build_generating_plan),
                        onCancel = onCancelGeneration,
                    )
                    BuilderSubmissionState.ValidatingPlan -> BuildWorkingNotice(
                        title = stringResource(R.string.build_validating_plan),
                        onCancel = onCancelGeneration,
                    )
                    is BuilderSubmissionState.Ready -> BuildCompletedNotice(
                        result = submission.result,
                        onOpenBuilds = onOpenBuilds,
                        onDismiss = onDismissSubmissionNotice,
                    )
                    is BuilderSubmissionState.Failed -> BuildFailedNotice(
                        error = submission.error,
                        onRetry = onRetryGeneration,
                        onOpenSettings = onOpenSettings,
                        onDismiss = onDismissSubmissionNotice,
                    )
                    BuilderSubmissionState.Cancelled -> BuildCancelledNotice(onDismissSubmissionNotice)
                }
            }
        }
    }
}

@Composable
private fun ComposerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ComponentSize.touchTarget),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Space.sm,
            vertical = Space.xs,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize.medium))
        Spacer(Modifier.size(Space.xs))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ImageReferencePreview(
    image: ImageReference,
    repository: ImageReferenceRepository,
    onRemove: () -> Unit,
) {
    var preview by remember(image.uri) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(image.uri) { mutableStateOf(true) }
    LaunchedEffect(image.uri) {
        loading = true
        val thumbnail = withContext(Dispatchers.IO) {
            repository.loadThumbnail(image.uri, maxDimensionPixels = 320)
        }
        preview = withContext(Dispatchers.Default) {
            thumbnail?.let {
                BitmapFactory.decodeByteArray(it.encodedBytes, 0, it.encodedBytes.size)?.asImageBitmap()
            }
        }
        loading = false
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Corners.medium),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(ComponentSize.border, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f)),
    ) {
        Row(
            modifier = Modifier.padding(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Box(
                modifier = Modifier
                    .size(ComponentSize.imageThumbnail)
                    .clip(RoundedCornerShape(Corners.small))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    preview != null -> Image(
                        bitmap = preview!!,
                        contentDescription = stringResource(R.string.image_preview_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    loading -> CircularProgressIndicator(
                        modifier = Modifier.size(IconSize.medium),
                        strokeWidth = 2.dp,
                    )
                    else -> Icon(
                        imageVector = Icons.Outlined.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.image_preview_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                Text(
                    text = image.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Formatter.formatShortFileSize(LocalContext.current, image.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.remove_image),
                    modifier = Modifier.size(IconSize.medium),
                )
            }
        }
    }
}

@Composable
private fun UrlReferencePreview(
    url: UrlReference,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Corners.medium),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.68f),
    ) {
        Row(
            modifier = Modifier.padding(start = Space.md, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.InsertLink,
                contentDescription = stringResource(R.string.url_chip_label),
                modifier = Modifier.size(IconSize.medium),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(
                modifier = Modifier.weight(1f).padding(vertical = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                Text(
                    text = stringResource(R.string.url_chip_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                )
                Text(
                    text = url.normalizedUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.url_remove_reference),
                    modifier = Modifier.size(IconSize.medium),
                )
            }
        }
    }
}

@Composable
private fun UrlReferenceEditor(
    value: String,
    error: UrlValidationError?,
    isReplacement: Boolean,
    onValueChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    val editorDescription = stringResource(R.string.url_editor_accessibility)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.medium))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            text = stringResource(R.string.url_editor_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChanged,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = editorDescription },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.url_placeholder)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { onAdd() }),
            isError = error != null,
        )
        if (error != null) ErrorLine(urlErrorMessage(error))
        Text(
            text = stringResource(R.string.url_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.dialog_close))
            }
            Button(onClick = onAdd) {
                Text(
                    if (isReplacement) {
                        stringResource(R.string.url_replace_reference)
                    } else {
                        stringResource(R.string.url_add_reference)
                    },
                )
            }
        }
    }
}

@Composable
private fun BuildWorkingNotice(
    title: String,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(Corners.medium),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(IconSize.medium),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel_generation))
            }
        }
    }
}

@Composable
private fun BuildCompletedNotice(
    result: BuildResult,
    onOpenBuilds: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(Corners.medium),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(IconSize.large),
                )
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.xxs),
                ) {
                    Text(
                        text = stringResource(R.string.build_completed_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = result.plan.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = stringResource(
                            R.string.build_completed_summary,
                            result.plan.dimensions.width,
                            result.plan.dimensions.length,
                            result.plan.dimensions.height,
                            result.plan.operations.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.dismiss_status),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Text(
                text = stringResource(R.string.build_no_execution_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onOpenBuilds) { Text(stringResource(R.string.view_build_plan)) }
        }
    }
}

@Composable
private fun BuildFailedNotice(
    error: com.craftmind.app.domain.model.BuildError,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val needsSetup = error.code in setOf(
        BuildErrorCode.MISSING_PROVIDER,
        BuildErrorCode.UNSUPPORTED_PROVIDER,
        BuildErrorCode.MISSING_MODEL,
        BuildErrorCode.UNSUPPORTED_MODEL,
        BuildErrorCode.MISSING_API_KEY,
        BuildErrorCode.CREDENTIAL_STORAGE_FAILED,
    )
    val message = when (error.code) {
        BuildErrorCode.INVALID_INPUT -> stringResource(R.string.build_error_invalid_input)
        BuildErrorCode.UNSUPPORTED_REFERENCE -> stringResource(R.string.build_error_unsupported_reference)
        BuildErrorCode.MISSING_PROVIDER -> stringResource(R.string.build_error_missing_provider)
        BuildErrorCode.UNSUPPORTED_PROVIDER -> stringResource(R.string.build_error_unsupported_provider)
        BuildErrorCode.MISSING_MODEL -> stringResource(R.string.build_error_missing_model)
        BuildErrorCode.UNSUPPORTED_MODEL -> stringResource(R.string.build_error_unsupported_model)
        BuildErrorCode.MISSING_API_KEY -> stringResource(R.string.build_error_missing_api_key)
        BuildErrorCode.INVALID_API_KEY -> stringResource(R.string.build_error_invalid_api_key)
        BuildErrorCode.PROVIDER_UNAVAILABLE -> stringResource(R.string.build_error_provider_unavailable)
        BuildErrorCode.NO_INTERNET -> stringResource(R.string.build_error_no_internet)
        BuildErrorCode.REQUEST_TIMED_OUT -> stringResource(R.string.build_error_timed_out)
        BuildErrorCode.RATE_LIMITED -> stringResource(R.string.build_error_rate_limited)
        BuildErrorCode.PROVIDER_REJECTED_REQUEST -> stringResource(R.string.build_error_rejected)
        BuildErrorCode.MALFORMED_RESPONSE -> stringResource(R.string.build_error_malformed_response)
        BuildErrorCode.RESPONSE_TOO_LARGE -> stringResource(R.string.build_error_response_too_large)
        BuildErrorCode.BUILD_PLAN_REJECTED -> stringResource(
            when (error.validationIssue) {
                BuildPlanValidationIssue.UNSUPPORTED_SCHEMA_VERSION -> R.string.build_error_unsupported_schema
                BuildPlanValidationIssue.UNSUPPORTED_MATERIAL,
                BuildPlanValidationIssue.UNSUPPORTED_BLOCK -> R.string.build_error_unsupported_material
                BuildPlanValidationIssue.TOO_MANY_OPERATIONS,
                BuildPlanValidationIssue.PLAN_VOLUME_TOO_LARGE -> R.string.build_error_plan_too_large
                else -> R.string.build_error_plan_rejected
            },
        )
        BuildErrorCode.CREDENTIAL_STORAGE_FAILED -> stringResource(R.string.build_error_credential_storage)
        BuildErrorCode.CANCELLED -> stringResource(R.string.build_error_cancelled)
        BuildErrorCode.UNKNOWN -> stringResource(R.string.build_error_unknown)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(Corners.medium),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(IconSize.large),
                )
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.xxs),
                ) {
                    Text(
                        text = stringResource(R.string.build_failed_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.dismiss_status),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                if (error.retryable) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry_request)) }
                if (needsSetup) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.configure_provider)) }
            }
        }
    }
}

@Composable
private fun BuildCancelledNotice(onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(Corners.medium),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.large),
            )
            Text(
                text = stringResource(R.string.build_cancelled_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss_status)) }
        }
    }
}

@Composable
private fun ErrorLine(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(IconSize.medium),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun imageErrorMessage(error: ImageValidationError): String = when (error) {
    ImageValidationError.UNSUPPORTED_FORMAT -> stringResource(R.string.error_image_format)
    ImageValidationError.FILE_TOO_LARGE -> stringResource(R.string.error_image_size)
    ImageValidationError.SIZE_UNAVAILABLE -> stringResource(R.string.error_image_size_unknown)
    ImageValidationError.UNREADABLE -> stringResource(R.string.error_image_unreadable)
}

@Composable
private fun urlErrorMessage(error: UrlValidationError): String = when (error) {
    UrlValidationError.EMPTY -> stringResource(R.string.error_url_empty)
    UrlValidationError.TOO_LONG -> stringResource(R.string.error_url_too_long)
    UrlValidationError.INVALID_FORMAT -> stringResource(R.string.error_url_invalid)
    UrlValidationError.UNSUPPORTED_SCHEME -> stringResource(R.string.error_url_scheme)
    UrlValidationError.MISSING_HOST -> stringResource(R.string.error_url_host)
    UrlValidationError.CREDENTIALS_NOT_ALLOWED -> stringResource(R.string.error_url_credentials)
}

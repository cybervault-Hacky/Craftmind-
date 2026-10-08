package com.craftmind.app.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craftmind.app.designsystem.CraftMindBrandMark
import com.craftmind.app.designsystem.CraftMindCard
import com.craftmind.app.designsystem.CraftMindDetailLines
import com.craftmind.app.designsystem.CraftMindDivider
import com.craftmind.app.designsystem.CraftMindExpandableSection
import com.craftmind.app.designsystem.CraftMindEyebrow
import com.craftmind.app.designsystem.CraftMindIconButton
import com.craftmind.app.designsystem.CraftMindInsetPanel
import com.craftmind.app.designsystem.CraftMindKeyValueRow
import com.craftmind.app.designsystem.CraftMindLayout
import com.craftmind.app.designsystem.CraftMindMetaChip
import com.craftmind.app.designsystem.CraftMindNotice
import com.craftmind.app.designsystem.CraftMindPrimaryButton
import com.craftmind.app.designsystem.CraftMindSecondaryButton
import com.craftmind.app.designsystem.CraftMindShapes
import com.craftmind.app.designsystem.CraftMindStatusDot
import com.craftmind.app.designsystem.CraftMindTertiaryButton
import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.designsystem.CraftMindType
import com.craftmind.app.domain.ai.AiGenerationStage
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.buildplan.BuildRequestValidationError
import com.craftmind.app.domain.buildplan.BuildRequestValidator
import java.util.Locale

/**
 * Home: the AI build composer (Phase 15 §5, §6).
 *
 * The screen follows one hierarchy, and nothing competes with it:
 *
 * 1. CraftMind · AI Minecraft Builder — the brand and the promise.
 * 2. "What do you want to build?" — the composer question.
 * 3. The description field.
 * 4. Optional references: an image, or a supported reference URL.
 * 5. **Generate Build** — the single primary action.
 *
 * Everything else is progressive disclosure: the request preview, the data-handling explanation, and the pipeline
 * explanation are collapsed by default, and provider/model configuration stays in Settings. Generation status is
 * derived only from real state ([BuildGenerationState] and [AiGenerationStage]); no percentage, timer, or animation
 * stands in for work that is not happening.
 */
@Composable
fun HomeScreen(
    state: BuildComposerState,
    onEvent: (BuildComposerEvent) -> Unit,
    onPickImage: () -> Unit,
    onReviewPlan: (BuildGenerationState.Ready) -> Unit,
    selectedModelId: String? = null,
    selectedModel: AiModel? = null,
    providerSettingsLoaded: Boolean = false,
    providerCredentialSaved: Boolean = false,
    bridgeConnected: Boolean = false,
    onOpenSettings: () -> Unit = {},
    onOpenMinecraft: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val setupStatus = homeSetupStatus(
        providerSettingsLoaded = providerSettingsLoaded,
        providerCredentialSaved = providerCredentialSaved,
        selectedModelId = selectedModelId,
        selectedModel = selectedModel,
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize().imePadding()) {
        val wideLayout = maxWidth >= CraftMindLayout.mediumBreakpoint

        if (wideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = CraftMindLayout.screenMarginWide,
                        vertical = CraftMindLayout.screenMarginVertical,
                    ),
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.xxl),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xl),
                ) {
                    HomeBrandHeader(showTagline = true)
                    HomePipelineCard()
                    HomeReferenceNotice()
                }
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(CraftMindLayout.lg),
                ) {
                    setupStatus?.let { status ->
                        HomeSetupNotice(status = status, onOpenSettings = onOpenSettings)
                    }
                    BuildComposerCard(
                        state = state,
                        onEvent = onEvent,
                        onPickImage = onPickImage,
                        onReviewPlan = onReviewPlan,
                        selectedModelId = selectedModelId,
                        selectedModel = selectedModel,
                        setupReady = setupStatus == null,
                        bridgeConnected = bridgeConnected,
                        onOpenSettings = onOpenSettings,
                        onOpenMinecraft = onOpenMinecraft,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = CraftMindLayout.screenMargin,
                        vertical = CraftMindLayout.screenMarginVertical,
                    ),
                verticalArrangement = Arrangement.spacedBy(CraftMindLayout.lg),
            ) {
                HomeBrandHeader(showTagline = true)
                setupStatus?.let { status ->
                    HomeSetupNotice(status = status, onOpenSettings = onOpenSettings)
                }
                BuildComposerCard(
                    state = state,
                    onEvent = onEvent,
                    onPickImage = onPickImage,
                    onReviewPlan = onReviewPlan,
                    selectedModelId = selectedModelId,
                    selectedModel = selectedModel,
                    setupReady = setupStatus == null,
                    bridgeConnected = bridgeConnected,
                    onOpenSettings = onOpenSettings,
                    onOpenMinecraft = onOpenMinecraft,
                )
                HomePipelineCard()
                HomeReferenceNotice()
            }
        }

        val urlEditor = state.urlEditor as? UrlEditorState.Editing
        if (urlEditor != null) {
            UrlEntryDialog(
                state = urlEditor,
                onDraftChanged = { onEvent(BuildComposerEvent.UrlDraftChanged(it)) },
                onSave = { onEvent(BuildComposerEvent.SaveUrl) },
                onDismiss = { onEvent(BuildComposerEvent.CloseUrlEditor) },
            )
        }
    }
}

/** Brand block: mark, wordmark, qualifier, and the tagline. */
@Composable
private fun HomeBrandHeader(showTagline: Boolean) {
    Column(
        modifier = Modifier.widthIn(max = CraftMindLayout.contentMaxWidth),
        verticalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
        ) {
            CraftMindBrandMark()
            Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xxs)) {
                Text(
                    text = "CRAFTMIND",
                    style = CraftMindType.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "AI MINECRAFT BUILDER",
                    style = CraftMindType.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showTagline) {
            Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.sm)) {
                Text(
                    text = "Describe it.\nShow it.\nBuild it.",
                    style = CraftMindType.display,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Turn a written idea — and optionally one image or one supported reference URL — into a " +
                        "validated build plan you review before anything happens in Minecraft.",
                    style = CraftMindType.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Setup precondition, shown only while something is actually missing. */
@Composable
private fun HomeSetupNotice(status: HomeSetupStatus, onOpenSettings: () -> Unit) {
    CraftMindNotice(
        tone = status.tone,
        title = status.title,
        message = status.message,
        actionLabel = status.actionLabel,
        onAction = onOpenSettings,
    )
}

/** The real pipeline, collapsed on phones so the composer stays first. */
@Composable
private fun HomePipelineCard() {
    var expanded by remember { mutableStateOf(false) }
    CraftMindCard {
        CraftMindExpandableSection(
            title = "How a build comes together",
            summary = "Your prompt → AI BuildPlan → local validation and review → Minecraft",
            expanded = expanded,
            onToggle = { expanded = !expanded },
        ) {
            CraftMindDetailLines(
                listOf(
                    "1. You describe the build and optionally attach one image or one supported reference URL.",
                    "2. Your own provider key is used to generate a structured BuildPlan; CraftMind has no hosted AI backend.",
                    "3. The plan is parsed and validated locally, then saved on this device as a reviewable record.",
                    "4. Building in Minecraft needs a paired bridge, a server preflight, and your separate confirmation.",
                ),
            )
        }
    }
}

/** The honest limit of reference analysis, always available but never shouting. */
@Composable
private fun HomeReferenceNotice() {
    CraftMindNotice(
        tone = CraftMindTone.INFORMATIVE,
        title = "Reference analysis ends at the reviewed plan",
        message = "A compatible selected model may analyze one image, or bounded frames from a narrowly supported " +
            "public video URL. CraftMind does not infer visuals from a web page, and every plan still goes through " +
            "BuildPlan v2 validation, your review, and acceptance.",
    )
}

@Composable
private fun BuildComposerCard(
    state: BuildComposerState,
    onEvent: (BuildComposerEvent) -> Unit,
    onPickImage: () -> Unit,
    onReviewPlan: (BuildGenerationState.Ready) -> Unit,
    selectedModelId: String?,
    selectedModel: AiModel?,
    setupReady: Boolean,
    bridgeConnected: Boolean,
    onOpenSettings: () -> Unit,
    onOpenMinecraft: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var advancedExpanded by remember { mutableStateOf(false) }
    val promptError = (state.generation as? BuildGenerationState.ValidationBlocked)
        ?.error
        ?.takeIf {
            it == BuildRequestValidationError.EMPTY_PROMPT ||
                it == BuildRequestValidationError.PROMPT_TOO_LONG
        }

    CraftMindCard(
        modifier = Modifier.widthIn(max = CraftMindLayout.contentMaxWidth),
        contentPadding = CraftMindLayout.xl,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CraftMindLayout.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xs)) {
                CraftMindEyebrow("AI build composer")
                Text(
                    text = "What do you want to build?",
                    style = CraftMindType.headline,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Describe the place, style, scale, and details you have in mind.",
                    style = CraftMindType.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = state.prompt,
                onValueChange = { onEvent(BuildComposerEvent.PromptChanged(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Describe your build" },
                placeholder = { Text("Describe your build…") },
                minLines = 4,
                maxLines = 8,
                isError = promptError != null,
                shape = CraftMindShapes.sm,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                supportingText = {
                    if (promptError != null) {
                        Text(
                            text = validationMessage(promptError),
                            style = CraftMindType.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                text = "${state.prompt.length} / ${BuildRequestValidator.MAX_PROMPT_LENGTH}",
                                style = CraftMindType.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CraftMindSecondaryButton(
                    text = "Image",
                    onClick = onPickImage,
                    icon = Icons.Default.Add,
                )
                CraftMindSecondaryButton(
                    text = "Reference URL",
                    onClick = { onEvent(BuildComposerEvent.OpenUrlEditor) },
                    icon = Icons.Default.Add,
                )
            }

            state.imageError?.let { error ->
                CraftMindNotice(tone = CraftMindTone.NEGATIVE, message = validationMessage(error))
            }

            state.imageReference?.let { image ->
                ImageReferenceCard(
                    reference = image,
                    onRemove = { onEvent(BuildComposerEvent.RemoveImage) },
                )
                if (selectedModel?.capabilities?.vision != true) {
                    VisionModelRequiredNotice(
                        selectedModelId = selectedModelId,
                        selectedModel = selectedModel,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }

            state.urlReference?.let { reference ->
                UrlReferenceCard(
                    reference = reference,
                    onRemove = { onEvent(BuildComposerEvent.RemoveUrl) },
                )
                if (selectedModel?.capabilities?.vision != true ||
                    selectedModel?.capabilities?.multipleImages != true
                ) {
                    VisionModelRequiredNotice(
                        selectedModelId = selectedModelId,
                        selectedModel = selectedModel,
                        onOpenSettings = onOpenSettings,
                        requiresMultipleImages = true,
                    )
                }
            }

            if (state.imageReference != null && state.urlReference != null) {
                CraftMindNotice(
                    tone = CraftMindTone.NEGATIVE,
                    title = "One visual reference at a time",
                    message = "Remove either the image or the reference URL. The two inputs are never silently " +
                        "combined, and neither is ignored.",
                )
            }

            when (val generation = state.generation) {
                BuildGenerationState.Idle, is BuildGenerationState.Prepared -> Unit

                is BuildGenerationState.ValidationBlocked -> {
                    val isPromptError = generation.error in setOf(
                        BuildRequestValidationError.EMPTY_PROMPT,
                        BuildRequestValidationError.PROMPT_TOO_LONG,
                    )
                    if (!isPromptError) {
                        CraftMindNotice(
                            tone = CraftMindTone.CAUTION,
                            title = "Check your reference",
                            message = validationMessage(generation.error),
                            onDismiss = { onEvent(BuildComposerEvent.DismissGenerationNotice) },
                        )
                    }
                }

                is BuildGenerationState.Generating -> GenerationProgressPanel(
                    stage = generation.stage,
                    hasVisualReference = state.imageReference != null || state.urlReference != null,
                    onCancel = { onEvent(BuildComposerEvent.CancelGeneration) },
                )

                is BuildGenerationState.Failed -> CraftMindNotice(
                    tone = CraftMindTone.NEGATIVE,
                    title = "Plan generation failed",
                    message = generationMessage(generation.code),
                    onDismiss = { onEvent(BuildComposerEvent.DismissGenerationNotice) },
                )

                is BuildGenerationState.Cancelled -> CraftMindNotice(
                    tone = CraftMindTone.INFORMATIVE,
                    title = "Generation cancelled",
                    message = "The provider request was cancelled. No plan was created and nothing was saved.",
                    onDismiss = { onEvent(BuildComposerEvent.DismissGenerationNotice) },
                )

                is BuildGenerationState.Ready -> GeneratedPlanCard(
                    state = generation,
                    bridgeConnected = bridgeConnected,
                    onReview = { onReviewPlan(generation) },
                    onOpenMinecraft = onOpenMinecraft,
                )
            }

            CraftMindExpandableSection(
                title = "Request preview and data handling",
                summary = if (state.hasRequestContent) {
                    "What will be sent, and what stays on this device"
                } else {
                    "Nothing is sent until you generate"
                },
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded },
            ) {
                RequestPreview(state = state, selectedModel = selectedModel)
            }

            CraftMindDivider()

            val generationInProgress = state.generation is BuildGenerationState.Generating
            val retryableFailure = (state.generation as? BuildGenerationState.Failed)?.retryable == true
            val visualModelReady = when {
                state.imageReference != null && state.urlReference != null -> false
                state.urlReference != null ->
                    selectedModel?.capabilities?.let { it.vision && it.multipleImages } == true

                state.imageReference != null -> selectedModel?.capabilities?.vision == true
                else -> true
            }
            CraftMindPrimaryButton(
                text = if (retryableFailure) "Retry generation" else "Generate Build",
                onClick = {
                    onEvent(if (retryableFailure) BuildComposerEvent.Retry else BuildComposerEvent.Generate)
                },
                enabled = !generationInProgress && visualModelReady,
                loading = generationInProgress,
                icon = if (generationInProgress) null else Icons.Default.ArrowForward,
            )
            Text(
                text = when {
                    !setupReady -> "Finish the AI setup in Settings; generation uses your own provider key."
                    state.imageReference != null && state.urlReference != null ->
                        "Use either the image or the reference URL, not both."

                    state.imageReference != null && visualModelReady ->
                        "One locally validated image is sent from this device to the selected vision model. Image " +
                            "bytes are never stored in build history or resent during refinement."

                    state.imageReference != null ->
                        "The image stays on this device until a verified vision-capable model is selected. CraftMind " +
                            "will not switch models or send it to a text-only model."

                    state.urlReference != null && visualModelReady ->
                        "CraftMind verifies the direct public video response, reads only bounded byte ranges, extracts " +
                            "up to five frames, and sends those frames to the exact selected multi-image model."

                    state.urlReference != null ->
                        "Video analysis stays disabled until a verified multi-image vision model is selected."

                    else -> "Your prompt is sent directly to the selected AI provider. No visual content is fetched."
                },
                style = CraftMindType.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Real generation progress: the phase label, the honest sentence for the exact stage, and a track that lights only
 * steps the app has really reached. No percentage, no estimate, no looping decoration.
 */
@Composable
private fun GenerationProgressPanel(
    stage: AiGenerationStage,
    hasVisualReference: Boolean,
    onCancel: () -> Unit,
) {
    val phase = stage.phase()
    val track = generationTrackFor(hasVisualReference)
    CraftMindInsetPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(CraftMindLayout.progressMedium)
                    .semantics { contentDescription = "Generation in progress: ${phase.label}" },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xxs),
            ) {
                CraftMindEyebrow(phase.label)
                Text(
                    text = generationStageMessage(stage),
                    style = CraftMindType.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            CraftMindTertiaryButton(text = "Cancel", onClick = onCancel)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = CraftMindLayout.sm),
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
        ) {
            track.forEach { step ->
                val stepState = generationTrackStepState(step = step, current = phase, track = track)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xxs),
                ) {
                    CraftMindStatusDot(
                        tone = when (stepState) {
                            GenerationTrackStepState.ACTIVE -> CraftMindTone.BRAND
                            GenerationTrackStepState.COMPLETE -> CraftMindTone.POSITIVE
                            else -> CraftMindTone.NEUTRAL
                        },
                    )
                    Text(
                        text = step.label,
                        style = CraftMindType.labelSmall,
                        color = if (stepState == GenerationTrackStepState.ACTIVE) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VisionModelRequiredNotice(
    selectedModelId: String?,
    selectedModel: AiModel?,
    onOpenSettings: () -> Unit,
    requiresMultipleImages: Boolean = false,
) {
    val selectedCanProcess = selectedModel?.capabilities?.let { capabilities ->
        capabilities.vision && (!requiresMultipleImages || capabilities.multipleImages)
    } == true
    val message = when {
        selectedModelId == null -> if (requiresMultipleImages) {
            "Select a verified multi-image vision model in Settings to analyze sampled video frames."
        } else {
            "Select a model labeled Vision in Settings. Image analysis stays disabled until a compatible model is verified."
        }

        selectedModel == null ->
            "The saved model has not been verified in this session. Test the provider connection in Settings to " +
                "refresh its capabilities."

        selectedCanProcess -> "${selectedModel.displayName} is verified for this visual workflow."
        requiresMultipleImages && selectedModel.capabilities.vision ->
            "${selectedModel.displayName} supports one-image vision but not the bounded multi-frame video request. " +
                "Choose a model labeled Multi-image Vision; CraftMind will not switch models."

        else -> "${selectedModel.displayName} is text-only for this workflow. Choose a model labeled Vision; " +
            "CraftMind will not fall back or switch models."
    }
    CraftMindNotice(
        tone = if (selectedCanProcess) CraftMindTone.POSITIVE else CraftMindTone.CAUTION,
        title = if (requiresMultipleImages) {
            "A verified multi-image vision model is required"
        } else {
            "A verified vision model is required"
        },
        message = message,
        actionLabel = if (selectedCanProcess) null else "Open AI settings",
        onAction = if (selectedCanProcess) null else onOpenSettings,
    )
}

@Composable
private fun GeneratedPlanCard(
    state: BuildGenerationState.Ready,
    bridgeConnected: Boolean,
    onReview: () -> Unit,
    onOpenMinecraft: () -> Unit,
) {
    val plan = state.plan.plan
    CraftMindCard(emphasized = true) {
        Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.md)) {
            CraftMindEyebrow("Validated plan ready for review")
            Text(
                text = plan.metadata.title,
                style = CraftMindType.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = plan.metadata.summary,
                style = CraftMindType.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
            ) {
                CraftMindMetaChip(
                    label = "${plan.metadata.dimensions.width} × ${plan.metadata.dimensions.height} × " +
                        "${plan.metadata.dimensions.depth} blocks",
                )
                CraftMindMetaChip(label = "${plan.operations.size} placements")
                CraftMindMetaChip(label = "${plan.metadata.providerId} / ${plan.metadata.modelId}")
            }
            if (state.localSaveFailed) {
                CraftMindNotice(
                    tone = CraftMindTone.CAUTION,
                    message = "The validated plan is available for review but could not be saved to the local Builds list.",
                )
            } else {
                Text(
                    text = "Saved locally on this device. Nothing has been placed in Minecraft.",
                    style = CraftMindType.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            CraftMindPrimaryButton(text = "Review build plan", onClick = onReview, fullWidth = false)
            if (!bridgeConnected) {
                CraftMindKeyValueRow(
                    label = "Building in Minecraft",
                    value = "Needs a connected, compatible runtime. Review and save the plan now; connect the bridge " +
                        "whenever you want to build it.",
                )
                CraftMindTertiaryButton(text = "Open Minecraft", onClick = onOpenMinecraft)
            }
        }
    }
}

@Composable
private fun ImageReferenceCard(
    reference: BuildInput.ImageReference,
    onRemove: () -> Unit,
) {
    CraftMindInsetPanel(contentPadding = CraftMindLayout.md) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ImageThumbnail(reference = reference)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xxs),
            ) {
                Text(
                    text = "Reference image",
                    style = CraftMindType.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${reference.mediaType.substringAfter('/').uppercase(Locale.ROOT)} · " +
                        "${formatSize(reference.sizeBytes)} · stays local until generation",
                    style = CraftMindType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CraftMindIconButton(
                icon = Icons.Default.Close,
                description = "Remove reference image",
                onClick = onRemove,
            )
        }
    }
}

@Composable
private fun UrlReferenceCard(
    reference: BuildInput.UrlReference,
    onRemove: () -> Unit,
) {
    CraftMindInsetPanel(contentPadding = CraftMindLayout.md) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(CraftMindLayout.iconMd),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xxs),
            ) {
                Text(
                    text = "Public reference URL",
                    style = CraftMindType.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = hostAndPath(reference.url),
                    style = CraftMindType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CraftMindIconButton(
                icon = Icons.Default.Close,
                description = "Remove URL reference",
                onClick = onRemove,
            )
        }
    }
}

/** What will actually be sent, and what stays on the device. Collapsed by default (§5). */
@Composable
private fun RequestPreview(state: BuildComposerState, selectedModel: AiModel?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
    ) {
        if (state.prompt.isNotBlank()) {
            CraftMindKeyValueRow(label = "Prompt", value = "“${state.prompt.trim()}”")
        } else {
            Text(
                text = when {
                    state.imageReference != null ->
                        "No written prompt. The selected image is the only visual input; generation requires a " +
                            "verified vision-capable model."

                    state.urlReference != null ->
                        "No written prompt. The selected supported video is the only visual input; generation requires " +
                            "a verified multi-image vision model."

                    else -> "Add a written description or attach one supported visual reference."
                },
                style = CraftMindType.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.imageReference != null) {
            CraftMindKeyValueRow(label = "Reference image", value = "Attached · sent only to the selected model")
        }
        state.urlReference?.let { CraftMindKeyValueRow(label = "Public URL", value = hostAndPath(it.url)) }
        selectedModel?.let { model ->
            CraftMindKeyValueRow(
                label = "Selected model",
                value = "${model.displayName} · " + when {
                    model.capabilities.vision && model.capabilities.multipleImages ->
                        "text, one-image, and bounded multi-frame video vision"

                    model.capabilities.vision -> "text and one-image vision"
                    else -> "text only"
                },
            )
        }
        CraftMindDetailLines(
            listOf(
                when {
                    state.imageReference != null ->
                        "On generation the selected image is sent directly to the selected compatible vision model. " +
                            "It is not resent during refinement."

                    state.urlReference != null ->
                        "Only the supported public video is fetched, as bounded byte ranges. Up to five sampled frames " +
                            "go to the selected multi-image vision model; refinement uses saved text notes and never " +
                            "re-downloads the video."

                    else -> "Your prompt is sent directly to the selected provider. No reference content is fetched."
                },
                "CraftMind has no hosted AI backend, no account service, and no analytics. Provider-side retention is " +
                    "governed by the provider's own terms.",
            ),
        )
    }
}

@Composable
private fun UrlEntryDialog(
    state: UrlEditorState.Editing,
    onDraftChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = CraftMindShapes.lg,
        title = { Text("Add a supported reference URL", style = CraftMindType.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.md)) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reference URL") },
                    placeholder = { Text("https://raw.githubusercontent.com/user/repo/main/video.mp4") },
                    singleLine = true,
                    isError = state.error != null,
                    shape = CraftMindShapes.sm,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    supportingText = {
                        state.error?.let { error ->
                            Text(
                                text = validationMessage(error),
                                style = CraftMindType.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
                Text(
                    text = "Supported input is a direct HTTPS .mp4 or .webm file on raw.githubusercontent.com. Pages, " +
                        "social and video platforms, playlists, private or login-required sources, query strings, " +
                        "fragments, and redirects are rejected, and the file must allow byte-range access. A small " +
                        "range probe is checked before any frame data is retrieved.",
                    style = CraftMindType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("Add reference URL", style = CraftMindType.labelLarge) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = CraftMindType.labelLarge) }
        },
    )
}

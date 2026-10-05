package com.craftmind.app.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalFocusManager
import com.craftmind.app.domain.buildplan.BuildInput
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiGenerationStage
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.buildplan.BuildRequestValidationError
import com.craftmind.app.domain.buildplan.BuildRequestValidator
import com.craftmind.app.presentation.home.BuildComposerEvent
import com.craftmind.app.presentation.home.BuildComposerState
import com.craftmind.app.presentation.home.BuildGenerationState
import com.craftmind.app.presentation.home.UrlEditorState
import java.net.URI
import java.util.Locale

@Composable
fun HomeScreen(
    state: BuildComposerState,
    onEvent: (BuildComposerEvent) -> Unit,
    onPickImage: () -> Unit,
    onReviewPlan: (BuildGenerationState.Ready) -> Unit,
    selectedModelId: String? = null,
    selectedModel: AiModel? = null,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().imePadding()) {
        val wideLayout = maxWidth >= 900.dp
        if (wideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    HomeIntroduction(showFoundationDetails = true)
                }
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    BuildComposerCard(
                        state = state,
                        onEvent = onEvent,
                        onPickImage = onPickImage,
                        onReviewPlan = onReviewPlan,
                        selectedModelId = selectedModelId,
                        selectedModel = selectedModel,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                HomeIntroduction(showFoundationDetails = false)
                BuildComposerCard(
                    state = state,
                    onEvent = onEvent,
                    onPickImage = onPickImage,
                    onReviewPlan = onReviewPlan,
                    selectedModelId = selectedModelId,
                    selectedModel = selectedModel,
                    onOpenSettings = onOpenSettings,
                )
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlannedPipelineCard()
                    PhaseTwoNotice()
                }
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

@Composable
private fun HomeIntroduction(showFoundationDetails: Boolean) {
    Column(
        modifier = Modifier.widthIn(max = 510.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandMark()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "CRAFTMIND",
                    style = MaterialTheme.typography.titleMedium,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    text = "AI MINECRAFT BUILDER",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.15.sp,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Describe it.\nReview it.\nRefine it.",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "A prompt and at most one supported visual reference begin an AI-generated Minecraft build plan—not a manual block layout.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showFoundationDetails) {
            PlannedPipelineCard()
            PhaseTwoNotice()
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 9.dp, top = 9.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 9.dp, top = 9.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.tertiary),
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 9.dp, bottom = 9.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.secondary),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 9.dp, bottom = 9.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)),
        )
    }
}

@Composable
private fun PlannedPipelineCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "PLANNED PIPELINE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.25.sp,
            )
            PipelineStep(number = "01", title = "Your prompt + references")
            PipelineStep(number = "02", title = "AI-generated BuildPlan", emphasized = true)
            PipelineStep(number = "03", title = "Validation, review, and local record")
        }
    }
}

@Composable
private fun PipelineStep(number: String, title: String, emphasized: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (emphasized) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall,
                color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun PhaseTwoNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Reference analysis still ends at the reviewed BuildPlan", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "A compatible selected Gemini model may analyze one image or bounded frames from a narrowly supported public video URL. CraftMind does not infer visuals from a page or URL, and every plan still uses BuildPlan v2 validation, review, acceptance, and the unchanged Phase 5 bridge safeguards.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BuildComposerCard(
    state: BuildComposerState,
    onEvent: (BuildComposerEvent) -> Unit,
    onPickImage: () -> Unit,
    onReviewPlan: (BuildGenerationState.Ready) -> Unit,
    selectedModelId: String?,
    selectedModel: AiModel?,
    onOpenSettings: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val promptError = (state.generation as? BuildGenerationState.ValidationBlocked)
        ?.error
        ?.takeIf {
            it == BuildRequestValidationError.EMPTY_PROMPT ||
                it == BuildRequestValidationError.PROMPT_TOO_LONG
        }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(17.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("YOUR BUILD REQUEST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
                Text("What do you want to build?", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Describe the place, style, scale, and details you have in mind.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = state.prompt,
                onValueChange = { onEvent(BuildComposerEvent.PromptChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("A quiet forest library with a glass roof…") },
                minLines = 4,
                maxLines = 7,
                isError = promptError != null,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                supportingText = {
                    if (promptError != null) {
                        Text(validationMessage(promptError), color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(
                                text = "${state.prompt.length} / ${BuildRequestValidator.MAX_PROMPT_LENGTH}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onPickImage,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Image")
                }
                OutlinedButton(
                    onClick = { onEvent(BuildComposerEvent.OpenUrlEditor) },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Video URL")
                }
            }

            state.imageError?.let { error ->
                Text(
                    text = validationMessage(error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
                if (selectedModel?.capabilities?.vision != true || selectedModel?.capabilities?.multipleImages != true) {
                    VisionModelRequiredNotice(
                        selectedModelId = selectedModelId,
                        selectedModel = selectedModel,
                        onOpenSettings = onOpenSettings,
                        requiresMultipleImages = true,
                    )
                }
            }
            if (state.imageReference != null && state.urlReference != null) {
                Text(
                    "Use only one visual reference at a time. Remove either the image or the video URL; the two inputs are not silently combined.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.hasRequestContent) {
                RequestPreview(state)
            }

            when (val generation = state.generation) {
                BuildGenerationState.Idle, is BuildGenerationState.Prepared -> Unit
                is BuildGenerationState.ValidationBlocked -> {
                    if (generation.error !in setOf(
                            BuildRequestValidationError.EMPTY_PROMPT,
                            BuildRequestValidationError.PROMPT_TOO_LONG,
                        )
                    ) {
                        InlineNotice(
                            title = "Check your reference",
                            message = validationMessage(generation.error),
                            onDismiss = { onEvent(BuildComposerEvent.DismissGenerationNotice) },
                        )
                    }
                }
                is BuildGenerationState.Generating -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(generationStageMessage(generation.stage), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onEvent(BuildComposerEvent.CancelGeneration) }) { Text("Cancel") }
                }
                is BuildGenerationState.Failed -> InlineNotice(
                    title = "Plan generation failed",
                    message = generationMessage(generation.code),
                    onDismiss = { onEvent(BuildComposerEvent.DismissGenerationNotice) },
                )
                is BuildGenerationState.Cancelled -> InlineNotice(
                    title = "Generation cancelled",
                    message = "The provider request was cancelled. No plan was created.",
                    onDismiss = { onEvent(BuildComposerEvent.DismissGenerationNotice) },
                )
                is BuildGenerationState.Ready -> GeneratedPlanCard(
                    state = generation,
                    onReview = { onReviewPlan(generation) },
                )
            }

            val generationInProgress = state.generation is BuildGenerationState.Generating
            val retryableFailure = (state.generation as? BuildGenerationState.Failed)?.retryable == true
            val visualModelReady = when {
                state.imageReference != null && state.urlReference != null -> false
                state.urlReference != null -> selectedModel?.capabilities?.let { it.vision && it.multipleImages } == true
                state.imageReference != null -> selectedModel?.capabilities?.vision == true
                else -> true
            }
            Button(
                onClick = {
                    onEvent(if (retryableFailure) BuildComposerEvent.Retry else BuildComposerEvent.Generate)
                },
                enabled = !generationInProgress && visualModelReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(if (retryableFailure) "Retry generation" else "Generate with AI", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                text = when {
                    state.imageReference != null && state.urlReference != null -> "Use either the image or video URL, not both. CraftMind will not silently ignore either visual input."
                    state.imageReference != null && visualModelReady -> "On generation, one locally validated image is sent directly from this device to the selected Vision model. Raw image bytes are not stored in CraftMind history or resent during refinement."
                    state.imageReference != null -> "The image stays local until a verified vision-capable model is selected. CraftMind will not switch models or send an image to a text-only model."
                    state.urlReference != null && visualModelReady -> "On generation, CraftMind verifies the direct public-video response, retrieves only bounded byte ranges, extracts up to five frames, and sends those frames to the exact selected multi-image Vision model. The provider's data terms apply; refinement uses saved text notes and does not fetch the video again."
                    state.urlReference != null -> "Video analysis stays disabled until a verified multi-image Vision model is selected."
                    else -> "Your prompt is sent directly to the selected AI provider. No visual reference content is fetched."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            "Select a verified multi-image Vision model in Settings to analyze sampled video frames."
        } else {
            "Select a model labeled Vision in Settings. Image analysis stays disabled until a compatible model is verified."
        }
        selectedModel == null -> "The saved model has not been verified in this session. Test the provider connection in Settings to refresh its capabilities."
        selectedCanProcess -> "${selectedModel.displayName} is verified for this visual workflow."
        requiresMultipleImages && selectedModel.capabilities.vision -> "${selectedModel.displayName} supports one-image vision but not the bounded multi-frame video request. Choose a model labeled Multi-image Vision; CraftMind will not switch models."
        else -> "${selectedModel.displayName} is text-only for this workflow. Choose a model labeled Vision; CraftMind will not fall back or switch models."
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.62f),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (requiresMultipleImages) "A verified multi-image Vision model is required" else "A verified vision model is required",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(message, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp)) {
                Text("Open AI settings")
            }
        }
    }
}

@Composable
private fun GeneratedPlanCard(
    state: BuildGenerationState.Ready,
    onReview: () -> Unit,
) {
    val plan = state.plan.plan
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("VALIDATED AI PLAN READY FOR REVIEW", style = MaterialTheme.typography.labelSmall, letterSpacing = 0.8.sp)
            Text(plan.metadata.title, style = MaterialTheme.typography.titleLarge)
            Text(plan.metadata.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${plan.metadata.dimensions.width} × ${plan.metadata.dimensions.height} × ${plan.metadata.dimensions.depth} blocks · ${plan.operations.size} placements · ${plan.metadata.providerId} / ${plan.metadata.modelId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.localSaveFailed) {
                Text(
                    "The validated plan is available for review but could not be saved to the local Builds list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text("Saved locally. Nothing has been placed in Minecraft.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onReview, shape = RoundedCornerShape(14.dp)) {
                Text("Review plan details")
            }
        }
    }
}

private fun generationStageMessage(stage: AiGenerationStage): String = when (stage) {
    AiGenerationStage.VALIDATING_REQUEST -> "Checking the request and selected provider/model…"
    AiGenerationStage.VALIDATING_REFERENCE_URL -> "Checking that the HTTPS video URL matches CraftMind's direct-source rules…"
    AiGenerationStage.RESOLVING_PUBLIC_VIDEO_REFERENCE -> "Checking public access, media type, size, and byte-range support…"
    AiGenerationStage.EXTRACTING_VIDEO_FRAMES -> "Extracting up to five bounded frames locally; no full video file is saved…"
    AiGenerationStage.PREPARING_IMAGE_LOCALLY -> "Validating and preparing the image locally…"
    AiGenerationStage.ANALYZING_IMAGE_WITH_SELECTED_MODEL -> "Sending one image directly to the selected Vision model for analysis…"
    AiGenerationStage.ANALYZING_VIDEO_FRAMES_WITH_SELECTED_MODEL -> "Sending sampled frames directly to the exact selected multi-image Vision model…"
    AiGenerationStage.GENERATING_BUILD_PLAN -> "Generating the BuildPlan v2 with the same selected provider/model…"
    AiGenerationStage.VALIDATING_BUILD_PLAN -> "Parsing and validating the BuildPlan locally…"
}

private fun generationMessage(code: AiErrorCode): String = when (code) {
    AiErrorCode.INVALID_API_KEY -> "The selected provider rejected its saved API key. Update it in Settings."
    AiErrorCode.PROVIDER_UNAVAILABLE -> "The provider is temporarily unavailable. You can retry the request."
    AiErrorCode.MODEL_UNAVAILABLE -> "The selected model is unavailable. Test the connection and choose another model in Settings."
    AiErrorCode.RATE_LIMITED -> "The provider rate-limited this request. Wait before retrying."
    AiErrorCode.NETWORK_TIMEOUT -> "The provider request timed out. Check your connection and retry."
    AiErrorCode.NETWORK_UNAVAILABLE -> "Could not reach the provider. Check your internet connection and retry."
    AiErrorCode.INVALID_AI_RESPONSE -> "The provider response was malformed or did not match the required JSON format."
    AiErrorCode.INVALID_BUILD_PLAN -> "The AI plan failed CraftMind's block, state, coordinate, or safety validation."
    AiErrorCode.INVALID_BUILD_EDIT -> "The AI proposed an unsafe or inconsistent change. The previous plan remains unchanged."
    AiErrorCode.NO_CHANGES_PROPOSED -> "The AI did not propose a validated change."
    AiErrorCode.REFINEMENT_CONTEXT_TOO_LARGE -> "This build is too large for a safe refinement with the selected model. Try a narrower change."
    AiErrorCode.BUILD_VERSION_CONFLICT -> "The saved version changed. Reopen the current build before refining."
    AiErrorCode.BUILD_HISTORY_FAILURE -> "The new local version could not be saved; the previous version remains available."
    AiErrorCode.BUILD_HISTORY_LIMIT_REACHED -> "Local history reached its storage limit; no earlier version was removed."
    AiErrorCode.UNSUPPORTED_SCHEMA_VERSION -> "The AI returned an unsupported BuildPlan schema version."
    AiErrorCode.BUILD_TOO_LARGE -> "The AI plan exceeded CraftMind's size or dimension limits."
    AiErrorCode.RESPONSE_TOO_LARGE -> "The AI response exceeded CraftMind's response-size limit."
    AiErrorCode.INVALID_BUILD_REQUEST -> "Add a written description, one readable image, or one supported public video URL."
    AiErrorCode.VISION_UNSUPPORTED -> "The exact selected provider/model does not support image/video visual analysis. Choose a verified Vision model; no fallback was used and no visual data was sent."
    AiErrorCode.IMAGE_UNREADABLE -> "The image could not be opened from its local reference. Choose it again; no image was uploaded."
    AiErrorCode.IMAGE_CONTENT_INVALID -> "The file is not a valid supported image. Choose a readable JPEG, PNG, or WebP file."
    AiErrorCode.IMAGE_TOO_LARGE -> "The image exceeds CraftMind's local or provider payload size limit. Choose a smaller image."
    AiErrorCode.IMAGE_DIMENSIONS_UNSUPPORTED -> "The image dimensions exceed CraftMind's safe decoding limit. Choose a smaller-resolution image."
    AiErrorCode.IMAGE_MIME_MISMATCH -> "The file contents do not match the selected image type. Choose the image again."
    AiErrorCode.MULTI_IMAGE_UNSUPPORTED -> "Video analysis requires the exact selected model to support multiple image inputs. No fallback was used and the source was not sent to another model."
    AiErrorCode.MULTIPLE_VISUAL_REFERENCES_UNSUPPORTED -> "Use one visual reference at a time. Remove either the uploaded image or the video URL; neither input will be ignored."
    AiErrorCode.REFERENCE_UNSAFE_URL -> "Use a direct HTTPS video URL without credentials, query parameters, fragments, an IP/localhost host, or a custom port."
    AiErrorCode.REFERENCE_UNSAFE_DESTINATION -> "The supported video host did not resolve only to public addresses. CraftMind stopped without connecting to a private destination."
    AiErrorCode.REFERENCE_UNSUPPORTED_SOURCE -> "Only direct public MP4/WebM files on raw.githubusercontent.com are supported. Pages, social/video platforms, playlists, and streaming manifests are not analyzed."
    AiErrorCode.REFERENCE_UNAVAILABLE -> "The public video could not be reached. Private, deleted, or inaccessible sources are not opened or bypassed."
    AiErrorCode.REFERENCE_ACCESS_RESTRICTED -> "The source rejected public access. CraftMind will not sign in, bypass a paywall, or work around access controls."
    AiErrorCode.REFERENCE_REDIRECT_BLOCKED -> "The video URL redirected. Redirects are not followed; use a direct raw-file URL."
    AiErrorCode.REFERENCE_RANGE_UNSUPPORTED -> "The source did not support bounded byte-range requests. CraftMind did not fall back to a full download."
    AiErrorCode.REFERENCE_MEDIA_UNSUPPORTED -> "The response was not a matching MP4 or WebM video file. No plan was generated from URL text."
    AiErrorCode.REFERENCE_TOO_LARGE -> "The video exceeds the 128 MiB source-file cap. Choose a smaller direct video."
    AiErrorCode.REFERENCE_DURATION_UNSUPPORTED -> "The video must be between 2 seconds and 3 minutes long."
    AiErrorCode.REFERENCE_FRAME_EXTRACTION_FAILED -> "CraftMind could not safely decode bounded frames from this video. No visual analysis or plan was produced."
    AiErrorCode.REFERENCE_NO_DISTINCT_FRAMES -> "The video did not yield at least two distinct usable frames. No plan was generated from the URL alone."
    AiErrorCode.REFERENCE_TRANSFER_LIMIT -> "Frame extraction reached its transfer or request cap. The video was not downloaded in full."
    AiErrorCode.REFERENCE_TIMEOUT -> "The public-reference request timed out. Check the connection and retry if the source is still publicly accessible."
    AiErrorCode.UNSUPPORTED_CAPABILITY -> "The selected provider or model cannot return the required structured plan."
    AiErrorCode.MISSING_CREDENTIAL -> "Save an API key for the selected provider in Settings before generating."
    AiErrorCode.CREDENTIAL_STORAGE_FAILURE -> "The encrypted provider key could not be accessed. Check Settings and device security."
    AiErrorCode.NO_PROVIDER_SELECTED -> "Choose a supported provider in Settings."
    AiErrorCode.NO_MODEL_SELECTED -> "Test the provider connection and select a model in Settings."
    AiErrorCode.NO_MODELS_AVAILABLE -> "The provider returned no models compatible with plan generation."
    AiErrorCode.CANCELLED -> "The provider request was cancelled. No plan was created."
    AiErrorCode.UNKNOWN_PROVIDER_ERROR -> "The provider request failed. No raw response or secret was displayed."
}

@Composable
private fun ImageReferenceCard(
    reference: BuildInput.ImageReference,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ImageThumbnail(reference = reference)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Reference image", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${reference.mediaType.substringAfter('/').uppercase(Locale.ROOT)} · ${formatSize(reference.sizeBytes)} · local until generation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "Remove reference image" },
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
        }
    }
}

@Composable
private fun UrlReferenceCard(
    reference: BuildInput.UrlReference,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Public video URL", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = hostAndPath(reference.url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "Remove URL reference" },
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
        }
    }
}

@Composable
private fun RequestPreview(state: BuildComposerState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = "BUILD REQUEST PREVIEW",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.1.sp,
        )
        if (state.prompt.isNotBlank()) {
            Text("Prompt", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "“${state.prompt.trim()}”",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = when {
                    state.imageReference != null -> "No written prompt. The selected image is the only visual input; generation requires a verified vision-capable model."
                    state.urlReference != null -> "No written prompt. The selected supported video is the only visual input; generation requires a verified multi-image Vision model."
                    else -> "Add a written description or attach one supported visual reference."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.imageReference != null) PreviewReferenceRow("Reference image", "Attached")
        state.urlReference?.let { PreviewReferenceRow("Public URL", hostAndPath(it.url)) }
        Text(
            text = when {
                state.imageReference != null -> "If you generate, the selected image is sent directly for analysis by the selected compatible Vision model. It is not resent during refinement."
                state.urlReference != null -> "Only the supported public video is fetched as bounded byte ranges. Up to five sampled frames are sent directly to the selected multi-image Vision model; refinement uses saved text notes and never re-downloads the video."
                else -> "Your prompt is sent directly to the selected provider. No reference content is fetched."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewReferenceRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 190.dp),
        )
    }
}

@Composable
private fun InlineNotice(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 13.dp, end = 4.dp, bottom = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Dismiss message" }) {
                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
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
        title = { Text("Add a supported public video") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reference URL") },
                    placeholder = { Text("https://raw.githubusercontent.com/user/repo/main/video.mp4") },
                    singleLine = true,
                    isError = state.error != null,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    supportingText = {
                        state.error?.let { error ->
                            Text(validationMessage(error), color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
                Text(
                    text = "Supported input is a direct HTTPS .mp4 or .webm file on raw.githubusercontent.com. CraftMind rejects pages, social/video platforms, playlists, login/private sources, query strings, fragments, and redirects. The file must allow byte-range access. At generation, a small range probe is checked before any frame data is retrieved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Add video URL") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun validationMessage(error: BuildRequestValidationError): String = when (error) {
    BuildRequestValidationError.EMPTY_PROMPT -> "Describe what you want to build or attach one supported visual reference."
    BuildRequestValidationError.PROMPT_TOO_LONG -> "Keep your description to ${BuildRequestValidator.MAX_PROMPT_LENGTH} characters or fewer."
    BuildRequestValidationError.INVALID_URL -> "Enter a complete HTTPS video URL, such as https://raw.githubusercontent.com/user/repo/main/video.mp4."
    BuildRequestValidationError.URL_SCHEME_NOT_ALLOWED -> "Video references require HTTPS; cleartext HTTP is not accepted."
    BuildRequestValidationError.URL_CREDENTIALS_NOT_ALLOWED -> "Remove any username or password from the URL."
    BuildRequestValidationError.URL_TOO_LONG -> "Keep the URL to ${BuildRequestValidator.MAX_URL_LENGTH} characters or fewer."
    BuildRequestValidationError.URL_UNSAFE_HOST -> "IP addresses, localhost, and local/internal hostnames are not accepted."
    BuildRequestValidationError.UNSUPPORTED_PUBLIC_VIDEO_SOURCE -> "Use a direct .mp4 or .webm file on raw.githubusercontent.com; pages, streaming links, and other hosts are not supported."
    BuildRequestValidationError.URL_QUERY_OR_FRAGMENT_NOT_ALLOWED -> "Remove query parameters and fragments; signed or secret URL tokens are not accepted."
    BuildRequestValidationError.URL_PORT_NOT_ALLOWED -> "Use the standard HTTPS port only; custom ports are not accepted."
    BuildRequestValidationError.MULTIPLE_VISUAL_REFERENCES_UNSUPPORTED -> "Use one visual reference at a time. Remove either the image or video URL; neither input is ignored."
    BuildRequestValidationError.UNSUPPORTED_IMAGE_TYPE -> "Choose a JPEG, PNG, or WebP image."
    BuildRequestValidationError.IMAGE_TOO_LARGE -> "Choose an image no larger than 12 MiB."
    BuildRequestValidationError.INVALID_IMAGE_REFERENCE -> "CraftMind could not read that image reference. Choose the image again."
}

private fun formatSize(sizeBytes: Long?): String {
    if (sizeBytes == null) return "Size unavailable"
    if (sizeBytes < 1024L) return "$sizeBytes B"
    val megabytes = sizeBytes / (1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.1f MiB", megabytes)
}

private fun hostAndPath(value: String): String = runCatching {
    val uri = URI(value)
    val host = uri.host ?: return@runCatching "Video URL (details hidden)"
    buildString {
        append(host)
        uri.rawPath?.takeIf { it.isNotBlank() }?.let { append(it) }
    }
}.getOrDefault("Video URL (details hidden)")

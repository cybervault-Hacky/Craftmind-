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
import com.craftmind.app.domain.build.BuildInput
import com.craftmind.app.domain.build.BuildRequestValidationError
import com.craftmind.app.domain.build.BuildRequestValidator
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
                    BuildComposerCard(state = state, onEvent = onEvent, onPickImage = onPickImage)
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
                BuildComposerCard(state = state, onEvent = onEvent, onPickImage = onPickImage)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlannedPipelineCard()
                    PhaseOneNotice()
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
                text = "Describe it.\nShow it.\nBuild it.",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "A prompt and optional references are the beginning of an AI-generated Minecraft build plan—not a manual block layout.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showFoundationDetails) {
            PlannedPipelineCard()
            PhaseOneNotice()
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
            PipelineStep(number = "03", title = "Validation, then Minecraft bridge")
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
private fun PhaseOneNotice() {
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
                Text("Phase 1 · Foundation/UI", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "AI generation, reference analysis, URL fetching, and Minecraft execution are not connected yet. Your inputs stay on this device.",
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
                    Text("URL")
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
            }

            state.urlReference?.let { reference ->
                UrlReferenceCard(
                    reference = reference,
                    onRemove = { onEvent(BuildComposerEvent.RemoveUrl) },
                )
            }

            if (state.hasRequestContent) {
                RequestPreview(state)
            }

            when (val generation = state.generation) {
                BuildGenerationState.Idle -> Unit
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

                is BuildGenerationState.AiUnavailable -> InlineNotice(
                    title = "AI generation is not available yet",
                    message = "This Phase 1 app has no AI provider connected. Your request was validated locally, remains on this device, and was not sent.",
                    onDismiss = { onEvent(BuildComposerEvent.DismissGenerationNotice) },
                )
            }

            Button(
                onClick = { onEvent(BuildComposerEvent.Generate) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Generate with AI", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                text = "No AI request is sent in Phase 1. Image and URL references are not analyzed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
            ImageThumbnail(contentUri = reference.contentUri)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Reference image", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${reference.mediaType.substringAfter('/').uppercase(Locale.ROOT)} · ${formatSize(reference.sizeBytes)} · on this device",
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
                Text("Public URL reference", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = reference.url,
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
                text = "Add a description to complete this request.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.imageReference != null) PreviewReferenceRow("Reference image", "Attached")
        state.urlReference?.let { PreviewReferenceRow("Public URL", hostAndPath(it.url)) }
        Text(
            text = "Local preview only · nothing has been sent or analyzed.",
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
        title = { Text("Add a public URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reference URL") },
                    placeholder = { Text("https://example.com/reference") },
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
                    text = "Only the URL syntax is checked. CraftMind will not fetch or analyze this page in Phase 1.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Add URL") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun validationMessage(error: BuildRequestValidationError): String = when (error) {
    BuildRequestValidationError.EMPTY_PROMPT -> "Describe what you want to build before continuing."
    BuildRequestValidationError.PROMPT_TOO_LONG -> "Keep your description to ${BuildRequestValidator.MAX_PROMPT_LENGTH} characters or fewer."
    BuildRequestValidationError.INVALID_URL -> "Enter a complete HTTP or HTTPS URL, such as https://example.com/reference."
    BuildRequestValidationError.URL_SCHEME_NOT_ALLOWED -> "Only public HTTP and HTTPS URLs are supported."
    BuildRequestValidationError.URL_CREDENTIALS_NOT_ALLOWED -> "Remove any username or password from the URL."
    BuildRequestValidationError.URL_TOO_LONG -> "Keep the URL to ${BuildRequestValidator.MAX_URL_LENGTH} characters or fewer."
    BuildRequestValidationError.UNSUPPORTED_IMAGE_TYPE -> "Choose a JPEG, PNG, or WebP image."
    BuildRequestValidationError.IMAGE_TOO_LARGE -> "Choose an image no larger than 12 MB."
    BuildRequestValidationError.INVALID_IMAGE_REFERENCE -> "CraftMind could not read that image reference. Choose the image again."
}

private fun formatSize(sizeBytes: Long?): String {
    if (sizeBytes == null) return "Size unavailable"
    if (sizeBytes < 1024L) return "$sizeBytes B"
    val megabytes = sizeBytes / (1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.1f MB", megabytes)
}

private fun hostAndPath(value: String): String = runCatching {
    val uri = URI(value)
    buildString {
        append(uri.host ?: value)
        uri.rawPath?.takeIf { it.isNotBlank() }?.let { append(it) }
    }
}.getOrDefault(value)

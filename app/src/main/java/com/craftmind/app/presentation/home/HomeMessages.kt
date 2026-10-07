package com.craftmind.app.presentation.home

import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.domain.ai.AiErrorCode
import com.craftmind.app.domain.ai.AiModel
import com.craftmind.app.domain.ai.AiGenerationStage
import com.craftmind.app.domain.buildplan.BuildRequestValidationError
import com.craftmind.app.domain.buildplan.BuildRequestValidator
import java.net.URI
import java.util.Locale

/**
 * Human-readable wording for the build composer (Phase 15 §6).
 *
 * Every message maps one real typed value — an [AiGenerationStage], an [AiErrorCode], or a
 * [BuildRequestValidationError] — to a sentence a person can act on. Nothing is shown for a state the app does not
 * hold, no raw provider payload or secret is ever surfaced, and no message invents progress.
 *
 * These are `internal` pure functions rather than screen-private ones so the JVM test suite can assert the wording
 * covers every enum entry: a new error code that nobody wrote a sentence for fails the build instead of falling
 * through to a `when` that no longer compiles.
 */
internal fun generationStageMessage(stage: AiGenerationStage): String = when (stage) {
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

internal fun generationMessage(code: AiErrorCode): String = when (code) {
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

internal fun validationMessage(error: BuildRequestValidationError): String = when (error) {
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

internal fun formatSize(sizeBytes: Long?): String {
    if (sizeBytes == null) return "Size unavailable"
    if (sizeBytes < 1024L) return "$sizeBytes B"
    val megabytes = sizeBytes / (1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.1f MiB", megabytes)
}

internal fun hostAndPath(value: String): String = runCatching {
    val uri = URI(value)
    val host = uri.host ?: return@runCatching "Video URL (details hidden)"
    buildString {
        append(host)
        uri.rawPath?.takeIf { it.isNotBlank() }?.let { append(it) }
    }
}.getOrDefault("Video URL (details hidden)")

/**
 * The AI setup precondition shown above the composer (Phase 15 §17).
 *
 * Derived only from real settings state: whether the provider list has loaded, whether a credential is saved, whether
 * a model is selected, and whether that model has been verified in this session. When everything is ready this
 * returns null and the composer shows no setup banner at all — the setup card only exists while something is missing.
 */
data class HomeSetupStatus(
    val tone: CraftMindTone,
    val title: String,
    val message: String,
    val actionLabel: String,
)

/** Null when generation is fully set up; otherwise the one thing the user must do in Settings. */
fun homeSetupStatus(
    providerSettingsLoaded: Boolean,
    providerCredentialSaved: Boolean,
    selectedModelId: String?,
    selectedModel: AiModel?,
): HomeSetupStatus? = when {
    !providerSettingsLoaded -> HomeSetupStatus(
        tone = CraftMindTone.INFORMATIVE,
        title = "Checking your AI setup",
        message = "Reading the provider setup saved on this device…",
        actionLabel = "Open AI settings",
    )

    !providerCredentialSaved -> HomeSetupStatus(
        tone = CraftMindTone.CAUTION,
        title = "Connect an AI provider in Settings to generate builds",
        message = "CraftMind has no hosted AI backend. Save your own provider API key; it stays encrypted in " +
            "app-private storage on this device and is never displayed.",
        actionLabel = "Set up AI provider",
    )

    selectedModelId == null -> HomeSetupStatus(
        tone = CraftMindTone.CAUTION,
        title = "No model selected",
        message = "Your key is saved, but no verified model is selected yet. Test the connection in Settings, then " +
            "choose a model the provider returned.",
        actionLabel = "Choose a model",
    )

    selectedModel == null -> HomeSetupStatus(
        tone = CraftMindTone.CAUTION,
        title = "Model not verified in this session",
        message = "The saved model has not been verified since the app started. Recheck the provider connection in " +
            "Settings before generating.",
        actionLabel = "Open AI settings",
    )

    else -> null
}

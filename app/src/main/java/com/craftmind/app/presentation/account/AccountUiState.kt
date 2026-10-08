package com.craftmind.app.presentation.account

import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.domain.account.AccountAuthErrorCode
import com.craftmind.app.domain.account.AccountAuthenticationAvailability
import com.craftmind.app.domain.account.AccountAvailabilityReason
import com.craftmind.app.domain.account.AccountDeletionOutcome
import com.craftmind.app.domain.account.AccountEmailDeliveryStatus
import com.craftmind.app.domain.account.AccountRemoteSession
import com.craftmind.app.domain.account.AccountSession
import com.craftmind.app.domain.account.AccountSessionEndReason
import com.craftmind.app.domain.account.AccountSessionSource
import com.craftmind.app.domain.account.AccountState
import com.craftmind.app.domain.account.CraftMindDataDomain

/** An action an account surface may offer. Labels live here so they are identical everywhere and reviewable. */
sealed interface AccountAction {
    val label: String

    data object SignIn : AccountAction {
        override val label: String = "Sign in"
    }

    data object SignOut : AccountAction {
        override val label: String = "Sign out"
    }

    data object TryAgain : AccountAction {
        override val label: String = "Try again"
    }

    data object RequestDeletion : AccountAction {
        override val label: String = "Request account deletion"
    }

    data object ContinueLocally : AccountAction {
        override val label: String = "Continue in local mode"
    }
}

/**
 * Everything the account surfaces render (Phase 16 §8).
 *
 * This is a pure projection of [AccountState] plus availability: no Compose types, no clock reads, no side effects, so
 * the wording of every account state — including the ones that cannot be reached in a build without an account service —
 * is covered by JVM tests. The screen renders exactly this object, and the Settings group renders
 * [AccountSettingsSummary], derived from it.
 *
 * Privacy rules encoded here:
 * * only [AccountIdentity.maskedEmailAddress] is ever shown, never the raw address;
 * * the account ID is never rendered, so a leak has no shape to take;
 * * no field can hold a session secret, because nothing in the account model has one.
 */
data class AccountUiState(
    /** Short status label, e.g. "Local mode". */
    val modeLabel: String,
    val tone: CraftMindTone,
    val headline: String,
    val detail: String,
    /** Display name, when a session exists. */
    val identityName: String?,
    /** Masked email identifier, when the account service provided one. */
    val identityReference: String?,
    /** Which account service the session belongs to, when there is one. */
    val identityProviderLine: String?,
    /** What the session is and how far it was verified. */
    val sessionLine: String?,
    /** Set only when authentication cannot be attempted, or when a reason must be stated. */
    val availabilityLine: String?,
    /** Always present: what stays on this device. */
    val localDataLine: String,
    /** The BYOK boundary, stated wherever an account is offered. */
    val securityLine: String,
    val primaryAction: AccountAction?,
    val secondaryAction: AccountAction?,
    /** Why [primaryAction] is not usable right now. A disabled action always carries its reason. */
    val actionUnavailableReason: String?,
    /** True only when a real account form can lead somewhere. Never true without an account service. */
    val showsAccountActions: Boolean,
    /** True exactly while a submission is in flight, taken from the canonical state rather than from a local flag. */
    val formBusy: Boolean,
    /** Screen-reader description of the account status. */
    val accessibilityLabel: String,
    /** True when the local ownership marker has been recorded on this device. */
    val ownershipRecorded: Boolean,
    /** Result of the last account-deletion request, stated plainly. Null when none was made. */
    val deletionLine: String?,
    /** Which account form the surface should show, if any. A form is never shown without a service to submit it to. */
    val formMode: AccountFormMode,
    /** The password rule, stated once so the client and the service cannot describe different rules. */
    val passwordRuleLine: String,
    /** How this device is currently being used, in the terms the user can act on. */
    val guestLine: String,
    /** Field-level complaints for the form that is open. Empty when the form is valid or closed. */
    val formIssues: List<AccountFormIssue>,
    val emailVerified: Boolean? = null,
    val emailVerificationRequired: Boolean = false,
    val emailVerificationLine: String? = null,
    val securityPanel: AccountSecurityPanel = AccountSecurityPanel.NONE,
    val securityOperationState: AccountSecurityOperationState = AccountSecurityOperationState.IDLE,
    val securityMessage: String? = null,
    val securitySessions: List<AccountRemoteSession> = emptyList(),
    val securityIssues: List<AccountSecurityIssue> = emptyList(),
)

/** The account form a surface is showing. */
enum class AccountFormMode {
    NONE,
    SIGN_IN,
    SIGN_UP,
}

enum class AccountSecurityPanel { NONE, FORGOT_PASSWORD, VERIFY_EMAIL, RESET_PASSWORD, CHANGE_PASSWORD, SESSIONS }
enum class AccountSecurityField { EMAIL, TOKEN, CURRENT_PASSWORD, NEW_PASSWORD, CONFIRM_PASSWORD }
data class AccountSecurityIssue(val field: AccountSecurityField, val message: String)
enum class AccountSecurityOperationState {
    IDLE, SUBMITTING, SUCCESS, INVALID_INPUT, EXPIRED_TOKEN, INVALID_TOKEN,
    NETWORK_UNAVAILABLE, BACKEND_UNAVAILABLE, RATE_LIMITED, UNKNOWN_ERROR,
}

/** Compact projection for the Settings group. */
data class AccountSettingsSummary(
    val badgeLabel: String,
    val badgeTone: CraftMindTone,
    val title: String,
    val detail: String,
    val identityName: String?,
    val primaryAction: AccountAction?,
    val actionUnavailableReason: String?,
)

/**
 * Projects account state into renderable content.
 *
 * [nowMillis] is passed in rather than read here so session wording is deterministic in tests.
 */
fun accountUiState(
    state: AccountState,
    availability: AccountAuthenticationAvailability,
    nowMillis: Long,
    ownershipRecorded: Boolean = false,
    deletionOutcome: AccountDeletionOutcome? = null,
    formMode: AccountFormMode = AccountFormMode.NONE,
    formIssues: List<AccountFormIssue> = emptyList(),
    securityPanel: AccountSecurityPanel = AccountSecurityPanel.NONE,
    securityOperationState: AccountSecurityOperationState = AccountSecurityOperationState.IDLE,
    securityMessage: String? = null,
    securitySessions: List<AccountRemoteSession> = emptyList(),
    securityIssues: List<AccountSecurityIssue> = emptyList(),
): AccountUiState {
    val unavailableReason = (availability as? AccountAuthenticationAvailability.Unavailable)?.reason
    val base = AccountUiState(
        modeLabel = "Local mode",
        tone = CraftMindTone.NEUTRAL,
        headline = "CraftMind is running locally",
        detail = "Every local feature works without an account: describing a build, generating and reviewing plans, " +
            "history, refinement, provider keys, and Minecraft pairing.",
        identityName = null,
        identityReference = null,
        identityProviderLine = null,
        sessionLine = null,
        availabilityLine = unavailableReason?.let(::availabilityLine),
        localDataLine = LOCAL_DATA_LINE,
        securityLine = SECURITY_LINE,
        primaryAction = if (unavailableReason == null) AccountAction.SignIn else null,
        secondaryAction = null,
        actionUnavailableReason = unavailableReason?.let(::signInUnavailableReason),
        showsAccountActions = unavailableReason == null,
        formBusy = state is AccountState.SigningIn || securityOperationState == AccountSecurityOperationState.SUBMITTING,
        accessibilityLabel = "Account status: local mode. CraftMind works on this device without an account.",
        ownershipRecorded = ownershipRecorded,
        deletionLine = deletionOutcome?.let(::deletionOutcomeLine),
        // A form is shown while it can lead somewhere: never without a service, never once signed in, and always while a
        // submission is in flight or has just failed, so the answer lands next to the fields that caused it.
        formMode = if (
            unavailableReason == null && state !is AccountState.Authenticated &&
                state !is AccountState.VerificationRequired && state !is AccountState.EmailVerified
        ) {
            formMode
        } else {
            AccountFormMode.NONE
        },
        passwordRuleLine = PASSWORD_RULE_LINE,
        guestLine = GUEST_LINE,
        formIssues = if (unavailableReason == null && formMode != AccountFormMode.NONE) formIssues else emptyList(),
        securityPanel = securityPanel,
        securityOperationState = securityOperationState,
        securityMessage = securityMessage,
        securitySessions = securitySessions,
        securityIssues = securityIssues,
    )
    return when (state) {
        AccountState.Guest -> base

        is AccountState.SigningIn -> base.copy(
            modeLabel = "Signing in",
            tone = CraftMindTone.INFORMATIVE,
            headline = "Signing in",
            detail = "Contacting the CraftMind account service. Local features keep working while this is in flight.",
            primaryAction = null,
            // The open form stays open and busy; it is a view of this state, not a separate flag.
            formBusy = true,
            accessibilityLabel = "Account status: signing in.",
        )

        is AccountState.VerificationRequired -> base.copy(
            modeLabel = "Verify email",
            tone = CraftMindTone.CAUTION,
            headline = "Your account needs email verification",
            detail = "The account was created, but no sign-in session exists until the email address is verified.",
            identityName = state.identity.displayName,
            identityReference = state.identity.maskedEmailAddress,
            emailVerified = false,
            emailVerificationRequired = true,
            emailVerificationLine = verificationDeliveryLine(state.deliveryStatus),
            primaryAction = null,
            showsAccountActions = false,
            accessibilityLabel = "Account status: email verification required for ${state.identity.displayName}.",
        )

        is AccountState.EmailVerified -> base.copy(
            modeLabel = "Email verified",
            tone = CraftMindTone.POSITIVE,
            headline = "Email verified",
            detail = "Your email address is verified. Sign in to establish a session on this device.",
            identityName = state.identity.displayName,
            identityReference = state.identity.maskedEmailAddress,
            emailVerified = true,
            primaryAction = null,
            showsAccountActions = true,
            accessibilityLabel = "Account status: email verified. Sign in to continue.",
        )

        is AccountState.Authenticated -> {
            val session = state.session
            base.copy(
                modeLabel = "Signed in",
                tone = CraftMindTone.POSITIVE,
                headline = "Signed in as ${session.identity.displayName}",
                detail = "A CraftMind account is active on this device, and local features still work exactly as before.",
                identityName = session.identity.displayName,
                identityReference = session.identity.maskedEmailAddress,
                identityProviderLine = "CraftMind account · ${session.method.name.lowercase().replace('_', ' ')}",
                sessionLine = describeSession(session, nowMillis),
                emailVerified = true,
                emailVerificationLine = "Email verified",
                primaryAction = AccountAction.SignOut,
                showsAccountActions = false,
                accessibilityLabel = "Account status: signed in as ${session.identity.displayName}. " +
                    describeSession(session, nowMillis),
            )
        }

        is AccountState.SigningOut -> base.copy(
            modeLabel = "Signing out",
            tone = CraftMindTone.INFORMATIVE,
            headline = "Signing out",
            detail = "Clearing the account session on this device. Builds, settings, provider keys, and Minecraft " +
                "pairing are not touched.",
            primaryAction = null,
            formBusy = false,
            showsAccountActions = false,
            accessibilityLabel = "Account status: signing out.",
        )

        is AccountState.SessionExpired -> {
            val reason = expiredReasonLine(state.reason)
            base.copy(
                modeLabel = "Session ended",
                tone = CraftMindTone.CAUTION,
                headline = "Your CraftMind session ended",
                detail = reason.copy,
                sessionLine = state.lastIdentity?.let { identity ->
                    "Last account on this device: ${identity.displayName}."
                },
                primaryAction = if (unavailableReason == null) AccountAction.TryAgain else null,
                actionUnavailableReason = unavailableReason?.let(::signInUnavailableReason),
                showsAccountActions = unavailableReason == null,
                accessibilityLabel = "Account status: session ended. ${reason.accessibility}",
            )
        }

        is AccountState.Unavailable -> {
            val line = availabilityLine(state.reason)
            base.copy(
                tone = CraftMindTone.INFORMATIVE,
                headline = "Account sign-in is not available",
                detail = line,
                availabilityLine = line,
                primaryAction = null,
                actionUnavailableReason = signInUnavailableReason(state.reason),
                showsAccountActions = false,
                accessibilityLabel = "Account status: sign-in unavailable. $line",
            )
        }

        is AccountState.Failed -> {
            val failure = failureCopy(state.error)
            base.copy(
                modeLabel = "Sign-in problem",
                tone = CraftMindTone.NEGATIVE,
                headline = failure.headline,
                detail = failure.detail,
                primaryAction = failure.action,
                actionUnavailableReason = failure.action?.let { _ ->
                    unavailableReason?.let(::signInUnavailableReason)
                },
                showsAccountActions = failure.action != null && unavailableReason == null,
                accessibilityLabel = "Account status: ${failure.headline}. ${failure.detail}",
            )
        }
    }
}

/**
 * The honest default for a caller that has no account state yet.
 *
 * It projects "local mode, authentication unavailable in this build" — which is the truth for this build — rather than a
 * neutral placeholder that could imply sign-in works.
 */
fun defaultAccountSummary(): AccountSettingsSummary = accountSettingsSummary(
    accountUiState(
        state = AccountState.Guest,
        availability = AccountAuthenticationAvailability.Unavailable(AccountAvailabilityReason.NO_BACKEND_CONFIGURED),
        nowMillis = 0L,
    ),
)

/** The compact Settings projection. */
fun accountSettingsSummary(state: AccountUiState): AccountSettingsSummary = AccountSettingsSummary(
    badgeLabel = state.modeLabel,
    badgeTone = state.tone,
    title = "CraftMind account",
    detail = when {
        state.sessionLine != null -> state.sessionLine
        state.availabilityLine != null -> state.detail
        else -> state.detail
    },
    identityName = state.identityName,
    primaryAction = state.primaryAction,
    actionUnavailableReason = state.actionUnavailableReason,
)

/** The "your data stays yours" card content, short form. */
fun accountLocalDataLines(): List<String> = listOf(
    CraftMindDataDomain.BUILD_HISTORY.summary,
    CraftMindDataDomain.AI_PROVIDER_CREDENTIALS.summary,
    CraftMindDataDomain.MINECRAFT_BRIDGE_PAIRING.summary,
)

/** Every data domain and its ownership classification, for the expandable technical section. */
fun accountDataOwnershipLines(): List<String> = CraftMindDataDomain.entries.map { domain ->
    "${domain.storedBy} — ${domain.ownership.name.lowercase().replace('_', ' ')}: ${domain.summary}"
}

/**
 * What an account-deletion request achieved, in plain language.
 *
 * Deletion is a remote operation. This wording never suggests that local data was removed, because the account layer
 * cannot remove it, and it never promises that a request was filed when no service exists to file it with.
 */
fun deletionOutcomeLine(outcome: AccountDeletionOutcome): String = when (outcome) {
    is AccountDeletionOutcome.Requested -> "The account service accepted a deletion request" +
        (outcome.requestReference?.let { " (reference $it)" } ?: "") + ". Local CraftMind data on this device is not " +
        "deleted by that request; delete it separately if you want it gone."

    is AccountDeletionOutcome.Unavailable -> "No deletion request could be made: " +
        availabilityLine(outcome.reason) + " Nothing was deleted, on this device or anywhere else."

    is AccountDeletionOutcome.Failure -> "The deletion request did not complete. Nothing was deleted, on this device " +
        "or anywhere else. This device's session was left untouched."
}

/** How a session's validity and provenance are described. Never implies a check that did not happen. */
fun describeSession(session: AccountSession, nowMillis: Long): String {
    val provenance = when (session.source) {
        AccountSessionSource.LIVE_SIGN_IN ->
            "Verified with the account service in this app run."

        AccountSessionSource.RESTORED_ON_DEVICE ->
            "Restored from this device; not re-checked with the account service in this app run."
    }
    val expiry = session.expiresAtEpochMillis?.let { expiresAt ->
        val remaining = expiresAt - nowMillis
        when {
            remaining <= 0L -> "The declared expiry has passed."
            remaining < ONE_HOUR_MILLIS -> "Declared valid for another ${remaining / ONE_MINUTE_MILLIS} minute(s)."
            remaining < TWO_DAYS_MILLIS -> "Declared valid for another ${remaining / ONE_HOUR_MILLIS} hour(s)."
            else -> "Declared valid for another ${remaining / ONE_DAY_MILLIS} day(s)."
        }
    } ?: "The account service declared no expiry for this session."
    return "$provenance $expiry"
}

private data class ExpiredReasonCopy(val copy: String, val accessibility: String)

private fun expiredReasonLine(reason: AccountSessionEndReason): ExpiredReasonCopy = when (reason) {
    AccountSessionEndReason.EXPIRED -> ExpiredReasonCopy(
        copy = "The session's declared expiry passed, so CraftMind is running locally again. Nothing on this device " +
            "was deleted.",
        accessibility = "The session expired. CraftMind is running locally.",
    )

    AccountSessionEndReason.REFRESH_NOT_SUPPORTED -> ExpiredReasonCopy(
        copy = "The session expired and the account service does not allow refreshing it. CraftMind is running " +
            "locally in the meantime.",
        accessibility = "The session expired and cannot be refreshed.",
    )

    AccountSessionEndReason.REFRESH_UNAVAILABLE -> ExpiredReasonCopy(
        copy = "The session could not be refreshed because the account service was unreachable. The saved session was " +
            "kept, so a later attempt can still succeed.",
        accessibility = "The session could not be refreshed; the account service was unreachable.",
    )

    AccountSessionEndReason.ACCOUNT_SUSPENDED -> ExpiredReasonCopy(
        copy = "The account service reports this account as suspended, so it can no longer be used to sign in. " +
            "CraftMind keeps working locally, and nothing on this device was deleted.",
        accessibility = "The account is suspended. CraftMind is running locally.",
    )

    AccountSessionEndReason.REJECTED_BY_SERVICE -> ExpiredReasonCopy(
        copy = "The account service no longer accepts this session, so the saved session was removed from this device.",
        accessibility = "The account service rejected the stored session.",
    )

    AccountSessionEndReason.INVALID_STORED_SESSION -> ExpiredReasonCopy(
        copy = "The saved session on this device could not be read and was discarded. Nothing else was affected.",
        accessibility = "The saved session could not be read.",
    )
}

private data class FailureCopy(
    val headline: String,
    val detail: String,
    val action: AccountAction?,
)

private fun failureCopy(error: AccountAuthErrorCode): FailureCopy = when (error) {
    AccountAuthErrorCode.INVALID_CREDENTIALS -> FailureCopy(
        headline = "Those credentials were not accepted",
        detail = "The account service received the attempt and rejected it. CraftMind is running locally, and " +
            "nothing was changed on this device.",
        action = AccountAction.SignIn,
    )

    AccountAuthErrorCode.CANCELLED -> FailureCopy(
        headline = "Sign-in was cancelled",
        detail = "No session was created. CraftMind keeps running locally.",
        action = AccountAction.SignIn,
    )

    AccountAuthErrorCode.EMAIL_NOT_VERIFIED -> FailureCopy(
        headline = "Verify your email before signing in",
        detail = "The credentials were accepted, but this account has no verified email yet. Resend the verification message from the account screen.",
        action = null,
    )

    AccountAuthErrorCode.RATE_LIMITED -> FailureCopy(
        headline = "Too many attempts",
        detail = "The account service is temporarily limiting sign-in attempts. Wait a while before trying again.",
        action = null,
    )

    AccountAuthErrorCode.NETWORK_UNAVAILABLE -> FailureCopy(
        headline = "No network connection",
        detail = "The account service could not be reached from this device. Local features are unaffected.",
        action = AccountAction.TryAgain,
    )

    AccountAuthErrorCode.SERVICE_UNAVAILABLE -> FailureCopy(
        headline = "The account service is unavailable",
        detail = "The service answered with an error. The attempt can be repeated; local features keep working.",
        action = AccountAction.TryAgain,
    )

    AccountAuthErrorCode.AUTHENTICATION_UNAVAILABLE -> FailureCopy(
        headline = "Account sign-in is not available",
        detail = "This build cannot authenticate against a CraftMind account service.",
        action = null,
    )

    AccountAuthErrorCode.INVALID_REQUEST -> FailureCopy(
        headline = "That sign-in request could not be used",
        detail = "The email address or password field was not usable, so nothing was sent anywhere. CraftMind keeps " +
            "running locally.",
        action = AccountAction.SignIn,
    )

    AccountAuthErrorCode.ACCOUNT_ALREADY_EXISTS -> FailureCopy(
        headline = "An account already exists for that email",
        detail = "CraftMind will not create a second account for the same address. Try signing in instead, or use a " +
            "different email address.",
        action = AccountAction.SignIn,
    )

    AccountAuthErrorCode.ACCOUNT_SUSPENDED -> FailureCopy(
        headline = "This account is suspended",
        detail = "The account service is refusing sign-in for this account. CraftMind is still fully usable locally, " +
            "and nothing on this device has changed.",
        action = null,
    )

    AccountAuthErrorCode.SESSION_REJECTED -> FailureCopy(
        headline = "Your session is no longer valid",
        detail = "The account service did not accept the saved session, so it was removed from this device. Sign in " +
            "again to continue; your local data is untouched.",
        action = AccountAction.SignIn,
    )

    AccountAuthErrorCode.MALFORMED_RESPONSE -> FailureCopy(
        headline = "The account service answered unexpectedly",
        detail = "The response did not match the contract, so it was discarded rather than trusted. CraftMind is " +
            "running locally.",
        action = null,
    )

    AccountAuthErrorCode.SESSION_STORAGE_FAILURE -> FailureCopy(
        headline = "The session could not be stored securely",
        detail = "This device could not write the encrypted session record, so no session was kept. Check that the " +
            "device can read its own private storage, then try again.",
        action = AccountAction.TryAgain,
    )

    AccountAuthErrorCode.UNEXPECTED_FAILURE -> FailureCopy(
        headline = "Sign-in did not complete",
        detail = "The attempt failed for a reason CraftMind does not report in detail, because that detail could " +
            "contain credentials. Nothing on this device changed.",
        action = AccountAction.TryAgain,
    )
}

private fun verificationDeliveryLine(status: AccountEmailDeliveryStatus): String = when (status) {
    AccountEmailDeliveryStatus.PROVIDER_ACCEPTED,
    AccountEmailDeliveryStatus.SENT -> "The configured email provider accepted the verification request. It has not confirmed delivery; check your inbox and spam folder."
    AccountEmailDeliveryStatus.DEVELOPMENT_SINK -> "No email was sent: this build uses a local development sink. Configure a real email provider before production use."
    AccountEmailDeliveryStatus.PROVIDER_CONFIGURED -> "Email delivery is configured. Request a verification message when ready."
    AccountEmailDeliveryStatus.UNAVAILABLE -> "The account was created, but email delivery did not complete. You can retry sending the verification message."
    AccountEmailDeliveryStatus.NOT_APPLICABLE -> "Use the verification action to request a one-time code."
}

private fun availabilityLine(reason: AccountAvailabilityReason): String = when (reason) {
    AccountAvailabilityReason.NO_BACKEND_CONFIGURED ->
        "This build of CraftMind has no account service, so there is nothing to sign in to yet. Local mode is the " +
            "complete CraftMind experience for now."

    AccountAvailabilityReason.NETWORK_UNAVAILABLE ->
        "This device has no network connection to the account service."

    AccountAvailabilityReason.SERVICE_UNREACHABLE ->
        "The CraftMind account service could not be reached from this device."

    AccountAvailabilityReason.MAINTENANCE ->
        "The CraftMind account service is temporarily not accepting sign-ins."

    AccountAvailabilityReason.DISABLED_BY_POLICY ->
        "Sign-in is switched off for this build."

    AccountAvailabilityReason.NOT_IMPLEMENTED_BY_SERVICE ->
        "The CraftMind account service does not implement this yet, so CraftMind says so rather than pretending " +
            "something happened."
}

private fun signInUnavailableReason(reason: AccountAvailabilityReason): String = when (reason) {
    AccountAvailabilityReason.NO_BACKEND_CONFIGURED -> "Sign-in is unavailable: this build has no account service."
    AccountAvailabilityReason.NETWORK_UNAVAILABLE -> "Sign-in is unavailable: this device is offline."
    AccountAvailabilityReason.SERVICE_UNREACHABLE -> "Sign-in is unavailable: the account service could not be reached."
    AccountAvailabilityReason.MAINTENANCE -> "Sign-in is unavailable: the account service is in maintenance."
    AccountAvailabilityReason.DISABLED_BY_POLICY -> "Sign-in is unavailable: it is switched off for this build."
    AccountAvailabilityReason.NOT_IMPLEMENTED_BY_SERVICE ->
        "Unavailable: the account service does not implement this yet."
}

/** Stated wherever an account is offered, so the BYOK boundary is never ambiguous. */
/** The password rule, identical to the one the account service enforces. */
internal const val PASSWORD_RULE_LINE: String =
    "Passwords need at least 10 characters, and at least two of: lowercase letters, uppercase letters, digits, symbols."

/** Guest mode, stated as a complete way to use CraftMind rather than a deficit. */
internal const val GUEST_LINE: String =
    "You are using CraftMind as a guest. Everything on this device works without an account, and an anonymous guest " +
        "identity is used only to tell an unregistered device apart from a registered account."

internal const val SECURITY_LINE: String =
    "A CraftMind account is separate from your AI provider keys and from your Minecraft pairing. Signing in never " +
        "uploads an API key, and it never gives anyone control of your Minecraft runtime."

/** Stated in every state, including signed in. */
internal const val LOCAL_DATA_LINE: String =
    "Signing out only clears the account session. Builds, saved plans, settings, provider keys, and Minecraft " +
        "pairing stay on this device until you delete them yourself."

private const val ONE_MINUTE_MILLIS = 60_000L
private const val ONE_HOUR_MILLIS = 60 * ONE_MINUTE_MILLIS
private const val ONE_DAY_MILLIS = 24 * ONE_HOUR_MILLIS
private const val TWO_DAYS_MILLIS = 2 * ONE_DAY_MILLIS

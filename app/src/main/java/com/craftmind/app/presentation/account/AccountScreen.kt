package com.craftmind.app.presentation.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.craftmind.app.designsystem.CraftMindCard
import com.craftmind.app.designsystem.CraftMindDetailLines
import com.craftmind.app.designsystem.CraftMindExpandableSection
import com.craftmind.app.designsystem.CraftMindKeyValueRow
import com.craftmind.app.designsystem.CraftMindLayout
import com.craftmind.app.designsystem.CraftMindNotice
import com.craftmind.app.designsystem.CraftMindPrimaryButton
import com.craftmind.app.designsystem.CraftMindScreen
import com.craftmind.app.designsystem.CraftMindSecondaryButton
import com.craftmind.app.designsystem.CraftMindSectionHeader
import com.craftmind.app.designsystem.CraftMindStatusBadge
import com.craftmind.app.designsystem.CraftMindTextInput
import com.craftmind.app.designsystem.CraftMindTertiaryButton
import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.designsystem.CraftMindType

/**
 * Account screen (Phase 16 §8).
 *
 * Reachable from Settings; it is **not** a navigation destination, and the four top-level destinations are unchanged.
 * The screen answers three questions in order: what is my account state, what would an account change, and what happens
 * to my data. It renders only real state, so in a build without an account service it reads as local mode with an
 * honest explanation rather than an inert sign-in form.
 *
 * Everything visual comes from the Phase 15 design system: no raw colours or radii, one filled action at most, and a
 * reason attached to every unavailable action.
 */
@Composable
fun AccountScreen(
    state: AccountUiState,
    onEvent: (AccountUiEvent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var technicalExpanded by remember { mutableStateOf(false) }
    var ownershipExpanded by remember { mutableStateOf(false) }

    CraftMindScreen(
        title = "CraftMind account",
        eyebrow = "Account",
        subtitle = "Identity, session, and what stays on this device.",
        modifier = modifier,
        actions = { CraftMindTertiaryButton(text = "Close", onClick = onClose) },
    ) {
        CraftMindCard(emphasized = true) {
            CraftMindSectionHeader(
                eyebrow = "Status",
                title = state.headline,
                subtitle = state.detail,
                trailing = { CraftMindStatusBadge(label = state.modeLabel, tone = state.tone) },
            )
            state.identityName?.let { name ->
                CraftMindKeyValueRow(label = "Account", value = name)
            }
            state.identityReference?.let { reference ->
                CraftMindKeyValueRow(label = "Identifier", value = reference)
            }
            state.identityProviderLine?.let { provider ->
                CraftMindKeyValueRow(label = "Service", value = provider)
            }
            state.sessionLine?.let { session ->
                Text(
                    text = session,
                    style = CraftMindType.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.availabilityLine?.let { line ->
                CraftMindNotice(tone = CraftMindTone.INFORMATIVE, message = line)
            }
            state.deletionLine?.let { line ->
                CraftMindNotice(tone = CraftMindTone.INFORMATIVE, title = "Account deletion", message = line)
            }
            state.actionUnavailableReason?.let { reason ->
                // A disabled action always explains itself; the user never has to guess why.
                CraftMindNotice(tone = CraftMindTone.CAUTION, message = reason)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
            ) {
                when (state.primaryAction) {
                    null -> Unit
                    AccountAction.SignOut -> CraftMindSecondaryButton(
                        text = state.primaryAction.label,
                        onClick = { onEvent(AccountUiEvent.SignOutRequested) },
                    )

                    else -> CraftMindPrimaryButton(
                        text = state.primaryAction.label,
                        onClick = { onEvent(AccountUiEvent.RetryRequested) },
                    )
                }
            }
            if (state.showsAccountActions) {
                // Actions exist only where a service can answer them. There is no sign-in button in a build without one.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
                ) {
                    CraftMindSecondaryButton(
                        text = "Sign in",
                        onClick = { onEvent(AccountUiEvent.SignInFormRequested) },
                    )
                    CraftMindSecondaryButton(
                        text = "Create account",
                        onClick = { onEvent(AccountUiEvent.SignUpFormRequested) },
                    )
                }
            } else {
                // Guest mode is a complete way to use CraftMind, and the screen says so rather than nagging.
                Text(
                    text = state.guestLine,
                    style = CraftMindType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.formMode == AccountFormMode.SIGN_IN) {
            AccountSignInForm(state = state, onEvent = onEvent)
        }

        if (state.formMode == AccountFormMode.SIGN_UP) {
            AccountSignUpForm(state = state, onEvent = onEvent)
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "What an account is for",
                title = "The foundation for cloud features",
                subtitle = "Accounts are the identity layer future CraftMind features are built on.",
            )
            CraftMindNotice(
                tone = CraftMindTone.CAUTION,
                title = "No cloud feature is switched on by signing in",
                message = "CraftMind does not synchronise anything today. Syncing builds across devices, an " +
                    "account-owned history, creator profiles, and the marketplace do not exist yet, and signing in " +
                    "cannot enable them.",
            )
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "Your data",
                title = "What stays on this device",
                subtitle = "Ownership of each kind of CraftMind data, stated plainly.",
            )
            CraftMindDetailLines(accountLocalDataLines())
            Text(
                text = state.securityLine,
                style = CraftMindType.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.localDataLine,
                style = CraftMindType.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CraftMindExpandableSection(
                title = "Every data domain and its owner",
                summary = if (ownershipExpanded) "Hide the classification" else "Builds, settings, secrets, runtime",
                expanded = ownershipExpanded,
                onToggle = { ownershipExpanded = !ownershipExpanded },
            ) {
                CraftMindDetailLines(accountDataOwnershipLines())
            }
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "Account management",
                title = "Sign-out, deletion, and local data",
                subtitle = "Three different operations with three different effects.",
            )
            CraftMindDetailLines(
                listOf(
                    "Sign out clears the account session on this device only. Builds, settings, provider keys, and " +
                        "Minecraft pairing are not touched.",
                    "Deleting local data removes builds or settings on this device. It does not affect an account.",
                    "Requesting account deletion asks the account service to remove the account. It does not delete " +
                        "anything on this device.",
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
            ) {
                CraftMindSecondaryButton(
                    text = AccountAction.RequestDeletion.label,
                    onClick = { onEvent(AccountUiEvent.AccountDeletionRequested) },
                )
            }
            if (state.availabilityLine != null || state.actionUnavailableReason != null) {
                Text(
                    text = "Account deletion requests need an account service. Without one, CraftMind says so " +
                        "instead of pretending a request was filed.",
                    style = CraftMindType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        CraftMindCard {
            CraftMindExpandableSection(
                title = "Technical detail",
                summary = if (technicalExpanded) "Hide diagnostics" else "Availability, session source, storage",
                expanded = technicalExpanded,
                onToggle = { technicalExpanded = !technicalExpanded },
            ) {
                CraftMindDetailLines(
                    listOfNotNull(
                        state.availabilityLine?.let { "Authentication availability: $it" },
                        state.sessionLine?.let { "Session: $it" },
                        "Local ownership marker recorded on this device: ${if (state.ownershipRecorded) "yes" else "not yet"}",
                        "Account secrets are stored in their own Android Keystore namespace, separate from AI provider keys.",
                    ),
                )
            }
        }
    }
}

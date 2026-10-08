package com.craftmind.app.presentation.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.craftmind.app.designsystem.CraftMindCard
import com.craftmind.app.designsystem.CraftMindDestructiveButton
import com.craftmind.app.designsystem.CraftMindLayout
import com.craftmind.app.designsystem.CraftMindNotice
import com.craftmind.app.designsystem.CraftMindPrimaryButton
import com.craftmind.app.designsystem.CraftMindSecondaryButton
import com.craftmind.app.designsystem.CraftMindSectionHeader
import com.craftmind.app.designsystem.CraftMindStatusBadge
import com.craftmind.app.designsystem.CraftMindTextInput
import com.craftmind.app.designsystem.CraftMindTertiaryButton
import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.designsystem.CraftMindType
import com.craftmind.app.domain.account.AccountRemoteSession
import java.util.Date

@Composable
fun AccountSecurityActions(onEvent: (AccountUiEvent) -> Unit) {
    CraftMindCard {
        CraftMindSectionHeader(
            eyebrow = "Security",
            title = "Manage your account security",
            subtitle = "Password changes retain this session and revoke other sessions. You can also review active sessions.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm)) {
            CraftMindSecondaryButton(
                text = "Change password",
                onClick = { onEvent(AccountUiEvent.SecurityPanelRequested(AccountSecurityPanel.CHANGE_PASSWORD)) },
            )
            CraftMindSecondaryButton(
                text = "Manage sessions",
                onClick = { onEvent(AccountUiEvent.SecurityPanelRequested(AccountSecurityPanel.SESSIONS)) },
            )
        }
    }
}

@Composable
fun AccountSecurityPanelContent(
    state: AccountUiState,
    onEvent: (AccountUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.securityPanel) {
        AccountSecurityPanel.NONE -> Unit
        AccountSecurityPanel.FORGOT_PASSWORD -> RecoveryRequestPanel(state, onEvent, modifier)
        AccountSecurityPanel.VERIFY_EMAIL -> VerificationPanel(state, onEvent, modifier)
        AccountSecurityPanel.RESET_PASSWORD -> PasswordResetPanel(state, onEvent, modifier)
        AccountSecurityPanel.CHANGE_PASSWORD -> PasswordChangePanel(state, onEvent, modifier)
        AccountSecurityPanel.SESSIONS -> SessionsPanel(state, onEvent, modifier)
    }
}

@Composable
private fun RecoveryRequestPanel(state: AccountUiState, onEvent: (AccountUiEvent) -> Unit, modifier: Modifier) {
    var email by remember { mutableStateOf("") }
    val busy = state.securityOperationState == AccountSecurityOperationState.SUBMITTING
    CraftMindCard(modifier) {
        CraftMindSectionHeader(
            eyebrow = "Account recovery",
            title = "Reset your password",
            subtitle = "The response does not reveal whether an account exists. A reset revokes all sessions.",
        )
        SecurityStatus(state)
        CraftMindTextInput(
            label = "Email address",
            value = email,
            onValueChange = { email = it },
            enabled = !busy,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            issue = state.securityIssue(AccountSecurityField.EMAIL),
        )
        CraftMindPrimaryButton(
            text = if (busy) "Requesting…" else "Send recovery instructions",
            onClick = { onEvent(AccountUiEvent.PasswordResetRequested(email)) },
            enabled = !busy,
            loading = busy,
        )
        if (!busy && state.securityOperationState == AccountSecurityOperationState.SUCCESS) {
            CraftMindTertiaryButton(
                text = "Enter a recovery code",
                onClick = { onEvent(AccountUiEvent.SecurityPanelRequested(AccountSecurityPanel.RESET_PASSWORD)) },
            )
        }
        CloseSecurityPanel(onEvent)
    }
}

@Composable
private fun VerificationPanel(state: AccountUiState, onEvent: (AccountUiEvent) -> Unit, modifier: Modifier) {
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val busy = state.securityOperationState == AccountSecurityOperationState.SUBMITTING
    CraftMindCard(modifier) {
        CraftMindSectionHeader(
            eyebrow = "Email verification",
            title = "Verify your email address",
            subtitle = "Verification codes are single-use and expire. Resending may invalidate an earlier code.",
        )
        SecurityStatus(state)
        if (state.emailVerificationLine != null) {
            CraftMindNotice(tone = CraftMindTone.INFORMATIVE, message = state.emailVerificationLine)
        }
        CraftMindTextInput(
            label = "Email address",
            value = email,
            onValueChange = { email = it },
            enabled = !busy,
            keyboardType = KeyboardType.Email,
            issue = state.securityIssue(AccountSecurityField.EMAIL),
        )
        CraftMindTextInput(
            label = "One-time verification code",
            value = token,
            onValueChange = { token = it },
            enabled = !busy,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
            issue = state.securityIssue(AccountSecurityField.TOKEN),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm)) {
            CraftMindPrimaryButton(
                text = if (busy) "Verifying…" else "Verify email",
                onClick = {
                    val submitted = token
                    token = ""
                    onEvent(AccountUiEvent.VerifyEmailSubmitted(submitted.toCharArray()))
                },
                enabled = !busy,
                loading = busy,
                fullWidth = false,
            )
            CraftMindSecondaryButton(
                text = "Resend code",
                onClick = { onEvent(AccountUiEvent.ResendVerificationSubmitted(email)) },
                enabled = !busy,
                loading = busy,
            )
        }
        CloseSecurityPanel(onEvent)
    }
}

@Composable
private fun PasswordResetPanel(state: AccountUiState, onEvent: (AccountUiEvent) -> Unit, modifier: Modifier) {
    var token by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val busy = state.securityOperationState == AccountSecurityOperationState.SUBMITTING
    CraftMindCard(modifier) {
        CraftMindSectionHeader(
            eyebrow = "Complete recovery",
            title = "Choose a new password",
            subtitle = "A successful reset invalidates every existing session. You will need to sign in again.",
        )
        SecurityStatus(state)
        CraftMindTextInput(
            label = "One-time recovery code",
            value = token,
            onValueChange = { token = it },
            enabled = !busy,
            keyboardType = KeyboardType.Ascii,
            issue = state.securityIssue(AccountSecurityField.TOKEN),
        )
        CraftMindTextInput(
            label = "New password",
            value = password,
            onValueChange = { password = it },
            enabled = !busy,
            isSecret = true,
            supportingText = state.passwordRuleLine,
            issue = state.securityIssue(AccountSecurityField.NEW_PASSWORD),
        )
        CraftMindTextInput(
            label = "Confirm new password",
            value = confirmation,
            onValueChange = { confirmation = it },
            enabled = !busy,
            isSecret = true,
            imeAction = ImeAction.Done,
            issue = state.securityIssue(AccountSecurityField.CONFIRM_PASSWORD),
        )
        CraftMindPrimaryButton(
            text = if (busy) "Resetting…" else "Reset password",
            onClick = {
                val submittedToken = token
                val submittedPassword = password
                val submittedConfirmation = confirmation
                token = ""
                password = ""
                confirmation = ""
                onEvent(
                    AccountUiEvent.PasswordResetConfirmed(
                        submittedToken.toCharArray(),
                        submittedPassword.toCharArray(),
                        submittedConfirmation.toCharArray(),
                    ),
                )
            },
            enabled = !busy,
            loading = busy,
        )
        CloseSecurityPanel(onEvent)
    }
}

@Composable
private fun PasswordChangePanel(state: AccountUiState, onEvent: (AccountUiEvent) -> Unit, modifier: Modifier) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val busy = state.securityOperationState == AccountSecurityOperationState.SUBMITTING
    CraftMindCard(modifier) {
        CraftMindSectionHeader(
            eyebrow = "Password change",
            title = "Change your password",
            subtitle = "Your current session remains active. Other sessions are revoked after the password changes.",
        )
        SecurityStatus(state)
        CraftMindTextInput(
            label = "Current password",
            value = currentPassword,
            onValueChange = { currentPassword = it },
            enabled = !busy,
            isSecret = true,
            issue = state.securityIssue(AccountSecurityField.CURRENT_PASSWORD),
        )
        CraftMindTextInput(
            label = "New password",
            value = newPassword,
            onValueChange = { newPassword = it },
            enabled = !busy,
            isSecret = true,
            supportingText = state.passwordRuleLine,
            issue = state.securityIssue(AccountSecurityField.NEW_PASSWORD),
        )
        CraftMindTextInput(
            label = "Confirm new password",
            value = confirmation,
            onValueChange = { confirmation = it },
            enabled = !busy,
            isSecret = true,
            imeAction = ImeAction.Done,
            issue = state.securityIssue(AccountSecurityField.CONFIRM_PASSWORD),
        )
        CraftMindPrimaryButton(
            text = if (busy) "Changing…" else "Change password",
            onClick = {
                val current = currentPassword
                val next = newPassword
                val confirm = confirmation
                currentPassword = ""
                newPassword = ""
                confirmation = ""
                onEvent(
                    AccountUiEvent.PasswordChangeRequested(
                        current.toCharArray(), next.toCharArray(), confirm.toCharArray(),
                    ),
                )
            },
            enabled = !busy,
            loading = busy,
        )
        CloseSecurityPanel(onEvent)
    }
}

@Composable
private fun SessionsPanel(state: AccountUiState, onEvent: (AccountUiEvent) -> Unit, modifier: Modifier) {
    var selectedSession by remember { mutableStateOf<AccountRemoteSession?>(null) }
    var confirmOtherSessions by remember { mutableStateOf(false) }
    val busy = state.securityOperationState == AccountSecurityOperationState.SUBMITTING
    CraftMindCard(modifier) {
        CraftMindSectionHeader(
            eyebrow = "Sessions",
            title = "Sessions for this account",
            subtitle = "Only safe metadata is shown. Session credentials and internal identifiers are never displayed.",
        )
        SecurityStatus(state)
        if (state.securitySessions.isEmpty() && state.securityOperationState != AccountSecurityOperationState.SUBMITTING) {
            Text(
                text = "No session metadata is available. Refresh to check again.",
                style = CraftMindType.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.securitySessions.forEach { session ->
            Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.xs)) {
                Text(
                    text = if (session.isCurrent) "This device · ${session.deviceLabel}" else "Other device · ${session.deviceLabel}",
                    style = CraftMindType.titleSmall,
                )
                session.lastUsedAtEpochMillis?.let { lastUsed ->
                    Text(
                        text = "Last active ${Date(lastUsed)}",
                        style = CraftMindType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!session.isCurrent) {
                    CraftMindDestructiveButton(
                        text = "Revoke this session",
                        onClick = { selectedSession = session },
                    )
                } else {
                    Text(
                        text = "Current session · retained by session-management actions",
                        style = CraftMindType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm)) {
            CraftMindSecondaryButton(
                text = "Refresh sessions",
                onClick = { onEvent(AccountUiEvent.SessionsRefreshRequested) },
                enabled = !busy,
                loading = busy,
            )
            CraftMindSecondaryButton(
                text = "Revoke other sessions",
                onClick = { confirmOtherSessions = true },
                enabled = !busy,
                loading = busy,
            )
        }
        CloseSecurityPanel(onEvent)
    }

    selectedSession?.let { session ->
        AlertDialog(
            onDismissRequest = { selectedSession = null },
            title = { Text("Revoke this session?") },
            text = { Text("${session.deviceLabel} will need to sign in again. This device is not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    onEvent(AccountUiEvent.SessionRevocationRequested(session.sessionId))
                    selectedSession = null
                }) { Text("Revoke session") }
            },
            dismissButton = { TextButton(onClick = { selectedSession = null }) { Text("Cancel") } },
        )
    }
    if (confirmOtherSessions) {
        AlertDialog(
            onDismissRequest = { confirmOtherSessions = false },
            title = { Text("Revoke other sessions?") },
            text = { Text("Other devices will need to sign in again. This current device remains signed in.") },
            confirmButton = {
                TextButton(onClick = {
                    onEvent(AccountUiEvent.OtherSessionsRevocationRequested)
                    confirmOtherSessions = false
                }) { Text("Revoke other sessions") }
            },
            dismissButton = { TextButton(onClick = { confirmOtherSessions = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SecurityStatus(state: AccountUiState) {
    val message = state.securityMessage ?: return
    val tone = when (state.securityOperationState) {
        AccountSecurityOperationState.SUCCESS -> CraftMindTone.POSITIVE
        AccountSecurityOperationState.INVALID_INPUT,
        AccountSecurityOperationState.EXPIRED_TOKEN,
        AccountSecurityOperationState.INVALID_TOKEN,
        AccountSecurityOperationState.RATE_LIMITED -> CraftMindTone.CAUTION
        AccountSecurityOperationState.NETWORK_UNAVAILABLE,
        AccountSecurityOperationState.BACKEND_UNAVAILABLE,
        AccountSecurityOperationState.UNKNOWN_ERROR -> CraftMindTone.NEGATIVE
        AccountSecurityOperationState.IDLE,
        AccountSecurityOperationState.SUBMITTING -> CraftMindTone.INFORMATIVE
    }
    CraftMindNotice(
        tone = tone,
        title = state.securityOperationState.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase),
        message = message,
    )
}

@Composable
private fun CloseSecurityPanel(onEvent: (AccountUiEvent) -> Unit) {
    CraftMindTertiaryButton(text = "Close", onClick = { onEvent(AccountUiEvent.SecurityPanelDismissed) })
}

private fun AccountUiState.securityIssue(field: AccountSecurityField): String? =
    securityIssues.firstOrNull { it.field == field }?.message

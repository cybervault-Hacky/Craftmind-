package com.craftmind.app.presentation.account

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
import com.craftmind.app.designsystem.CraftMindLayout
import com.craftmind.app.designsystem.CraftMindPrimaryButton
import com.craftmind.app.designsystem.CraftMindSectionHeader
import com.craftmind.app.designsystem.CraftMindTertiaryButton
import com.craftmind.app.designsystem.CraftMindTextInput
import com.craftmind.app.designsystem.CraftMindType

/**
 * The sign-in form (Phase 17 §6).
 *
 * It is a view of the canonical account state, not a second place where "am I signed in?" lives: the button is busy
 * exactly while the state is [AccountState.SigningIn], and it becomes enabled again the moment the state machine
 * leaves that state. There is no local flag that could disagree with the state machine, and no success message is ever
 * shown that the state machine did not produce.
 *
 * Password characters live in this composition only: they are handed to the event, cleared by the state machine, and
 * never copied into state that outlives the entry.
 */
@Composable
fun AccountSignInForm(
    state: AccountUiState,
    onEvent: (AccountUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy = state.formBusy

    CraftMindCard(modifier = modifier) {
        CraftMindSectionHeader(
            eyebrow = "Sign in",
            title = "Sign in to CraftMind",
            subtitle = "Your account is the identity layer future cloud features are built on.",
        )
        CraftMindTextInput(
            label = "Email address",
            value = email,
            onValueChange = { email = it },
            enabled = !busy,
            keyboardType = KeyboardType.Email,
            issue = state.issueFor(AccountFormField.EMAIL),
        )
        CraftMindTextInput(
            label = "Password",
            value = password,
            onValueChange = { password = it },
            enabled = !busy,
            isSecret = true,
            imeAction = ImeAction.Done,
            issue = state.issueFor(AccountFormField.PASSWORD),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            CraftMindTertiaryButton(
                text = "Forgot password?",
                onClick = { onEvent(AccountUiEvent.SecurityPanelRequested(AccountSecurityPanel.FORGOT_PASSWORD)) },
                enabled = !busy,
            )
            CraftMindTertiaryButton(
                text = "Resend verification",
                onClick = { onEvent(AccountUiEvent.SecurityPanelRequested(AccountSecurityPanel.VERIFY_EMAIL)) },
                enabled = !busy,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            CraftMindPrimaryButton(
                text = if (busy) "Signing in…" else "Sign in",
                onClick = {
                    val submitted = password
                    password = ""
                    onEvent(AccountUiEvent.SignInRequested(emailAddress = email, password = submitted.toCharArray()))
                },
                enabled = !busy,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) {
            CraftMindTertiaryButton(
                text = "Create an account instead",
                onClick = { onEvent(AccountUiEvent.SignUpFormRequested) },
                enabled = !busy,
            )
            CraftMindTertiaryButton(
                text = "Continue as Guest",
                onClick = { onEvent(AccountUiEvent.FormDismissed) },
                enabled = !busy,
            )
        }
    }
}

/**
 * The sign-up form (Phase 17 §5).
 *
 * Validation happens in two places for two different reasons: here, before anything is sent, so a typo does not become
 * a network round trip; and on the service, because a client is never the authority on what an account may contain.
 * Both answer in the same words, and neither ever shows a raw error payload.
 */
@Composable
fun AccountSignUpForm(
    state: AccountUiState,
    onEvent: (AccountUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val busy = state.formBusy

    CraftMindCard(modifier = modifier) {
        CraftMindSectionHeader(
            eyebrow = "Create account",
            title = "Create your CraftMind account",
            subtitle = "Everything CraftMind does locally keeps working exactly as it does now.",
        )
        CraftMindTextInput(
            label = "Email address",
            value = email,
            onValueChange = { email = it },
            enabled = !busy,
            keyboardType = KeyboardType.Email,
            issue = state.issueFor(AccountFormField.EMAIL),
        )
        CraftMindTextInput(
            label = "Display name",
            value = displayName,
            onValueChange = { displayName = it },
            enabled = !busy,
            supportingText = "Shown to you in settings. CraftMind has no public profiles yet.",
            issue = state.issueFor(AccountFormField.DISPLAY_NAME),
        )
        CraftMindTextInput(
            label = "Password",
            value = password,
            onValueChange = { password = it },
            enabled = !busy,
            isSecret = true,
            supportingText = state.passwordRuleLine,
            issue = state.issueFor(AccountFormField.PASSWORD),
        )
        CraftMindTextInput(
            label = "Confirm password",
            value = confirmation,
            onValueChange = { confirmation = it },
            enabled = !busy,
            isSecret = true,
            imeAction = ImeAction.Done,
            issue = state.issueFor(AccountFormField.CONFIRM_PASSWORD),
        )
        Text(
            text = "Verify your email before signing in. A session is not created until the address is verified; " +
                "delivery status is shown honestly for this service configuration.",
            style = CraftMindType.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            CraftMindPrimaryButton(
                text = if (busy) "Creating account…" else "Create account",
                onClick = {
                    val submitted = password
                    val submittedConfirmation = confirmation
                    password = ""
                    confirmation = ""
                    onEvent(
                        AccountUiEvent.SignUpRequested(
                            emailAddress = email,
                            password = submitted.toCharArray(),
                            confirmPassword = submittedConfirmation.toCharArray(),
                            displayName = displayName,
                        ),
                    )
                },
                enabled = !busy,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            CraftMindTertiaryButton(
                text = "I already have an account",
                onClick = { onEvent(AccountUiEvent.SignInFormRequested) },
                enabled = !busy,
            )
            CraftMindTertiaryButton(
                text = "Cancel",
                onClick = { onEvent(AccountUiEvent.FormDismissed) },
                enabled = !busy,
            )
        }
    }
}

/** The first issue reported for a field, if any, so a field shows one message rather than a stack of them. */
private fun AccountUiState.issueFor(field: AccountFormField): String? =
    formIssues.firstOrNull { it.field == field }?.message

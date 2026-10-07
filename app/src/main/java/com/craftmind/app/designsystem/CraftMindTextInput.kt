package com.craftmind.app.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * The design system's text input (Phase 17).
 *
 * Phase 15 shipped no input control because no screen took typed text. Adding one is a design system change, so it is
 * made where the system lives and in the system's terms: the height is [CraftMindSizing.CONTROL_HEIGHT_MD], the corner
 * radius is [CraftMindRadius.MD], the label and help text are [CraftMindType] roles, and every colour comes from the
 * theme. No new token, no new scale, and nothing raw.
 *
 * A field that has a problem states it in [issue], next to the field it belongs to, in the same place and grammatical
 * person as the rest of the app — never as a toast, a dialog, or an exception message.
 */
@Composable
fun CraftMindTextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    issue: String? = null,
    isSecret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            isError = issue != null,
            singleLine = true,
            shape = RoundedCornerShape(CraftMindRadius.MD),
            textStyle = CraftMindType.bodyLarge,
            label = { Text(text = label, style = CraftMindType.labelLarge) },
            visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
        )
        val help = issue ?: supportingText
        if (help != null) {
            Text(
                text = help,
                style = CraftMindType.bodySmall,
                color = if (issue != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = CraftMindLayout.XS, start = CraftMindLayout.SM),
            )
        }
    }
}

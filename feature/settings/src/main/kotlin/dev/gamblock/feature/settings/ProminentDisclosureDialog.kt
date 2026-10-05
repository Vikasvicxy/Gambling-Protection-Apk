package dev.gamblock.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gamblock.core.designsystem.component.ShieldText

/**
 * The prominent disclosure Google Play requires before the Accessibility prompt.
 *
 * ## Why this is a separate dialog rather than a line in Settings
 *
 * Play's Accessibility API policy requires the disclosure to be shown *before* the
 * app sends the user to the system permission screen, in the app itself, and to state
 * what the service does, what data is handled, and how to decline. A paragraph inside
 * a collapsed settings card cannot satisfy any of that: it is skippable, it is not in
 * the path of the request, and the user reaches the system prompt having read nothing.
 * So the guard's only route to Android's Accessibility settings runs through here.
 *
 * ## What the wording is required to say
 *
 * [DISCLOSURE_BODY] is the exact text the declaration form and the appeal rely on
 * (see `docs/store/accessibility_declaration.md`). It is a constant rather than
 * inline copy so that the sentence Play is asked to approve and the sentence the user
 * reads cannot drift apart, and so a test can assert on the real string.
 *
 * Note it also states the honest limits: the guard challenges with the Guardian PIN,
 * cannot read screen content, and collects nothing. Overstating what the service does
 * would be both untrue and, in a Play review, self-defeating.
 */
@Composable
fun ProminentDisclosureDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        // Declining is always available and always safe, so this dialog is a choice
        // rather than a trap. Blocking the back gesture would only make it feel like
        // one.
        onDismissRequest = onDecline,
        title = {
            Text(
                text = "Before Android asks for Accessibility access",
                modifier = Modifier.testTag(TAG_TITLE),
            )
        },
        text = {
            Column {
                ShieldText(
                    text = DISCLOSURE_BODY,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TAG_BODY),
                )
                Spacer(Modifier.height(12.dp))
                ShieldText(
                    text = "Declining changes nothing. You can turn this on later from " +
                        "Settings, and the guard is not needed for blocking, filtering or " +
                        "recovery to work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                modifier = Modifier.testTag(TAG_ACCEPT),
            ) {
                Text("I Understand, Continue to Android Settings")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDecline,
                modifier = Modifier.testTag(TAG_DECLINE),
            ) {
                Text("Not now")
            }
        },
    )
}

/**
 * The disclosure text shown before the Accessibility prompt, and quoted verbatim in
 * the Play Console declaration.
 *
 * Kept as one string so the app and the declaration present one claim. The
 * AccessibilityService is declared with `canRetrieveWindowContent="false"` and reads
 * only the window's package and class name, which is what makes the "no screen
 * content is read" sentence true.
 */
const val DISCLOSURE_BODY =
    "Shield uses the Accessibility Service API to detect when you attempt to uninstall " +
        "the app or open App Settings during a moment of weakness. This allows Shield to " +
        "lock the screen and ask for your Guardian PIN. No other screen content is read, " +
        "and no data is collected or sent off your device."

const val TAG_TITLE = "prominent_disclosure_title"
const val TAG_BODY = "prominent_disclosure_body"
const val TAG_ACCEPT = "prominent_disclosure_accept"
const val TAG_DECLINE = "prominent_disclosure_decline"
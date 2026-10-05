package dev.gamblock.protection.tamper

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.gamblock.core.model.GuardianPinHasher

/**
 * The PIN prompt shown over the system uninstall screen.
 *
 * Built as a plain view tree owned by the accessibility service rather than an
 * Activity, because that is what allows it to be added as a
 * `TYPE_ACCESSIBILITY_OVERLAY` window. Going through an Activity would require
 * SYSTEM_ALERT_WINDOW, a permission the user would rightly be wary of granting and
 * which would make this feature look far more invasive than it is.
 *
 * No Compose either: this has to be constructible from nothing but the service's
 * context, with no app UI state available.
 *
 * The prompt is deliberately dismissible. A PIN prompt that cannot be dismissed is
 * a device brick waiting to happen if the user forgets their PIN, and a guardian
 * feature must never be able to strand someone on their own phone.
 */
internal object UninstallGuardChallenge {

    /** The service side: verifies the PIN and manages the overlay window. */
    interface Host {
        fun onPinEntered(pin: String, view: View)
        fun onDismissed()
    }

    /**
     * Handle on the built view, so the service can return it to an editable state
     * after a wrong PIN without rebuilding the whole overlay.
     */
    class Handle(val root: ViewGroup) {
        private val pinField: EditText = root.findViewWithTag(TAG_PIN)
        private val status: TextView = root.findViewWithTag(TAG_STATUS)
        private val confirm: Button = root.findViewWithTag(TAG_CONFIRM)

        fun onVerificationPending() {
            pinField.isEnabled = false
            confirm.isEnabled = false
            status.setText(R.string.uninstall_guard_challenge_checking)
        }

        fun onVerificationFailed() {
            pinField.isEnabled = true
            pinField.text = null
            confirm.isEnabled = true
            status.setText(R.string.uninstall_guard_challenge_wrong)
        }

        fun showBadFormat() {
            status.setText(R.string.uninstall_guard_challenge_bad_format)
        }
    }

    fun build(context: Context, host: Host): Handle {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
            setBackgroundColor(SCRIM)
            // Consume touches so taps cannot reach the uninstall button underneath.
            isClickable = true
            isFocusable = true
        }
        val handle = Handle(root)

        root.addView(
            TextView(context).apply {
                text = context.getString(R.string.uninstall_guard_challenge_title)
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
            },
        )
        root.addView(
            TextView(context).apply {
                text = context.getString(R.string.uninstall_guard_challenge_body)
                setTextColor(SECONDARY)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 12), 0, dp(context, 20))
            },
        )

        val pinField = EditText(context).apply {
            tag = TAG_PIN
            hint = context.getString(R.string.uninstall_guard_challenge_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(GuardianPinHasher.PIN_LENGTH))
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#FF8A93A0"))
            background = rounded(Color.parseColor("#FF1E2128"), dp(context, 12))
            contentDescription = context.getString(R.string.uninstall_guard_challenge_hint)
        }
        root.addView(pinField)

        root.addView(
            TextView(context).apply {
                tag = TAG_STATUS
                setTextColor(ERROR)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 12), 0, 0)
            },
        )

        root.addView(
            Button(context).apply {
                tag = TAG_CONFIRM
                text = context.getString(R.string.uninstall_guard_challenge_confirm)
                isAllCaps = false
                setOnClickListener {
                    val pin = pinField.text.toString()
                    if (!GuardianPinHasher.isValidFormat(pin)) {
                        handle.showBadFormat()
                    } else {
                        host.onPinEntered(pin, root)
                    }
                }
            },
        )
        root.addView(
            Button(context).apply {
                text = context.getString(R.string.uninstall_guard_challenge_cancel)
                isAllCaps = false
                setOnClickListener { host.onDismissed() }
            },
        )

        return handle
    }

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(fill)
    }

    private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    private const val TAG_PIN = "guard_pin"
    private const val TAG_STATUS = "guard_status"
    private const val TAG_CONFIRM = "guard_confirm"

    private val SCRIM = Color.parseColor("#F2121418")
    private val SECONDARY = Color.parseColor("#FFB9C0CC")
    private val ERROR = Color.parseColor("#FFE06C75")
}
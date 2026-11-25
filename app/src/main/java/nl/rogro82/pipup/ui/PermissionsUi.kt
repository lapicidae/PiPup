package nl.rogro82.pipup.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.PowerController
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.showToast

/**
 * UI labels, explanatory copy, and guided fix dialogs for [Permissions].
 */
@OptIn(UnstableApi::class)
object PermissionsUi {

    private const val OPTION_TITLE_TEXT_SIZE_SP = 18f
    private const val OPTION_DESC_TEXT_SIZE_SP = 14f
    private const val DIALOG_MESSAGE_TEXT_SIZE_SP = 16f
    private const val PAD_LG_DP = 16
    private const val PAD_MD_DP = 12
    private const val MARGIN_SM_DP = 8
    private const val PAD_XS_DP = 2

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Returns a human-readable label for the given permission key.
     */
    fun getLabel(context: Context, key: String): String {
        return when (key) {
            Permissions.KEY_OVERLAY -> context.getString(R.string.permission_overlay)
            Permissions.KEY_INSTALL -> context.getString(R.string.permission_install)
            Permissions.KEY_ENERGY -> context.getString(R.string.energy_optimization_title)
            Permissions.KEY_ADMIN -> context.getString(R.string.permission_admin)
            Permissions.KEY_ACCESSIBILITY -> context.getString(R.string.permission_accessibility)
            Permissions.KEY_AUTO_START -> context.getString(R.string.permission_autostart)
            Permissions.KEY_POWER -> {
                val sleepMethod = PowerController.getSleepMethod(context)
                if (sleepMethod != null) {
                    val methodRes = if (sleepMethod == PowerController.METHOD_DEVICE_ADMIN) {
                        R.string.permission_admin
                    } else {
                        R.string.permission_accessibility
                    }
                    context.getString(R.string.settings_module_power) + " (${context.getString(methodRes)})"
                } else {
                    context.getString(R.string.settings_module_power)
                }
            }
            else -> key
        }
    }

    /**
     * Returns a human-readable reason/why-text for the given permission key.
     */
    fun getWhyText(context: Context, key: String): String? {
        val resId = when (key) {
            Permissions.KEY_OVERLAY -> R.string.permission_overlay_why
            Permissions.KEY_INSTALL -> R.string.permission_install_why
            Permissions.KEY_AUTO_START -> R.string.permission_autostart_why
            Permissions.KEY_ENERGY -> R.string.permission_energy_why
            Permissions.KEY_ACCESSIBILITY -> R.string.permission_accessibility_why
            Permissions.KEY_ADMIN, Permissions.KEY_POWER -> R.string.permission_power_why
            else -> 0
        }
        return if (resId != 0) context.getString(resId) else null
    }

    /**
     * Shows a guided instruction dialog for any permission.
     */
    fun showFixDialog(context: Context, key: String) {
        if (key == Permissions.KEY_POWER) {
            showPowerChoiceDialog(context)
            return
        }

        val label = getLabel(context, key)
        val appName = context.getString(R.string.app_name)
        val why = getWhyText(context, key)
        val fixIntentAvailable = Permissions.isFixable(context, key)

        val message = SpannableStringBuilder()
        if (why != null) {
            message.append(why)
        }
        if (key == Permissions.KEY_ENERGY) {
            message.append("\n\n").append(context.getString(R.string.energy_optimization_manual, appName))
        }
        if (key == Permissions.KEY_ADMIN || key == Permissions.KEY_ACCESSIBILITY) {
            message.append("\n\n").append(context.getString(R.string.permission_restricted_hint))
        }
        appendAdbHint(context, message, key)
        if (fixIntentAvailable) {
            message.append("\n\n").append(context.getString(R.string.permission_ask_open_settings))
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(label)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)

        if (fixIntentAvailable) {
            builder.setPositiveButton(R.string.settings_open) { _, _ ->
                if (!Permissions.launchFix(context, key)) {
                    context.showToast(context.getString(R.string.permission_blocked_device))
                }
            }
        }

        val dialog = builder.create()
        dialog.show()

        if (fixIntentAvailable) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        }
    }

    private fun appendAdbHint(context: Context, message: SpannableStringBuilder, key: String) {
        val adb = Permissions.adbCommand(key, context)
        if (adb.isNotEmpty()) {
            message.append("\n\n").append(context.getString(R.string.permission_adb_hint))
            val start = message.length
            message.append("\n").append(adb)
            val end = message.length
            message.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showPowerChoiceDialog(context: Context) {
        val adminFixable = Permissions.isFixable(context, Permissions.KEY_ADMIN)
        val accFixable = Permissions.isFixable(context, Permissions.KEY_ACCESSIBILITY)

        if (!adminFixable && !accFixable) {
            context.showToast(context.getString(R.string.permission_blocked_device))
            return
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = context.dpToPx(PAD_LG_DP)
            setPadding(p, p, p, p)
        }

        val msg = TextView(context).apply {
            text = context.getString(R.string.permission_power_why)
            textSize = DIALOG_MESSAGE_TEXT_SIZE_SP
            setPadding(0, 0, 0, context.dpToPx(PAD_LG_DP))
            setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
        }
        container.addView(msg)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_module_power)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        if (adminFixable) {
            container.addView(
                createOptionRow(
                    context,
                    context.getString(R.string.permission_admin),
                    context.getString(R.string.permission_admin_desc),
                ) {
                    dialog.dismiss()
                    showFixDialog(context, Permissions.KEY_ADMIN)
                },
            )
        }

        if (accFixable) {
            container.addView(
                createOptionRow(
                    context,
                    context.getString(R.string.permission_accessibility),
                    context.getString(R.string.permission_accessibility_desc),
                ) {
                    dialog.dismiss()
                    showFixDialog(context, Permissions.KEY_ACCESSIBILITY)
                },
            )
        }

        dialog.show()
        handler.post { container.getChildAt(1)?.requestFocus() }
    }

    private fun createOptionRow(context: Context, title: String, desc: String, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dpToPx(PAD_LG_DP),
                context.dpToPx(PAD_MD_DP),
                context.dpToPx(PAD_LG_DP),
                context.dpToPx(PAD_MD_DP),
            )
            background = ContextCompat.getDrawable(context, R.drawable.focus_background)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.bottomMargin = context.dpToPx(MARGIN_SM_DP)
            layoutParams = params
        }

        row.addView(
            TextView(context).apply {
                text = title
                textSize = OPTION_TITLE_TEXT_SIZE_SP
                setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
            },
        )

        row.addView(
            TextView(context).apply {
                text = desc
                textSize = OPTION_DESC_TEXT_SIZE_SP
                setTextColor(ContextCompat.getColor(context, R.color.colorOnSurfaceVariant))
                setPadding(0, context.dpToPx(PAD_XS_DP), 0, 0)
            },
        )

        return row
    }
}

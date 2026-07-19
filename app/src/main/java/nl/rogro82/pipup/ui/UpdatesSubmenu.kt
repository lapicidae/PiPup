package nl.rogro82.pipup.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.*
import nl.rogro82.pipup.R

@UnstableApi
class UpdatesSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    private var availableRelease: GitHubRelease? = null

    override fun onBind(root: View) {
        val suffix = context.getString(R.string.settings_default_suffix)

        // Check Update Button
        root.findViewById<Button>(R.id.btn_check_update)?.apply {
            updateButtonText(this)
            setOnClickListener { performUpdateCheck() }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        // Update Channel
        val defaultChannelIndex = if (settings.isBetaBuild) 1 else 0
        val channelItems = listOf(context.getString(R.string.settings_update_stable), context.getString(R.string.settings_update_beta)).mapIndexed { i, s -> if (i == defaultChannelIndex) "$s $suffix" else s }
        setupSpinner(root, R.id.spinner_update_channel, ArrayAdapter(context, R.layout.spinner_item, channelItems).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }, settings.updateChannel) {
            settings.updateChannel = it
        }

        // Update Interval
        val intervalItems = listOf(
            context.getString(R.string.settings_update_off),
            context.getString(R.string.settings_update_on_open),
            context.getString(R.string.settings_update_daily),
            context.getString(R.string.settings_update_weekly),
            context.getString(R.string.settings_update_monthly)
        ).mapIndexed { i, s -> if (i == 4) "$s $suffix" else s }
        setupSpinner(root, R.id.spinner_update_interval, ArrayAdapter(context, R.layout.spinner_item, intervalItems).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }, settings.updateInterval) {
            settings.updateInterval = it
            UpdateWorker.schedule(context, it)
        }

        // Notification Style
        val styleItems = listOf(
            context.getString(R.string.settings_update_style_silent),
            context.getString(R.string.settings_update_style_pipup),
            context.getString(R.string.settings_update_style_toast)
        ).mapIndexed { i, s -> if (i == 1) "$s $suffix" else s }
        setupSpinner(root, R.id.spinner_update_notification_style, ArrayAdapter(context, R.layout.spinner_item, styleItems).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }, settings.updateNotificationStyle) {
            settings.updateNotificationStyle = it
        }

        // Repeat Notification
        root.findViewById<View>(R.id.container_update_repeat)?.apply {
            val sw = findViewById<SwitchCompat>(R.id.switch_update_repeat)
            sw.isChecked = settings.updateRepeat
            setOnClickListener { sw.toggle() }
            sw.setOnCheckedChangeListener { _, isChecked ->
                settings.updateRepeat = isChecked
                onSettingsChanged(false)
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }
    }

    private fun updateButtonText(btn: Button) {
        btn.text = if (settings.updateAvailableTag.isNotEmpty() && UpdateManager(context).isNewer(settings.updateAvailableTag)) {
            context.getString(R.string.settings_update_to, settings.updateAvailableTag)
        } else {
            context.getString(R.string.settings_check_update)
        }
    }

    private fun performUpdateCheck() {
        availableRelease?.let {
            showUpdateDialog(it)
            return
        }

        val btn = (context as? SettingsActivity)?.findViewById<Button>(R.id.btn_check_update)

        val isRtl = context.isRtl()
        val dir = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        val messageView = TextView(context).apply {
            setText(R.string.settings_checking_update)
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START
            }
            val p = context.dpToPx(24)
            setPadding(p, p, p, 0)
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
            gravity = Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            layoutDirection = dir
        }

        val container = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            addView(messageView)
            layoutDirection = dir
        }

        val progress = AlertDialog.Builder(context)
            .setView(container)
            .setCancelable(true)
            .show()

        UpdateManager(context).checkForUpdates(settings.updateChannel == 1, object : UpdateManager.UpdateCallback {
            override fun onUpdateAvailable(release: GitHubRelease) {
                (context as? SettingsActivity)?.runOnUiThread {
                    availableRelease = release
                    settings.updateAvailableTag = release.tagName
                    settings.lastUpdateCheck = System.currentTimeMillis()
                    btn?.let { updateButtonText(it) }
                    progress.dismiss()
                    showUpdateDialog(release)
                }
            }
            override fun onNoUpdate() {
                (context as? SettingsActivity)?.runOnUiThread {
                    availableRelease = null
                    settings.updateAvailableTag = ""
                    settings.lastUpdateCheck = System.currentTimeMillis()
                    btn?.let { updateButtonText(it) }
                    progress.dismiss()
                    Toast.makeText(context, R.string.settings_update_none, Toast.LENGTH_SHORT).show()
                }
            }
            override fun onError(message: String) {
                (context as? SettingsActivity)?.runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(context, context.getString(R.string.settings_update_error, message), Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun showUpdateDialog(targetRelease: GitHubRelease) {
        val isRtl = context.isRtl()
        val dir = if (isRtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = context.dpToPx(20)
            setPadding(p, p, p, 0)
            layoutDirection = dir
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val introView = TextView(context).apply {
            text = context.getString(R.string.settings_update_dialog_msg, targetRelease.tagName)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START
            }
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
            gravity = Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            layoutDirection = dir
        }
        container.addView(introView)

        val body = targetRelease.body ?: ""
        if (body.isNotEmpty()) {
            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    context.dpToPx(250)
                ).apply { topMargin = context.dpToPx(16) }
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant))
                setPadding(context.dpToPx(12), context.dpToPx(12), context.dpToPx(12), context.dpToPx(12))
            }
            val bodyView = TextView(context).apply {
                text = body
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.START
                }
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.colorOnSurfaceVariant))
                gravity = Gravity.START
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                layoutDirection = dir
            }
            scrollView.addView(bodyView)
            container.addView(scrollView)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_update_available)
            .setView(container)
            .setPositiveButton(R.string.settings_update_install) { _, _ ->
                val mgr = UpdateManager(context)
                Toast.makeText(context, R.string.settings_update_verifying, Toast.LENGTH_SHORT).show()

                mgr.checkForUpdates(settings.updateChannel == 1, object : UpdateManager.UpdateCallback {
                    override fun onUpdateAvailable(release: GitHubRelease) {
                        (context as? SettingsActivity)?.runOnUiThread {
                            if (release.tagName == targetRelease.tagName) {
                                mgr.downloadAndInstall(release)
                                Toast.makeText(context, R.string.settings_update_downloading, Toast.LENGTH_LONG).show()
                            } else {
                                // A different (likely newer) update was found during the re-check
                                showUpdateDialog(release)
                            }
                        }
                    }

                    override fun onNoUpdate() {
                        (context as SettingsActivity).run {
                            runOnUiThread {
                                Toast.makeText(context, R.string.settings_update_no_longer_available, Toast.LENGTH_LONG).show()
                                // Refresh UI state
                                availableRelease = null
                                settings.updateAvailableTag = ""
                                findViewById<Button>(R.id.btn_check_update)?.let { updateButtonText(it) }
                            }
                        }
                    }

                    override fun onError(message: String) {
                        (context as SettingsActivity).runOnUiThread {
                            Toast.makeText(context, "Verification failed: $message", Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
            .setNegativeButton(R.string.settings_update_later, null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).post {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        }
    }
}

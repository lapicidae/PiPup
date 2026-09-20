package nl.rogro82.pipup.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val appName = context.getString(R.string.app_name)

        // Check Update Button
        root.findViewById<Button>(R.id.btn_check_update)?.apply {
            updateButtonText(this)
            setOnClickListener { performUpdateCheck() }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }

        // Update Channel
        val defaultChannelIndex = if (settings.isBetaBuild) 1 else 0
        val channelItems = listOf(context.getString(R.string.settings_update_stable), context.getString(R.string.settings_update_beta)).mapIndexed { i, s -> if (i == defaultChannelIndex) "$s $suffix" else s }
        setupSpinner(root, R.id.spinner_update_channel, ArrayAdapter(context, android.R.layout.simple_spinner_item, channelItems).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }, settings.updateChannel) {
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
        setupSpinner(root, R.id.spinner_update_interval, ArrayAdapter(context, android.R.layout.simple_spinner_item, intervalItems).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }, settings.updateInterval) {
            settings.updateInterval = it
            UpdateWorker.schedule(context, it)
        }

        // Notification Style
        val styleItems = listOf(
            context.getString(R.string.settings_update_style_silent),
            context.getString(R.string.settings_update_style_pipup, appName),
            context.getString(R.string.settings_update_style_toast)
        ).mapIndexed { i, s -> if (i == 1) "$s $suffix" else s }
        setupSpinner(root, R.id.spinner_update_notification_style, ArrayAdapter(context, android.R.layout.simple_spinner_item, styleItems).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }, settings.updateNotificationStyle) {
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

        renderModuleSettings(root as android.view.ViewGroup, nl.rogro82.pipup.core.SettingCategory.UPDATES)
    }

    private fun updateButtonText(btn: Button) {
        btn.text = if (settings.updateAvailableTag.isNotEmpty() && UpdateManager.isNewer(context, settings.updateAvailableTag)) {
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

        val progress = AlertDialog.Builder(context)
            .setMessage(R.string.settings_checking_update)
            .setCancelable(true)
            .show()
        progress.findViewById<TextView>(android.R.id.message)?.gravity = android.view.Gravity.CENTER

        val scope = (context.applicationContext as? PiPupApp)?.applicationScope ?: kotlinx.coroutines.MainScope()
        scope.launch(Dispatchers.Main) {
            val updateManager = UpdateManager(context)
            updateManager.checkForUpdates(settings.updateChannel == 1).onSuccess { release ->
                availableRelease = release
                if (release != null) {
                    settings.updateAvailableTag = release.tagName
                    settings.lastUpdateCheck = System.currentTimeMillis()
                    btn?.let { updateButtonText(it) }
                    progress.dismiss()
                    showUpdateDialog(release)
                } else {
                    settings.updateAvailableTag = ""
                    settings.lastUpdateCheck = System.currentTimeMillis()
                    btn?.let { updateButtonText(it) }
                    progress.dismiss()
                    context.showToast(context.getString(R.string.settings_update_none))
                }
            }.onFailure {
                progress.dismiss()
                context.showToast(context.getString(R.string.settings_update_error, it.message), true)
            }
        }
    }

    private fun showUpdateDialog(targetRelease: GitHubRelease) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = context.dpToPx(20)
            setPadding(p, p, p, 0)
        }

        val introView = TextView(context).apply {
            text = context.getString(R.string.settings_update_dialog_msg, targetRelease.tagName)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
        }
        container.addView(introView)

        val body = targetRelease.body ?: ""
        if (body.isNotEmpty()) {
            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    context.dpToPx(250)
                ).apply { topMargin = context.dpToPx(16) }
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundColor(ContextCompat.getColor(context, R.color.colorSurfaceVariant))
                setPadding(context.dpToPx(12), context.dpToPx(12), context.dpToPx(12), context.dpToPx(12))
            }
            val bodyView = TextView(context).apply {
                text = body
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.colorOnSurfaceVariant))
            }
            scrollView.addView(bodyView)
            container.addView(scrollView)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_update_available)
            .setView(container)
            .setPositiveButton(R.string.settings_update_install) { _, _ ->
                val scope = (context.applicationContext as? PiPupApp)?.applicationScope ?: kotlinx.coroutines.MainScope()
                scope.launch(Dispatchers.Main) {
                    val mgr = UpdateManager(context)
                    context.showToast(context.getString(R.string.settings_update_verifying))

                    mgr.checkForUpdates(settings.updateChannel == 1).onSuccess { release ->
                        if (release != null) {
                            if (release.tagName == targetRelease.tagName) {
                                mgr.downloadAndInstall(release)
                                context.showToast(context.getString(R.string.settings_update_downloading), true)
                            } else {
                                // A different (likely newer) update was found during the re-check
                                showUpdateDialog(release)
                            }
                        } else {
                            context.showToast(context.getString(R.string.settings_update_no_longer_available), true)
                            // Refresh UI state
                            availableRelease = null
                            settings.updateAvailableTag = ""
                            btn?.let { updateButtonText(it) }
                        }
                    }.onFailure {
                        context.showToast("Verification failed: ${it.message}", true)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).post {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        }
    }

    private val btn: Button?
        get() = (context as? SettingsActivity)?.findViewById(R.id.btn_check_update)
}

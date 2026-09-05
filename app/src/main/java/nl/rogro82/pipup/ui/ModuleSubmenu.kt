package nl.rogro82.pipup.ui

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import nl.rogro82.pipup.PiPupApp
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.R
import nl.rogro82.pipup.core.ActivationStrategy
import nl.rogro82.pipup.core.ModuleSettingDefinition
import nl.rogro82.pipup.core.PiPupModule
import nl.rogro82.pipup.core.SettingType

/**
 * Dynamically rendered settings screen for a specific module.
 */
@OptIn(UnstableApi::class)
class ModuleSubmenu(
    private val activity: SettingsActivity,
    private val settings: AppSettings,
    private val module: PiPupModule,
    private val onPreviewUpdate: (Boolean) -> Unit,
    private val previewContainer: ViewGroup
) : SubmenuController {

    private lateinit var rootContainer: LinearLayout

    override fun onBind(root: View) {
        rootContainer = root.findViewById(R.id.module_settings_container) ?: return
        render()
    }

    private fun render() {
        rootContainer.removeAllViews()
        val context = rootContainer.context

        // 1. Resource Mode Setting
        renderResourceMode(context)

        // 2. Module-specific settings
        val metadata = module.getSettingsMetadata()
        metadata.forEach { def ->
            renderSetting(context, def)
        }

        activity.setupSubmenuFocus()
    }

    private fun renderResourceMode(context: Context) {
        val view = LayoutInflater.from(context).inflate(R.layout.item_setting_select, rootContainer, false)
        view.findViewById<TextView>(R.id.setting_label)?.text = context.getString(R.string.settings_resource_mode)

        val currentStrategy = settings.getActivationStrategy(module.id)
        val valueText = view.findViewById<TextView>(R.id.setting_value)

        fun updateText(strategy: Int) {
            valueText?.text = when (strategy) {
                ActivationStrategy.ECO -> context.getString(R.string.resource_mode_eco)
                ActivationStrategy.PERFORMANCE -> context.getString(R.string.resource_mode_performance)
                else -> context.getString(R.string.resource_mode_eco) // Default to Eco if somehow OFF
            }
        }

        updateText(currentStrategy)

        view.setOnClickListener {
            // Cycle only between ECO (1) and PERFORMANCE (2)
            val current = settings.getActivationStrategy(module.id)
            val next = if (current == ActivationStrategy.ECO) ActivationStrategy.PERFORMANCE else ActivationStrategy.ECO

            settings.setActivationStrategy(module.id, next)
            updateText(next)
            // Notify ModuleManager about the change
            (activity.application as PiPupApp).moduleManager.updateModuleState(module.id, next)
            notifySettingsChanged()
        }

        rootContainer.addView(view)
    }

    private fun renderSetting(context: Context, def: ModuleSettingDefinition) {
        when (def.type) {
            SettingType.BOOLEAN -> renderBooleanSetting(context, def)
            // Add other types as needed
            else -> {}
        }
    }

    private fun renderBooleanSetting(context: Context, def: ModuleSettingDefinition) {
        val defaultValue = def.defaultValue as? Boolean ?: return

        val view = LayoutInflater.from(context).inflate(R.layout.item_setting_toggle, rootContainer, false)
        view.findViewById<TextView>(R.id.setting_label)?.text = context.getString(def.labelRes)
        val switch = view.findViewById<SwitchCompat>(R.id.setting_switch)

        val current = settings.getModuleSetting(module.id, def.key, defaultValue)
        switch?.isChecked = current

        view.setOnClickListener {
            val next = !settings.getModuleSetting(module.id, def.key, defaultValue)
            settings.setModuleSetting(module.id, def.key, next)
            switch?.isChecked = next
            onPreviewUpdate(false)
        }

        rootContainer.addView(view)
    }

    private fun notifySettingsChanged() {
        val intent = Intent(PiPupApp.ACTION_SETTINGS_CHANGED).apply {
            setPackage(activity.packageName)
        }
        activity.sendBroadcast(intent)
    }

    override fun onBackPress(): Boolean = false

    override fun updatePreviewPosition(v: View) {
        val popup = previewContainer.getChildAt(0) ?: return
        val params = popup.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return

        val location = IntArray(2)
        v.getLocationOnScreen(location)
        val screenHeight = v.resources.displayMetrics.heightPixels

        val targetGravity = if (location[1] > screenHeight * 0.4) {
            android.view.Gravity.TOP or android.view.Gravity.END
        } else {
            android.view.Gravity.BOTTOM or android.view.Gravity.END
        }

        if (params.gravity != targetGravity) {
            params.gravity = targetGravity
            popup.layoutParams = params
        }
    }
}

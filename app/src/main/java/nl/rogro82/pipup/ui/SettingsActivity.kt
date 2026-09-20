package nl.rogro82.pipup.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.MainActivity
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.Permissions
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.applyAppLocaleAndTheme
import nl.rogro82.pipup.colorToHex
import nl.rogro82.pipup.core.PiPupModule
import nl.rogro82.pipup.databinding.ActivitySettingsBinding
import nl.rogro82.pipup.registerProtectedReceiver
import nl.rogro82.pipup.showToast

/**
 * Main settings activity providing a multi-pane interface for TV configuration.
 *
 * Manages various submenus for styling, animations, and system updates
 * using a navigation rail and pivot-scrolling focus management.
 */
@UnstableApi
class SettingsActivity : AppCompatActivity() {

    lateinit var binding: ActivitySettingsBinding
    private val settings = PiPupApp.settings
    private val handler = Handler(Looper.getMainLooper())

    private var currentLayoutRes: Int = -1
    private var currentNavId: Int = -1
    private var isInitializing = false
    private var lastFocusedViewId: Int = View.NO_ID
    private var shouldFocusSubmenuOnStart = false

    private val adminLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            showToast(getString(R.string.admin_enabled))
        }
    }

    private val submenuControllers = mutableMapOf<Int, SubmenuController>()
    private val moduleControllers = mutableMapOf<String, SubmenuController>()
    private val inflatedSubmenus = mutableMapOf<Int, View>()

    /**
     * Registry for module-specific submenu controllers.
     * New modules can "dock" by providing their layoutRes in ModuleMenuDefinition.
     */
    private val moduleSubmenuFactories = mapOf<Int, (SettingsActivity, AppSettings, PiPupModule, (Boolean) -> Unit, ViewGroup) -> SubmenuController>(
        R.layout.submenu_vendor to ::VendorSubmenu,
        R.layout.submenu_module_dynamic to ::ModuleSubmenu
    )

    private val coreRailItems = listOf(
        NavItem(R.id.nav_item_general, R.string.settings_nav_general, R.drawable.ic_general_style, 10, R.layout.submenu_general),
        NavItem(R.id.nav_item_background, R.string.settings_nav_background, R.drawable.ic_bg, 20, R.layout.submenu_background),
        NavItem(R.id.nav_item_text_style, R.string.settings_nav_text, R.drawable.ic_text_style, 30, R.layout.submenu_text),
        NavItem(R.id.nav_item_border, R.string.settings_nav_border, R.drawable.ic_border_style, 40, R.layout.submenu_border),
        NavItem(R.id.nav_item_animation, R.string.settings_nav_animation, R.drawable.ic_animation, 50, R.layout.submenu_animation),
        NavItem(R.id.nav_item_updates, R.string.settings_nav_updates, R.drawable.ic_updates, 60, R.layout.submenu_updates),
        NavItem(R.id.nav_item_modules, R.string.settings_nav_modules, R.drawable.ic_modules, 70, R.layout.submenu_modules),
        NavItem(R.id.nav_item_permissions, R.string.settings_nav_permissions, R.drawable.ic_permissions, 80, R.layout.submenu_permissions),
        NavItem(R.id.nav_item_advanced, R.string.settings_nav_advanced, R.drawable.ic_advanced, 90, R.layout.submenu_advanced)
    )

    private var dynamicRailItems = mutableListOf<NavItem>()
    private val allRailIds = mutableListOf<Int>()

    data class NavItem(
        val id: Int,
        val labelRes: Int,
        val iconRes: Int,
        val priority: Int,
        val layoutRes: Int = -1,
        val moduleId: String? = null
    )

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PiPupApp.ACTION_SETTINGS_CHANGED) {
                if (intent.getStringExtra("origin") == "remote") {
                    Log.d("SettingsActivity", "Remote settings change detected, refreshing UI")
                    if (!isFinishing && !isDestroyed) {
                        recreate()
                    }
                } else {
                    // Local change (e.g. from ModulesSubmenu), refresh dynamic rail
                    refreshDynamicNavRail()
                }
            }
        }
    }

    val materialColors by lazy {
        listOf(
            ColorEntry(R.string.color_deep_slate,    colorToHex(R.color.preset_deep_slate)),
            ColorEntry(R.string.color_midnight_violet,   colorToHex(R.color.preset_midnight_violet)),
            ColorEntry(R.string.color_black_plum,     colorToHex(R.color.preset_black_plum)),
            ColorEntry(R.string.color_rosewood,      colorToHex(R.color.preset_rosewood)),
            ColorEntry(R.string.color_forest_green,    colorToHex(R.color.preset_forest_green)),
            ColorEntry(R.string.color_deep_umber,   colorToHex(R.color.preset_deep_umber)),
            ColorEntry(R.string.color_gunmetal,     colorToHex(R.color.preset_gunmetal)),
            ColorEntry(R.string.color_navy_blue,    colorToHex(R.color.preset_navy_blue)),
            ColorEntry(R.string.color_platinum,    colorToHex(R.color.preset_platinum)),
            ColorEntry(R.string.color_sweet_lavender, colorToHex(R.color.preset_sweet_lavender)),
            ColorEntry(R.string.color_cotton_candy, colorToHex(R.color.preset_cotton_candy)),
            ColorEntry(R.string.color_peach_blossom, colorToHex(R.color.preset_peach_blossom)),
            ColorEntry(R.string.color_celadon_pastel,     colorToHex(R.color.preset_celadon_pastel)),
            ColorEntry(R.string.color_peach_cream,     colorToHex(R.color.preset_peach_cream)),
            ColorEntry(R.string.color_silver,       colorToHex(R.color.preset_silver)),
            ColorEntry(R.string.color_sky_blue,     colorToHex(R.color.preset_sky_blue))
        )
    }

    private var cachedPlaceholder: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme and locale before super.onCreate to ensure the UI reflects settings
        applyAppLocaleAndTheme(settings.language, settings.appTheme)
        setTheme(R.style.SettingsTheme)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        refreshDynamicNavItemsList()

        val allItems = (coreRailItems + dynamicRailItems).sortedBy { it.priority }
        allRailIds.clear()
        allRailIds.addAll(allItems.map { it.id })

        lastFocusedViewId = savedInstanceState?.getInt("lastFocusedViewId", View.NO_ID) ?: View.NO_ID
        val restoredNavId = savedInstanceState?.getInt("currentNavId", -1).takeIf { it != null && it != -1 }
            ?: intent.getIntExtra(EXTRA_NAV_ID, -1).takeIf { it != -1 }
            ?: allRailIds.firstOrNull() ?: -1

        if (savedInstanceState == null && intent.hasExtra(EXTRA_NAV_ID)) {
            shouldFocusSubmenuOnStart = true
        }

        val restoredItem = allItems.find { it.id == restoredNavId }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentLayoutRes != -1 && getControllerForNav(currentNavId).onBackPress()) return
                if (binding.submenuContainer.findFocus() != null) {
                    focusRail()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setupNavRail(allItems)
        loadSubmenuForItem(restoredItem)

        registerProtectedReceiver(settingsReceiver, IntentFilter(PiPupApp.ACTION_SETTINGS_CHANGED))
    }

    private fun refreshDynamicNavItemsList() {
        val moduleManager = (application as PiPupApp).moduleManager
        val newDynamicItems = mutableListOf<NavItem>()

        // 1. Central Performance Tab (if needed)
        if (PerformanceSubmenu.hasContent(this, settings)) {
            newDynamicItems.add(
                NavItem(
                    id = R.id.nav_item_performance,
                    labelRes = R.string.settings_nav_performance,
                    iconRes = R.drawable.ic_performance,
                    priority = 78,
                    layoutRes = R.layout.submenu_performance
                )
            )
        }

        // 2. Module specific tabs
        val moduleItems = moduleManager.getEnabledModules()
            .mapNotNull { it.getSettingsMenu()?.let { menu -> it to menu } }
            .map { (module, menu) ->
                val existingId = dynamicRailItems.find { it.moduleId == module.id }?.id ?: View.generateViewId()
                NavItem(existingId, menu.labelRes, menu.iconRes, menu.priority, layoutRes = menu.layoutRes, moduleId = module.id)
            }

        newDynamicItems.addAll(moduleItems)

        dynamicRailItems.clear()
        dynamicRailItems.addAll(newDynamicItems)
    }

    private fun refreshDynamicNavRail() {
        refreshDynamicNavItemsList()
        val allItems = (coreRailItems + dynamicRailItems).sortedBy { it.priority }
        allRailIds.clear()
        allRailIds.addAll(allItems.map { it.id })
        setupNavRail(allItems)
        updateNavAppearance()
    }

    private fun getControllerForNav(navId: Int): SubmenuController {
        val item = (coreRailItems + dynamicRailItems).find { it.id == navId } ?: return getController(-1)
        return if (item.moduleId != null) {
            val module = (application as PiPupApp).moduleManager.getModule(item.moduleId)!!
            moduleControllers.getOrPut(item.moduleId) {
                // Modular creation: Use factory from registry based on the requested layoutRes
                val factory = moduleSubmenuFactories[item.layoutRes] ?: moduleSubmenuFactories[R.layout.submenu_module_dynamic]!!
                factory(this, settings, module, { updatePreview(it) }, binding.previewArea)
            }
        } else {
            getController(item.layoutRes)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        handler.postDelayed({
            isInitializing = false

            if (shouldFocusSubmenuOnStart) {
                focusFirstInSubmenu()
                shouldFocusSubmenuOnStart = false
                return@postDelayed
            }

            if (lastFocusedViewId != View.NO_ID) {
                val target = findViewById<View>(lastFocusedViewId)
                if (target != null && target.isFocusable && target.isVisible) {
                    target.requestFocus()
                    return@postDelayed
                }
            }
            ensureFocus(currentNavId)
        }, 300)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (binding.navRail.findFocus() == null && binding.submenuContainer.findFocus() == null) {
                ensureFocus(currentNavId)
            }
        }
    }

    private fun ensureFocus(targetId: Int) {
        if (targetId == -1) return
        val target = findViewById<View>(targetId) ?: return

        // TV Focus Safeguard: Ensure the target is scrolled into view before requesting focus.
        // On Android TV, focus requests on off-screen items are often silently ignored.
        val parentScroll = (target.parent?.parent as? ScrollView) ?: (target.parent as? ScrollView)
        parentScroll?.let { scroll ->
            val rect = Rect()
            target.getDrawingRect(rect)
            scroll.offsetDescendantRectToMyCoords(target, rect)
            val centerY = rect.top - (scroll.height / 2) + (target.height / 2)
            scroll.scrollTo(0, centerY.coerceAtLeast(0))
        }

        if (!target.isFocused) {
            target.requestFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("currentNavId", currentNavId)
        outState.putInt("currentLayoutRes", currentLayoutRes)

        val focused = currentFocus
        if (focused != null && focused.id != View.NO_ID && focused.id !in allRailIds) {
            outState.putInt("lastFocusedViewId", focused.id)
        }
    }

    private fun setupNavRail(items: List<NavItem>) {
        binding.navItemsContainer.removeAllViews()
        items.forEach { item ->
            val navItemView = LayoutInflater.from(this).inflate(R.layout.nav_item, binding.navItemsContainer, false)
            navItemView.id = item.id
            configureNavItem(item, navItemView)
            binding.navItemsContainer.addView(navItemView)
        }

        allRailIds.forEachIndexed { i, id ->
            findViewById<View>(id)?.apply {
                nextFocusUpId = if (i > 0) allRailIds[i - 1] else id
                nextFocusDownId = if (i < (allRailIds.size - 1)) allRailIds[i + 1] else id
                nextFocusLeftId = id
                nextFocusRightId = R.id.settings_scroll
            }
        }
    }

    private fun configureNavItem(item: NavItem, view: View) {
        view.findViewById<TextView>(R.id.nav_text)?.setText(item.labelRes)
        view.findViewById<ImageView>(R.id.nav_icon)?.setImageResource(item.iconRes)

        view.setOnClickListener { focusFirstInSubmenu() }
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                (v.parent.parent as? ScrollView)?.let { scroll ->
                    val rect = Rect()
                    v.getDrawingRect(rect)
                    scroll.offsetDescendantRectToMyCoords(v, rect)
                    val pivotY = scroll.height * 0.3f
                    scroll.smoothScrollTo(0, (rect.top - pivotY).toInt().coerceAtLeast(0))
                }

                if (currentNavId != item.id) {
                    loadSubmenuForItem(item)
                }
            }
            updateNavAppearance()
        }

        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                focusFirstInSubmenu()
                true
            } else false
        }
    }

    private fun updateNavAppearance() {
        allRailIds.forEach { id ->
            val v = findViewById<View>(id) ?: return@forEach
            val label = v.findViewById<TextView>(R.id.nav_text)
            val icon = v.findViewById<ImageView>(R.id.nav_icon)
            val isSelected = currentNavId == id
            val hasFocus = v.isFocused

            val color = when {
                hasFocus -> ContextCompat.getColor(this, R.color.colorOnPrimary)
                isSelected -> ContextCompat.getColor(this, R.color.colorOnPrimaryContainer)
                else -> ContextCompat.getColor(this, R.color.colorOnSurfaceVariant)
            }
            label?.setTextColor(color)
            icon?.imageTintList = ColorStateList.valueOf(color)
            v.isSelected = isSelected
        }
    }

    private fun loadSubmenuForItem(item: NavItem?) {
        if (item == null) return
        currentNavId = item.id
        currentLayoutRes = item.layoutRes

        val stubId = getStubIdForNavItem(item)

        // 1. Hide all previously inflated views
        inflatedSubmenus.values.forEach { it.visibility = View.GONE }

        // 2. Inflate or just show the target view
        val root = inflatedSubmenus.getOrPut(stubId) {
            val stub = findViewById<ViewStub>(stubId)
            if (stub != null) {
                stub.inflate()
            } else {
                // Fallback: If stub is missing (already inflated but map lost it?),
                // try to find the view by its inflated ID if possible.
                // For simplicity, we assume the map is consistent.
                binding.submenuContainer.findViewById(R.id.permissions_root) ?: View(this)
            }
        }
        root.visibility = View.VISIBLE

        binding.settingsScroll.post { binding.settingsScroll.scrollTo(0, 0) }

        getControllerForNav(item.id).onBind(root)
        setupSubmenuFocus(item.id)
        updateNavAppearance()
        updatePreview(animate = false)
    }

    private fun getStubIdForNavItem(item: NavItem): Int {
        return if (item.moduleId != null) {
            R.id.stub_module_dynamic
        } else when (item.id) {
            R.id.nav_item_general -> R.id.stub_general
            R.id.nav_item_background -> R.id.stub_background
            R.id.nav_item_text_style -> R.id.stub_text
            R.id.nav_item_border -> R.id.stub_border
            R.id.nav_item_animation -> R.id.stub_animation
            R.id.nav_item_updates -> R.id.stub_updates
            R.id.nav_item_modules -> R.id.stub_modules
            R.id.nav_item_permissions -> R.id.stub_permissions
            R.id.nav_item_advanced -> R.id.stub_advanced
            R.id.nav_item_performance -> R.id.stub_performance
            else -> R.id.stub_module_dynamic
        }
    }

    /**
     * Resets the UI state of the settings activity by restarting the application task.
     * Ensures MainActivity is at the root so the user can navigate back to it.
     */
    fun resetSettingsUI() {
        Log.i("SettingsActivity", "Resetting Settings UI and restarting app task")
        lastFocusedViewId = View.NO_ID
        currentNavId = R.id.nav_item_general

        // 1. Prepare intent for MainActivity (the root)
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        // 2. Prepare intent for SettingsActivity (the current screen)
        val settingsIntent = Intent(this, SettingsActivity::class.java)

        // 3. Start both to restore the stack: [Main] -> [Settings]
        startActivities(arrayOf(mainIntent, settingsIntent))
        finish()
    }

    /**
     * Helper to launch the Device Admin activation screen using the modern Result API.
     */
    fun requestAdminRights(intent: Intent) {
        adminLauncher.launch(intent)
    }

    /**
     * Retrieves or creates the controller for the specified submenu layout.
     *
     * @param layoutRes The layout resource ID of the submenu.
     * @return The corresponding [SubmenuController].
     */
    fun getController(layoutRes: Int): SubmenuController {
        return submenuControllers.getOrPut(layoutRes) {
            when (layoutRes) {
                R.layout.submenu_general -> GeneralSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_background -> BackgroundSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_text -> TextSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_border -> BorderSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_animation -> AnimationSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_advanced -> AdvancedSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_performance -> PerformanceSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_updates -> UpdatesSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_permissions -> PermissionsSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_modules -> ModulesSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                else -> object : SubmenuController {
                    override fun onBind(root: View) {}
                    override fun onBackPress(): Boolean = false
                    override fun updatePreviewPosition(v: View) {}
                }
            }
        }
    }

    /**
     * Returns the resource ID of the currently active submenu layout.
     */
    fun getCurrentSubmenuLayout(): Int = currentLayoutRes

    private fun findFocusableChildrenRecursive(view: View): List<View> {
        val result = mutableListOf<View>()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.isFocusable && child.isVisible) {
                    result.add(child)
                } else if (child is ViewGroup) {
                    result.addAll(findFocusableChildrenRecursive(child))
                }
            }
        }
        return result
    }

    /**
     * Configures the focus navigation between the navigation rail and the submenu content.
     * @param targetNavId The ID of the currently active navigation rail item. Defaults to current if -1.
     */
    fun setupSubmenuFocus(targetNavId: Int = -1) {
        val navId = if (targetNavId == -1) currentNavId else targetNavId
        if (navId == -1) return

        val item = (coreRailItems + dynamicRailItems).find { it.id == navId } ?: return
        val stubId = getStubIdForNavItem(item)
        val container = inflatedSubmenus[stubId] as? ViewGroup ?: return

        val focusableChildren = findFocusableChildrenRecursive(container)

        if (focusableChildren.isNotEmpty()) {
            findViewById<View>(navId)?.nextFocusRightId = focusableChildren[0].id
        }

        focusableChildren.forEachIndexed { i, child ->
            // Ensure child has a valid ID for focus mapping
            if (child.id == View.NO_ID) child.id = View.generateViewId()

            child.nextFocusLeftId = navId
            child.nextFocusRightId = child.id
            child.nextFocusUpId = if (i > 0) focusableChildren[i - 1].id else child.id
            child.nextFocusDownId = if (i < focusableChildren.size - 1) focusableChildren[i + 1].id else child.id

            // Trap focus on the right side to prevent it from disappearing.
            if (child !is SeekBar) {
                child.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        return@setOnKeyListener true
                    }
                    false
                }
            }

            val old = child.onFocusChangeListener
            child.setOnFocusChangeListener { v, f ->
                if (f) {
                    // Gold Standard: Smooth Focus Centering (Pivot Scrolling)
                    val scroll = binding.settingsScroll
                    val c = binding.submenuContainer

                    val rect = Rect()
                    v.getDrawingRect(rect)
                    scroll.offsetDescendantRectToMyCoords(v, rect)

                    val viewportHeight = scroll.height
                    if (viewportHeight > 0) {
                        // Pivot at 30% from the top
                        val pivotY = viewportHeight * 0.3f
                        val targetScrollY = (rect.top - pivotY).toInt()
                        val maxScroll = (c.height - viewportHeight).coerceAtLeast(0)
                        scroll.smoothScrollTo(0, targetScrollY.coerceIn(0, maxScroll))
                    }
                }
                old?.onFocusChange(v, f)
                if (f) getControllerForNav(currentNavId).updatePreviewPosition(v)
            }
        }
    }

    /**
     * Moves focus back to the navigation rail.
     */
    fun focusRail() {
        findViewById<View>(currentNavId)?.requestFocus()
    }

    /**
     * Focuses the first focusable child in the currently active submenu.
     */
    fun focusFirstInSubmenu() {
        val item = (coreRailItems + dynamicRailItems).find { it.id == currentNavId } ?: return
        val stubId = getStubIdForNavItem(item)
        val container = inflatedSubmenus[stubId] as? ViewGroup ?: return

        findFocusableChildrenRecursive(container).firstOrNull()?.requestFocus()
    }

    private fun updatePreview(animate: Boolean = false) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val placeholder = cachedPlaceholder ?: createBitmap(320, 180).applyCanvas {
                drawColor(ContextCompat.getColor(this@SettingsActivity, R.color.preview_placeholder_bg))
                val paint = Paint().apply {
                    color = ContextCompat.getColor(this@SettingsActivity, R.color.preview_placeholder_text)
                    textSize = 40f
                    textAlign = Paint.Align.CENTER
                }
                drawText(getString(R.string.settings_preview_media), 160f, 100f, paint)
            }.also { cachedPlaceholder = it }

            val props = PopupProps(
                title = getString(R.string.settings_preview_title),
                message = getString(R.string.settings_preview_message),
                backgroundColor = settings.getFullBackgroundColor(),
                borderRadius = settings.borderRadius,
                borderWidth = settings.borderWidth,
                borderColor = settings.borderColor,
                titleColor = settings.titleColor,
                titleSize = settings.titleSize,
                messageColor = settings.messageColor,
                messageSize = settings.messageSize,
                titleAlignment = settings.titleAlignment,
                messageAlignment = settings.messageAlignment,
                mediaPosition = settings.mediaPosition,
                animationType = settings.animationType,
                animationDuration = settings.animationDuration,
                animationExit = settings.animationExit,
                media = PopupProps.Media.Bitmap(placeholder, 180)
            )

            // Optimized update: Check if preview already exists and update it instead of re-creation
            val existingPreview = binding.previewArea.getChildAt(0) as? PopupView

            if (existingPreview != null && !animate) {
                existingPreview.updateFromProps(props)
                val focus = currentFocus
                if (focus != null && focus.id != View.NO_ID && focus.id !in allRailIds) {
                    getControllerForNav(currentNavId).updatePreviewPosition(focus)
                } else {
                    // Ensure default position when focused on the navigation rail
                    (existingPreview.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                        val defaultGravity = Gravity.BOTTOM or Gravity.END
                        if (params.gravity != defaultGravity) {
                            params.gravity = defaultGravity
                            val m = (resources.displayMetrics.density * 10).toInt()
                            params.setMargins(0, m, m, m)
                            existingPreview.layoutParams = params
                        }
                    }
                }
            } else {
                binding.previewArea.removeAllViews()
                val preview = PopupView.build(this, props)

                var initialGravity = Gravity.BOTTOM or Gravity.END
                currentFocus?.let { v ->
                    if (v.id !in allRailIds) {
                        val location = IntArray(2)
                        v.getLocationOnScreen(location)
                        if (location[1] > resources.displayMetrics.heightPixels * 0.4) {
                            initialGravity = Gravity.TOP or Gravity.END
                        }
                    }
                }

                binding.previewArea.addView(
                    preview,
                    FrameLayout.LayoutParams(-2, -2).apply {
                        gravity = initialGravity
                        val m = (resources.displayMetrics.density * 10).toInt()
                        setMargins(0, m, m, m)
                    }
                )

                if (animate && props.animationType != 0) {
                    preview.postDelayed({ if (preview.parent != null) preview.animateIn() }, 500)
                }
            }
        }, 10)
    }

    override fun onResume() {
        isInitializing = true
        super.onResume()
        Permissions.onActivityResumed()
    }

    override fun onPause() {
        super.onPause()
        Permissions.onActivityPaused()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(settingsReceiver)
        } catch (_: Exception) {}
        // Note: We don't manually recycle the placeholder here to avoid "recycled bitmap" errors
        // if the UI performs a final layout pass after activity destruction.
        cachedPlaceholder = null
    }

    companion object {
        const val EXTRA_NAV_ID = "${PiPupApp.APP_PACKAGE}.extra.NAV_ID"
    }

    /**
     * Data class representing a color preset.
     */
    data class ColorEntry(val nameRes: Int, val hex: String)

    /**
     * Custom adapter for the color selection spinner in submenus.
     */
    class ColorSpinnerAdapter(context: Context, val colors: List<ColorEntry>, val defaultHex: String) : ArrayAdapter<ColorEntry>(context, 0, colors) {
        override fun getView(p: Int, v: View?, g: ViewGroup): View = create(p, v, g)
        override fun getDropDownView(p: Int, v: View?, g: ViewGroup): View = create(p, v, g)
        private fun create(p: Int, v: View?, g: ViewGroup): View {
            val res = v ?: LayoutInflater.from(context).inflate(R.layout.item_color_spinner, g, false)
            val entry = colors[p]
            res.findViewById<View>(R.id.color_preview).background.setTint(entry.hex.toColorInt())
            val label = res.findViewById<TextView>(R.id.color_name)
            val name = context.getString(entry.nameRes)
            label.text = if (entry.hex.equals(other = defaultHex, ignoreCase = true)) "$name ${context.getString(R.string.settings_default_suffix)}" else name
            label.setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
            return res
        }
    }
}

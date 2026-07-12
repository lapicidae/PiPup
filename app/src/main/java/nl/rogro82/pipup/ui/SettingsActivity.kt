package nl.rogro82.pipup.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.databinding.ActivitySettingsBinding

@UnstableApi
class SettingsActivity : AppCompatActivity() {

    lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { AppSettings(this) }
    private val handler = Handler(Looper.getMainLooper())
    val mapper = jacksonObjectMapper()

    private var currentLayoutRes: Int = -1
    private var currentNavId: Int = -1

    private val submenuControllers = mutableMapOf<Int, SubmenuController>()

    private val railIds = listOf(
        R.id.nav_item_general, R.id.nav_item_background, R.id.nav_item_text_style,
        R.id.nav_item_border, R.id.nav_item_animation, R.id.nav_item_updates, R.id.nav_item_advanced,
    )

    val materialColors = listOf(
        ColorEntry(R.string.color_deep_slate,    "#0F1417"), // Deep Slate (Basis-Hintergrund)
        ColorEntry(R.string.color_midnight_violet,   "#312B3F"), // Midnight Violet (Tonal Tone 20)
        ColorEntry(R.string.color_black_plum,     "#492532"), // Black Plum (Tonal Tone 20)
        ColorEntry(R.string.color_rosewood,      "#601410"), // Rosewood (Tonal Tone 20)
        ColorEntry(R.string.color_forest_green,    "#0A3915"), // Forest Green (Tonal Tone 20)
        ColorEntry(R.string.color_deep_umber,   "#4D2B00"), // Deep Umber (Tonal Tone 20)
        ColorEntry(R.string.color_gunmetal,     "#2F3033"), // Gunmetal (Tonal Tone 20)
        ColorEntry(R.string.color_navy_blue,    "#002F54"), // Navy Blue (Tonal Tone 20)
        ColorEntry(R.string.color_platinum,    "#DFE3E7"), // Platinum (Basis-Text)
        ColorEntry(R.string.color_sweet_lavender, "#D0BCFF"), // Sweet Lavender (Tonal Tone 80)
        ColorEntry(R.string.color_cotton_candy,"#FFB2C5"),// Cotton Candy (Tonal Tone 80)
        ColorEntry(R.string.color_peach_blossom,"#F2B8B5"), // Peach Blossom (Tonal Tone 80)
        ColorEntry(R.string.color_celadon_pastel,     "#B6E3B3"), // Celadon Pastel (Tonal Tone 80)
        ColorEntry(R.string.color_peach_cream,     "#FFDCBE"), // Peach Cream (Tonal Tone 80)
        ColorEntry(R.string.color_silver,       "#C6C6C9"), // Silver (Tonal Tone 80)
        ColorEntry(R.string.color_sky_blue,     "#D1E4FF")  // Sky Blue (Tonal Tone 80)
    )

    private var cachedPlaceholder: android.graphics.Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.SettingsTheme)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (getController(currentLayoutRes).onBackPress()) return
                if (binding.submenuContainer.findFocus() != null) {
                    focusRail()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setupNavRail()
        loadSubmenu(R.layout.submenu_general, R.id.nav_item_general)
        findViewById<View>(R.id.nav_item_general).requestFocus()
    }

    private fun setupNavRail() {
        configureNavItem(R.id.nav_item_general, R.string.settings_nav_general, R.drawable.ic_general_style, R.layout.submenu_general)
        configureNavItem(R.id.nav_item_background, R.string.settings_nav_background, R.drawable.ic_bg, R.layout.submenu_background)
        configureNavItem(R.id.nav_item_text_style, R.string.settings_nav_text, R.drawable.ic_text_style, R.layout.submenu_text)
        configureNavItem(R.id.nav_item_border, R.string.settings_nav_border, R.drawable.ic_border_style, R.layout.submenu_border)
        configureNavItem(R.id.nav_item_animation, R.string.settings_nav_animation, R.drawable.ic_animation, R.layout.submenu_animation)
        configureNavItem(R.id.nav_item_updates, R.string.settings_nav_updates, R.drawable.ic_updates, R.layout.submenu_updates)
        configureNavItem(R.id.nav_item_advanced, R.string.settings_nav_advanced, R.drawable.ic_advanced, R.layout.submenu_advanced)

        railIds.forEachIndexed { i, id ->
            findViewById<View>(id)?.apply {
                nextFocusUpId = if (i > 0) railIds[i - 1] else id
                nextFocusDownId = if (i < (railIds.size - 1)) railIds[i + 1] else id
                nextFocusLeftId = id
                nextFocusRightId = R.id.settings_scroll
            }
        }
    }

    private fun configureNavItem(navId: Int, textRes: Int, iconRes: Int, layoutRes: Int) {
        val root = findViewById<View>(navId) ?: return
        root.findViewById<TextView>(R.id.nav_text)?.setText(textRes)
        root.findViewById<ImageView>(R.id.nav_icon)?.setImageResource(iconRes)

        root.setOnClickListener { focusFirstInSubmenu() }
        root.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                // Centering for NavRail (if it scrolls)
                (v.parent.parent as? ScrollView)?.let { scroll ->
                    val rect = android.graphics.Rect()
                    v.getDrawingRect(rect)
                    scroll.offsetDescendantRectToMyCoords(v, rect)
                    val pivotY = scroll.height * 0.3f
                    scroll.smoothScrollTo(0, (rect.top - pivotY).toInt().coerceAtLeast(0))
                }
                if (currentLayoutRes != layoutRes) loadSubmenu(layoutRes, navId)
            }
            updateNavAppearance()
        }

        root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                focusFirstInSubmenu()
                true
            } else false
        }
    }

    private fun updateNavAppearance() {
        val layoutMap = mapOf(
            R.id.nav_item_general to R.layout.submenu_general,
            R.id.nav_item_background to R.layout.submenu_background,
            R.id.nav_item_text_style to R.layout.submenu_text,
            R.id.nav_item_border to R.layout.submenu_border,
            R.id.nav_item_animation to R.layout.submenu_animation,
            R.id.nav_item_updates to R.layout.submenu_updates,
            R.id.nav_item_advanced to R.layout.submenu_advanced
        )

        railIds.forEach { id ->
            val v = findViewById<View>(id) ?: return@forEach
            val label = v.findViewById<TextView>(R.id.nav_text)
            val icon = v.findViewById<ImageView>(R.id.nav_icon)
            val isSelected = currentLayoutRes == layoutMap[id]
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

    private fun loadSubmenu(layoutRes: Int, navId: Int) {
        currentLayoutRes = layoutRes
        currentNavId = navId
        binding.submenuContainer.removeAllViews()
        val root = LayoutInflater.from(this).inflate(layoutRes, binding.submenuContainer, true)

        binding.settingsScroll.post { binding.settingsScroll.scrollTo(0, 0) }

        getController(layoutRes).onBind(root)
        setupSubmenuFocus(navId)
        updateNavAppearance()
        updatePreview(animate = false)
    }

    fun getController(layoutRes: Int): SubmenuController {
        return submenuControllers.getOrPut(layoutRes) {
            when (layoutRes) {
                R.layout.submenu_general -> GeneralSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_background -> BackgroundSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_text -> TextSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_border -> BorderSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_animation -> AnimationSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_advanced -> AdvancedSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                R.layout.submenu_updates -> UpdatesSubmenu(this, settings, { updatePreview(it) }, binding.previewArea)
                else -> object : SubmenuController {
                    override fun onBind(root: View) {}
                    override fun onBackPress(): Boolean = false
                    override fun updatePreviewPosition(v: View) {}
                }
            }
        }
    }

    private fun setupSubmenuFocus(navId: Int) {
        val container = binding.submenuContainer.getChildAt(0) as? ViewGroup ?: return
        val focusableChildren = (0 until container.childCount).asSequence()
            .map { container.getChildAt(it) }
            .filter { it.isFocusable && it.isVisible }
            .toList()

        if (focusableChildren.isNotEmpty()) {
            findViewById<View>(navId)?.nextFocusRightId = focusableChildren[0].id
        }

        focusableChildren.forEachIndexed { i, child ->
            child.nextFocusLeftId = navId
            child.nextFocusUpId = if (i > 0) focusableChildren[i - 1].id else child.id
            child.nextFocusDownId = if (i < focusableChildren.size - 1) focusableChildren[i + 1].id else child.id

            val old = child.onFocusChangeListener
            child.setOnFocusChangeListener { v, f ->
                if (f) {
                    // Gold Standard: Smooth Focus Centering (Pivot Scrolling)
                    val scroll = binding.settingsScroll
                    val container = binding.submenuContainer

                    val rect = android.graphics.Rect()
                    v.getDrawingRect(rect)
                    scroll.offsetDescendantRectToMyCoords(v, rect)

                    val viewportHeight = scroll.height
                    if (viewportHeight > 0) {
                        // Pivot at 30% from the top
                        val pivotY = viewportHeight * 0.3f
                        val targetScrollY = (rect.top - pivotY).toInt()
                        val maxScroll = (container.height - viewportHeight).coerceAtLeast(0)
                        scroll.smoothScrollTo(0, targetScrollY.coerceIn(0, maxScroll))
                    }
                }
                old?.onFocusChange(v, f)
                if (f) getController(currentLayoutRes).updatePreviewPosition(v)
            }
        }
    }

    fun focusRail() {
        findViewById<View>(currentNavId)?.requestFocus()
    }

    fun focusFirstInSubmenu() {
        val container = binding.submenuContainer.getChildAt(0) as? ViewGroup ?: return
        (0 until container.childCount).map { container.getChildAt(it) }.firstOrNull { it.isFocusable && it.isVisible }?.requestFocus()
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
                if (focus != null && focus.id !in railIds) {
                    getController(currentLayoutRes).updatePreviewPosition(focus)
                } else {
                    // Ensure default position when focused on the navigation rail
                    val params = existingPreview.layoutParams as FrameLayout.LayoutParams
                    val defaultGravity = Gravity.BOTTOM or Gravity.END
                    if (params.gravity != defaultGravity) {
                        params.gravity = defaultGravity
                        val m = (resources.displayMetrics.density * 10).toInt()
                        params.setMargins(0, m, m, m)
                        existingPreview.layoutParams = params
                    }
                }
            } else {
                binding.previewArea.removeAllViews()
                val preview = PopupView.build(this, props)

                var initialGravity = Gravity.BOTTOM or Gravity.END
                currentFocus?.let { v ->
                    if (v.id !in railIds) {
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

    override fun onDestroy() {
        super.onDestroy()
        cachedPlaceholder?.recycle()
        cachedPlaceholder = null
    }

    data class ColorEntry(val nameRes: Int, val hex: String)

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

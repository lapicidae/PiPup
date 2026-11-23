package nl.rogro82.pipup.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import nl.rogro82.pipup.AppSettings
import nl.rogro82.pipup.BuildConfig
import nl.rogro82.pipup.R
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.showToast

/**
 * Settings submenu controller displaying application version, project credits,
 * and an on-demand TV-friendly viewer for open-source library licenses.
 */
@UnstableApi
class AboutSubmenu(
    context: Context,
    settings: AppSettings,
    onSettingsChanged: (Boolean) -> Unit,
    previewArea: FrameLayout,
) : SubmenuBase(context, settings, onSettingsChanged, previewArea) {

    override fun onBind(root: View) {
        val versionRes = if (BuildConfig.DEBUG) R.string.version_number_debug else R.string.version_number
        val versionString = context.getString(versionRes, BuildConfig.VERSION_NAME)
        root.findViewById<TextView>(R.id.text_about_version)?.text =
            context.getString(R.string.settings_about_version_format, context.getString(R.string.app_name), versionString)

        val translatorCredits = context.getString(R.string.translator_credits).trim()
        root.findViewById<View>(R.id.container_about_translators)?.isVisible = translatorCredits.isNotEmpty()
        root.findViewById<TextView>(R.id.text_about_translators)?.text = translatorCredits

        root.findViewById<Button>(R.id.btn_open_source_licenses)?.apply {
            setOnClickListener { showOpenSourceLicensesDialog() }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> if (f) updatePreviewPosition(v) }
        }
    }

    private fun loadLibraries(): List<LibraryInfo> {
        return try {
            val json = context.resources.openRawResource(R.raw.aboutlibraries)
                .bufferedReader()
                .use { it.readText() }
            OpenSourceLicenses.parseGrouped(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun showOpenSourceLicensesDialog() {
        val libraries = loadLibraries()
        if (libraries.isEmpty()) {
            context.showToast(context.getString(R.string.settings_about_licenses_empty))
            return
        }

        val padV = context.dpToPx(8)
        val listView = ListView(context).apply {
            divider = null
            dividerHeight = 0
            clipToPadding = false
            setPadding(0, padV, 0, padV)
            adapter = createLibraryAdapter(libraries)
            setOnItemClickListener { _, _, position, _ ->
                showLibraryDetailDialog(libraries[position])
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings_about_licenses_title)
            .setView(listView)
            .setNegativeButton(R.string.settings_about_licenses_close, null)
            .create()
        dialog.show()
        applyTvDialogWidth(dialog, LIST_DIALOG_WIDTH_FRACTION)
    }

    private fun createLibraryAdapter(libraries: List<LibraryInfo>): ArrayAdapter<LibraryInfo> {
        val inflater = LayoutInflater.from(context)
        return object : ArrayAdapter<LibraryInfo>(context, R.layout.item_open_source_license, libraries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: inflater.inflate(R.layout.item_open_source_license, parent, false)
                val item = libraries[position]
                view.findViewById<TextView>(R.id.text_license_name).text = item.name
                val metaView = view.findViewById<TextView>(R.id.text_license_meta)
                val metaText = formatLibraryMeta(item)
                metaView.text = metaText
                metaView.isVisible = metaText.isNotEmpty()
                return view
            }
        }
    }

    private fun showLibraryDetailDialog(library: LibraryInfo) {
        val padH = context.dpToPx(24)
        val padV = context.dpToPx(16)
        val textView = TextView(context).apply {
            text = buildLibraryDetails(library)
            setTextColor(ContextCompat.getColor(context, R.color.colorOnSurface))
            textSize = 14f
            setPadding(padH, padV, padH, padV)
        }
        val scrollView = ScrollView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            addView(textView)
        }

        val title = library.version?.let { "${library.name} ($it)" } ?: library.name
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton(R.string.settings_about_licenses_close, null)
            .create()
        dialog.show()
        applyTvDialogWidth(dialog, DETAIL_DIALOG_WIDTH_FRACTION)
        scrollView.requestFocus()
    }

    private fun applyTvDialogWidth(dialog: AlertDialog, widthFraction: Float) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        dialog.window?.setLayout((screenWidth * widthFraction).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun formatLibraryMeta(library: LibraryInfo): String {
        val parts = mutableListOf<String>()
        when {
            !library.version.isNullOrBlank() -> parts.add(library.version)
            library.artifacts.size > 1 -> parts.add(
                context.resources.getQuantityString(
                    R.plurals.settings_about_license_modules,
                    library.artifacts.size,
                    library.artifacts.size,
                ),
            )
        }
        val licenseNames = library.licenses.joinToString(", ") { it.name }
        if (licenseNames.isNotEmpty()) {
            parts.add(licenseNames)
        }
        return parts.joinToString(" · ")
    }

    private fun buildLibraryDetails(library: LibraryInfo): String {
        val sections = mutableListOf<String>()
        val header = buildList {
            if (library.artifacts.size == 1) {
                add(library.artifacts.first())
            }
            library.author?.let { add(context.getString(R.string.settings_about_license_author, it)) }
            library.website?.let { add(it) }
            library.description?.let { add("\n$it") }
        }.joinToString("\n")
        if (header.isNotEmpty()) {
            sections.add(header)
        }

        if (library.artifacts.size > 1) {
            val artifactList = buildString {
                append(context.getString(R.string.settings_about_license_artifacts, library.artifacts.size))
                library.artifacts.forEach { append("\n• $it") }
            }
            sections.add(artifactList)
        }

        library.licenses.forEach { license ->
            val licenseBlock = buildString {
                append("--- ${license.name} ---")
                license.url?.let { append("\n$it") }
                license.content?.let { append("\n\n$it") }
            }
            sections.add(licenseBlock)
        }
        return sections.joinToString("\n\n")
    }

    companion object {
        private const val LIST_DIALOG_WIDTH_FRACTION = 0.62f
        private const val DETAIL_DIALOG_WIDTH_FRACTION = 0.72f
    }
}

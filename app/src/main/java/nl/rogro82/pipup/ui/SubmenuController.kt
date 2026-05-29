package nl.rogro82.pipup.ui

import android.view.View

/**
 * Interface for controlling a specific settings submenu.
 */
interface SubmenuController {
    fun onBind(root: View)
    fun onBackPress(): Boolean
    fun updatePreviewPosition(v: View)
}

package nl.rogro82.pipup.ui

import android.view.View

/**
 * Interface for controlling a specific settings submenu.
 */
interface SubmenuController {
    /** Binds controls and listeners to the inflated submenu [root] view. */
    fun onBind(root: View)

    /** Handles a back press within the submenu; returns true if consumed. */
    fun onBackPress(): Boolean

    /** Repositions the live popup preview so it does not obscure focused view [v]. */
    fun updatePreviewPosition(v: View)
}

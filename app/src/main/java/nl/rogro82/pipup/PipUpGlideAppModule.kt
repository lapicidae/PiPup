package nl.rogro82.pipup

import android.content.Context
import android.graphics.drawable.PictureDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG
import java.io.InputStream

/**
 * Main Glide module for the PiPup application.
 *
 * Configures global Glide settings and registers custom components for
 * specialized media formats like SVG.
 */
@GlideModule
@Suppress("unused")
class PipUpGlideAppModule : AppGlideModule() {

    /**
     * Registers custom components for Glide's image loading pipeline.
     *
     * This implementation adds support for SVG decoding and transcoding
     * to [PictureDrawable].
     */
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry
            .register(SVG::class.java, PictureDrawable::class.java, SvgDrawableTranscoder())
            .append(InputStream::class.java, SVG::class.java, SvgDecoder())
    }

    /**
     * Disables manifest parsing to improve Glide's initialization performance.
     */
    override fun isManifestParsingEnabled(): Boolean = false
}

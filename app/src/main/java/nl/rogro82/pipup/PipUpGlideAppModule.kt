package nl.rogro82.pipup

import android.content.Context
import android.graphics.drawable.PictureDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.caverock.androidsvg.SVG
import java.io.InputStream

/**
 * Main Glide module for the PiPup application.
 *
 * Configures global Glide settings and registers custom components for
 * specialized media formats like SVG.
 */
@GlideModule
class PipUpGlideAppModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // Drastically reduce memory consumption by favoring Disk Cache over RAM Cache
        val calculator = MemorySizeCalculator.Builder(context)
            .setMemoryCacheScreens(0.5f) // Use only half a screen worth of RAM cache
            .setBitmapPoolScreens(0.5f)
            .build()

        builder.setMemoryCache(LruResourceCache(calculator.memoryCacheSize.toLong()))
        builder.setBitmapPool(LruBitmapPool(calculator.bitmapPoolSize.toLong()))

        // Set a reasonable disk cache (50MB) to avoid re-downloading images from cameras/web
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, 50 * 1024 * 1024))

        // Prefer RGB_565 for lower memory footprint where transparency isn't strictly required
        // (Note: SVG and some overlays might still use ARGB_8888 as needed)
        builder.setDefaultRequestOptions(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
    }

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

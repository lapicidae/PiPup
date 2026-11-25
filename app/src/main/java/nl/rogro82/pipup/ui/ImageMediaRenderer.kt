package nl.rogro82.pipup.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.core.view.isNotEmpty
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.R
import nl.rogro82.pipup.getScaledPixels
import java.io.File
import java.lang.ref.WeakReference

/**
 * Handles Glide image/local-file rendering, raw bitmap rendering, and stream poster/loading placeholders.
 */
@UnstableApi
internal class ImageMediaRenderer(private val host: MediaRenderer) {

    internal data class GlideLoadSpec(
        val source: Any,
        val width: Int,
        val scale: Boolean,
        val diskCache: DiskCacheStrategy,
        val skipMemory: Boolean,
    )

    internal data class GlideTarget(
        val frame: WeakReference<FrameLayout>,
        val iv: WeakReference<ImageView>,
        val tw: Int,
        val uiContext: WeakReference<Context>,
    )

    fun renderImage(
        uiContext: Context,
        frame: FrameLayout,
        media: PopupProps.Media.Image,
        retryCount: Int = 0,
    ) {
        val strategy = if (media.cache) DiskCacheStrategy.DATA else DiskCacheStrategy.NONE
        val spec = GlideLoadSpec(media.uri, media.width, media.scale, strategy, !media.cache)
        renderGlide(uiContext, frame, spec, retryCount)
    }

    fun renderLocalFile(uiContext: Context, frame: FrameLayout, path: String, width: Int, scale: Boolean) {
        val spec = GlideLoadSpec(File(path), width, scale, DiskCacheStrategy.NONE, skipMemory = true)
        renderGlide(uiContext, frame, spec, retryCount = 0)
    }

    fun renderBitmap(uiContext: Context, frame: FrameLayout, bitmap: Bitmap, width: Int, scale: Boolean) {
        if (bitmap.isRecycled) {
            host.callback?.notifyReady()
            return
        }
        host.updateSourceDimensions(bitmap.width, bitmap.height)
        val isFullscreen = host.isFullscreenActive
        if (isFullscreen) {
            host.applyFullscreenMediaFrameSize(frame)
        } else {
            val tw = if (scale) host.appContext.getScaledPixels(width) else width
            val th = (tw * bitmap.height) / bitmap.width
            host.callback?.onDimensionsUpdated(tw, th)
            frame.layoutParams.width = tw
            frame.layoutParams.height = th
            frame.requestLayout()
        }

        val iv = configureImageView(uiContext, frame, isFullscreen)
        iv.setImageBitmap(bitmap)
        host.removeStaleViews(frame, iv)
        host.callback?.notifyReady()
    }

    fun renderGlide(
        uiContext: Context,
        frame: FrameLayout,
        spec: GlideLoadSpec,
        retryCount: Int,
    ) {
        val isFullscreen = host.isFullscreenActive
        val maxDim = if (spec.scale) host.appContext.getScaledPixels(MAX_IMAGE_DIM_PX) else MAX_IMAGE_DIM_PX
        val (tw, th) = if (isFullscreen) {
            host.fullscreenBoundingBox
        } else {
            (if (spec.scale) host.appContext.getScaledPixels(spec.width) else spec.width).coerceAtMost(maxDim) to 0
        }
        host.callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        if (isFullscreen) {
            frame.layoutParams.height = th
        }
        frame.requestLayout()

        val iv = configureImageView(uiContext, frame, isFullscreen)
        Glide.with(host.appContext).clear(iv)

        val target = GlideTarget(WeakReference(frame), WeakReference(iv), tw, WeakReference(uiContext))
        val requestListener = GlideRequestListener(this, target, spec, retryCount)
        val effectiveStrategy = if (spec.skipMemory) DiskCacheStrategy.NONE else spec.diskCache

        var builder = Glide.with(host.appContext)
            .`as`(Drawable::class.java)
            .load(spec.source)
            .diskCacheStrategy(effectiveStrategy)
            .skipMemoryCache(spec.skipMemory)
            .override(tw, if (isFullscreen) th else Target.SIZE_ORIGINAL)
            .let {
                if (isFullscreen) {
                    if (host.currentFullscreenUpscale) it.fitCenter() else it.centerInside()
                } else {
                    it
                }
            }
            .dontAnimate()
            .listener(requestListener)

        if (spec.skipMemory) {
            builder = builder.signature(ObjectKey(System.currentTimeMillis().toString()))
        }

        builder.into(iv)
    }

    fun attachPosterOrPlaceholder(
        uiContext: Context,
        frame: FrameLayout,
        posterUrl: String?,
        posterMode: String?,
    ) {
        if (!posterUrl.isNullOrBlank()) {
            val iv = ImageView(uiContext).apply {
                scaleType = resolvePosterScaleType(posterMode)
                setBackgroundColor(Color.TRANSPARENT)
            }
            frame.addView(
                iv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            Log.d("PiPup-Media", "Starting poster load via Glide CustomTarget: $posterUrl")
            Glide.with(host.appContext)
                .asDrawable()
                .load(posterUrl)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        if (host.callback?.isCleanedUp() == true) return
                        iv.setImageDrawable(resource)
                        host.callback?.notifyReady()
                        host.callback?.adjustHeights()
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        iv.setImageDrawable(null)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        Log.w("PiPup-Media", "Poster load failed via CustomTarget: $posterUrl")
                        if (host.appSettings.mediaLoadingPlaceholder && host.callback?.isCleanedUp() == false) {
                            val placeholderView = createLoadingPlaceholder(uiContext)
                            frame.addView(
                                placeholderView,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    Gravity.CENTER,
                                ),
                            )
                            frame.removeView(iv)
                            host.callback?.notifyReady()
                            host.callback?.adjustHeights()
                        }
                    }
                })
        } else if (host.appSettings.mediaLoadingPlaceholder) {
            val placeholderView = createLoadingPlaceholder(uiContext)
            frame.addView(
                placeholderView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
            host.callback?.notifyReady()
            host.callback?.adjustHeights()
        }
    }

    private fun configureImageView(uiContext: Context, frame: FrameLayout, isFullscreen: Boolean): ImageView {
        val iv = (if (frame.isNotEmpty()) frame.getChildAt(0) else null) as? ImageView
            ?: ImageView(uiContext).apply { frame.addView(this) }
        iv.adjustViewBounds = !isFullscreen
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            if (isFullscreen) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
        return iv
    }

    private fun resolvePosterScaleType(posterMode: String?): ImageView.ScaleType =
        when (PopupProps.parsePosterMode(posterMode)) {
            PopupProps.PosterMode.FIT -> ImageView.ScaleType.FIT_CENTER
            PopupProps.PosterMode.STRETCH -> ImageView.ScaleType.FIT_XY
            PopupProps.PosterMode.CENTER -> ImageView.ScaleType.CENTER
            PopupProps.PosterMode.FILL, null -> ImageView.ScaleType.CENTER_CROP
        }

    private fun createLoadingPlaceholder(context: Context): View {
        val container = FrameLayout(context).apply {
            tag = "pipup_loading_placeholder"
            setBackgroundColor(Color.TRANSPARENT)
        }

        val iv = ImageView(context).apply {
            setImageResource(R.drawable.ic_banner)
            alpha = WATERMARK_ALPHA
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(
            iv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        val indicatorSize = (LOADING_INDICATOR_DP * context.resources.displayMetrics.density).toInt()
        val primaryColor = ContextCompat.getColor(context, R.color.colorPrimary)
        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(primaryColor)
        }
        container.addView(progressBar, FrameLayout.LayoutParams(indicatorSize, indicatorSize, Gravity.CENTER))

        return container
    }

    internal class GlideRequestListener(
        imageRenderer: ImageMediaRenderer,
        private val target: GlideTarget,
        private val spec: GlideLoadSpec,
        private val retryCount: Int,
    ) : RequestListener<Drawable> {
        private val imageRendererRef = WeakReference(imageRenderer)

        override fun onLoadFailed(
            e: GlideException?,
            model: Any?,
            target: Target<Drawable>,
            isFirstResource: Boolean,
        ): Boolean {
            Log.w("MediaRenderer", "Glide load failed: ${e?.message}, model: $model")
            e?.logRootCauses("MediaRenderer")

            val imgRenderer = imageRendererRef.get()
            val renderer = imgRenderer?.host
            val f = this.target.frame.get()
            val ui = this.target.uiContext.get()
            if (renderer == null || f == null || ui == null) return false

            val shouldRetry = retryCount < renderer.appSettings.mediaRetries &&
                renderer.callback?.isCleanedUp() == false
            return if (shouldRetry) {
                renderer.mainHandler.postAtTime({
                    val r = imageRendererRef.get() ?: return@postAtTime
                    if (r.host.callback?.isCleanedUp() == false) {
                        r.renderGlide(ui, f, spec, retryCount + 1)
                    }
                }, renderer.rendererToken, SystemClock.uptimeMillis() + GLIDE_RETRY_DELAY_MS * (retryCount + 1))
                true
            } else {
                renderer.callback?.showPlaceholder(renderer.appContext.getString(R.string.media_error_load_failed))
                renderer.callback?.notifyReady()
                false
            }
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>?,
            dataSource: DataSource,
            isFirstResource: Boolean,
        ): Boolean {
            val renderer = imageRendererRef.get()?.host
            val f = this.target.frame.get()
            val imageView = this.target.iv.get()
            if (renderer == null || f == null || imageView == null) return false

            if (renderer.callback?.isReadyCalled() != true && renderer.callback?.isCleanedUp() != true) {
                if (resource.intrinsicWidth > 0 && resource.intrinsicHeight > 0) {
                    renderer.updateSourceDimensions(resource.intrinsicWidth, resource.intrinsicHeight)
                    if (renderer.currentCoversScreen) {
                        renderer.applyFullscreenMediaFrameSize(f)
                    } else {
                        val maxH = (renderer.appContext.resources.displayMetrics.heightPixels * MAX_IMAGE_HEIGHT_RATIO)
                            .toInt()
                        val targetH = ((this.target.tw * resource.intrinsicHeight) / resource.intrinsicWidth)
                            .coerceAtMost(maxH)
                        val fittedW = (targetH * resource.intrinsicWidth) / resource.intrinsicHeight
                        f.layoutParams.width = fittedW
                        f.layoutParams.height = targetH
                        f.requestLayout()
                        renderer.callback?.onDimensionsUpdated(fittedW, targetH)
                    }
                }
                renderer.removeStaleViews(f, imageView)
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
            return false
        }
    }

    companion object {
        private const val GLIDE_RETRY_DELAY_MS = 500L
        private const val MAX_IMAGE_HEIGHT_RATIO = 0.40
        private const val MAX_IMAGE_DIM_PX = 1280
        private const val LOADING_INDICATOR_DP = 48
        private const val WATERMARK_ALPHA = 0.20f
    }
}

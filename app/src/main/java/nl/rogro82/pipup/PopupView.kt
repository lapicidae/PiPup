package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.TextureView
import android.view.animation.OvershootInterpolator
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import nl.rogro82.pipup.databinding.PopupBinding

/**
 * Modern PopupView using a single background container for both text and media.
 * This ensures unified transparency across all notification components.
 *
 * @param context The application context.
 * @param props The properties defining the popup's appearance and content.
 */
@SuppressLint("ViewConstructor")
@UnstableApi
class PopupView(context: Context, val props: PopupProps) : FrameLayout(context) {

    private val binding: PopupBinding = PopupBinding.inflate(LayoutInflater.from(context), this)
    var readyListener: ReadyListener? = null
    private var mPlayer: ExoPlayer? = null
    private var mVideoView: android.view.View? = null
    private var mWebView: WebView? = null
    private var isScrolling = false
    private var targetMediaHeight = 0

    /**
     * Interface for monitoring when the popup media is fully loaded and ready to display.
     */
    interface ReadyListener {
        /**
         * Called when all media (images, video, web) has been loaded or failed gracefully.
         */
        fun onReady()
    }

    /**
     * Initializes the styled notification view and its components.
     * Sets padding, background styling, and populates content.
     *
     * @return The initialized PopupView instance.
     */
    fun create(): PopupView {
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        )

        // Reset root background and alpha to ensure only container draws the background
        this.alpha = 1.0f
        this.background = null

        val settings = AppSettings(context)

        // 1. Content Padding
        val paddingVal = props.contentPadding ?: settings.contentPadding
        val scaledPadding = if (props.scale) Utils.getScaledPixels(context, paddingVal) else Utils.dpToPx(context, paddingVal)
        binding.popupContainer.setPadding(scaledPadding, scaledPadding, scaledPadding, scaledPadding)

        // 2. Background Styling (Applied to the shared container)
        val radiusPx = if (props.scale) Utils.getScaledPixels(context, props.borderRadius).toFloat() else Utils.dpToPx(context, props.borderRadius).toFloat()

        val bgColor = props.getBackgroundColorInt()
        val bgDrawable = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = radiusPx
            if (props.borderWidth > 0) {
                val bw = if (props.scale) Utils.getScaledPixels(context, props.borderWidth) else Utils.dpToPx(context, props.borderWidth)
                setStroke(bw, props.getBorderColorInt())
            }
        }
        binding.popupContainer.background = bgDrawable
        binding.popupContainer.alpha = 1.0f

        // 3. Dynamic Constraints & Auto-Scroll
        val maxTextWidth = if (props.scale) Utils.getScaledPixels(context, 500) else Utils.dpToPx(context, 500)

        binding.popupTitle.maxWidth = maxTextWidth
        binding.popupMessage.maxWidth = maxTextWidth

        // 4. Position Reordering
        reorderViews()

        // 5. Content Population
        if (!props.title.isNullOrEmpty()) {
            val titleGravity = props.getTitleGravity()
            binding.popupTitle.text = props.title
            binding.popupTitle.setTextColor(props.getTitleColorInt())
            binding.popupTitle.textSize = props.titleSize
            binding.popupTitle.gravity = titleGravity
            binding.popupTitle.isVisible = true

            (binding.popupTitle.layoutParams as? LinearLayout.LayoutParams)?.gravity = titleGravity
        }

        if (!props.message.isNullOrEmpty()) {
            val messageGravity = props.getMessageGravity()
            binding.popupMessage.text = props.message
            binding.popupMessage.setTextColor(props.getMessageColorInt())
            binding.popupMessage.textSize = props.messageSize
            binding.popupMessage.gravity = messageGravity
            binding.popupMessage.isVisible = true
            binding.popupScrollView.isVisible = true

            (binding.popupScrollView.layoutParams as? LinearLayout.LayoutParams)?.gravity = messageGravity

            // Post-layout logic for auto-scrolling and height adjustment
            binding.popupContainer.post {
                adjustHeights()
            }
        }

        setupMediaContent(binding.popupMediaFrame)

        return this
    }

    /**
     * Reorders the text and media containers based on the configured position.
     */
    private fun reorderViews() {
        val container = binding.popupContainer
        val textContainer = binding.textContainer
        val mediaFrame = binding.popupMediaFrame

        // 1. Remove views to prepare for re-addition in new order
        container.removeAllViews()

        val pos = props.mediaPosition ?: 0
        Log.d("PopupView", "Reordering views for position: $pos")

        when (pos) {
            0 -> { // Top
                container.orientation = LinearLayout.VERTICAL

                val mediaParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, Utils.dpToPx(context, 8))
                    gravity = Gravity.CENTER_HORIZONTAL
                }

                val textParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                container.addView(mediaFrame, mediaParams)
                container.addView(textContainer, textParams)
            }
            1 -> { // Bottom
                container.orientation = LinearLayout.VERTICAL

                val textParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                val mediaParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, Utils.dpToPx(context, 8), 0, 0)
                    gravity = Gravity.CENTER_HORIZONTAL
                }

                container.addView(textContainer, textParams)
                container.addView(mediaFrame, mediaParams)
            }
            2 -> { // Left
                container.orientation = LinearLayout.HORIZONTAL

                val mediaParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, Utils.dpToPx(context, 12), 0)
                    gravity = Gravity.CENTER_VERTICAL
                }

                val textParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }

                container.addView(mediaFrame, mediaParams)
                container.addView(textContainer, textParams)
            }
            3 -> { // Right
                container.orientation = LinearLayout.HORIZONTAL

                val textParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                }

                val mediaParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(Utils.dpToPx(context, 12), 0, 0, 0)
                    gravity = Gravity.CENTER_VERTICAL
                }

                container.addView(textContainer, textParams)
                container.addView(mediaFrame, mediaParams)
            }
        }
    }

    /**
     * Dynamically adjusts the height of the message scroll area to ensure all components fit on screen.
     */
    private fun adjustHeights() {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val maxPopupHeight = (screenHeight * 0.85).toInt() // Max 85% of screen height

        binding.popupContainer.post {
            // Force media frame to its target height immediately if known
            if (binding.popupMediaFrame.isVisible && targetMediaHeight > 0) {
                binding.popupMediaFrame.layoutParams.height = targetMediaHeight
                binding.popupMediaFrame.requestLayout()
            }

            val titleHeight = if (binding.popupTitle.isVisible) binding.popupTitle.measuredHeight else 0
            val mediaHeight = if (binding.popupMediaFrame.isVisible) {
                if (targetMediaHeight > 0) targetMediaHeight else binding.popupMediaFrame.measuredHeight
            } else 0

            val paddingHeight = binding.popupContainer.paddingTop + binding.popupContainer.paddingBottom
            val margins = Utils.dpToPx(context, 12) // Buffer for vertical margins

            val otherViewsHeight = titleHeight + mediaHeight + paddingHeight + margins
            val availableHeightForScroll = maxPopupHeight - otherViewsHeight

            // For horizontal layout, we might have more height available for the text
            val maxScrollHeight = if (binding.popupContainer.orientation == LinearLayout.HORIZONTAL) {
                (screenHeight * 0.7).toInt() // Max 70% of screen height in horizontal mode
            } else {
                availableHeightForScroll
            }

            // Get actual height of the text inside the scrollview
            val messageContentHeight = binding.popupMessage.measuredHeight

            if (messageContentHeight > maxScrollHeight) {
                Log.d("PopupView", "Restricting scroll height: content=$messageContentHeight, max allowed=$maxScrollHeight")
                binding.popupScrollView.layoutParams.height = maxScrollHeight.coerceAtLeast(Utils.dpToPx(context, 100))
                binding.popupScrollView.requestLayout()
                if (!isScrolling) startAutoScroll()
            } else {
                // Fits without scrolling, but reset height to WRAP_CONTENT just in case it was restricted before
                binding.popupScrollView.layoutParams.height = LayoutParams.WRAP_CONTENT
                binding.popupScrollView.requestLayout()
            }
        }
    }

    /**
     * Automatically scrolls the message text vertically if it exceeds the max height.
     */
    private fun startAutoScroll() {
        if (isScrolling) return
        isScrolling = true

        val scrollView = binding.popupScrollView
        val scrollContent = binding.popupMessage

        scrollView.postDelayed(object : Runnable {
            var scrollPos = 0
            val step = 1 // pixels per tick

            override fun run() {
                val maxScroll = scrollContent.height - scrollView.height
                if (maxScroll <= 0) return

                scrollPos += step
                if (scrollPos > maxScroll) {
                    // Wait a bit at the bottom and reset
                    scrollView.postDelayed({
                        scrollPos = 0
                        scrollView.scrollTo(0, 0)
                        scrollView.postDelayed(this, 2000) // Pause at top
                    }, 3000)
                    return
                }

                scrollView.scrollTo(0, scrollPos)
                scrollView.postDelayed(this, 30) // ~33 FPS
            }
        }, 2000) // Initial delay
    }

    /**
     * Configures and initiates the loading of media content (image, video, or web).
     */
    private fun setupMediaContent(frame: FrameLayout) {
        val media = props.media
        if (media == null && props.image == null) {
            frame.isVisible = false
            readyListener?.onReady()
            return
        }

        frame.isVisible = true
        // Container for media stays logically opaque; background shows through from popupContainer
        frame.alpha = 1.0f
        frame.background = null

        when (media) {
            is PopupProps.Media.Image -> renderImage(frame, media.uri, media.width, media.cache, media.scale)
            is PopupProps.Media.Video -> renderVideo(frame, media.uri, media.width, media.scale)
            is PopupProps.Media.Web -> renderWeb(frame, media.uri, media.width, media.height, media.cache, media.scale)
            is PopupProps.Media.Bitmap -> renderBitmap(frame, media.bitmap, media.width, media.scale)
            else -> {
                if (props.image != null) {
                    renderImage(frame, props.image, props.imageWidth ?: 480, cache = true, scale = true)
                } else {
                    readyListener?.onReady()
                    adjustHeights()
                }
            }
        }
    }

    /**
     * Renders an image using Glide.
     */
    private fun renderImage(frame: FrameLayout, uri: String, width: Int, cache: Boolean, scale: Boolean) {
        val tw = if (scale) Utils.getScaledPixels(context, width) else Utils.dpToPx(context, width)

        frame.layoutParams.width = tw
        frame.layoutParams.height = LayoutParams.WRAP_CONTENT

        val iv = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 1.0f
        }
        frame.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        Glide.with(context.applicationContext)
            .`as`(Drawable::class.java)
            .load(uri)
            .diskCacheStrategy(if (cache) DiskCacheStrategy.DATA else DiskCacheStrategy.NONE)
            .override(tw, com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
            .dontAnimate()
            .listener(object : com.bumptech.glide.request.RequestListener<Drawable> {
                override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<Drawable>, isFirstResource: Boolean): Boolean {
                    Log.w("PopupView", "Image load failed: $uri")
                    readyListener?.onReady()
                    return false
                }
                override fun onResourceReady(resource: Drawable, model: Any, target: com.bumptech.glide.request.target.Target<Drawable>?, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                    // Calculate target height based on intrinsic ratio
                    val intrinsicWidth = resource.intrinsicWidth
                    val intrinsicHeight = resource.intrinsicHeight
                    if (intrinsicWidth > 0) {
                        targetMediaHeight = (tw * intrinsicHeight) / intrinsicWidth
                        Log.d("PopupView", "Image target height: $targetMediaHeight (intrinsic: ${intrinsicWidth}x${intrinsicHeight})")
                    }

                    readyListener?.onReady()
                    adjustHeights()
                    return false
                }
            })
            .into(iv)
    }

    /**
     * Renders a video stream using ExoPlayer for low latency.
     * Uses [TextureView] for hardware-accelerated animations and transparency.
     */
    private fun renderVideo(frame: FrameLayout, uri: String, width: Int, scale: Boolean) {
        val tw = if (scale) Utils.getScaledPixels(context, width) else Utils.dpToPx(context, width)
        // Default to 16:9 aspect ratio to prevent layout jumps during initialization
        val th = (tw * 9) / 16

        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        // Configure ExoPlayer for extreme low latency (ideal for IP cameras)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                500,  // minBufferMs
                1000, // maxBufferMs
                250,  // bufferForPlaybackMs
                500   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()

        mPlayer = player

        val tv = TextureView(context).apply {
            alpha = 1.0f
            isVisible = false // Keep hidden until startMedia is called
        }
        mVideoView = tv
        player.setVideoTextureView(tv)

        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()

        val signaledReady = false
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (!signaledReady && state == Player.STATE_READY) {
                    Log.d("PopupView", "Video ready: $uri")

                    // Adjust height to actual aspect ratio if it differs from 16:9
                    val videoFormat = player.videoFormat
                    if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
                        targetMediaHeight = (tw * videoFormat.height) / videoFormat.width
                        if (targetMediaHeight != th) {
                            frame.layoutParams.height = targetMediaHeight
                            frame.requestLayout()
                        }
                    } else {
                        targetMediaHeight = th
                    }
                    readyListener?.onReady()
                    adjustHeights()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("PopupView", "ExoPlayer error: ${error.message}")
                if (!signaledReady) {
                    readyListener?.onReady()
                    adjustHeights()
                }
            }
        })

        frame.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER
        })
    }

    /**
     * Starts any media playback (e.g. Video) when the popup is actually shown.
     * Also handles visibility of the video surface.
     */
    fun startMedia() {
        mVideoView?.let { it.isVisible = true }
        mPlayer?.play()
    }

    /**
     * Cleans up all heavy resources (Video, WebView, Animations) to prevent memory leaks.
     */
    fun cleanup() {
        Log.d("PopupView", "Performing explicit cleanup of popup resources")
        handler?.removeCallbacksAndMessages(null)

        // Clear Glide resources explicitly
        try {
            Glide.with(context.applicationContext).clear(this)
            // Loop through ImageViews and clear them to release Bitmaps
            val mediaFrame = binding.popupMediaFrame
            for (i in 0 until mediaFrame.childCount) {
                val child = mediaFrame.getChildAt(i)
                if (child is android.widget.ImageView) {
                    Glide.with(context.applicationContext).clear(child)
                    child.setImageDrawable(null)
                }
            }
        } catch (e: Exception) {
            Log.e("PopupView", "Error clearing Glide", e)
        }

        // Explicitly recycle manual Bitmap if present
        if (props.media is PopupProps.Media.Bitmap) {
            try {
                Log.v("PopupView", "Recycling manual bitmap")
                props.media.bitmap.recycle()
            } catch (_: Exception) {}
        }

        mPlayer?.let {
            it.stop()
            it.release()
        }
        mPlayer = null

        mWebView?.let {
            try {
                it.stopLoading()
                it.loadUrl("about:blank")
                it.onPause()
                it.removeAllViews()
                it.destroy()
                Log.v("PopupView", "WebView destroyed")
            } catch (e: Exception) {
                Log.e("PopupView", "Error destroying WebView", e)
            }
        }
        mWebView = null

        // Help GC
        binding.popupMediaFrame.removeAllViews()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }

    /**
     * Renders a web page using WebView.
     */
    private fun renderWeb(frame: FrameLayout, uri: String, width: Int, height: Int, cache: Boolean, scale: Boolean) {
        val tw = if (scale) Utils.getScaledPixels(context, width) else Utils.dpToPx(context, width)
        val th = if (scale) Utils.getScaledPixels(context, height) else Utils.dpToPx(context, height)

        targetMediaHeight = th
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        val wv = WebView(context).apply {
            alpha = 1.0f
            mWebView = this
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    readyListener?.onReady()
                    adjustHeights()
                }
                @Deprecated("Deprecated in Java")
                override fun onReceivedError(v: WebView?, r: Int, d: String?, u: String?) {
                    readyListener?.onReady()
                    adjustHeights()
                }
            }
            @SuppressLint("SetJavaScriptEnabled")
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = if (cache) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
            }
        }

        frame.addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.CENTER
        })
        wv.loadUrl(uri)
    }

    /**
     * Renders a raw Bitmap directly.
     */
    private fun renderBitmap(frame: FrameLayout, bitmap: android.graphics.Bitmap, width: Int, scale: Boolean) {
        val tw = if (scale) Utils.getScaledPixels(context, width) else Utils.dpToPx(context, width)

        targetMediaHeight = (tw * bitmap.height) / bitmap.width
        frame.layoutParams.width = tw
        frame.layoutParams.height = targetMediaHeight

        val iv = ImageView(context).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 1.0f
        }
        frame.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        // Bitmap is already in memory, signal ready immediately
        readyListener?.onReady()
    }

    /**
     * Executes the configured animation to show the popup.
     */
    fun animateIn() {
        val duration = props.animationDuration.toLong()

        // Reset properties to ensure a clean start
        this.alpha = 1f
        this.scaleX = 1f
        this.scaleY = 1f
        this.translationX = 0f
        this.translationY = 0f
        this.rotationY = 0f
        this.rotation = 0f

        if (props.animationType == 0 || duration <= 0) return

        this.alpha = 0f
        val metrics = resources.displayMetrics
        val baseMargin = Utils.dpToPx(context, 20)
        val pos = props.getPositionEnum()

        fun applySlide() {
            when (pos) {
                PopupProps.Position.TopRight, PopupProps.Position.BottomRight -> this.translationX = this.width.toFloat() + baseMargin.toFloat() + 100f
                PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft -> this.translationX = -(this.width.toFloat() + baseMargin.toFloat() + 100f)
                PopupProps.Position.Center -> this.translationY = metrics.heightPixels.toFloat() / 2f
            }
        }

        when (props.animationType) {
            1 -> { // Fade
                this.animate().alpha(1f).setDuration(duration).start()
            }
            2 -> { // Slide
                this.alpha = 1f
                applySlide()
                this.animate().translationX(0f).translationY(0f).setDuration(duration).start()
            }
            3 -> { // Slide & Bounce
                this.alpha = 1f
                applySlide()
                this.animate().translationX(0f).translationY(0f)
                    .setInterpolator(OvershootInterpolator(1.2f))
                    .setDuration(duration).start()
            }
            4 -> { // Scale In
                this.scaleX = 0f; this.scaleY = 0f
                this.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).start()
            }
            5 -> { // Scale & Bounce
                this.scaleX = 0f; this.scaleY = 0f
                this.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .setDuration(duration).start()
            }
            6 -> { // Scale Ta-da
                this.scaleX = 0f; this.scaleY = 0f
                this.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration)
                    .withEndAction { executeTaDa() }.start()
            }
            7 -> { // Slide & Zoom
                this.alpha = 1f; this.scaleX = 0.5f; this.scaleY = 0.5f
                applySlide()
                this.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(duration).start()
            }
            8 -> { // Slide & Flip
                this.alpha = 1f; this.rotationY = -90f
                applySlide()
                this.animate().translationX(0f).translationY(0f).rotationY(0f).setDuration(duration).start()
            }
            9 -> { // Slide & Ta-da
                this.alpha = 1f
                applySlide()
                this.animate().translationX(0f).translationY(0f).setDuration(duration)
                    .withEndAction { executeTaDa() }.start()
            }
            10 -> { // Diagonal Zoom
                this.alpha = 0f; this.scaleX = 0f; this.scaleY = 0f
                when (pos) {
                    PopupProps.Position.TopRight -> { this.translationX = metrics.widthPixels.toFloat(); this.translationY = -500f }
                    PopupProps.Position.TopLeft -> { this.translationX = -metrics.widthPixels.toFloat(); this.translationY = -500f }
                    PopupProps.Position.BottomRight -> { this.translationX = metrics.widthPixels.toFloat(); this.translationY = metrics.heightPixels.toFloat() }
                    PopupProps.Position.BottomLeft -> { this.translationX = -metrics.widthPixels.toFloat(); this.translationY = metrics.heightPixels.toFloat() }
                    PopupProps.Position.Center -> { this.translationY = metrics.heightPixels.toFloat() }
                }
                this.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(duration).start()
            }
        }
    }

    /**
     * Executes a "Ta-da" attention sequence (small pulse and wobble).
     */
    private fun executeTaDa() {
        this.animate().scaleX(1.1f).scaleY(1.1f).rotation(3f).setDuration(150)
            .withEndAction {
                this.animate().rotation(-3f).setDuration(150)
                    .withEndAction {
                        this.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(150).start()
                    }.start()
            }.start()
    }

    companion object {
        fun build(context: Context, props: PopupProps, listener: ReadyListener? = null): PopupView {
            return PopupView(context, props).apply { readyListener = listener }.create()
        }
    }
}

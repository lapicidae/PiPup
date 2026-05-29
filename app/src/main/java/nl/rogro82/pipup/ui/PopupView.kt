package nl.rogro82.pipup.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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
import androidx.core.view.contains
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import nl.rogro82.pipup.*
import nl.rogro82.pipup.databinding.PopupBinding

/**
 * Modern PopupView using ViewBinding and modular rendering logic.
 */
@SuppressLint("ViewConstructor")
@UnstableApi
class PopupView(context: Context, var props: PopupProps) : FrameLayout(context) {

    private val binding: PopupBinding = PopupBinding.inflate(LayoutInflater.from(context), this)
    var readyListener: ReadyListener? = null

    private var mPlayer: ExoPlayer? = null
    private var mVideoView: android.view.View? = null
    private var mWebView: WebView? = null
    private var isScrolling = false
    private var targetMediaHeight = 0

    interface ReadyListener {
        fun onReady()
    }

    init {
        clipChildren = false
        clipToPadding = false
    }

    fun create(): PopupView {
        updateVisuals()
        setupMediaContent()
        return this
    }

    /**
     * Updates only the visual properties (colors, size, padding) without recreating media.
     * Useful for fluid settings previews.
     */
    fun updateFromProps(newProps: PopupProps) {
        val mediaChanged = props.media != newProps.media ||
                         props.image != newProps.image ||
                         props.mediaPosition != newProps.mediaPosition

        this.props = newProps
        updateVisuals()

        if (mediaChanged) {
            setupMediaContent()
        } else {
            adjustHeights()
        }
    }

    private fun updateVisuals() {
        layoutParams = layoutParams ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        alpha = 1.0f
        background = null
        setPadding(0, 0, 0, 0)

        val settings = AppSettings(context)

        // 1. Padding
        val paddingVal = props.contentPadding ?: settings.contentPadding
        val scaledPadding = if (props.scale) context.getScaledPixels(paddingVal) else context.dpToPx(paddingVal)
        binding.popupContainer.setPadding(scaledPadding, scaledPadding, scaledPadding, scaledPadding)

        // 2. Background
        val radiusPx = if (props.scale) context.getScaledPixels(props.borderRadius).toFloat() else context.dpToPx(props.borderRadius).toFloat()
        binding.popupContainer.background = GradientDrawable().apply {
            setColor(props.getBackgroundColorInt())
            cornerRadius = radiusPx
            if (props.borderWidth > 0) {
                val bw = if (props.scale) context.getScaledPixels(props.borderWidth) else context.dpToPx(props.borderWidth)
                setStroke(bw, props.getBorderColorInt())
            }
        }

        // 3. Constraints
        val maxTextWidth = if (props.scale) context.getScaledPixels(500) else context.dpToPx(500)
        binding.popupTitle.maxWidth = maxTextWidth
        binding.popupMessage.maxWidth = maxTextWidth

        reorderViews()

        // 4. Content
        props.title?.let {
            binding.popupTitle.text = it
            binding.popupTitle.setTextColor(props.getTitleColorInt())
            binding.popupTitle.textSize = props.titleSize
            val gravity = props.getTitleGravity()
            binding.popupTitle.gravity = gravity
            binding.popupTitle.isVisible = true
            (binding.popupTitle.layoutParams as? LinearLayout.LayoutParams)?.gravity = gravity
        } ?: run { binding.popupTitle.isVisible = false }

        props.message?.let {
            binding.popupMessage.text = it
            binding.popupMessage.setTextColor(props.getMessageColorInt())
            binding.popupMessage.textSize = props.messageSize
            val gravity = props.getMessageGravity()
            binding.popupMessage.gravity = gravity
            binding.popupMessage.isVisible = true
            binding.popupScrollView.isVisible = true
            (binding.popupScrollView.layoutParams as? LinearLayout.LayoutParams)?.gravity = gravity

            binding.popupContainer.post { adjustHeights() }
        } ?: run {
            binding.popupMessage.isVisible = false
            binding.popupScrollView.isVisible = false
        }
    }

    private fun reorderViews() {
        val container = binding.popupContainer
        val textContainer = binding.textContainer
        val mediaFrame = binding.popupMediaFrame

        if (textContainer in container &&
            mediaFrame in container &&
            container.tag == props.mediaPosition) {
            return // Already in correct order
        }

        container.removeAllViews()
        val pos = props.mediaPosition ?: 0
        container.tag = pos

        when (pos) {
            0 -> setupVertical(container, mediaFrame, textContainer, true) // Top
            1 -> setupVertical(container, textContainer, mediaFrame, false) // Bottom
            2 -> setupHorizontal(container, mediaFrame, textContainer, true) // Left
            3 -> setupHorizontal(container, textContainer, mediaFrame, false) // Right
        }
    }

    private fun setupVertical(container: LinearLayout, first: android.view.View, second: android.view.View, mediaFirst: Boolean) {
        container.orientation = LinearLayout.VERTICAL
        val margin = context.dpToPx(8)

        val firstParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            if (mediaFirst) gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, if (mediaFirst) margin else 0)
        }
        val secondParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            if (!mediaFirst) gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, if (!mediaFirst) margin else 0, 0, 0)
        }

        container.addView(first, firstParams)
        container.addView(second, secondParams)
    }

    private fun setupHorizontal(container: LinearLayout, first: android.view.View, second: android.view.View, mediaFirst: Boolean) {
        container.orientation = LinearLayout.HORIZONTAL
        val margin = context.dpToPx(12)

        val firstParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(0, 0, if (mediaFirst) margin else 0, 0)
        }
        val secondParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(if (!mediaFirst) margin else 0, 0, 0, 0)
        }

        container.addView(first, firstParams)
        container.addView(second, secondParams)
    }

    private fun adjustHeights() {
        val screenHeight = resources.displayMetrics.heightPixels
        val maxPopupHeight = (screenHeight * 0.85).toInt()

        binding.popupContainer.post {
            if (binding.popupMediaFrame.isVisible && targetMediaHeight > 0) {
                binding.popupMediaFrame.layoutParams.height = targetMediaHeight
                binding.popupMediaFrame.requestLayout()
            }

            val otherViewsHeight = (if (binding.popupTitle.isVisible) binding.popupTitle.measuredHeight else 0) +
                    (if (binding.popupMediaFrame.isVisible) (if (targetMediaHeight > 0) targetMediaHeight else binding.popupMediaFrame.measuredHeight) else 0) +
                    binding.popupContainer.paddingTop + binding.popupContainer.paddingBottom + context.dpToPx(12)

            val maxScrollHeight = if (binding.popupContainer.orientation == LinearLayout.HORIZONTAL) (screenHeight * 0.7).toInt()
                                 else maxPopupHeight - otherViewsHeight

            val contentHeight = binding.popupMessage.measuredHeight
            if (contentHeight > maxScrollHeight) {
                binding.popupScrollView.layoutParams.height = maxScrollHeight.coerceAtLeast(context.dpToPx(100))
                binding.popupScrollView.requestLayout()
                if (!isScrolling) startAutoScroll()
            } else {
                binding.popupScrollView.layoutParams.height = LayoutParams.WRAP_CONTENT
                binding.popupScrollView.requestLayout()
            }
        }
    }

    private fun startAutoScroll() {
        if (isScrolling) return
        isScrolling = true

        val runnable = object : Runnable {
            var scrollPos = 0
            override fun run() {
                val maxScroll = binding.popupMessage.height - binding.popupScrollView.height
                if (maxScroll <= 0) { isScrolling = false; return }

                scrollPos += 1
                if (scrollPos > maxScroll) {
                    binding.popupScrollView.postDelayed({
                        scrollPos = 0
                        binding.popupScrollView.scrollTo(0, 0)
                        binding.popupScrollView.postDelayed(this, 2000)
                    }, 3000)
                    return
                }
                binding.popupScrollView.scrollTo(0, scrollPos)
                binding.popupScrollView.postDelayed(this, 30)
            }
        }
        binding.popupScrollView.postDelayed(runnable, 2000)
    }

    private fun setupMediaContent() {
        val frame = binding.popupMediaFrame
        val currentImage = props.image
        val media = props.media ?: if (currentImage != null) PopupProps.Media.Image(currentImage, props.imageWidth ?: 480, cache = true, scale = true) else null

        if (media == null) {
            frame.isVisible = false
            readyListener?.onReady()
            return
        }

        frame.isVisible = true
        // Important: clear frame before adding new media if we are updating
        frame.removeAllViews()

        when (media) {
            is PopupProps.Media.Image -> renderImage(frame, media.uri, media.width, media.cache, media.scale)
            is PopupProps.Media.Video -> renderVideo(frame, media.uri, media.width, media.scale)
            is PopupProps.Media.Web -> renderWeb(frame, media.uri, media.width, media.height, media.cache, media.scale)
            is PopupProps.Media.Bitmap -> renderBitmap(frame, media.bitmap, media.width, media.scale)
        }
    }

    private fun renderImage(frame: FrameLayout, uri: String, width: Int, cache: Boolean, scale: Boolean) {
        val tw = if (scale) context.getScaledPixels(width) else context.dpToPx(width)
        frame.layoutParams.width = tw

        val iv = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        frame.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        Glide.with(context.applicationContext)
            .`as`(Drawable::class.java)
            .load(uri)
            .diskCacheStrategy(if (cache) DiskCacheStrategy.DATA else DiskCacheStrategy.NONE)
            .override(tw, com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
            .dontAnimate()
            .listener(object : com.bumptech.glide.request.RequestListener<Drawable> {
                override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<Drawable>, isFirstResource: Boolean): Boolean {
                    readyListener?.onReady()
                    return false
                }
                override fun onResourceReady(resource: Drawable, model: Any, target: com.bumptech.glide.request.target.Target<Drawable>?, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                    if (resource.intrinsicWidth > 0) {
                        targetMediaHeight = (tw * resource.intrinsicHeight) / resource.intrinsicWidth
                    }
                    readyListener?.onReady()
                    adjustHeights()
                    return false
                }
            }).into(iv)
    }

    private fun renderVideo(frame: FrameLayout, uri: String, width: Int, scale: Boolean) {
        val tw = if (scale) context.getScaledPixels(width) else context.dpToPx(width)
        val th = (tw * 9) / 16
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        val player = ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 1000, 250, 500).build())
            .build().also { mPlayer = it }

        val tv = TextureView(context).also { mVideoView = it; it.isVisible = false }
        player.setVideoTextureView(tv)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()

        player.addListener(object : Player.Listener {
            var ready = false
            override fun onPlaybackStateChanged(state: Int) {
                if (!ready && state == Player.STATE_READY) {
                    ready = true
                    player.videoFormat?.let { if (it.width > 0) targetMediaHeight = (tw * it.height) / it.width }
                    if (targetMediaHeight > 0 && targetMediaHeight != th) {
                        frame.layoutParams.height = targetMediaHeight
                        frame.requestLayout()
                    }
                    readyListener?.onReady()
                    adjustHeights()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!ready) { readyListener?.onReady(); adjustHeights() }
            }
        })
        frame.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    private fun renderWeb(frame: FrameLayout, uri: String, width: Int, height: Int, cache: Boolean, scale: Boolean) {
        val tw = if (scale) context.getScaledPixels(width) else context.dpToPx(width)
        val th = if (scale) context.getScaledPixels(height) else context.dpToPx(height)
        targetMediaHeight = th
        frame.layoutParams.apply { this.width = tw; this.height = th }

        val wv = WebView(context).apply {
            mWebView = this
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) { readyListener?.onReady(); adjustHeights() }
                @Deprecated("Deprecated in Java")
                override fun onReceivedError(v: WebView?, r: Int, d: String?, u: String?) { readyListener?.onReady(); adjustHeights() }
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
        frame.addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        wv.loadUrl(uri)
    }

    private fun renderBitmap(frame: FrameLayout, bitmap: Bitmap, width: Int, scale: Boolean) {
        if (bitmap.isRecycled) {
            readyListener?.onReady()
            return
        }
        val tw = if (scale) context.getScaledPixels(width) else context.dpToPx(width)
        targetMediaHeight = (tw * bitmap.height) / bitmap.width
        frame.layoutParams.apply { this.width = tw; this.height = targetMediaHeight }

        val iv = ImageView(context).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        frame.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        readyListener?.onReady()
    }

    fun startMedia() {
        mVideoView?.isVisible = true
        mPlayer?.play()
    }

    fun cleanup() {
        handler?.removeCallbacksAndMessages(null)
        try {
            Glide.with(context.applicationContext).clear(this)
            val frame = binding.popupMediaFrame
            for (i in 0 until frame.childCount) {
                (frame.getChildAt(i) as? ImageView)?.let { Glide.with(context.applicationContext).clear(it); it.setImageDrawable(null) }
            }
        } catch (_: Exception) {}

        // Note: Do NOT recycle Bitmap here if it's the shared preview placeholder!
        // That logic should be handled by the owner (SettingsActivity).

        mPlayer?.let { it.stop(); it.release() }
        mPlayer = null

        mWebView?.let { try { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() } catch (_: Exception) {} }
        mWebView = null
        binding.popupMediaFrame.removeAllViews()
    }

    fun animateIn() {
        val duration = props.animationDuration.toLong()
        resetAnimationProps()
        if (props.animationType == 0 || duration <= 0) return

        alpha = 0f
        val metrics = resources.displayMetrics
        val pos = props.getPositionEnum()

        fun applySlide() {
            val offset = (if (width > 0) width.toFloat() else context.dpToPx(400).toFloat()) + context.dpToPx(20).toFloat() + 100f
            when (pos) {
                PopupProps.Position.TopRight, PopupProps.Position.BottomRight -> translationX = offset
                PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft -> translationX = -offset
                PopupProps.Position.Center -> translationY = metrics.heightPixels.toFloat() / 2f
            }
        }

        when (props.animationType) {
            1 -> animate().alpha(1f).setDuration(duration).start()
            2 -> { alpha = 1f; applySlide(); animate().translationX(0f).translationY(0f).setDuration(duration).start() }
            3 -> { alpha = 1f; applySlide(); animate().translationX(0f).translationY(0f).setInterpolator(OvershootInterpolator(1.5f)).setDuration(duration).start() }
            4 -> { scaleX = 0f; scaleY = 0f; animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).start() }
            5 -> { scaleX = 0f; scaleY = 0f; animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(1.5f)).setDuration(duration).start() }
            6 -> { scaleX = 0f; scaleY = 0f; animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration).withEndAction { executeTaDa() }.start() }
            7 -> { alpha = 1f; scaleX = 0.5f; scaleY = 0.5f; applySlide(); animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(duration).start() }
            8 -> { alpha = 1f; rotationY = -90f; applySlide(); animate().translationX(0f).translationY(0f).rotationY(0f).setDuration(duration).start() }
            9 -> { alpha = 1f; applySlide(); animate().translationX(0f).translationY(0f).setDuration(duration).withEndAction { executeTaDa() }.start() }
            10 -> {
                alpha = 0f; scaleX = 0f; scaleY = 0f
                when (pos) {
                    PopupProps.Position.TopRight -> { translationX = metrics.widthPixels.toFloat(); translationY = -500f }
                    PopupProps.Position.TopLeft -> { translationX = -metrics.widthPixels.toFloat(); translationY = -500f }
                    PopupProps.Position.BottomRight -> { translationX = metrics.widthPixels.toFloat(); translationY = metrics.heightPixels.toFloat() }
                    PopupProps.Position.BottomLeft -> { translationX = -metrics.widthPixels.toFloat(); translationY = metrics.heightPixels.toFloat() }
                    PopupProps.Position.Center -> { translationY = metrics.heightPixels.toFloat() }
                }
                animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(duration).start()
            }
        }
    }

    private fun resetAnimationProps() {
        alpha = 1f; scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f; rotationY = 0f; rotation = 0f
    }

    fun animateOut(completion: () -> Unit) {
        val duration = props.animationDuration.toLong()
        if (props.animationType == 0 || duration <= 0 || !props.animationExit) {
            animate().alpha(0f).setDuration(if (duration > 0) duration else 300).withEndAction(completion).start()
            return
        }

        val metrics = resources.displayMetrics
        val pos = props.getPositionEnum()
        val baseMargin = context.dpToPx(20).toFloat()

        fun getSlideX() = when (pos) {
            PopupProps.Position.TopRight, PopupProps.Position.BottomRight -> width.toFloat() + baseMargin + 100f
            PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft -> -(width.toFloat() + baseMargin + 100f)
            else -> 0f
        }
        fun getSlideY() = if (pos == PopupProps.Position.Center) metrics.heightPixels.toFloat() / 2f else 0f

        when (props.animationType) {
            1 -> animate().alpha(0f).setDuration(duration).withEndAction(completion).start()
            2, 3, 9 -> animate().translationX(getSlideX()).translationY(getSlideY()).setDuration(duration).withEndAction(completion).start()
            4, 5, 6 -> animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(duration).withEndAction(completion).start()
            7 -> animate().translationX(getSlideX()).translationY(getSlideY()).scaleX(0.5f).scaleY(0.5f).setDuration(duration).withEndAction(completion).start()
            8 -> animate().translationX(getSlideX()).translationY(getSlideY()).rotationY(-90f).setDuration(duration).withEndAction(completion).start()
            10 -> {
                val (tx, ty) = when (pos) {
                    PopupProps.Position.TopRight -> metrics.widthPixels.toFloat() to -500f
                    PopupProps.Position.TopLeft -> -metrics.widthPixels.toFloat() to -500f
                    PopupProps.Position.BottomRight -> metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
                    PopupProps.Position.BottomLeft -> -metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
                    PopupProps.Position.Center -> 0f to metrics.heightPixels.toFloat()
                }
                animate().alpha(0f).translationX(tx).translationY(ty).scaleX(0f).scaleY(0f).setDuration(duration).withEndAction(completion).start()
            }
            else -> animate().alpha(0f).setDuration(duration).withEndAction(completion).start()
        }
    }

    private fun executeTaDa() {
        animate().scaleX(1.1f).scaleY(1.1f).rotation(3f).setDuration(150).withEndAction {
            animate().rotation(-3f).setDuration(150).withEndAction {
                animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(150).start()
            }.start()
        }.start()
    }

    companion object {
        fun build(context: Context, props: PopupProps, listener: ReadyListener? = null): PopupView {
            return PopupView(context, props).apply { readyListener = listener }.create()
        }
    }
}

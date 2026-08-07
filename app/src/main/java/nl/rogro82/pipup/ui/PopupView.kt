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
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.Keep
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.lang.ref.WeakReference
import nl.rogro82.pipup.BuildConfig
import nl.rogro82.pipup.PiPupApp
import nl.rogro82.pipup.PopupProps
import nl.rogro82.pipup.databinding.PopupBinding
import nl.rogro82.pipup.dpToPx
import nl.rogro82.pipup.getScaledPixels
import nl.rogro82.pipup.isEmulator

/**
 * Modern PopupView using ViewBinding and modular rendering logic.
 */
@SuppressLint("ViewConstructor")
@UnstableApi
class PopupView(context: Context, var props: PopupProps) : FrameLayout(context) {

    private val binding: PopupBinding = PopupBinding.inflate(LayoutInflater.from(context), this)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val settings = PiPupApp.settings
    var readyListener: ReadyListener? = null

    private var mPlayer: ExoPlayer? = null
    private var mVideoView: android.view.View? = null
    private var mWebView: WebView? = null
    private var isScrolling = false
    private var targetMediaWidth = 0
    private var targetMediaHeight = 0
    private var isReadyCalled = false
    private var isCleanedUp = false
    private var isFirstAnimateIn = true
    private var lastMediaError: String? = null

    @Keep
    inner class JsBridge(private val retryCount: Int = 0) {
        // Use WeakReference to avoid memory leaks if WebView outlives the View
        private val viewRef = WeakReference(this@PopupView)

        @JavascriptInterface
        fun onMediaPlaying() {
            viewRef.get()?.let { popup ->
                popup.mainHandler.post {
                    if (popup.isCleanedUp) return@post
                    android.util.Log.d("PopupView", "WHEP video playing signal received from JS")
                    popup.mWebView?.let {
                        it.visibility = VISIBLE
                        popup.removeStaleViews(it)
                    }
                    popup.notifyReady()
                    popup.adjustHeights()
                }
            }
        }

        @JavascriptInterface
        fun onMediaError(error: String) {
            viewRef.get()?.let { popup ->
                popup.mainHandler.post {
                    if (popup.isCleanedUp) return@post
                    android.util.Log.e("PopupView", "WHEP error signal received from JS: $error")
                    popup.handleWhepRetry(error, retryCount)
                }
            }
        }
    }

    private fun handleWhepRetry(error: String, currentRetry: Int) {
        lastMediaError = error
        val maxRetries = this@PopupView.settings.mediaRetries
        if (currentRetry < maxRetries && !isCleanedUp) {
            val nextRetry = currentRetry + 1
            android.util.Log.w("PopupView", "WHEP load failed, retrying ($nextRetry/$maxRetries): $error")
            mainHandler.postDelayed({
                val frame = binding.popupMediaFrame
                val m = props.media as? PopupProps.Media.Whep
                if (m != null) {
                    renderWhep(frame, m.uri, m.width, m.height, m.scale, m.videoFit, nextRetry)
                }
            }, 1000L * nextRetry)
        } else {
            android.util.Log.e("PopupView", "WHEP load failed permanently after $currentRetry retries")
            mainHandler.post {
                showPlaceholder(error)
                notifyReady()
            }
        }
    }

    private val timeoutRunnable = Runnable {
        if (!isReadyCalled) {
            android.util.Log.w("PopupView", "Media loading timed out, showing placeholder (last error: $lastMediaError)")
            // Use the specific error message if available, otherwise fallback to generic timeout
            showPlaceholder(lastMediaError ?: context.getString(nl.rogro82.pipup.R.string.media_error_timeout))
            notifyReady()
        }
    }

    private fun notifyReady() {
        if (isReadyCalled || isCleanedUp) return
        isReadyCalled = true
        mainHandler.removeCallbacks(timeoutRunnable)
        readyListener?.onReady()
    }

    fun showPlaceholder(errorMessage: String? = null) {
        // Ensure visibility is restored if we were in an invisible pre-loading state
        mWebView?.visibility = VISIBLE

        val frame = binding.popupMediaFrame
        frame.removeAllViews()
        val iv = ImageView(context).apply {
            setImageResource(nl.rogro82.pipup.R.drawable.ic_banner)
            alpha = 0.5f
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // Determine width once to keep layout stable
        val width = when (val m = props.media) {
            is PopupProps.Media.Image -> m.width
            is PopupProps.Media.Video -> m.width
            is PopupProps.Media.Web -> m.width
            is PopupProps.Media.Whep -> m.width
            is PopupProps.Media.LocalFile -> m.width
            is PopupProps.Media.Bitmap -> m.width
            else -> props.imageWidth ?: 480
        }
        val tw = if (props.scale) context.getScaledPixels(width) else context.dpToPx(width)

        // Preserve original target height if already set (e.g. from WHEP props)
        // to prevent jumps during error state transitions.
        if (targetMediaHeight <= 0) {
            targetMediaHeight = (tw * 9) / 16
        }

        frame.layoutParams.width = tw
        frame.layoutParams.height = targetMediaHeight
        frame.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        frame.isVisible = true

        errorMessage?.let { rawMsg ->
            val prettyError = beautifyErrorMessage(rawMsg)
            val mainMessage = props.message

            binding.popupMessage.text = if (mainMessage.isNullOrBlank()) {
                context.getString(nl.rogro82.pipup.R.string.media_error_only, prettyError)
            } else {
                context.getString(nl.rogro82.pipup.R.string.media_error_with_message, mainMessage, prettyError)
            }
            binding.popupMessage.isVisible = true
            binding.popupScrollView.isVisible = true
        }

        // Apply final heights but avoid re-calculation jumps
        mainHandler.post { adjustHeights() }
    }

    private fun beautifyErrorMessage(rawError: String): String {
        return when {
            rawError.contains("codecs not matched", ignoreCase = true) ->
                context.getString(nl.rogro82.pipup.R.string.media_error_codec_mismatch)
            rawError.contains("404") || rawError.contains("not found", ignoreCase = true) ->
                context.getString(nl.rogro82.pipup.R.string.media_error_not_found)
            rawError.contains("ICE", ignoreCase = true) || rawError.contains("connection", ignoreCase = true) ->
                context.getString(nl.rogro82.pipup.R.string.media_error_connection)
            rawError.contains("timeout", ignoreCase = true) ->
                context.getString(nl.rogro82.pipup.R.string.media_error_timeout)
            else -> rawError
        }
    }

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

    fun updateFromProps(newProps: PopupProps) {
        if (isCleanedUp) {
            android.util.Log.w("PopupView", "updateFromProps called on cleaned up view")
            return
        }

        val oldMedia = props.media ?: props.image?.let { PopupProps.Media.Image(it, props.imageWidth ?: 480) }
        val newMedia = newProps.media ?: newProps.image?.let { PopupProps.Media.Image(it, newProps.imageWidth ?: 480) }

        val contentChanged = !isMediaContentSame(oldMedia, newMedia)
        if (contentChanged) {
            android.util.Log.d("PopupView", "Media content changed, triggering reload")
        }

        this.props = newProps
        calculateTargetDimensions(newMedia, newProps)
        updateVisuals()

        if (contentChanged || newMedia is PopupProps.Media.Bitmap) {
            setupMediaContent()
        } else {
            // Handle property-only updates (e.g. WHEP videoFit) without full reload
            if (oldMedia is PopupProps.Media.Whep && newMedia is PopupProps.Media.Whep) {
                if (oldMedia.videoFit != newMedia.videoFit) {
                    android.util.Log.d("PopupView", "Updating WHEP videoFit dynamically to ${newMedia.videoFit}")
                    mWebView?.evaluateJavascript("document.getElementById('v').style.objectFit = '${newMedia.videoFit}';", null)
                }
            }
            adjustHeights()
        }
    }

    private fun calculateTargetDimensions(media: PopupProps.Media?, props: PopupProps) {
        val width = when (media) {
            is PopupProps.Media.Image -> media.width
            is PopupProps.Media.Video -> media.width
            is PopupProps.Media.Web -> media.width
            is PopupProps.Media.Whep -> media.width
            is PopupProps.Media.LocalFile -> media.width
            is PopupProps.Media.Bitmap -> media.width
            else -> props.imageWidth ?: 480
        }
        targetMediaWidth = if (props.scale) context.getScaledPixels(width) else context.dpToPx(width)

        targetMediaHeight = when (media) {
            is PopupProps.Media.Video -> (targetMediaWidth * 9) / 16
            is PopupProps.Media.Web -> if (props.scale) context.getScaledPixels(media.height) else context.dpToPx(media.height)
            is PopupProps.Media.Whep -> if (props.scale) context.getScaledPixels(media.height) else context.dpToPx(media.height)
            is PopupProps.Media.Bitmap -> (targetMediaWidth * media.bitmap.height) / media.bitmap.width
            else -> 0 // For images, height is determined after load in renderGlide
        }
    }

    private fun isMediaContentSame(m1: PopupProps.Media?, m2: PopupProps.Media?): Boolean {
        if (m1 == null || m2 == null) return m1 == m2
        if (m1::class != m2::class) return false
        return when (m1) {
            is PopupProps.Media.Image -> (m2 as PopupProps.Media.Image).let { m1.uri == it.uri && m1.cache == it.cache && m1.scale == it.scale }
            is PopupProps.Media.Video -> (m2 as PopupProps.Media.Video).let { m1.uri == it.uri && m1.scale == it.scale }
            is PopupProps.Media.Web -> (m2 as PopupProps.Media.Web).let { m1.uri == it.uri && m1.cache == it.cache && m1.scale == it.scale }
            is PopupProps.Media.Whep -> (m2 as PopupProps.Media.Whep).let { m1.uri == it.uri && m1.scale == it.scale }
            is PopupProps.Media.LocalFile -> m1.path == (m2 as PopupProps.Media.LocalFile).path
            else -> m1 == m2
        }
    }

    private fun updateVisuals() {
        if (layoutParams == null) {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        alpha = 1.0f
        if (background != null) background = null
        setPadding(0, 0, 0, 0)

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

            // Additionally align the text container content block within the popup
            (binding.textContainer.layoutParams as? LinearLayout.LayoutParams)?.gravity = gravity

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

        val pos = props.mediaPosition ?: 0
        if (container.tag == pos && textContainer.parent == container && mediaFrame.parent == container) {
            // Already in correct order. However, since props might have changed,
            // update existing layout params to keep them in sync with targetMediaWidth/Height.
            mediaFrame.layoutParams?.let { lp ->
                if (targetMediaWidth > 0 && targetMediaHeight > 0) {
                    lp.width = targetMediaWidth
                    lp.height = targetMediaHeight
                }
            }
            return
        }

        // Extremely careful reordering: only detach if absolutely necessary
        // to avoid WebView surface destruction.
        if (textContainer.parent != null && textContainer.parent != container) {
            (textContainer.parent as android.view.ViewGroup).removeView(textContainer)
        }
        if (mediaFrame.parent != null && mediaFrame.parent != container) {
            (mediaFrame.parent as android.view.ViewGroup).removeView(mediaFrame)
        }

        if (textContainer.parent == container) container.removeView(textContainer)
        if (mediaFrame.parent == container) container.removeView(mediaFrame)

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

        val firstWidth = if (first == binding.popupMediaFrame && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val firstHeight = if (first == binding.popupMediaFrame && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val firstParams = LinearLayout.LayoutParams(firstWidth, firstHeight).apply {
            if (first != binding.textContainer && mediaFirst) gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, if (mediaFirst) margin else 0)
        }

        val secondWidth = if (second == binding.popupMediaFrame && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val secondHeight = if (second == binding.popupMediaFrame && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val secondParams = LinearLayout.LayoutParams(secondWidth, secondHeight).apply {
            if (second != binding.textContainer && !mediaFirst) gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, if (!mediaFirst) margin else 0, 0, 0)
        }

        container.addView(first, firstParams)
        container.addView(second, secondParams)
    }

    private fun setupHorizontal(container: LinearLayout, first: android.view.View, second: android.view.View, mediaFirst: Boolean) {
        container.orientation = LinearLayout.HORIZONTAL
        val margin = context.dpToPx(12)

        val firstWidth = if (first == binding.popupMediaFrame && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val firstHeight = if (first == binding.popupMediaFrame && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val firstParams = LinearLayout.LayoutParams(firstWidth, firstHeight).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(0, 0, if (mediaFirst) margin else 0, 0)
        }

        val secondWidth = if (second == binding.popupMediaFrame && targetMediaWidth > 0) targetMediaWidth else LinearLayout.LayoutParams.WRAP_CONTENT
        val secondHeight = if (second == binding.popupMediaFrame && targetMediaHeight > 0) targetMediaHeight else LinearLayout.LayoutParams.WRAP_CONTENT

        val secondParams = LinearLayout.LayoutParams(secondWidth, secondHeight).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(if (!mediaFirst) margin else 0, 0, 0, 0)
        }

        container.addView(first, firstParams)
        container.addView(second, secondParams)
    }

    private val adjustHeightsRunnable = Runnable {
        if (isCleanedUp) return@Runnable
        val screenHeight = resources.displayMetrics.heightPixels
        val maxPopupHeight = (screenHeight * 0.85).toInt()

        if (binding.popupMediaFrame.isVisible && targetMediaWidth > 0 && targetMediaHeight > 0) {
            binding.popupMediaFrame.layoutParams.width = targetMediaWidth
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
            binding.popupScrollView.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
            binding.popupScrollView.requestLayout()
        }
    }

    private fun adjustHeights() {
        mainHandler.removeCallbacks(adjustHeightsRunnable)
        mainHandler.post(adjustHeightsRunnable)
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

        isReadyCalled = false
        mainHandler.removeCallbacks(timeoutRunnable)

        if (media == null) {
            frame.isVisible = false
            cleanupMediaResources()
            frame.removeAllViews()
            notifyReady()
            return
        }

        val timeoutSec = settings.mediaTimeout
        if (timeoutSec > 0) {
            android.util.Log.d("PopupView", "Setting media loading timeout to $timeoutSec seconds")
            mainHandler.postDelayed(timeoutRunnable, timeoutSec * 1000L)
        }

        frame.isVisible = true

        // Clean up stale content if the media type has changed to avoid showing
        // unrelated content from previous notifications during the loading phase.
        val isSameType = if (frame.isNotEmpty()) {
            val child = frame.getChildAt(0)
            (media is PopupProps.Media.Image && child is ImageView) ||
            (media is PopupProps.Media.Bitmap && child is ImageView) ||
            (media is PopupProps.Media.Whep && child is WebView) ||
            (media is PopupProps.Media.Web && child is WebView) ||
            (media is PopupProps.Media.Video && child is TextureView)
        } else false

        if (!isSameType) {
            cleanupMediaResources()
            frame.removeAllViews()
        }

        when (media) {
            is PopupProps.Media.Image -> renderImage(frame, media.uri, media.width, media.cache, media.scale)
            is PopupProps.Media.Video -> renderVideo(frame, media.uri, media.width, media.scale)
            is PopupProps.Media.Web -> renderWeb(frame, media.uri, media.width, media.height, media.cache, media.scale)
            is PopupProps.Media.Whep -> renderWhep(frame, media.uri, media.width, media.height, media.scale, media.videoFit)
            is PopupProps.Media.LocalFile -> renderLocalFile(frame, media.path, media.width, media.scale)
            is PopupProps.Media.Bitmap -> renderBitmap(frame, media.bitmap, media.width, media.scale)
        }
    }

    private fun removeStaleViews(keepView: android.view.View) {
        val frame = binding.popupMediaFrame
        val stale = mutableListOf<android.view.View>()
        for (i in 0 until frame.childCount) {
            val v = frame.getChildAt(i)
            if (v != keepView) stale.add(v)
        }

        for (v in stale) {
            if (v is WebView) {
                try {
                    v.stopLoading()
                    v.loadUrl("about:blank")
                    v.destroy()
                } catch (_: Exception) {}
            } else if (v is ImageView) {
                try { Glide.with(context.applicationContext).clear(v) } catch (_: Exception) {}
            }
            frame.removeView(v)
        }

        // If the new view is NOT a video, we can safely release the player now
        if (keepView !is TextureView) {
            mPlayer?.stop()
            mPlayer?.release()
            mPlayer = null
            mVideoView = null
        }
    }

    private fun cleanupMediaResources() {
        val frame = binding.popupMediaFrame
        // Clear Glide loads for all image views in the frame
        for (i in 0 until frame.childCount) {
            val child = frame.getChildAt(i)
            if (child is ImageView) {
                try { Glide.with(context.applicationContext).clear(child) } catch (_: Exception) {}
            }
        }

        mPlayer?.stop()
        mPlayer?.release()
        mPlayer = null
        mVideoView = null

        mWebView?.let { wv ->
            try {
                wv.onPause()
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.webChromeClient = WebChromeClient()
                wv.removeJavascriptInterface("PiPup")
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.loadUrl("about:blank")
                wv.post { try { wv.destroy() } catch (_: Exception) {} }
            } catch (e: Exception) {
                android.util.Log.d("PopupView", "WebView cleanup error: ${e.message}")
            }
        }
        mWebView = null
    }

    private fun renderImage(frame: FrameLayout, uri: String, width: Int, cache: Boolean, scale: Boolean) {
        android.util.Log.d("PopupView", "Rendering image (cache=$cache): $uri")
        targetMediaWidth = if (scale) context.getScaledPixels(width) else context.dpToPx(width)
        renderGlide(frame, uri, width, scale, if (cache) DiskCacheStrategy.DATA else DiskCacheStrategy.NONE, !cache)
    }

    private fun renderVideo(frame: FrameLayout, uri: String, width: Int, scale: Boolean, retryCount: Int = 0) {
        val tw = if (scale) context.getScaledPixels(width) else context.dpToPx(width)
        val th = (tw * 9) / 16
        targetMediaWidth = tw
        targetMediaHeight = th
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        // Clean up previous player if this is a retry, but keep placeholders
        mPlayer?.let {
            it.stop()
            it.release()
        }
        mPlayer = null
        mVideoView?.let { frame.removeView(it) }

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
                    mVideoView?.let { removeStaleViews(it) }
                    notifyReady()
                    adjustHeights()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!ready) {
                    val maxRetries = this@PopupView.settings.mediaRetries
                    if (retryCount < maxRetries && !isCleanedUp) {
                        val nextRetry = retryCount + 1
                        android.util.Log.w("PopupView", "Video load failed, retrying ($nextRetry/$maxRetries): ${error.message}")

                        mPlayer?.stop()
                        mPlayer?.release()
                        mPlayer = null
                        mVideoView = null

                        mainHandler.postDelayed({
                            if (!isCleanedUp) {
                                frame.removeAllViews()
                                renderVideo(frame, uri, width, scale, nextRetry)
                            }
                        }, 1000L * nextRetry)
                        return
                    }
                    android.util.Log.e("PopupView", "Video load failed permanently after $retryCount retries")
                    showPlaceholder(error.errorCodeName)
                    notifyReady()
                    adjustHeights()
                }
            }
        })
        frame.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    private fun renderWeb(frame: FrameLayout, uri: String, width: Int, height: Int, cache: Boolean, scale: Boolean, retryCount: Int = 0) {
        val tw = if (scale) context.getScaledPixels(width) else context.dpToPx(width)
        val th = if (scale) context.getScaledPixels(height) else context.dpToPx(height)
        targetMediaWidth = tw
        targetMediaHeight = th

        // Apply dimensions immediately to avoid fullscreen flash before next layout pass
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        // Clean up previous WebView if this is a retry, but keep placeholders
        mWebView?.let { oldWv ->
            oldWv.stopLoading()
            oldWv.loadUrl("about:blank")
            frame.removeView(oldWv)
            oldWv.destroy()
        }
        mWebView = null

        val wv = WebView(context).apply {
            visibility = INVISIBLE // Hide until page finished
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            mWebView = this
            webViewClient = object : WebViewClient() {
                var errorOccurred = false
                override fun onPageFinished(v: WebView?, u: String?) {
                    if (!errorOccurred) {
                        v?.visibility = VISIBLE
                        mWebView?.let { removeStaleViews(it) }
                        notifyReady()
                        adjustHeights()
                    }
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        handleWebError(error?.description?.toString() ?: "Unknown")
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onReceivedError(v: WebView?, r: Int, d: String?, u: String?) {
                    handleWebError(d ?: "Unknown")
                }

                private fun handleWebError(description: String) {
                    lastMediaError = description
                    errorOccurred = true
                    val maxRetries = this@PopupView.settings.mediaRetries
                    if (retryCount < maxRetries && !isCleanedUp) {
                        val nextRetry = retryCount + 1
                        android.util.Log.w("PopupView", "Web load failed, retrying ($nextRetry/$maxRetries): $description")
                        mainHandler.postDelayed({
                            if (!isCleanedUp) {
                                renderWeb(frame, uri, width, height, cache, scale, nextRetry)
                            }
                        }, 1000L * nextRetry)
                    } else {
                        android.util.Log.e("PopupView", "Web load failed permanently after $retryCount retries")
                        showPlaceholder(description)
                        notifyReady()
                        adjustHeights()
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                }
            }
            @SuppressLint("SetJavaScriptEnabled")
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = if (cache) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_NO_CACHE
                android.util.Log.d("PopupView", "WebView cache mode: ${if (cache) "LOAD_DEFAULT" else "LOAD_NO_CACHE"}")
            }
        }
        frame.addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        wv.onResume()
        wv.loadUrl(uri)
    }

    private fun renderWhep(frame: FrameLayout, uri: String, width: Int, height: Int, scale: Boolean, videoFit: String, retryCount: Int = 0) {
        val finalUri = if (isEmulator()) {
            if (uri.contains("127.0.0.1")) uri.replace("127.0.0.1", "10.0.2.2")
            else if (uri.contains("localhost")) uri.replace("localhost", "10.0.2.2")
            else uri
        } else uri

        try {
            val html = context.assets.open("whep.html").bufferedReader().use { it.readText() }
            val injectedHtml = html.replace(
                "const urlParams = new URLSearchParams(window.location.search);",
                "const streamUrl = '$finalUri';"
            ).replace(
                "const streamUrl = urlParams.get('url');",
                ""
            ).replace(
                "object-fit: cover;",
                "object-fit: $videoFit;"
            )

            val tw = if (scale) context.getScaledPixels(width) else context.dpToPx(width)
            val th = if (scale) context.getScaledPixels(height) else context.dpToPx(height)
            targetMediaWidth = tw
            targetMediaHeight = th

            // Apply dimensions immediately to avoid fullscreen flash before next layout pass
            frame.layoutParams.width = tw
            frame.layoutParams.height = th

            // Clean up previous WebView if this is a retry, but keep placeholders
            mWebView?.let { oldWv ->
                oldWv.stopLoading()
                oldWv.loadUrl("about:blank")
                frame.removeView(oldWv)
                oldWv.destroy()
            }
            mWebView = null

            val wv = WebView(context).apply {
                visibility = INVISIBLE // Hide until WHEP signal received
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                if (BuildConfig.DEBUG) {
                    WebView.setWebContentsDebuggingEnabled(true)
                }
                mWebView = this
                addJavascriptInterface(JsBridge(retryCount), "PiPup")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView?, u: String?) {
                        adjustHeights()
                    }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            handleWhepRetry(error?.description?.toString() ?: "Unknown", retryCount)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onReceivedError(v: WebView?, r: Int, d: String?, u: String?) {
                        handleWhepRetry(d ?: "Unknown", retryCount)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) { request.grant(request.resources) }
                }
                @SuppressLint("SetJavaScriptEnabled")
                with(settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            frame.addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
            wv.onResume()
            wv.loadDataWithBaseURL(finalUri, injectedHtml, "text/html", "UTF-8", null)
        } catch (_: Exception) {
            notifyReady()
        }
    }

    private fun renderLocalFile(frame: FrameLayout, path: String, width: Int, scale: Boolean) {
        renderGlide(frame, java.io.File(path), width, scale, DiskCacheStrategy.NONE, true)
    }

    private fun renderGlide(frame: FrameLayout, source: Any, width: Int, scale: Boolean, diskCache: DiskCacheStrategy, skipMemory: Boolean) {
        val tw = if (scale) context.getScaledPixels(width) else context.dpToPx(width)
        targetMediaWidth = tw
        frame.layoutParams.width = tw
        frame.requestLayout()

        // If caching is disabled (skipMemory is true), we use DiskCacheStrategy.DATA
        // with a unique signature instead of NONE. This ensures Glide buffers the
        // large remote images to disk during the fetch/decode process, avoiding
        // InvalidMarkException, while still forcing a fresh download from the network.
        val effectiveStrategy = if (skipMemory) DiskCacheStrategy.DATA else diskCache
        val signature = if (skipMemory) com.bumptech.glide.signature.ObjectKey(System.currentTimeMillis().toString()) else null

        android.util.Log.d("PopupView", "Glide config: strategy=$effectiveStrategy, skipMemory=$skipMemory, sig=$signature")

        val iv = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        frame.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        fun startLoad(retryCount: Int = 0) {
            if (isCleanedUp) return

            var builder = Glide.with(context.applicationContext)
                .`as`(Drawable::class.java)
                .load(source)
                .diskCacheStrategy(effectiveStrategy)
                .skipMemoryCache(skipMemory)
                .override(tw, com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
                .dontAnimate()

            if (signature != null) {
                builder = builder.signature(signature)
            }

            builder.listener(object : com.bumptech.glide.request.RequestListener<Drawable> {
                override fun onLoadFailed(e: com.bumptech.glide.load.engine.GlideException?, model: Any?, target: com.bumptech.glide.request.target.Target<Drawable>, isFirstResource: Boolean): Boolean {
                    val errorMsg = e?.message ?: "Glide load failed"
                    lastMediaError = errorMsg

                    val maxRetries = this@PopupView.settings.mediaRetries
                    if (retryCount < maxRetries && !isCleanedUp) {
                        val nextRetry = retryCount + 1
                        android.util.Log.w("PopupView", "Glide load failed, retrying ($nextRetry/$maxRetries): ${e?.message}")
                        mainHandler.postDelayed({ startLoad(nextRetry) }, 500L * nextRetry)
                        return true
                    }
                    android.util.Log.e("PopupView", "Glide load failed permanently after $retryCount retries: ${e?.message}")
                    showPlaceholder(context.getString(nl.rogro82.pipup.R.string.media_error_load_failed))
                    notifyReady()
                    return false
                }

                override fun onResourceReady(resource: Drawable, model: Any, target: com.bumptech.glide.request.target.Target<Drawable>?, dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean): Boolean {
                    if (resource.intrinsicWidth > 0) {
                        targetMediaHeight = (tw * resource.intrinsicHeight) / resource.intrinsicWidth
                    }
                    removeStaleViews(iv)
                    notifyReady()
                    adjustHeights()
                    return false
                }
            }).into(iv)
        }

        startLoad()
    }

    private fun renderBitmap(frame: FrameLayout, bitmap: Bitmap, width: Int, scale: Boolean) {
        if (bitmap.isRecycled) {
            notifyReady()
            return
        }
        val tw = if (scale) context.getScaledPixels(width) else context.dpToPx(width)
        targetMediaWidth = tw
        targetMediaHeight = (tw * bitmap.height) / bitmap.width
        frame.layoutParams.width = tw
        frame.layoutParams.height = targetMediaHeight
        frame.requestLayout()

        // Reuse existing ImageView if available to prevent flickering
        val iv = (if (frame.isNotEmpty()) frame.getChildAt(0) else null) as? ImageView ?: ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            frame.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        iv.setImageBitmap(bitmap)
        removeStaleViews(iv)
        notifyReady()
    }

    fun startMedia() {
        if (isCleanedUp) {
            android.util.Log.w("PopupView", "startMedia() called on cleaned up view, ignoring")
            return
        }
        try {
            mVideoView?.isVisible = true
            mPlayer?.play()
        } catch (e: Exception) {
            android.util.Log.e("PopupView", "Failed to start media: ${e.message}")
        }
    }

    fun cleanup() {
        if (isCleanedUp) return
        isCleanedUp = true

        mainHandler.removeCallbacksAndMessages(null)
        // Use post to avoid "You can't start or clear loads in RequestListener or Target callbacks"
        // if cleanup is called from a Glide listener.
        mainHandler.post {
            try {
                Glide.with(context.applicationContext).clear(this)
                val frame = binding.popupMediaFrame
                for (i in 0 until frame.childCount) {
                    (frame.getChildAt(i) as? ImageView)?.let { Glide.with(context.applicationContext).clear(it); it.setImageDrawable(null) }
                }
                binding.popupMediaFrame.removeAllViews()
            } catch (e: Exception) {
                android.util.Log.d("PopupView", "Glide cleanup error: ${e.message}")
            }
        }

        // Note: Do NOT recycle Bitmap here if it's the shared preview placeholder!
        // That logic should be handled by the owner (SettingsActivity).

        try {
            mPlayer?.let {
                it.stop()
                it.release()
            }
        } catch (e: Exception) {
            android.util.Log.w("PopupView", "Player release error: ${e.message}")
        }
        mPlayer = null

        (props.media as? PopupProps.Media.LocalFile)?.let {
            try { java.io.File(it.path).delete() } catch (_: Exception) {}
        }

        mWebView?.let { wv ->
            try {
                wv.onPause()
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.webChromeClient = WebChromeClient()
                wv.removeJavascriptInterface("PiPup")
                // Detach from parent before destroying
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.loadUrl("about:blank")
                // Use post to destroy after current event loop to avoid "call on destroyed"
                wv.post { try { wv.destroy() } catch (_: Exception) {} }
            } catch (e: Exception) {
                android.util.Log.d("PopupView", "WebView cleanup error: ${e.message}")
            }
        }
        mWebView = null
    }

    fun animateIn() {
        if (isCleanedUp) return
        if (!isFirstAnimateIn && alpha == 1.0f && scaleX == 1.0f && translationX == 0f && translationY == 0f) {
            // Already visible and positioned correctly, skip entrance animation to avoid blinking on overwrite
            return
        }
        isFirstAnimateIn = false
        val duration = props.animationDuration.toLong()
        resetAnimationProps()
        if (props.animationType == 0 || duration <= 0) return

        alpha = 0f
        val pos = props.getPositionEnum()

        fun applySlide() {
            val offset = (if (width > 0) width.toFloat() else context.dpToPx(400).toFloat()) + context.dpToPx(20) + 100f
            when (pos) {
                PopupProps.Position.TopRight, PopupProps.Position.BottomRight -> translationX = offset
                PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft -> translationX = -offset
                PopupProps.Position.Center -> translationY = resources.displayMetrics.heightPixels.toFloat() / 2f
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
                val metrics = resources.displayMetrics
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

        val pos = props.getPositionEnum()

        fun getSlideX(): Float {
            val margin = context.dpToPx(20).toFloat()
            return when (pos) {
                PopupProps.Position.TopRight, PopupProps.Position.BottomRight -> width + margin + 100f
                PopupProps.Position.TopLeft, PopupProps.Position.BottomLeft -> -(width + margin + 100f)
                else -> 0f
            }
        }
        fun getSlideY() = if (pos == PopupProps.Position.Center) resources.displayMetrics.heightPixels.toFloat() / 2f else 0f

        when (props.animationType) {
            1 -> animate().alpha(0f).setDuration(duration).withEndAction(completion).start()
            2, 3, 9 -> animate().translationX(getSlideX()).translationY(getSlideY()).setDuration(duration).withEndAction(completion).start()
            4, 5, 6 -> animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(duration).withEndAction(completion).start()
            7 -> animate().translationX(getSlideX()).translationY(getSlideY()).scaleX(0.5f).scaleY(0.5f).setDuration(duration).withEndAction(completion).start()
            8 -> animate().translationX(getSlideX()).translationY(getSlideY()).rotationY(-90f).setDuration(duration).withEndAction(completion).start()
            10 -> {
                val metrics = resources.displayMetrics
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

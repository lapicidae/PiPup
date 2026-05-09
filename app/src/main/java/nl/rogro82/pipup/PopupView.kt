package nl.rogro82.pipup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.HttpException
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * Base class for all popup notification views.
 * 
 * Each subclass handles a specific type of media (Image, Video, Web, etc.).
 * The view reports its readiness via the [ReadyListener] to allow the service
 * to coordinate the display timing.
 */
@SuppressLint("ViewConstructor")
sealed class PopupView(context: Context, val popup: PopupProps) : LinearLayout(context) {

    /**
     * Interface to notify when the popup media has been fully loaded.
     */
    interface ReadyListener {
        /**
         * Called when the media is ready to be displayed.
         */
        fun onReady()
    }

    /**
     * Listener to be notified when the view is ready for display.
     */
    var readyListener: ReadyListener? = null

    /**
     * Common initialization for all popup types.
     */
    open fun create() {
        inflate(context, R.layout.popup, this)

        layoutParams = LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            orientation = VERTICAL
            minimumWidth = Utils.getScaledPixels(context, 240)
        }

        val padding = Utils.getScaledPixels(context, 20)
        setPadding(padding, padding, padding, padding)

        val title = findViewById<TextView>(R.id.popup_title)
        val message = findViewById<TextView>(R.id.popup_message)
        val frame = findViewById<FrameLayout>(R.id.popup_frame)

        if (popup.media == null) {
            removeView(frame)
        }

        if (popup.title.isNullOrEmpty()) {
            removeView(title)
        } else {
            title.text = popup.title
            title.textSize = popup.titleSize
            title.setTextColor(Color.parseColor(popup.titleColor))
        }

        if (popup.message.isNullOrEmpty()) {
            removeView(message)
        } else {
            message.text = popup.message
            message.textSize = popup.messageSize
            message.setTextColor(Color.parseColor(popup.messageColor))
        }

        // Apply advanced background styling
        val backgroundDrawable = GradientDrawable().apply {
            setColor(Color.parseColor(popup.backgroundColor))
            
            if (popup.borderRadius > 0) {
                cornerRadius = Utils.getScaledPixels(context, popup.borderRadius).toFloat()
            }
            
            if (popup.borderWidth > 0) {
                setStroke(
                    Utils.getScaledPixels(context, popup.borderWidth),
                    Color.parseColor(popup.borderColor)
                )
            }
        }
        background = backgroundDrawable
    }

    /**
     * Cleans up resources when the popup is destroyed.
     */
    open fun destroy() {}

    /**
     * Default text-only popup.
     */
    private class Default(context: Context, popup: PopupProps) : PopupView(context, popup) {
        init { 
            create()
            // Text-only popups are ready immediately
            post { readyListener?.onReady() }
        }
    }

    /**
     * Video media popup.
     */
    private class Video(context: Context, popup: PopupProps, val media: PopupProps.Media.Video) : PopupView(context, popup) {
        private lateinit var mVideoView: VideoView

        init { create() }

        override fun create() {
            super.create()
            visibility = View.INVISIBLE

            val frame = findViewById<FrameLayout>(R.id.popup_frame)

            mVideoView = VideoView(context).apply {
                setVideoURI(Uri.parse(media.uri))
                setOnPreparedListener {
                    it.setOnVideoSizeChangedListener { _, _, _ ->
                        val widthPx = if (media.scale) Utils.getScaledPixels(context, media.width) else media.width
                        layoutParams = FrameLayout.LayoutParams(widthPx, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.CENTER
                        }
                        this@Video.visibility = View.VISIBLE
                        // Video is ready when the first frame is prepared and sized
                        readyListener?.onReady()
                    }
                }
                start()
            }
            frame.addView(mVideoView, FrameLayout.LayoutParams(1, 1))
        }

        override fun destroy() {
            try {
                if (mVideoView.isPlaying) {
                    mVideoView.stopPlayback()
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Image media popup using Glide.
     */
    private class Image(context: Context, popup: PopupProps, val media: PopupProps.Media.Image) : PopupView(context, popup) {
        init { create() }

        override fun create() {
            super.create()
            val frame = findViewById<FrameLayout>(R.id.popup_frame)

            try {
                val imageView = ImageView(context)
                imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                val widthPx = if (media.scale) Utils.getScaledPixels(context, media.width) else media.width
                val layoutParams = FrameLayout.LayoutParams(widthPx, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                }
                frame.addView(imageView, layoutParams)

                val uri = GlideUrl(media.uri)
                val glideRequest = Glide.with(context)
                    .`as`(Drawable::class.java)
                    .load(uri)
                    .timeout(20000)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            p0: GlideException?,
                            p1: Any?,
                            target: Target<Drawable>,
                            p3: Boolean
                        ): Boolean {
                            Log.e(LOG_TAG, "Image load failed", p0)
                            // Notify ready even on failure so the notification isn't stuck
                            readyListener?.onReady()
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            p2: Target<Drawable>?,
                            dataSource: DataSource,
                            p4: Boolean
                        ): Boolean {
                            Log.d(LOG_TAG, "Image resource ready")
                            readyListener?.onReady()
                            return false
                        }
                    })

                if (media.cache) {
                    glideRequest.diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).skipMemoryCache(false)
                } else {
                    glideRequest.diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                }

                glideRequest.into(imageView)

            } catch (e: Throwable) {
                removeView(frame)
                post { readyListener?.onReady() }
            }
        }
    }

    /**
     * Bitmap media popup (already in-memory).
     */
    private class Bitmap(context: Context, popup: PopupProps, val media: PopupProps.Media.Bitmap) : PopupView(context, popup) {
        var mImageView: ImageView? = null

        init { 
            create()
            // Bitmaps are ready immediately as they are passed as objects
            post { readyListener?.onReady() }
        }

        override fun create() {
            super.create()
            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            mImageView = ImageView(context).apply {
                setImageBitmap(media.image)
            }

            val widthPx = if (media.scale) Utils.getScaledPixels(context, media.width) else media.width
            val scaledHeight = ((widthPx.toFloat() / media.image.width) * media.image.height).toInt()
            val layoutParams = FrameLayout.LayoutParams(widthPx, scaledHeight).apply {
                gravity = Gravity.CENTER
            }
            frame.addView(mImageView, layoutParams)
        }

        override fun destroy() {
            try {
                mImageView?.setImageDrawable(null)
                media.image.recycle()
            } catch (_: Throwable) {}
        }
    }

    /**
     * Web media popup.
     */
    private class Web(context: Context, popup: PopupProps, val media: PopupProps.Media.Web) : PopupView(context, popup) {
        var mWebView: WebView? = null
        init { create() }

        @SuppressLint("SetJavaScriptEnabled")
        override fun create() {
            super.create()
            val frame = findViewById<FrameLayout>(R.id.popup_frame)
            mWebView = WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(LOG_TAG, "Web page finished loading")
                        readyListener?.onReady()
                    }
                }
                with(settings) {
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = if (media.cache) android.webkit.WebSettings.LOAD_DEFAULT else android.webkit.WebSettings.LOAD_NO_CACHE
                }
                loadUrl(media.uri)
            }

            val widthPx = if (media.scale) Utils.getScaledPixels(context, media.width) else media.width
            val heightPx = if (media.scale) Utils.getScaledPixels(context, media.height) else media.height
            val layoutParams = FrameLayout.LayoutParams(widthPx, heightPx).apply {
                gravity = Gravity.CENTER
            }
            frame.addView(mWebView, layoutParams)
        }

        override fun destroy() {
            mWebView?.also {
                it.stopLoading()
                it.loadUrl("about:blank")
                it.clearFormData()
            }
        }
    }

    companion object {
        const val LOG_TAG = "PopupView"

        /**
         * Factory method to build the appropriate PopupView based on media type.
         */
        fun build(context: Context, popup: PopupProps): PopupView {
            return when (popup.media) {
                is PopupProps.Media.Web -> Web(context, popup, popup.media)
                is PopupProps.Media.Video -> Video(context, popup, popup.media)
                is PopupProps.Media.Image -> Image(context, popup, popup.media)
                is PopupProps.Media.Bitmap -> Bitmap(context, popup, popup.media)
                else -> Default(context, popup)
            }
        }
    }
}

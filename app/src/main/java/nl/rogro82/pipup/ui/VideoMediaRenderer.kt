package nl.rogro82.pipup.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import nl.rogro82.pipup.PopupProps
import java.lang.ref.WeakReference

/**
 * Handles Media3 ExoPlayer video and RTSP stream rendering inside [MediaRenderer].
 */
@UnstableApi
internal class VideoMediaRenderer(private val host: MediaRenderer) {

    internal data class VideoListenerSpec(
        val tw: Int,
        val th: Int,
        val video: PopupProps.Media.Video,
        val retryCount: Int,
    )

    internal var player: ExoPlayer? = null
        private set
    internal var videoView: View? = null
        private set
    internal var mediaStarted: Boolean = false

    fun renderVideo(
        frame: FrameLayout,
        video: PopupProps.Media.Video,
        retryCount: Int = 0,
        videoContext: Context,
    ) {
        val (tw, th) = host.resolveStreamFrameDimensions(video.width, video.height, video.scale)
        host.callback?.onDimensionsUpdated(tw, th)
        frame.layoutParams.width = tw
        frame.layoutParams.height = th

        player?.let {
            it.stop()
            it.release()
        }
        player = null
        videoView?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val bufferMs = video.bufferMs ?: host.appSettings.defaultBufferMs.takeIf { it > 0 }
        val p = ExoPlayer.Builder(host.appContext)
            .setLoadControl(buildVideoLoadControl(bufferMs))
            .build().also { player = it }

        val tv = TextureView(videoContext).also {
            videoView = it
            // Use 0.01f (> 0f) so Android's RenderNode does not skip TextureView.draw(),
            // ensuring SurfaceTexture is created while remaining invisible during buffering.
            it.alpha = MediaRenderer.INVISIBLE_SURFACE_ALPHA
            it.isVisible = false
        }
        configurePlayerAudio(p, video.muted)
        p.setVideoTextureView(tv)
        p.repeatMode = Player.REPEAT_MODE_ONE
        preparePlayerMedia(p, video.uri, video.udp)

        p.addListener(
            VideoPlayerListener(
                this,
                WeakReference(frame),
                VideoListenerSpec(tw, th, video, retryCount),
                videoContext,
            ),
        )
        host.imageRenderer.attachPosterOrPlaceholder(videoContext, frame, video.poster, video.posterMode)
        frame.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        if (mediaStarted) {
            tv.isVisible = true
            p.play()
        }
    }

    fun startMedia() {
        mediaStarted = true
        videoView?.isVisible = true
        player?.play()
    }

    fun syncTextureViewMatchParent() {
        videoView?.let { tv ->
            val lp = tv.layoutParams as? FrameLayout.LayoutParams
            val needsMatchParent = lp != null && (
                lp.width != FrameLayout.LayoutParams.MATCH_PARENT ||
                    lp.height != FrameLayout.LayoutParams.MATCH_PARENT
                )
            if (needsMatchParent) {
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.CENTER
                tv.requestLayout()
            }
        }
    }

    fun releaseIfNotKept(keepView: View) {
        if (keepView !is TextureView) {
            cleanup()
        }
    }

    fun cleanup() {
        player?.let {
            it.stop()
            it.release()
        }
        player = null
        videoView = null
    }

    private fun buildVideoLoadControl(bufferMs: Int?): DefaultLoadControl {
        val buf = MediaRenderer.lowLatencyBufferDurations(bufferMs)
        return if (buf != null) {
            Log.d(
                "PiPup-Media",
                "Configuring ExoPlayer LoadControl with custom bufferMs=$bufferMs " +
                    "(minBuf=${buf.minMs}, maxBuf=${buf.maxMs}, " +
                    "playbackBuf=${buf.startMs}, rebufferBuf=${buf.rebufferMs})",
            )
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(buf.minMs, buf.maxMs, buf.startMs, buf.rebufferMs)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            Log.d("PiPup-Media", "Configuring ExoPlayer LoadControl with defaults (500, 1000, 250, 500)")
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(DEFAULT_MIN_BUF_MS, DEFAULT_MAX_BUF_MS, DEFAULT_START_BUF_MS, DEFAULT_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
    }

    private fun configurePlayerAudio(p: ExoPlayer, muted: Boolean) {
        if (muted) {
            // Disable audio entirely: avoids audio-codec failures (e.g. RTSP Opus) and keeps TV audio untouched.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            p.volume = 0f
        } else {
            p.volume = 1f
        }
    }

    private fun preparePlayerMedia(p: ExoPlayer, uri: String, udp: Boolean) {
        val mediaItem = MediaItem.fromUri(uri)
        if (uri.startsWith("rtsp://", ignoreCase = true) || uri.startsWith("rtsps://", ignoreCase = true)) {
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(!udp)
                .createMediaSource(mediaItem)
            p.setMediaSource(mediaSource)
        } else {
            p.setMediaItem(mediaItem)
        }
        p.prepare()
    }

    internal class VideoPlayerListener(
        videoRenderer: VideoMediaRenderer,
        private val frame: WeakReference<FrameLayout>,
        private val spec: VideoListenerSpec,
        uiContext: Context,
    ) : Player.Listener {
        private val videoRendererRef = WeakReference(videoRenderer)
        private val uiContextRef = WeakReference(uiContext)
        private var ready = false
        private var hasRenderedFirstFrame = false
        private var isPlayerReady = false
        private val startTimeMs = SystemClock.elapsedRealtime()

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val renderer = videoRendererRef.get()?.host
            val f = frame.get()
            if (renderer == null || f == null) return

            renderer.updateSourceDimensions(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            if (renderer.currentCoversScreen) {
                renderer.applyFullscreenMediaFrameSize(f)
                renderer.callback?.adjustHeights()
            } else {
                val finalHeight = MediaRenderer.heightForDisplayAspect(
                    spec.tw,
                    videoSize.width,
                    videoSize.height,
                    videoSize.pixelWidthHeightRatio,
                )
                if (finalHeight > 0 && f.layoutParams.height != finalHeight) {
                    f.layoutParams.height = finalHeight
                    f.requestLayout()
                    renderer.callback?.onDimensionsUpdated(spec.tw, finalHeight)
                    renderer.callback?.adjustHeights()
                }
            }
        }

        private fun syncVideoSizeBeforeReveal(videoRenderer: VideoMediaRenderer, f: FrameLayout) {
            val renderer = videoRenderer.host
            val vs = videoRenderer.player?.videoSize?.takeIf { it.width > 0 && it.height > 0 }
            if (renderer.currentCoversScreen) {
                vs?.let { renderer.updateSourceDimensions(it.width, it.height, it.pixelWidthHeightRatio) }
                renderer.applyFullscreenMediaFrameSize(f)
                return
            }
            val finalHeight = vs?.let {
                MediaRenderer.heightForDisplayAspect(spec.tw, it.width, it.height, it.pixelWidthHeightRatio)
            } ?: f.layoutParams.height.takeIf { it > 0 } ?: spec.th
            if (finalHeight > 0 && f.layoutParams.height != finalHeight) {
                f.layoutParams.height = finalHeight
                f.requestLayout()
                renderer.callback?.onDimensionsUpdated(spec.tw, finalHeight)
            }
        }

        private fun revealVideo(videoRenderer: VideoMediaRenderer, f: FrameLayout) {
            val renderer = videoRenderer.host
            if (ready || renderer.callback?.isCleanedUp() == true) return
            ready = true
            val revealStartElapsed = SystemClock.elapsedRealtime() - startTimeMs
            syncVideoSizeBeforeReveal(videoRenderer, f)
            videoRenderer.videoView?.let { keepView ->
                renderer.crossfadeRevealStream(f, keepView, revealStartElapsed)
            } ?: run {
                renderer.callback?.onFirstFrameRendered(revealStartElapsed)
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
        }

        override fun onRenderedFirstFrame() {
            val videoRenderer = videoRendererRef.get() ?: return
            val f = frame.get() ?: return
            hasRenderedFirstFrame = true
            val elapsed = SystemClock.elapsedRealtime() - startTimeMs
            val playerReady = isPlayerReady || videoRenderer.player?.playbackState == Player.STATE_READY
            Log.d("PiPup-Media", "onRenderedFirstFrame after $elapsed ms (isPlayerReady=$playerReady)")
            if (playerReady) {
                revealVideo(videoRenderer, f)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            val videoRenderer = videoRendererRef.get() ?: return
            val f = frame.get() ?: return
            isPlayerReady = (state == Player.STATE_READY)
            if (!ready && isPlayerReady) {
                val elapsed = SystemClock.elapsedRealtime() - startTimeMs
                val popupAlreadyVisible = videoRenderer.host.callback?.isReadyCalled() == true
                Log.d(
                    "PiPup-Media",
                    "ExoPlayer reached STATE_READY after $elapsed ms " +
                        "(hasRenderedFirstFrame=$hasRenderedFirstFrame, popupAlreadyVisible=$popupAlreadyVisible)",
                )
                if (hasRenderedFirstFrame || !popupAlreadyVisible) {
                    revealVideo(videoRenderer, f)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (ready) return
            val videoRenderer = videoRendererRef.get()
            val renderer = videoRenderer?.host
            val f = frame.get()
            val ui = uiContextRef.get()
            if (renderer == null || f == null || ui == null) return

            if (spec.retryCount < renderer.appSettings.mediaRetries && renderer.callback?.isCleanedUp() == false) {
                renderer.mainHandler.postAtTime({
                    val vr = videoRendererRef.get() ?: return@postAtTime
                    if (vr.host.callback?.isCleanedUp() == false) {
                        f.removeAllViews()
                        vr.renderVideo(f, spec.video, spec.retryCount + 1, ui)
                    }
                }, renderer.rendererToken, SystemClock.uptimeMillis() + VIDEO_RETRY_DELAY_MS * (spec.retryCount + 1))
            } else {
                renderer.callback?.showPlaceholder(error.errorCodeName)
                renderer.callback?.notifyReady()
                renderer.callback?.adjustHeights()
            }
        }
    }

    companion object {
        private const val VIDEO_RETRY_DELAY_MS = 1000L
        private const val DEFAULT_MIN_BUF_MS = 500
        private const val DEFAULT_MAX_BUF_MS = 1000
        private const val DEFAULT_START_BUF_MS = 250
        private const val DEFAULT_REBUFFER_MS = 500
    }
}

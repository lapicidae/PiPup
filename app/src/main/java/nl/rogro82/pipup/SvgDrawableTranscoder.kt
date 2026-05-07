package nl.rogro82.pipup

import android.graphics.drawable.PictureDrawable
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder
import com.caverock.androidsvg.SVG

/**
 * Transcodes an [SVG] resource into a [PictureDrawable].
 *
 * This allows Glide to convert the parsed SVG object into a standard
 * Android [PictureDrawable] that can be displayed in an [android.widget.ImageView].
 */
class SvgDrawableTranscoder : ResourceTranscoder<SVG, PictureDrawable> {

    /**
     * Converts the [SVG] resource to a [PictureDrawable].
     *
     * @param toTranscode The parsed SVG resource.
     * @param options The current load options.
     * @return A [Resource] containing the renderable [PictureDrawable].
     */
    override fun transcode(
        toTranscode: Resource<SVG>,
        options: Options
    ): Resource<PictureDrawable>? {
        val svg = toTranscode.get()
        val picture = svg.renderToPicture()
        val drawable = PictureDrawable(picture)
        return SimpleResource(drawable)
    }
}

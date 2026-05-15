package nl.rogro82.pipup

import android.util.Log
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.IOException
import java.io.InputStream

/**
 * Decodes [SVG] objects from an [InputStream].
 *
 * This decoder leverages the AndroidSVG library to parse raw SVG data
 * into a renderable [SVG] instance.
 */
class SvgDecoder : ResourceDecoder<InputStream, SVG> {

    /**
     * Determines if this decoder can handle the provided source.
     *
     * @param source The input stream to check.
     * @param options The current load options.
     * @return Always returns true, as we rely on [decode] to handle parsing errors.
     */
    override fun handles(source: InputStream, options: Options): Boolean = true

    /**
     * Decodes the [InputStream] into an [SVG] resource.
     *
     * @param source The input stream containing SVG data.
     * @param width The requested width (not used during parsing).
     * @param height The requested height (not used during parsing).
     * @param options The current load options.
     * @return A [Resource] containing the parsed [SVG], or null if decoding fails.
     * @throws IOException If the SVG data is malformed or cannot be read.
     */
    override fun decode(
        source: InputStream,
        width: Int,
        height: Int,
        options: Options
    ): Resource<SVG>? {
        Log.d("PiPupSvgDecoder", "Decoding SVG from stream...")
        return try {
            val svg = SVG.getFromInputStream(source)
            Log.d("PiPupSvgDecoder", "Successfully decoded SVG: ${svg.documentWidth}x${svg.documentHeight}")
            SimpleResource(svg)
        } catch (ex: SVGParseException) {
            Log.e("PiPupSvgDecoder", "Failed to parse SVG", ex)
            throw IOException("Failed to parse SVG from stream", ex)
        }
    }
}

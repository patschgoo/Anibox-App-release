package io.github.patschgoo.anibox.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Loads a poster by its name in library.json (the app: from the cache or the share; screenshots: fakes). */
fun interface PosterLoader {
    suspend fun load(name: String): ImageBitmap?
}

val LocalPosters = compositionLocalOf { PosterLoader { null } }

/** Posters decoded once and kept in memory (small: about 300 px wide). */
object PosterMemory {
    private val cache = LruCache<String, ImageBitmap>(160)

    fun get(name: String): ImageBitmap? = cache.get(name)

    suspend fun decode(name: String, file: File?): ImageBitmap? {
        cache.get(name)?.let { return it }
        if (file == null) return null
        return withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 300) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                    ?.also { cache.put(name, it) }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** A show's poster (2:3), or its title on a striped placeholder while there's none. */
@Composable
fun Poster(name: String, title: String, modifier: Modifier = Modifier, corner: Int = 12) {
    val loader = LocalPosters.current
    val image by produceState(if (name.isEmpty()) null else PosterMemory.get(name), name) {
        if (name.isNotEmpty() && value == null) value = loader.load(name)
    }
    val shape = RoundedCornerShape(corner.dp)
    Box(
        modifier
            .clip(shape)
            .background(AniColors.placeholder)
            .border(1.dp, AniColors.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = image
        if (bmp != null) {
            Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Stripes(Modifier.fillMaxSize())
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = AniColors.muted,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/** The PC's ".ph" placeholder: faint diagonal stripes. */
@Composable
fun Stripes(modifier: Modifier) {
    Canvas(modifier) {
        val step = 20.dp.toPx()
        val c = Color.White.copy(alpha = 0.03f)
        var x = -size.height
        while (x < size.width) {
            drawLine(c, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 10.dp.toPx())
            x += step
        }
    }
}

/** A thin progress line (accent on a dark track). */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = AniColors.acc) {
    Canvas(modifier.height(4.dp)) {
        val y = size.height / 2
        drawLine(Color.White.copy(alpha = 0.14f), Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
        if (fraction > 0f) {
            drawLine(color, Offset(0f, y), Offset(size.width * fraction.coerceIn(0f, 1f), y), strokeWidth = size.height, cap = StrokeCap.Round)
        }
    }
}

/** Small uppercase accent label (the PC's ".kicker"). */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = AniColors.acc) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        fontSize = if (LocalTv.current) 14.sp else 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        fontFamily = Inter,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A rounded pill with a short status text. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = AniColors.acc) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .clip(shape)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** AniBox's logo: a TV with a play sign (web/icon.svg). */
@Composable
fun Logo(modifier: Modifier = Modifier) {
    Canvas(modifier.size(28.dp)) {
        val s = size.minDimension / 32f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * s, cap = StrokeCap.Round)
        drawRoundRect(
            AniColors.acc,
            topLeft = Offset(3 * s, 7 * s),
            size = androidx.compose.ui.geometry.Size(26 * s, 19 * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5 * s),
            style = stroke,
        )
        val antenna = androidx.compose.ui.graphics.Path().apply {
            moveTo(11 * s, 3.5f * s); lineTo(16 * s, 7 * s); lineTo(21 * s, 3.5f * s)
        }
        drawPath(antenna, AniColors.acc, style = stroke)
        val play = androidx.compose.ui.graphics.Path().apply {
            moveTo(14 * s, 12.5f * s); lineTo(14 * s, 20.5f * s); lineTo(20.5f * s, 16.5f * s); close()
        }
        drawPath(play, AniColors.acc)
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int = 8) = Spacer(Modifier.width(w.dp))

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = AniColors.text, modifier = modifier.fillMaxWidth())
}

/** "1:02:03" / "24:05" for seconds. */
fun clock(seconds: Double): String {
    val t = maxOf(0, seconds.toInt())
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "24 min" / "1 h 40 min". */
fun minutes(seconds: Double): String {
    val m = Math.round(seconds / 60.0).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

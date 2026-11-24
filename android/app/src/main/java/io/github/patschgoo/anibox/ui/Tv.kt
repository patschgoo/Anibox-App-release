package io.github.patschgoo.anibox.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.patschgoo.anibox.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** True on a TV (the Shield, Android TV, Google TV): bigger text, a side menu, remote-first. */
val LocalTv = staticCompositionLocalOf { false }

fun isTv(context: Context): Boolean {
    val ui = context.getSystemService(UiModeManager::class.java)
    if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/** A camera that can scan the PC's QR code (TVs have none). */
fun hasCamera(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

/**
 * The remote's focus: a 3 dp white ring with a 3 dp gap around the element, and a slight grow (like the
 * TV mockups and CastApp TV). Put it before `clickable` in the chain. Touch never focuses a clickable, so
 * phones only see it with a keyboard or a D-pad.
 *
 * [inset] draws the ring inside the bounds and doesn't grow: for rows as wide as the screen.
 */
fun Modifier.focusRing(corner: Dp = 12.dp, inset: Boolean = false, grow: Float = 1.04f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && !inset) grow else 1f, label = "focus")
    this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .drawWithContent {
            drawContent()
            if (focused) {
                val w = RING_WIDTH.toPx()
                val o = if (inset) -w / 2 else RING_GAP.toPx() + w / 2
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(-o, -o),
                    size = Size(size.width + 2 * o, size.height + 2 * o),
                    cornerRadius = CornerRadius((corner.toPx() + o).coerceAtLeast(0f)),
                    style = Stroke(w),
                )
            }
        }
}

private val RING_WIDTH = 3.dp
private val RING_GAP = 3.dp

/**
 * Gives this element the remote's focus when it first shows (on a TV only), so the first press of the
 * remote does something. Put it before `clickable`.
 */
fun Modifier.firstFocus(enabled: Boolean, key: Any? = Unit): Modifier = composed {
    if (!enabled) return@composed this
    val requester = remember { FocusRequester() }
    LaunchedEffect(key) {
        // Lazy rows place their items a frame later.
        repeat(5) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
    this.focusRequester(requester)
}

@Composable
fun tvOr(tv: Int, phone: Int): Dp = (if (LocalTv.current) tv else phone).dp

/** The TV's side menu (Home, Library, Settings: [selected] counts from 0), like the TV mockup. */
@Composable
fun TvMenu(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier
            .width(176.dp)
            .fillMaxHeight()
            .background(AniColors.s1)
            .padding(horizontal = 16.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo()
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.brand), style = MaterialTheme.typography.titleLarge, color = AniColors.text)
        }
        TvMenuItem(Icons.Filled.Home, stringResource(R.string.tab_home), selected == 0) { onSelect(0) }
        TvMenuItem(Icons.Filled.VideoLibrary, stringResource(R.string.tab_library), selected == 1) { onSelect(1) }
        Spacer(Modifier.weight(1f))
        TvMenuItem(Icons.Filled.Settings, stringResource(R.string.tab_settings), selected == 2) { onSelect(2) }
    }
}

@Composable
private fun TvMenuItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val color = if (selected) AniColors.acc else AniColors.soft
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .focusRing(12.dp, grow = 1.02f)
            .clip(shape)
            .background(if (selected) AniColors.s2 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

package io.github.patschgoo.anibox.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.patschgoo.anibox.R
import io.github.patschgoo.anibox.ui.AniColors
import io.github.patschgoo.anibox.ui.Field
import io.github.patschgoo.anibox.ui.Gap
import io.github.patschgoo.anibox.ui.HGap
import io.github.patschgoo.anibox.ui.PrimaryButton
import io.github.patschgoo.anibox.ui.QuietButton
import io.github.patschgoo.anibox.ui.clock
import io.github.patschgoo.anibox.ui.focusRing
import io.github.patschgoo.anibox.ui.LocalTv
import io.github.patschgoo.anibox.ui.ProgressLine
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay

/** Everything the player's controls show. */
data class PlayerUi(
    val title: String = "",
    val episode: String = "",
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val loading: Boolean = false,
    val controls: Boolean = true,
    val tracks: List<Track> = emptyList(),
    val decoder: String = "",
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    /** Seconds until the next episode starts by itself, or null. */
    val countdown: Int? = null,
    val nextLabel: String = "",
    val finished: Boolean = false,
    val error: String? = null,
    /** A share that refused its sign-in: ask for its own user name and password. */
    val signInShare: String? = null,
    val signInHost: String? = null,
)

interface PlayerControlActions {
    fun togglePause()
    fun seekTo(seconds: Double)
    fun seekBy(seconds: Double)
    fun toggleControls()
    fun showControls()
    fun next()
    fun previous()
    fun playNow()
    fun cancelCountdown()
    fun audio(track: Track)
    fun subtitle(track: Track?)
    fun close()
    fun retry()
    fun sendDebug() {}
    fun signIn(user: String, password: String)
}

private enum class Picker { AUDIO, SUBS }

@Composable
fun PlayerControls(ui: PlayerUi, actions: PlayerControlActions) {
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    /** Jumps added up while the hint shows (holding Right: "+40 s"). */
    var jumped by remember { mutableStateOf(0.0) }
    /** Counts the remote's presses, so the buttons stay while it's being used. */
    var presses by remember { mutableStateOf(0) }
    val buttonsShown = ui.controls || ui.error != null
    val tv = LocalTv.current
    val rootFocus = remember { FocusRequester() }
    val primary = remember { FocusRequester() }

    // The controls hide by themselves after a few seconds of playing.
    LaunchedEffect(ui.controls, ui.paused, seeking, picker, presses) {
        if (ui.controls && !ui.paused && seeking == null && picker == null && ui.countdown == null && !ui.finished && ui.error == null) {
            delay(if (tv) 5000 else 4000)
            actions.toggleControls()
        }
    }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(if (tv) 1200 else 700)
            seekHint = null
            jumped = 0.0
        }
    }
    fun jump(by: Double) {
        actions.seekBy(by)
        jumped += by
        seekHint = PlayerKeys.jumpLabel(jumped)
    }
    // The remote: hidden buttons -> the whole screen listens; shown -> the main button gets the white ring.
    val mode = when {
        ui.error != null -> if (ui.signInShare != null) "signin" else "error"
        ui.countdown != null -> "countdown"
        ui.finished -> "finished"
        else -> "playing"
    }
    LaunchedEffect(buttonsShown, mode) {
        if (buttonsShown && mode == "signin") return@LaunchedEffect // the user name box comes first
        repeat(5) {
            // The screen itself first (touch mode on phones: the buttons can't take focus, the screen can, so a
            // headset's play/pause still arrives), then the main button when the buttons show.
            val ok = runCatching {
                rootFocus.requestFocus()
                if (buttonsShown) primary.requestFocus()
            }.isSuccess
            if (ok) return@LaunchedEffect
            delay(60)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                if (picker != null || ui.error != null) return@onPreviewKeyEvent false
                val key = PlayerKeys.map(ev.nativeKeyEvent.keyCode, buttonsShown)
                if (ev.type == KeyEventType.KeyDown) presses++
                if (key == null) return@onPreviewKeyEvent false
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true // the release of a key handled here
                when (key) {
                    PlayerKey.TOGGLE -> {
                        if (!ui.paused) actions.showControls()
                        actions.togglePause()
                    }
                    PlayerKey.PLAY -> if (ui.paused) actions.togglePause()
                    PlayerKey.PAUSE -> if (!ui.paused) { actions.showControls(); actions.togglePause() }
                    PlayerKey.BACK -> jump(-PlayerKeys.JUMP)
                    PlayerKey.FORWARD -> jump(PlayerKeys.JUMP)
                    PlayerKey.NEXT -> if (ui.hasNext) actions.next()
                    PlayerKey.PREVIOUS -> if (ui.hasPrevious) actions.previous()
                    PlayerKey.SHOW -> actions.showControls()
                    PlayerKey.CLOSE -> actions.close()
                }
                true
            }
            .focusRequester(rootFocus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { actions.toggleControls() },
                    onDoubleTap = { o ->
                        if (o.x < size.width / 2f) {
                            actions.seekBy(-10.0); seekHint = "−10 s"
                        } else {
                            actions.seekBy(10.0); seekHint = "+10 s"
                        }
                    },
                )
            },
    ) {
        if (ui.loading || ui.buffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = AniColors.acc, strokeWidth = 3.dp)
        }
        seekHint?.let {
            Text(
                it,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(14.dp),
            )
            // Buttons hidden (the remote's jumps): where in the episode, at the bottom.
            if (!buttonsShown && ui.duration > 0) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 48.dp, vertical = 27.dp)) {
                    ProgressLine((ui.position / ui.duration).toFloat(), Modifier.fillMaxWidth())
                    Gap(6)
                    Text(clock(ui.position) + " / " + clock(ui.duration), style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
        }
        AnimatedVisibility(ui.controls || ui.error != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).safeDrawingPadding()) {
                // top: back, title, tracks
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(modifier = Modifier.focusRing(24.dp), onClick = actions::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(ui.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (ui.episode.isNotEmpty()) {
                            Text(ui.episode, style = MaterialTheme.typography.bodySmall, color = AniColors.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(modifier = Modifier.focusRing(24.dp), onClick = { picker = Picker.AUDIO }, enabled = ui.tracks.any { it.type == "audio" }) {
                        Icon(Icons.Filled.Audiotrack, stringResource(R.string.player_audio), tint = Color.White)
                    }
                    IconButton(modifier = Modifier.focusRing(24.dp), onClick = { picker = Picker.SUBS }, enabled = ui.tracks.isNotEmpty()) {
                        Icon(Icons.Filled.Subtitles, stringResource(R.string.player_subtitles), tint = Color.White)
                    }
                }
                // middle: previous, back 10, play/pause, forward 10, next
                if (ui.error == null && ui.countdown == null && !ui.finished) {
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        RoundButton(Icons.Filled.SkipPrevious, stringResource(R.string.player_previous), enabled = ui.hasPrevious, onClick = actions::previous)
                        RoundButton(Icons.Filled.Replay10, stringResource(R.string.player_back10)) { actions.seekBy(-10.0) }
                        RoundButton(
                            if (ui.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            stringResource(if (ui.paused) R.string.common_play else R.string.player_pause),
                            big = true,
                            modifier = Modifier.focusRequester(primary),
                            onClick = actions::togglePause,
                        )
                        RoundButton(Icons.Filled.Forward10, stringResource(R.string.player_forward10)) { actions.seekBy(10.0) }
                        RoundButton(Icons.Filled.SkipNext, stringResource(R.string.player_next), enabled = ui.hasNext, onClick = actions::next)
                    }
                }
                // the end of the episode, or a problem
                Column(Modifier.align(Alignment.Center).widthIn(max = 460.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        ui.error != null -> ErrorPanel(ui, actions, Modifier.focusRequester(primary))
                        ui.countdown != null -> {
                            Text(stringResource(R.string.player_next_in, ui.nextLabel, ui.countdown), style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Gap(12)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PrimaryButton(stringResource(R.string.player_play_now), Modifier.focusRequester(primary), onClick = actions::playNow)
                                QuietButton(stringResource(R.string.common_cancel), onClick = actions::cancelCountdown)
                            }
                        }
                        ui.finished -> {
                            Text(
                                if (ui.nextLabel.isNotEmpty()) stringResource(R.string.player_finished_next, ui.nextLabel) else stringResource(R.string.player_last),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                            )
                            Gap(12)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (ui.nextLabel.isNotEmpty()) {
                                    PrimaryButton(stringResource(R.string.player_play_next), Modifier.focusRequester(primary), onClick = actions::playNow)
                                    QuietButton(stringResource(R.string.common_close), onClick = actions::close)
                                } else {
                                    QuietButton(stringResource(R.string.common_close), Modifier.focusRequester(primary), onClick = actions::close)
                                }
                            }
                        }
                    }
                }
                // bottom: the seek bar with the times
                if (ui.error == null && ui.duration > 0) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val shown = seeking?.toDouble() ?: ui.position
                        Slider(
                            value = (shown / ui.duration).toFloat().coerceIn(0f, 1f),
                            onValueChange = { seeking = (it * ui.duration).toFloat() },
                            onValueChangeFinished = {
                                seeking?.let { actions.seekTo(it.toDouble()) }
                                seeking = null
                            },
                            colors = SliderDefaults.colors(thumbColor = AniColors.acc, activeTrackColor = AniColors.acc, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                            // The remote on the bar: Left/Right jump 10 seconds (not 1 % of the episode).
                            modifier = Modifier.focusRing(14.dp, inset = true).onPreviewKeyEvent { ev ->
                                val dir = when (ev.nativeKeyEvent.keyCode) {
                                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
                                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                                    else -> return@onPreviewKeyEvent false
                                }
                                if (ev.type == KeyEventType.KeyDown) jump(dir * PlayerKeys.JUMP)
                                true
                            },
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(clock(shown), style = MaterialTheme.typography.labelMedium, color = Color.White)
                            Box(Modifier.weight(1f))
                            if (ui.decoder.isNotEmpty()) {
                                Text(decoderLabel(ui.decoder), style = MaterialTheme.typography.labelSmall, color = AniColors.muted)
                                HGap(12)
                            }
                            Text("−" + clock(ui.duration - shown), style = MaterialTheme.typography.labelMedium, color = Color.White)
                        }
                        if (tv) {
                            Gap(6)
                            Text(stringResource(R.string.player_tv_hint), style = MaterialTheme.typography.labelMedium, color = AniColors.soft)
                        }
                    }
                }
            }
        }
    }
    when (picker) {
        Picker.AUDIO -> TrackDialog(stringResource(R.string.player_audio), ui.tracks.filter { it.type == "audio" }, allowOff = false, onPick = { t ->
            picker = null
            t?.let(actions::audio)
        }) { picker = null }
        Picker.SUBS -> TrackDialog(stringResource(R.string.player_subtitles), ui.tracks.filter { it.type == "sub" }, allowOff = true, onPick = { t ->
            picker = null
            actions.subtitle(t)
        }) { picker = null }
        null -> {}
    }
}

@Composable
private fun decoderLabel(d: String): String = when {
    d.startsWith("mediacodec") -> stringResource(R.string.player_decoder_hw)
    d == "no" || d.isEmpty() -> stringResource(R.string.player_decoder_sw)
    else -> d
}

@Composable
private fun ErrorPanel(ui: PlayerUi, actions: PlayerControlActions, retryFocus: Modifier) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(AniColors.s1).padding(18.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(ui.error ?: "", style = MaterialTheme.typography.bodyMedium, color = AniColors.text)
        if (ui.signInShare != null) {
            var user by rememberSaveable { mutableStateOf("") }
            var password by rememberSaveable { mutableStateOf("") }
            Gap(10)
            Text(stringResource(R.string.player_share_signin, ui.signInShare), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
            Gap(8)
            Field(user, { user = it }, stringResource(R.string.setup_user), "")
            Gap(8)
            androidx.compose.material3.OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.setup_password)) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors = io.github.patschgoo.anibox.ui.fieldColors(),
            )
            Gap(12)
            PrimaryButton(stringResource(R.string.setup_connect)) { actions.signIn(user, password) }
        } else {
            Gap(12)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.common_try_again), retryFocus, onClick = actions::retry)
                QuietButton(stringResource(R.string.common_close), onClick = actions::close)
            }
            Gap(10)
            QuietButton(stringResource(R.string.debug_send), onClick = actions::sendDebug)
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    big: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val size = if (big) 68.dp else 52.dp
    Box(
        modifier
            .focusRing(size / 2)
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (big) 0.55f else 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(if (big) 40.dp else 28.dp))
    }
}

@Composable
private fun TrackDialog(title: String, tracks: List<Track>, allowOff: Boolean, onPick: (Track?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (allowOff) TrackRow(stringResource(R.string.player_subs_off), tracks.none { it.selected }) { onPick(null) }
                if (tracks.isEmpty()) Text(stringResource(R.string.player_no_tracks), color = AniColors.muted, style = MaterialTheme.typography.bodyMedium)
                for (t in tracks) TrackRow(t.label, t.selected) { onPick(t) }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.focusRing(20.dp), onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        containerColor = AniColors.s1,
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRing(8.dp, inset = true).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, null, tint = AniColors.acc)
        }
        HGap(8)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) AniColors.acc else AniColors.text)
    }
}

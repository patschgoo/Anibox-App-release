package io.github.patschgoo.anibox.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.patschgoo.anibox.R
import io.github.patschgoo.anibox.library.Watching
import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.LocalProgress
import io.github.patschgoo.anibox.sync.Show

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShowScreen(
    show: Show,
    progress: Map<String, LocalProgress>,
    playable: (Episode) -> Boolean,
    onBack: () -> Unit,
    onPlay: (Episode, Boolean) -> Unit,
    onWatched: (List<String>, Boolean) -> Unit,
) {
    val next = remember(show, progress) { Watching.nextUp(show, progress, playable) }
    val watched = show.episodes.count { progress[it.key]?.watched == true }
    var more by rememberSaveable(show.id) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var cut by remember(show.id) { mutableStateOf(false) }
    val tv = LocalTv.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(modifier = Modifier.focusRing(24.dp), onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                Text(show.library, style = MaterialTheme.typography.labelLarge, color = AniColors.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    IconButton(modifier = Modifier.focusRing(24.dp), onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.show_mark_all)) }, onClick = {
                            showMenu = false
                            onWatched(show.episodes.map { it.key }, true)
                        })
                        if (watched > 0) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.show_unmark_all)) }, onClick = {
                                showMenu = false
                                onWatched(show.episodes.filter { progress[it.key]?.watched == true }.map { it.key }, false)
                            })
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Poster(show.poster, show.title, Modifier.width(tvOr(150, 120)).aspectRatio(2f / 3f))
                HGap(16)
                Column(Modifier.weight(1f)) {
                    val kicker = listOfNotNull(show.type.takeIf { it.isNotEmpty() }, show.year?.toString()).joinToString(" · ")
                    if (kicker.isNotEmpty()) Kicker(kicker)
                    Gap(4)
                    Text(show.title, style = MaterialTheme.typography.headlineSmall, color = AniColors.text)
                    val alt = listOf(show.englishTitle, show.originalTitle).filter { it.isNotEmpty() && it != show.title }.distinct()
                    for (a in alt) {
                        Gap(2)
                        Text(a, style = MaterialTheme.typography.bodySmall, color = AniColors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Gap(6)
                    if (show.studio.isNotEmpty()) Text(show.studio, style = MaterialTheme.typography.bodySmall, color = AniColors.soft, maxLines = 2)
                    Text(
                        pluralStringResource(R.plurals.episodes_watched, show.episodes.size, watched, show.episodes.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = AniColors.muted,
                    )
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (next != null) {
                    val label = if (next.start > 0) stringResource(R.string.show_resume, next.episode.label, clock(next.start))
                    else stringResource(R.string.show_play, next.episode.label)
                    if (tv) {
                        // The remote starts here: OK plays, Right offers the start of the episode.
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(6.dp)) {
                            PrimaryButton(label, Modifier.firstFocus(true, show.id)) { onPlay(next.episode, false) }
                            if (next.start > 0) QuietButton(stringResource(R.string.show_play_start)) { onPlay(next.episode, true) }
                        }
                    } else {
                        PrimaryButton(label, Modifier.fillMaxWidth()) { onPlay(next.episode, false) }
                    }
                } else if (show.episodes.isNotEmpty()) {
                    Text(
                        stringResource(if (show.episodes.none(playable)) R.string.show_only_pc else R.string.show_all_watched),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AniColors.muted,
                    )
                }
                if (show.genres.isNotEmpty()) {
                    Gap(14)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        show.genres.take(10).forEach { g -> Pill(g, color = AniColors.soft) }
                    }
                }
                if (show.description.isNotEmpty()) {
                    Gap(14)
                    Text(
                        show.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AniColors.soft,
                        maxLines = if (more) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!more) cut = it.hasVisualOverflow },
                        modifier = Modifier.animateContentSize().focusRing(8.dp, inset = true).clickable { more = !more },
                    )
                    if (!more && cut) {
                        Text(stringResource(R.string.show_more), color = AniColors.acc, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp).clickable { more = true })
                    }
                }
                Gap(18)
                SectionTitle(stringResource(R.string.show_episodes))
            }
        }
        itemsIndexed(show.episodes, key = { _, e -> e.key }) { _, e ->
            EpisodeRow(e, progress[e.key], playable(e), next?.episode?.key == e.key, onPlay, onWatched)
        }
    }
}

@Composable
private fun EpisodeRow(
    e: Episode,
    p: LocalProgress?,
    playable: Boolean,
    isNext: Boolean,
    onPlay: (Episode, Boolean) -> Unit,
    onWatched: (List<String>, Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val watched = p?.watched == true
    val duration = (p?.duration ?: 0.0).takeIf { it > 0 } ?: e.duration ?: 0.0
    val pos = p?.position ?: 0.0
    val alpha = if (playable) 1f else 0.45f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .focusRing(10.dp, inset = true)
            .clickable(enabled = playable) { onPlay(e, false) }
            .background(if (isNext) AniColors.s1 else AniColors.bg)
            .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = CircleShape
        Box(
            Modifier
                .size(34.dp)
                .clip(shape)
                .background(if (watched) AniColors.green.copy(alpha = 0.12f) else AniColors.s2)
                .border(1.dp, if (watched) AniColors.green.copy(alpha = 0.5f) else AniColors.line2, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (watched) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.show_watched), tint = AniColors.green, modifier = Modifier.size(18.dp))
            else Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = if (playable) AniColors.acc else AniColors.muted, modifier = Modifier.size(18.dp))
        }
        HGap(14)
        Column(Modifier.weight(1f)) {
            Text(
                e.label,
                style = MaterialTheme.typography.titleSmall,
                color = AniColors.text.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                e.title.takeIf { it.isNotEmpty() },
                if (!playable) stringResource(R.string.show_only_pc_one) else null,
                if (duration > 0) minutes(duration) else null,
            ).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = AniColors.muted.copy(alpha = alpha), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!watched && pos > 0 && duration > 0) {
                Gap(6)
                ProgressLine((pos / duration).toFloat(), Modifier.fillMaxWidth(0.85f))
            }
        }
        Box {
            IconButton(modifier = Modifier.focusRing(24.dp), onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.common_more), tint = AniColors.muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (playable) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.show_play_start)) }, onClick = { menu = false; onPlay(e, true) })
                }
                if (watched) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.show_unmark)) }, onClick = { menu = false; onWatched(listOf(e.key), false) })
                } else {
                    DropdownMenuItem(text = { Text(stringResource(R.string.show_mark)) }, onClick = { menu = false; onWatched(listOf(e.key), true) })
                }
            }
        }
    }
}

/** A rounded box for small grouped content. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(AniColors.s1).border(1.dp, AniColors.line, shape).padding(16.dp)) { content() }
}

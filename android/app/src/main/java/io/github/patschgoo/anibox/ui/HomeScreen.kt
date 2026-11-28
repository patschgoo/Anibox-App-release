package io.github.patschgoo.anibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.patschgoo.anibox.R
import io.github.patschgoo.anibox.data.SyncStatus
import io.github.patschgoo.anibox.library.Browse
import io.github.patschgoo.anibox.library.Sort
import io.github.patschgoo.anibox.library.Watching
import io.github.patschgoo.anibox.net.Problem
import io.github.patschgoo.anibox.sync.Episode
import io.github.patschgoo.anibox.sync.Library
import io.github.patschgoo.anibox.sync.LocalProgress
import io.github.patschgoo.anibox.sync.Show
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    library: Library?,
    progress: Map<String, LocalProgress>,
    status: SyncStatus,
    onSync: () -> Unit,
    onSignInAgain: () -> Unit,
    onPlay: (Show, Episode) -> Unit,
    onShow: (Show) -> Unit,
    onLibrary: (String) -> Unit,
    /** The show opened last: its card gets the remote's focus again when coming back (TV). */
    focusShow: String? = null,
) {
    val tv = LocalTv.current
    val playable: (Episode) -> Boolean = { e -> library?.playable(e) == true }
    val cont = remember(library, progress) { library?.let { Watching.continueWatching(it.shows, progress, 20, playable) }.orEmpty() }
    PullToRefreshBox(isRefreshing = status == SyncStatus.Running, onRefresh = onSync, modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // The remote starts on the show opened last, else on the first card.
        val target = cont.indexOfFirst { it.show.id == focusShow }.takeIf { it >= 0 } ?: 0
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tv) {
                    // The side menu shows the name already.
                    Text(stringResource(R.string.tab_home), style = MaterialTheme.typography.headlineSmall, color = AniColors.text)
                } else {
                    Logo()
                    HGap(10)
                    Text(stringResource(R.string.brand), style = MaterialTheme.typography.headlineSmall, color = AniColors.text)
                }
                HGap(12)
                Box(Modifier.weight(1f)) { StatusPill(status) }
                IconButton(modifier = Modifier.focusRing(24.dp), onClick = onSync, enabled = status != SyncStatus.Running) {
                    if (status == SyncStatus.Running) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AniColors.acc)
                    } else {
                        Icon(Icons.Filled.Sync, contentDescription = stringResource(R.string.common_sync_now), tint = AniColors.soft)
                    }
                }
            }
        }
        item { StatusBanner(status, onSync, onSignInAgain) }
        if (library == null) {
            item {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        stringResource(if (status == SyncStatus.Running) R.string.home_loading else R.string.home_no_library),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AniColors.muted,
                    )
                }
            }
            return@LazyColumn
        }
        if (cont.isNotEmpty()) {
            item {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp)) {
                    Kicker(stringResource(R.string.home_continue_kicker))
                    Gap(4)
                    SectionTitle(stringResource(R.string.home_continue))
                }
            }
            item {
                LazyRow(
                    state = androidx.compose.foundation.lazy.rememberLazyListState(target),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(tvOr(20, 14)),
                ) {
                    itemsIndexed(cont, key = { _, c -> c.show.id }) { i, c ->
                        ContinueCard(c, progress, onPlay, onShow, Modifier.firstFocus(tv && i == target, focusShow))
                    }
                }
            }
        } else {
            item {
                Text(
                    stringResource(R.string.home_nothing_started),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AniColors.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
        for (section in library.libraries) {
            val shows = library.shows.filter { it.library == section.name }
            if (shows.isEmpty()) continue
            val first = tv && cont.isEmpty() && section == library.libraries.firstOrNull { l -> library.shows.any { it.library == l.name } }
            item(key = "lib-" + section.name) {
                LibraryRow(section.name, kindLabel(section.kind), shows, progress, onShow, first) { onLibrary(section.name) }
            }
        }
    }
    }
}

@Composable
fun kindLabel(kind: String): String = when (kind) {
    "anime" -> stringResource(R.string.kind_anime)
    "movies" -> stringResource(R.string.kind_movies)
    "series" -> stringResource(R.string.kind_series)
    else -> ""
}

@Composable
private fun ContinueCard(
    c: Watching.Card,
    progress: Map<String, LocalProgress>,
    onPlay: (Show, Episode) -> Unit,
    onShow: (Show) -> Unit,
    focus: Modifier = Modifier,
) {
    val next = c.next ?: return
    val state = Watching.state(next.episode, progress)
    // OK on the poster plays; the title below opens the show.
    Column(Modifier.width(tvOr(132, 150))) {
        Box(focus.focusRing().clickable { onPlay(c.show, next.episode) }) {
            Poster(c.show.poster, c.show.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(1.dp, AniColors.acc.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.common_play), tint = AniColors.acc) }
            if (next.start > 0 && state.duration > 0) {
                ProgressLine(state.fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
            }
        }
        Gap(8)
        Column(Modifier.focusRing(8.dp).clickable { onShow(c.show) }) {
            Text(c.show.title, style = MaterialTheme.typography.titleSmall, color = AniColors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Gap(2)
            val line = if (next.start > 0 && state.duration > 0) {
                stringResource(R.string.home_left, next.episode.label, minutes(state.duration - next.start))
            } else {
                stringResource(R.string.home_next, next.episode.label)
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = AniColors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibraryRow(
    name: String,
    kind: String,
    shows: List<Show>,
    progress: Map<String, LocalProgress>,
    onShow: (Show) -> Unit,
    focusFirst: Boolean,
    onAll: () -> Unit,
) {
    val sorted = remember(shows, progress) { Browse.sort(shows, Sort.RECENT, progress).take(20) }
    Column(Modifier.padding(top = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (kind.isNotEmpty() && !kind.equals(name, ignoreCase = true)) Kicker(kind)
                Text(name, style = MaterialTheme.typography.titleLarge, color = AniColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.shows, shows.size, shows.size), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
            }
            TextButton(modifier = Modifier.focusRing(20.dp), onClick = onAll) { Text(stringResource(R.string.home_see_all)) }
        }
        Gap(8)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(sorted, key = { _, s -> s.id }) { i, s ->
                Column(Modifier.width(110.dp).firstFocus(focusFirst && i == 0).focusRing(10.dp).clickable { onShow(s) }) {
                    Poster(s.poster, s.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f), corner = 10)
                    Gap(6)
                    Text(s.title, style = MaterialTheme.typography.bodySmall, color = AniColors.soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun StatusPill(status: SyncStatus) {
    when (status) {
        SyncStatus.Never -> {}
        SyncStatus.Running -> Pill(stringResource(R.string.status_running))
        is SyncStatus.Done -> Pill(stringResource(R.string.status_done, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.time))), color = AniColors.green)
        is SyncStatus.TooNew -> Pill(stringResource(R.string.status_too_new_short), color = AniColors.orange)
        is SyncStatus.Failed -> Pill(stringResource(R.string.status_failed), color = AniColors.orange)
    }
}

@Composable
fun StatusBanner(status: SyncStatus, onRetry: () -> Unit, onSignInAgain: () -> Unit) {
    val (text, problem) = when (status) {
        is SyncStatus.TooNew -> stringResource(R.string.status_too_new) to null
        is SyncStatus.Failed -> status.message to status.problem
        else -> return
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(AniColors.orange.copy(alpha = 0.07f))
            .border(1.dp, AniColors.orange.copy(alpha = 0.3f), shape)
            .padding(14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = AniColors.text)
        if (status is SyncStatus.Failed) {
            Gap(2)
            Text(stringResource(R.string.status_offline_note), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
            Row {
                TextButton(modifier = Modifier.focusRing(20.dp), onClick = onRetry) { Text(stringResource(R.string.common_try_again)) }
                if (problem == Problem.WRONG_SIGN_IN) TextButton(modifier = Modifier.focusRing(20.dp), onClick = onSignInAgain) { Text(stringResource(R.string.settings_sign_in_again)) }
            }
        }
    }
}

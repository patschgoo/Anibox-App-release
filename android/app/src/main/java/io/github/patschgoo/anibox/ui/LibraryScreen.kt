package io.github.patschgoo.anibox.ui

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.patschgoo.anibox.R
import io.github.patschgoo.anibox.library.Browse
import io.github.patschgoo.anibox.library.Kind
import io.github.patschgoo.anibox.library.Sort
import io.github.patschgoo.anibox.sync.Library
import io.github.patschgoo.anibox.sync.LocalProgress
import io.github.patschgoo.anibox.sync.Show

/** What the Library page shows; kept by the caller so it survives switching tabs. */
data class LibraryFilter(
    val kind: Kind? = null,
    val atype: String = "",
    val library: String = "",
    val query: String = "",
    val sort: Sort = Sort.TITLE,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    library: Library?,
    progress: Map<String, LocalProgress>,
    filter: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    onShow: (Show) -> Unit,
    syncing: Boolean = false,
    onSync: () -> Unit = {},
) {
    val all = library?.shows.orEmpty()
    val kinds = remember(all) { Browse.kinds(all) }
    val kind = filter.kind?.takeIf { it in kinds } ?: kinds.firstOrNull()
    val inKind = remember(all, kind) { all.filter { kind == null || Browse.kindOf(it) == kind } }
    val libs = remember(inKind) { inKind.map { it.library }.distinct().sorted() }
    val lib = filter.library.takeIf { it in libs } ?: ""
    val inLib = remember(inKind, lib) { if (lib.isEmpty()) inKind else inKind.filter { it.library == lib } }
    val types = remember(inLib, kind) { if (kind == Kind.ANIME) Browse.animeTypes(inLib) else emptyList() }
    val atype = filter.atype.takeIf { t -> types.any { it.first == t } } ?: ""
    val shown = remember(inLib, kind, atype, filter.query, filter.sort, progress) {
        Browse.sort(Browse.filter(inLib, kind, atype, filter.query), filter.sort, progress)
    }
    var sortOpen by remember { mutableStateOf(false) }
    val tv = LocalTv.current
    var searching by rememberSaveable { mutableStateOf(filter.query.isNotEmpty()) }

    PullToRefreshBox(isRefreshing = syncing, onRefresh = onSync, modifier = Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tab_library), style = MaterialTheme.typography.headlineMedium, color = AniColors.text, modifier = Modifier.weight(1f))
                    IconButton(modifier = Modifier.focusRing(24.dp), onClick = { searching = !searching; if (!searching) onFilter(filter.copy(query = "")) }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = stringResource(R.string.lib_search))
                    }
                    Box {
                        IconButton(modifier = Modifier.focusRing(24.dp), onClick = { sortOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.lib_sort))
                        }
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            for (s in Sort.entries) {
                                DropdownMenuItem(
                                    text = { Text(sortLabel(s)) },
                                    leadingIcon = { if (s == filter.sort) Icon(Icons.Filled.Check, contentDescription = null) },
                                    onClick = { sortOpen = false; onFilter(filter.copy(sort = s)) },
                                )
                            }
                        }
                    }
                }
                if (searching) {
                    OutlinedTextField(
                        value = filter.query,
                        onValueChange = { onFilter(filter.copy(query = it)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                        placeholder = { Text(stringResource(R.string.lib_search_hint)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        colors = fieldColors(),
                    )
                }
                if (kinds.size > 1) {
                    ChipRow(kinds.map { it to kindName(it) }, kind) { onFilter(filter.copy(kind = it, atype = "", library = "")) }
                }
                if (libs.size > 1) {
                    ChipRow(listOf("" to stringResource(R.string.lib_all_libraries)) + libs.map { it to it }, lib) { onFilter(filter.copy(library = it, atype = "")) }
                }
                if (types.size > 2) {
                    ChipRow(types.map { (k, label, n) -> k to "${typeName(k, label)} · $n" }, atype) { onFilter(filter.copy(atype = it)) }
                }
                Text(
                    pluralStringResource(R.plurals.shows, shown.size, shown.size) + " · " + sortLabel(filter.sort),
                    style = MaterialTheme.typography.bodySmall,
                    color = AniColors.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (library == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.home_no_library), color = AniColors.muted, style = MaterialTheme.typography.bodyLarge)
            }
        }
        itemsIndexed(shown, key = { _, s -> s.id }) { i, s -> GridCard(s, progress, onShow, Modifier.firstFocus(tv && i == 0)) }
    }
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onPick(value) },
                modifier = Modifier.focusRing(8.dp),
                label = { Text(label, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = AniColors.s1,
                    labelColor = AniColors.soft,
                    selectedContainerColor = AniColors.s2,
                    selectedLabelColor = AniColors.acc,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = value == selected,
                    borderColor = AniColors.line2,
                    selectedBorderColor = AniColors.acc.copy(alpha = 0.6f),
                ),
            )
        }
    }
}

@Composable
private fun GridCard(s: Show, progress: Map<String, LocalProgress>, onShow: (Show) -> Unit, focus: Modifier = Modifier) {
    val watched = s.episodes.count { progress[it.key]?.watched == true }
    Column(focus.focusRing(10.dp).clickable { onShow(s) }) {
        Box {
            Poster(s.poster, s.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f), corner = 10)
            if (s.episodes.isNotEmpty() && watched > 0) {
                ProgressLine(watched.toFloat() / s.episodes.size, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp),
                    color = if (watched >= s.episodes.size) AniColors.green else AniColors.acc)
            }
        }
        Gap(6)
        Text(s.title, style = MaterialTheme.typography.bodySmall, color = AniColors.soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = listOfNotNull(s.year?.toString(), s.type.takeIf { it.isNotEmpty() }).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = AniColors.muted, maxLines = 1)
    }
}

@Composable
fun kindName(k: Kind): String = when (k) {
    Kind.ANIME -> stringResource(R.string.kind_anime)
    Kind.SERIES -> stringResource(R.string.kind_series)
    Kind.MOVIES -> stringResource(R.string.kind_movies)
}

@Composable
private fun typeName(key: String, fallback: String): String = when (key) {
    "" -> stringResource(R.string.lib_type_all)
    "tv" -> stringResource(R.string.kind_series)
    "movie" -> stringResource(R.string.kind_movies)
    "ova" -> stringResource(R.string.lib_type_ova)
    "special" -> stringResource(R.string.lib_type_special)
    "ona" -> stringResource(R.string.lib_type_ona)
    "music" -> stringResource(R.string.lib_type_music)
    else -> fallback
}

@Composable
fun sortLabel(s: Sort): String = when (s) {
    Sort.TITLE -> stringResource(R.string.lib_sort_title)
    Sort.YEAR -> stringResource(R.string.lib_sort_year)
    Sort.RECENT -> stringResource(R.string.lib_sort_recent)
}

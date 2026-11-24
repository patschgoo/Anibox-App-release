package io.github.patschgoo.anibox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.patschgoo.anibox.R
import io.github.patschgoo.anibox.data.HostAddress
import io.github.patschgoo.anibox.data.SyncStatus
import io.github.patschgoo.anibox.sync.LanguagePrefs
import io.github.patschgoo.anibox.sync.SyncTarget

data class SettingsUi(
    val target: SyncTarget?,
    val status: SyncStatus,
    val pc: String,
    val journal: String,
    val pending: Int,
    val addresses: List<HostAddress>,
    /** Hosts the library's episodes are on (so an address can be typed for each). */
    val hosts: List<String>,
    val audio: String,
    val subs: String,
    val mode: String,
    val pcPrefs: LanguagePrefs?,
    val autoplay: Boolean,
    val resumeBack: Int,
    val hwdec: Boolean,
    val version: String,
)

interface SettingsActions {
    fun syncNow()
    fun changeFolder()
    fun signInAgain()
    fun setAddress(host: String, address: String): String?
    fun forgetAddress(host: String)
    fun setAudio(text: String)
    fun setSubs(text: String)
    fun setMode(mode: String)
    fun setAutoplay(on: Boolean)
    fun setResumeBack(seconds: Int)
    fun setHwdec(on: Boolean)
}

@Composable
fun SettingsScreen(ui: SettingsUi, actions: SettingsActions) {
    var editHost by rememberSaveable { mutableStateOf<String?>(null) }
    var licence by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium, color = AniColors.text)

        // -- the sync folder
        Card {
            Kicker(stringResource(R.string.settings_folder_kicker))
            Gap(6)
            Text(ui.target?.display ?: stringResource(R.string.settings_no_folder), style = MaterialTheme.typography.titleSmall, color = AniColors.text)
            if (ui.pc.isNotEmpty()) Text(stringResource(R.string.setup_from_pc, ui.pc), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
            Gap(8)
            Row(verticalAlignment = Alignment.CenterVertically) { StatusPill(ui.status) }
            if (ui.status is SyncStatus.Failed) {
                Gap(6)
                Text(ui.status.message, style = MaterialTheme.typography.bodySmall, color = AniColors.soft)
            }
            Gap(12)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.common_sync_now), enabled = ui.status != SyncStatus.Running && ui.target != null) { actions.syncNow() }
                QuietButton(stringResource(R.string.settings_change_folder)) { actions.changeFolder() }
            }
            Gap(8)
            QuietButton(stringResource(R.string.settings_sign_in_again)) { actions.signInAgain() }
            Gap(8)
            Text(stringResource(R.string.settings_sign_in_note), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
        }

        // -- addresses
        Card {
            Kicker(stringResource(R.string.settings_addresses_kicker))
            Gap(6)
            Text(stringResource(R.string.settings_addresses_note), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
            val hosts = (ui.hosts + ui.addresses.map { it.host }).distinctBy { it.lowercase() }
            for (h in hosts) {
                val saved = ui.addresses.firstOrNull { it.host.equals(h, ignoreCase = true) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRing(8.dp, inset = true).clickable { editHost = h }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(h, style = MaterialTheme.typography.titleSmall, color = AniColors.text)
                        Text(
                            saved?.let { "${it.address} · ${it.how.label}" } ?: stringResource(R.string.settings_address_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = AniColors.muted,
                        )
                    }
                    TextButton(modifier = Modifier.focusRing(20.dp), onClick = { editHost = h }) { Text(stringResource(R.string.common_edit)) }
                }
            }
        }

        // -- playback
        Card {
            Kicker(stringResource(R.string.settings_playback_kicker))
            Gap(8)
            val pcAudio = ui.pcPrefs?.audioLangs?.joinToString(",").orEmpty()
            val pcSubs = ui.pcPrefs?.subLangs?.joinToString(",").orEmpty()
            LangField(ui.audio, stringResource(R.string.settings_audio), pcAudio.ifEmpty { "jpn,ger,eng" }, actions::setAudio)
            Gap(10)
            LangField(ui.subs, stringResource(R.string.settings_subs), pcSubs.ifEmpty { "ger,eng" }, actions::setSubs)
            Gap(6)
            Text(
                if (ui.pcPrefs != null) stringResource(R.string.settings_langs_pc) else stringResource(R.string.settings_langs_default),
                style = MaterialTheme.typography.bodySmall,
                color = AniColors.muted,
            )
            Gap(12)
            Text(stringResource(R.string.settings_subs_mode), style = MaterialTheme.typography.titleSmall, color = AniColors.text)
            Gap(4)
            ChoiceChips(
                listOf(
                    "" to stringResource(R.string.settings_mode_pc, ui.pcPrefs?.subs ?: "auto"),
                    "auto" to stringResource(R.string.settings_mode_auto),
                    "on" to stringResource(R.string.settings_mode_on),
                    "off" to stringResource(R.string.settings_mode_off),
                ),
                ui.mode,
                actions::setMode,
            )
            Gap(4)
            Text(stringResource(R.string.settings_mode_note), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
            Gap(12)
            SwitchRow(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_note), ui.autoplay, actions::setAutoplay)
            Gap(8)
            Text(stringResource(R.string.settings_resume), style = MaterialTheme.typography.titleSmall, color = AniColors.text)
            Gap(4)
            ChoiceChips(listOf(0, 5, 10, 15, 30).map { it to stringResource(R.string.settings_seconds, it) }, ui.resumeBack, actions::setResumeBack)
            Gap(12)
            SwitchRow(stringResource(R.string.settings_hwdec), stringResource(R.string.settings_hwdec_note), ui.hwdec, actions::setHwdec)
        }

        // -- this phone
        Card {
            Kicker(stringResource(R.string.settings_phone_kicker))
            Gap(6)
            Text(stringResource(R.string.settings_journal, ui.journal), style = MaterialTheme.typography.bodySmall, color = AniColors.soft)
            Gap(4)
            Text(
                if (ui.pending == 0) stringResource(R.string.settings_pending_none) else pluralStringResource(R.plurals.settings_pending, ui.pending, ui.pending),
                style = MaterialTheme.typography.bodySmall,
                color = AniColors.muted,
            )
            Gap(4)
            Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
        }

        // -- about
        Card {
            Kicker(stringResource(R.string.settings_about_kicker))
            Gap(6)
            Text(stringResource(R.string.settings_version, ui.version), style = MaterialTheme.typography.titleSmall, color = AniColors.text)
            Gap(4)
            Text(stringResource(R.string.settings_gpl), style = MaterialTheme.typography.bodySmall, color = AniColors.soft)
            Gap(8)
            for ((name, file) in LICENCES) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).focusRing(8.dp, inset = true).clickable { licence = file },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = AniColors.acc, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Gap(8)
    }
    editHost?.let { h ->
        AddressDialog(h, ui.addresses.firstOrNull { it.host.equals(h, ignoreCase = true) }?.address.orEmpty(),
            onSave = { a -> actions.setAddress(h, a).also { if (it == null) editHost = null } },
            onForget = { actions.forgetAddress(h); editHost = null },
            onDismiss = { editHost = null })
    }
    licence?.let { f -> LicenceDialog(f) { licence = null } }
}

/** The licences shown under About (texts in assets/licenses/). */
val LICENCES = listOf(
    "Components and their licences" to "NOTICES.txt",
    "Sora font: SIL Open Font License 1.1" to "OFL-Sora.txt",
    "Inter font: SIL Open Font License 1.1" to "OFL-Inter.txt",
)

@Composable
private fun LangField(value: String, label: String, placeholder: String, onDone: (String) -> Unit) {
    // Kept as typed (", " included); the settings keep the cleaned codes.
    var text by rememberSaveable(label) { mutableStateOf(value) }
    Field(
        text,
        { text = it; onDone(it) },
        if (text.isBlank()) "$label: $placeholder" else label, // empty: what's used instead
        placeholder,
        last = true,
    )
}

@Composable
private fun SwitchRow(title: String, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().focusRing(8.dp, inset = true).clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = AniColors.text)
            Text(note, style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
        }
        HGap(12)
        Switch(
            checked = on,
            onCheckedChange = null, // the whole row switches (one stop for the remote)
            colors = SwitchDefaults.colors(checkedTrackColor = AniColors.acc, checkedThumbColor = AniColors.on),
        )
    }
}

@Composable
private fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) {
            FilterChip(
                selected = value == selected,
                onClick = { onPick(value) },
                modifier = Modifier.focusRing(8.dp),
                label = { Text(label, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = AniColors.s2,
                    labelColor = AniColors.soft,
                    selectedContainerColor = AniColors.acc.copy(alpha = 0.16f),
                    selectedLabelColor = AniColors.acc,
                ),
            )
        }
    }
}

@Composable
private fun AddressDialog(host: String, current: String, onSave: (String) -> String?, onForget: () -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(host) { mutableStateOf(current) }
    var error by rememberSaveable(host) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_address_title, host)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_address_dialog), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
                Gap(10)
                Field(text, { text = it }, stringResource(R.string.settings_address_field), stringResource(R.string.setup_address_hint), last = true, number = true)
                error?.let { ErrorBox(it) }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.focusRing(20.dp), onClick = { error = onSave(text) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = {
            Row {
                if (current.isNotEmpty()) TextButton(modifier = Modifier.focusRing(20.dp), onClick = onForget) { Text(stringResource(R.string.settings_address_forget)) }
                TextButton(modifier = Modifier.focusRing(20.dp), onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
        containerColor = AniColors.s1,
    )
}

@Composable
private fun LicenceDialog(file: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(file) {
        try {
            context.assets.open("licenses/$file").use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (_: Exception) {
            ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(modifier = Modifier.focusRing(20.dp), onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodySmall, color = AniColors.soft)
            }
        },
        containerColor = AniColors.s1,
    )
}

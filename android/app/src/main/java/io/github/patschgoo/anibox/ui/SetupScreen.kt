package io.github.patschgoo.anibox.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.patschgoo.anibox.R
import io.github.patschgoo.anibox.SetupState
import io.github.patschgoo.anibox.SetupStep

/** What the setup screen can do (MainViewModel in the app, nothing in the screenshots). */
interface SetupActions {
    fun step(step: SetupStep)
    fun back()
    fun qrText(text: String): String?
    fun typed(host: String, share: String, folder: String): String?
    fun connect(user: String, password: String, address: String)
    /** "Use this device's own drives" (the Shield): no password. */
    fun useLocal() {}
}

@Composable
fun SetupScreen(state: SetupState, actions: SetupActions, scanner: @Composable (Modifier, (String) -> Boolean) -> Unit = { m, f -> QrScanner(m, f) }) {
    Column(
        Modifier
            .fillMaxSize()
            .background(AniColors.bg)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.step != SetupStep.CHOOSE || state.fromSettings) {
                IconButton(modifier = Modifier.focusRing(24.dp), onClick = actions::back, enabled = !state.busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                HGap(4)
            }
            Logo()
            HGap(10)
            Text(stringResource(R.string.brand), style = MaterialTheme.typography.headlineSmall, color = AniColors.text)
        }
        Gap(20)
        when (state.step) {
            SetupStep.CHOOSE -> Choose(state, actions)
            SetupStep.SCAN -> Scan(state, actions, scanner)
            SetupStep.PASTE -> Paste(state, actions)
            SetupStep.TYPE -> Typed(state, actions)
            SetupStep.SIGN_IN -> SignInStep(state, actions)
        }
    }
}

@Composable
private fun Choose(state: SetupState, actions: SetupActions) {
    Kicker(stringResource(R.string.setup_kicker))
    Gap(6)
    Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium, color = AniColors.text)
    Gap(10)
    Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge, color = AniColors.soft)
    Gap(24)
    // A TV has no camera: typing the share comes first there (the PC's "Copy as text" also works).
    val context = LocalContext.current
    val camera = hasCamera(context)
    val tv = LocalTv.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val readAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) actions.useLocal() }
    if (state.localDrives) {
        // The Shield itself: the drives are plugged in here, so the app reads them directly (A2/A3).
        ChoiceCard(Icons.Filled.Storage, stringResource(R.string.setup_local), stringResource(R.string.setup_local_hint), primary = true,
            modifier = Modifier.firstFocus(tv)) {
            if (state.busy) return@ChoiceCard
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    actions.useLocal()
                } else {
                    asked = true
                    openFileAccess(context)
                }
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                actions.useLocal()
            } else {
                readAsk.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (asked) {
            Gap(6)
            Text(stringResource(R.string.setup_local_access), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
        }
        if (state.busy) {
            Gap(8)
            CircularProgressIndicator(Modifier.size(22.dp), color = AniColors.acc, strokeWidth = 2.dp)
        }
        state.error?.let { Gap(8); ErrorBox(it) }
        Gap(12)
    }
    if (camera) {
        ChoiceCard(Icons.Filled.QrCodeScanner, stringResource(R.string.setup_scan), stringResource(R.string.setup_scan_hint), primary = true,
            modifier = if (state.localDrives) Modifier else Modifier.firstFocus(tv)) {
            actions.step(SetupStep.SCAN)
        }
        Gap(12)
    }
    if (!camera) {
        ChoiceCard(Icons.Filled.Edit, stringResource(R.string.setup_type), stringResource(R.string.setup_type_hint), primary = true,
            modifier = if (state.localDrives) Modifier else Modifier.firstFocus(tv)) {
            actions.step(SetupStep.TYPE)
        }
        Gap(12)
    }
    ChoiceCard(Icons.Filled.ContentPaste, stringResource(R.string.setup_paste), stringResource(R.string.setup_paste_hint)) {
        actions.step(SetupStep.PASTE)
    }
    if (camera) {
        Gap(12)
        ChoiceCard(Icons.Filled.Edit, stringResource(R.string.setup_type), stringResource(R.string.setup_type_hint)) {
            actions.step(SetupStep.TYPE)
        }
    }
    Gap(24)
    Text(stringResource(R.string.setup_privacy), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
}

/** Android's "All files access" page for this app (needed to read the Shield's own drives). */
private fun openFileAccess(context: Context) {
    val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
    try {
        context.startActivity(own)
    } catch (_: ActivityNotFoundException) {
        try { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: ActivityNotFoundException) { }
    }
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, hint: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .focusRing(14.dp, grow = 1.02f)
            .clip(shape)
            .background(AniColors.s1)
            .border(1.dp, if (primary) AniColors.acc.copy(alpha = 0.6f) else AniColors.line2, shape)
            .clickableRow(onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AniColors.acc, modifier = Modifier.size(28.dp))
        HGap(14)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AniColors.text)
            Gap(2)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
        }
    }
}

@Composable
private fun Scan(state: SetupState, actions: SetupActions, scanner: @Composable (Modifier, (String) -> Boolean) -> Unit) {
    val context = LocalContext.current
    var granted by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
    }
    Text(stringResource(R.string.setup_scan), style = MaterialTheme.typography.headlineSmall, color = AniColors.text)
    Gap(8)
    Text(stringResource(R.string.setup_scan_where), style = MaterialTheme.typography.bodyMedium, color = AniColors.soft)
    Gap(16)
    if (granted) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(2.dp, AniColors.acc.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
        ) {
            scanner(Modifier.fillMaxSize()) { text -> actions.qrText(text) == null }
        }
    } else {
        Text(
            stringResource(if (refused) R.string.setup_camera_refused else R.string.setup_camera_why),
            style = MaterialTheme.typography.bodyMedium,
            color = AniColors.muted,
        )
        Gap(12)
        PrimaryButton(stringResource(R.string.setup_camera_allow)) { ask.launch(Manifest.permission.CAMERA) }
    }
    state.error?.let { ErrorBox(it) }
    Gap(16)
    TextButton(modifier = Modifier.focusRing(20.dp), onClick = { actions.step(SetupStep.PASTE) }) { Text(stringResource(R.string.setup_paste_instead)) }
}

@Composable
private fun Paste(state: SetupState, actions: SetupActions) {
    var text by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.setup_paste), style = MaterialTheme.typography.headlineSmall, color = AniColors.text)
    Gap(8)
    Text(stringResource(R.string.setup_paste_where), style = MaterialTheme.typography.bodyMedium, color = AniColors.soft)
    Gap(16)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        placeholder = { Text("{\"v\":1,\"type\":\"anibox-sync\",…}") },
        colors = fieldColors(),
    )
    state.error?.let { ErrorBox(it) }
    Gap(16)
    PrimaryButton(stringResource(R.string.common_next), enabled = text.isNotBlank()) { actions.qrText(text) }
}

@Composable
private fun Typed(state: SetupState, actions: SetupActions) {
    var host by rememberSaveable { mutableStateOf(state.target?.host ?: "") }
    var share by rememberSaveable { mutableStateOf(state.target?.share ?: "") }
    var folder by rememberSaveable { mutableStateOf(state.target?.path ?: "") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.setup_type), style = MaterialTheme.typography.headlineSmall, color = AniColors.text)
    Gap(8)
    Text(stringResource(R.string.setup_type_where), style = MaterialTheme.typography.bodyMedium, color = AniColors.soft)
    Gap(16)
    Field(host, { host = it }, stringResource(R.string.setup_host), stringResource(R.string.setup_host_hint))
    Gap(10)
    Field(share, { share = it }, stringResource(R.string.setup_share), stringResource(R.string.setup_share_hint))
    Gap(10)
    Field(folder, { folder = it }, stringResource(R.string.setup_folder), stringResource(R.string.setup_folder_hint), last = true)
    (error ?: state.error)?.let { ErrorBox(it) }
    Gap(16)
    PrimaryButton(stringResource(R.string.common_next)) { error = actions.typed(host, share, folder) }
}

@Composable
private fun SignInStep(state: SetupState, actions: SetupActions) {
    val t = state.target ?: return
    var user by rememberSaveable(t.host) { mutableStateOf(state.user) }
    var password by rememberSaveable(t.host) { mutableStateOf("") }
    var address by rememberSaveable(t.host) { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    Kicker(stringResource(R.string.setup_signin_kicker))
    Gap(6)
    Text(stringResource(R.string.setup_signin_title), style = MaterialTheme.typography.headlineSmall, color = AniColors.text)
    Gap(12)
    InfoBox {
        Text(t.display, style = MaterialTheme.typography.titleSmall, color = AniColors.text)
        if (t.pc.isNotEmpty()) {
            Gap(2)
            Text(stringResource(R.string.setup_from_pc, t.pc), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
        }
    }
    Gap(12)
    Text(stringResource(R.string.setup_signin_where), style = MaterialTheme.typography.bodyMedium, color = AniColors.soft)
    Gap(16)
    Field(user, { user = it }, stringResource(R.string.setup_user), "", enabled = !state.busy)
    Gap(10)
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.setup_password)) },
        singleLine = true,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (state.askAddress) ImeAction.Next else ImeAction.Done),
        trailingIcon = {
            IconButton(modifier = Modifier.focusRing(24.dp), onClick = { show = !show }) {
                Icon(
                    if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (show) R.string.setup_password_hide else R.string.setup_password_show),
                )
            }
        },
        colors = fieldColors(),
    )
    if (state.askAddress) {
        Gap(10)
        Field(address, { address = it }, stringResource(R.string.setup_address, t.host), stringResource(R.string.setup_address_hint), last = true, enabled = !state.busy, number = true)
    }
    state.error?.let { ErrorBox(it) }
    Gap(16)
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(stringResource(R.string.setup_connect), enabled = !state.busy) { actions.connect(user, password, address) }
        if (state.busy) {
            HGap(14)
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AniColors.acc)
            HGap(10)
            Text(stringResource(R.string.setup_testing), color = AniColors.muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Gap(20)
    Text(stringResource(R.string.setup_password_note), style = MaterialTheme.typography.bodySmall, color = AniColors.muted)
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    last: Boolean = false,
    enabled: Boolean = true,
    number: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder, color = AniColors.muted) }),
        singleLine = true,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        colors = fieldColors(),
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AniColors.acc,
    unfocusedBorderColor = AniColors.line2,
    focusedContainerColor = AniColors.s1,
    unfocusedContainerColor = AniColors.s1,
    disabledContainerColor = AniColors.s1,
    cursorColor = AniColors.acc,
    focusedLabelColor = AniColors.acc,
)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.focusRing().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AniColors.acc, contentColor = AniColors.on),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun QuietButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.focusRing().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AniColors.line2),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = AniColors.s1, contentColor = AniColors.text),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun ErrorBox(text: String) {
    Gap(12)
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AniColors.red.copy(alpha = 0.08f))
            .border(1.dp, AniColors.red.copy(alpha = 0.35f), shape)
            .padding(12.dp),
    ) { Text(text, color = AniColors.text, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
fun InfoBox(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AniColors.s1)
            .border(1.dp, AniColors.line, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) { content() }
}

fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

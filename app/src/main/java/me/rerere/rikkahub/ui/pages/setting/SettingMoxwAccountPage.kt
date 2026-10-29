package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.moxw.MOXW_DEFAULT_MODEL
import me.rerere.rikkahub.data.moxw.MoxwAccountRepository
import me.rerere.rikkahub.data.moxw.moxwQuotaToCny
import me.rerere.rikkahub.data.moxw.toUserMessage
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private enum class MoxwAuthMode { SIGN_IN, SIGN_UP }

/**
 * Sign in to the Moxw gateway.
 *
 * Deliberately not a form that asks for an API key: the entire point is that the user never
 * sees one. Signing in mints the key server-side and writes it into the bundled provider, so
 * afterwards chat works with no further setup.
 *
 * Sign-up is a second mode on the same page rather than a separate destination - it is a few
 * extra fields on the same card, and splitting them would mean two near-identical pages.
 */
@Composable
fun SettingMoxwAccountPage() {
    val repository = koinInject<MoxwAccountRepository>()
    val account by repository.account.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var mode by remember { mutableStateOf(MoxwAuthMode.SIGN_IN) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var codeSent by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    val signedIn = account != null

    // Re-read quota/group when the page opens: the number moves with every chat, and a stale
    // balance on the one screen that exists to show it is worse than showing nothing.
    LaunchedEffect(signedIn) {
        if (signedIn) {
            refreshing = true
            runCatching { repository.refreshSnapshot() }
            refreshing = false
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_moxw_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (signedIn) {
                item {
                    MoxwSignedInCard(
                        account = account!!,
                        refreshing = refreshing,
                        onRefresh = {
                            refreshing = true
                            scope.launch {
                                runCatching { repository.refreshSnapshot() }
                                    .onFailure { toaster.show(it.toUserMessage(), type = ToastType.Error) }
                                refreshing = false
                            }
                        },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                runCatching { repository.signOut() }
                                    .onSuccess { toaster.show("已退出登录", type = ToastType.Success) }
                                    .onFailure { toaster.show(it.toUserMessage(), type = ToastType.Error) }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    ) { Text(stringResource(R.string.setting_moxw_sign_out)) }
                }
            } else {
                item {
                    MoxwAuthCard(
                        mode = mode,
                        username = username,
                        onUsernameChange = { username = it },
                        password = password,
                        onPasswordChange = { password = it },
                        email = email,
                        onEmailChange = { email = it },
                        code = code,
                        onCodeChange = { code = it },
                        passwordVisible = passwordVisible,
                        onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                        busy = busy,
                        codeSent = codeSent,
                        onSendCode = {
                            busy = true
                            scope.launch {
                                runCatching { repository.sendVerificationCode(email) }
                                    .onSuccess {
                                        codeSent = true
                                        toaster.show("验证码已发送，请查收邮箱", type = ToastType.Success)
                                    }
                                    .onFailure { toaster.show(it.toUserMessage(), type = ToastType.Error) }
                                busy = false
                            }
                        },
                        onSubmit = {
                            busy = true
                            scope.launch {
                                runCatching {
                                    if (mode == MoxwAuthMode.SIGN_UP) {
                                        repository.register(username, password, email, code)
                                    }
                                    repository.signIn(username, password)
                                }
                                    .onSuccess {
                                        toaster.show(
                                            if (mode == MoxwAuthMode.SIGN_UP) "注册成功，已自动登录" else "登录成功",
                                            type = ToastType.Success,
                                        )
                                        password = ""
                                        code = ""
                                    }
                                    .onFailure { toaster.show(it.toUserMessage(), type = ToastType.Error) }
                                busy = false
                            }
                        },
                        onToggleMode = {
                            mode = if (mode == MoxwAuthMode.SIGN_IN) MoxwAuthMode.SIGN_UP else MoxwAuthMode.SIGN_IN
                            codeSent = false
                        },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.setting_moxw_trial_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoxwSignedInCard(
    account: me.rerere.rikkahub.data.moxw.MoxwAccount,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    CardGroup(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.setting_moxw_account_title)) },
    ) {
        item(
            headlineContent = { Text(account.displayName.ifBlank { account.username }) },
            overlineContent = {
                Text(stringResource(R.string.setting_moxw_signed_in_as, account.username))
            },
            supportingContent = {
                Column {
                    Text(stringResource(R.string.setting_moxw_group, account.group.ifBlank { "default" }))
                    Text(
                        stringResource(
                            R.string.setting_moxw_balance,
                            "%.2f".format(moxwQuotaToCny(account.quota)),
                        )
                    )
                }
            },
            trailingContent = {
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.setting_moxw_refresh))
                    }
                }
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_moxw_default_model)) },
            supportingContent = { Text(MOXW_DEFAULT_MODEL) },
        )
    }
}

@Composable
private fun MoxwAuthCard(
    mode: MoxwAuthMode,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    busy: Boolean,
    codeSent: Boolean,
    onSendCode: () -> Unit,
    onSubmit: () -> Unit,
    onToggleMode: () -> Unit,
) {
    val signingUp = mode == MoxwAuthMode.SIGN_UP
    CardGroup(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        title = {
            Text(
                stringResource(
                    if (signingUp) R.string.setting_moxw_sign_up else R.string.setting_moxw_sign_in
                )
            )
        },
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_moxw_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.setting_moxw_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.setting_moxw_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (signingUp) ImeAction.Next else ImeAction.Done,
                    ),
                    trailingIcon = {
                        TextButton(onClick = onTogglePasswordVisible) {
                            Icon(
                                imageVector = if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                )

                if (signingUp) {
                    HorizontalDivider()
                    OutlinedTextField(
                        value = email,
                        onValueChange = onEmailChange,
                        label = { Text(stringResource(R.string.setting_moxw_email)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = onCodeChange,
                            label = { Text(stringResource(R.string.setting_moxw_code)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Button(onClick = onSendCode, enabled = !busy && email.contains('@')) {
                            Text(
                                stringResource(
                                    if (codeSent) R.string.setting_moxw_resend_code
                                    else R.string.setting_moxw_send_code
                                )
                            )
                        }
                    }
                }

                Button(
                    onClick = onSubmit,
                    enabled = !busy && username.isNotBlank() && password.isNotBlank() &&
                        (!signingUp || (email.contains('@') && code.isNotBlank())),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            stringResource(
                                if (signingUp) R.string.setting_moxw_sign_up else R.string.setting_moxw_sign_in
                            )
                        )
                    }
                }

                TextButton(onClick = onToggleMode, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            if (signingUp) R.string.setting_moxw_switch_to_sign_in
                            else R.string.setting_moxw_switch_to_sign_up
                        )
                    )
                }
            }
        }
    }
}

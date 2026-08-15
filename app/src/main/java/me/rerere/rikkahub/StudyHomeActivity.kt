package me.rerere.rikkahub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.study.StudyDesktopManager
import me.rerere.rikkahub.data.study.StudyDesktopMode
import me.rerere.rikkahub.ui.pages.study.StudyDesktopDashboardPage
import org.koin.android.ext.android.inject

/**
 * A dedicated HOME task. It deliberately does not inherit RouteActivity or restore the app's
 * Navigation3 stack, so HOME can never reopen chat, safe mode, or an empty transient back stack.
 */
class StudyHomeActivity : ComponentActivity() {
    private val manager by inject<StudyDesktopManager>()
    private var showExitDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(null)
        manager.reconcileState()
        keepSystemGesturesAvailable()
        setContent {
            val mode by manager.mode.collectAsStateWithLifecycle()
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color.Black,
                    onPrimary = Color.White,
                    background = Color.White,
                    onBackground = Color.Black,
                    surface = Color.White,
                    onSurface = Color.Black,
                    outline = Color.Black,
                ),
            ) {
                StudyHomeRoot(
                    mode = mode,
                    onOpenScreen = ::openStudyScreen,
                    onRequestExit = { showExitDialog = true },
                    onContinueEnable = ::continueEnable,
                    onCancelEnable = { showExitDialog = true },
                    onContinueExit = ::openHomeSelection,
                    onCancelExit = { manager.cancelExit() },
                    onRecoverHome = ::openHomeSelection,
                )
                if (showExitDialog) {
                    StudyExitPasswordDialog(
                        onDismiss = { showExitDialog = false },
                        onConfirm = ::beginExit,
                    )
                }
            }
        }
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        manager.reconcileState()
        keepSystemGesturesAvailable()
    }

    override fun onResume() {
        super.onResume()
        manager.reconcileState()
        manager.startKeepAliveIfPossible()
        keepSystemGesturesAvailable()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) keepSystemGesturesAvailable()
    }

    override fun onPause() {
        // Some vendor SystemUI builds retain a HOME window's hidden-system-bar state while the
        // next task is opening. Explicitly leave both bars visible so a permitted study app does
        // not inherit a desktop-only navigation lock.
        keepSystemGesturesAvailable()
        super.onPause()
    }

    private fun keepSystemGesturesAvailable() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun beginExit(password: String): Boolean {
        if (!manager.verifyPassword(password)) return false
        val needsHomeSelection = runCatching { manager.disable() }.getOrElse { return false }
        showExitDialog = false
        if (needsHomeSelection) {
            openHomeSelection()
        } else {
            manager.openSystemHome()
            finishAndRemoveTask()
        }
        return true
    }

    private fun continueEnable() {
        if (manager.reconcileState() == StudyDesktopMode.ACTIVE) return
        runCatching { startActivity(manager.defaultHomeRequestIntent()) }
    }

    private fun openHomeSelection() {
        runCatching { startActivity(manager.homeSettingsIntent()) }
    }

    private fun openStudyScreen(screen: Screen) {
        val destination = screen.toStudyDestination() ?: return
        startActivity(
            Intent(this, RouteActivity::class.java).apply {
                putExtra(RouteActivity.EXTRA_STUDY_DESTINATION, destination)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

@Composable
private fun StudyHomeRoot(
    mode: StudyDesktopMode,
    onOpenScreen: (Screen) -> Unit,
    onRequestExit: () -> Unit,
    onContinueEnable: () -> Unit,
    onCancelEnable: () -> Unit,
    onContinueExit: () -> Unit,
    onCancelExit: () -> Unit,
    onRecoverHome: () -> Unit,
) {
    BackHandler { }
    when (mode) {
        StudyDesktopMode.ACTIVE -> StudyDesktopDashboardPage(
            onOpenScreen = onOpenScreen,
            onRequestExit = onRequestExit,
        )
        StudyDesktopMode.ENABLING -> StudyHomeStatusPage(
            title = "继续开启学习桌面",
            detail = "上次默认桌面确认没有完整结束。可以继续完成确认，或使用开启时设置的密码取消，不会进入聊天或调试页面。",
            primaryText = "继续确认默认桌面",
            onPrimary = onContinueEnable,
            secondaryText = "输入密码取消",
            onSecondary = onCancelEnable,
        )
        StudyDesktopMode.EXITING -> StudyHomeStatusPage(
            title = "正在退出学习桌面",
            detail = "退出密码仍被安全保留。请选择原系统桌面；如果刚才取消了系统页面，可以继续选择或返回学习桌面。",
            primaryText = "继续选择系统桌面",
            onPrimary = onContinueExit,
            secondaryText = "取消退出",
            onSecondary = onCancelExit,
        )
        StudyDesktopMode.RECOVERY,
        StudyDesktopMode.DISABLED -> StudyHomeStatusPage(
            title = "桌面恢复",
            detail = "本应用仍被系统设为默认桌面，但学习模式没有完整凭据。这里不会再进入聊天或循环闪退，请选择原系统桌面恢复设备。",
            primaryText = "选择系统桌面",
            onPrimary = onRecoverHome,
        )
    }
}

@Composable
private fun StudyHomeStatusPage(
    title: String,
    detail: String,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Surface(color = Color.White, contentColor = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 620.dp),
                color = Color.White,
                contentColor = Color.Black,
                border = BorderStroke(1.dp, Color.Black),
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    ) {
                        if (secondaryText != null && onSecondary != null) {
                            OutlinedButton(onClick = onSecondary) { Text(secondaryText) }
                        }
                        Button(onClick = onPrimary) { Text(primaryText) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyExitPasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Boolean,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("退出学习桌面") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    error = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("退出密码") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = error,
                supportingText = { if (error) Text("密码不正确，或退出状态无法保存") },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (!onConfirm(password)) error = true }) { Text("退出") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun Screen.toStudyDestination(): String? = when (this) {
    is Screen.Chat -> "chat"
    Screen.History -> "history"
    Screen.Favorite -> "favorite"
    Screen.Assistant -> "assistant"
    Screen.Translator -> "translator"
    Screen.ImageGen -> "image_gen"
    Screen.MessageSearch -> "message_search"
    Screen.Extensions -> "extensions"
    Screen.QuickMessages -> "quick_messages"
    Screen.Prompts -> "prompts"
    Screen.Skills -> "skills"
    Screen.Workspaces -> "workspaces"
    Screen.Stats -> "stats"
    Screen.Backup -> "backup"
    Screen.Setting -> "setting"
    else -> null
}

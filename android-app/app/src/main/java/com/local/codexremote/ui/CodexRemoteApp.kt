package com.local.codexremote.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.codexremote.data.ChatMessage
import com.local.codexremote.data.ThreadSummary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class AppColorPalette(
    val appBackground: Color,
    val sidebarBackground: Color,
    val surfaceBackground: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val disabledText: Color,
    val warning: Color,
    val scannerBackground: Color,
    val scannerSecondary: Color
)

private val LightAppPalette = AppColorPalette(
    appBackground = Color(0xFFFBFBF8),
    sidebarBackground = Color(0xFFF3F3EF),
    surfaceBackground = Color.White,
    border = Color(0xFFE1E1DC),
    textPrimary = Color(0xFF20201D),
    textSecondary = Color(0xFF71716A),
    accent = Color(0xFF8B8B83),
    disabledText = Color(0xFFB6B6AE),
    warning = Color(0xFF9A3412),
    scannerBackground = Color(0xFF111827),
    scannerSecondary = Color(0xFFD7DEE6)
)

private val DarkComfortAppPalette = AppColorPalette(
    appBackground = Color(0xFF121411),
    sidebarBackground = Color(0xFF1A1D18),
    surfaceBackground = Color(0xFF20241E),
    border = Color(0xFF343A31),
    textPrimary = Color(0xFFE9ECE4),
    textSecondary = Color(0xFFAEB5A8),
    accent = Color(0xFF9DB88B),
    disabledText = Color(0xFF6F766B),
    warning = Color(0xFFE1A36F),
    scannerBackground = Color(0xFF0F1412),
    scannerSecondary = Color(0xFFCAD4C6)
)

private val HighContrastAppPalette = AppColorPalette(
    appBackground = Color(0xFF050505),
    sidebarBackground = Color(0xFF101010),
    surfaceBackground = Color(0xFF181818),
    border = Color(0xFF5F6C7B),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFD6DEE8),
    accent = Color(0xFF67E8F9),
    disabledText = Color(0xFF7B8492),
    warning = Color(0xFFFBBF24),
    scannerBackground = Color(0xFF000000),
    scannerSecondary = Color(0xFFE5EDF5)
)

internal val LocalAppColors = staticCompositionLocalOf { LightAppPalette }

private val AppBackground: Color
    @Composable get() = LocalAppColors.current.appBackground
private val SidebarBackground: Color
    @Composable get() = LocalAppColors.current.sidebarBackground
private val SurfaceBackground: Color
    @Composable get() = LocalAppColors.current.surfaceBackground
private val BorderColor: Color
    @Composable get() = LocalAppColors.current.border
private val TextPrimary: Color
    @Composable get() = LocalAppColors.current.textPrimary
private val TextSecondary: Color
    @Composable get() = LocalAppColors.current.textSecondary
private val Accent: Color
    @Composable get() = LocalAppColors.current.accent
private val DisabledText: Color
    @Composable get() = LocalAppColors.current.disabledText
private val WarningText: Color
    @Composable get() = LocalAppColors.current.warning
private val ScannerBackground: Color
    @Composable get() = LocalAppColors.current.scannerBackground
private val ScannerSecondary: Color
    @Composable get() = LocalAppColors.current.scannerSecondary

private data class MessageToneColors(
    val background: Color,
    val border: Color,
    val title: Color,
    val body: Color,
    val selectedBackground: Color
)

@Composable
private fun messageToneColors(tone: MessageTone): MessageToneColors = when (tone) {
    MessageTone.Neutral -> MessageToneColors(
        background = SurfaceBackground,
        border = BorderColor,
        title = TextPrimary,
        body = TextSecondary,
        selectedBackground = TextPrimary
    )
    MessageTone.Warning -> MessageToneColors(
        background = Color(0xFFFFF8EC),
        border = Color(0xFFF2D6A2),
        title = Color(0xFFB45309),
        body = Color(0xFF7C4A03),
        selectedBackground = Color(0xFFB45309)
    )
    MessageTone.Error -> MessageToneColors(
        background = Color(0xFFFFF1F1),
        border = Color(0xFFF4B4B4),
        title = Color(0xFFB91C1C),
        body = Color(0xFF7F1D1D),
        selectedBackground = Color(0xFFB91C1C)
    )
}

internal fun appColorPaletteFor(theme: AppTheme): AppColorPalette = when (theme) {
    AppTheme.Default -> LightAppPalette
    AppTheme.DarkComfort -> DarkComfortAppPalette
    AppTheme.HighContrast -> HighContrastAppPalette
}

@Composable
fun CodexRemoteApp(viewModel: CodexRemoteViewModel = viewModel()) {
    MaterialTheme {
        val state = viewModel.state
        CompositionLocalProvider(LocalAppColors provides appColorPaletteFor(state.selectedTheme)) {
            Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
                if (state.shouldShowPairing) {
                    PairingScreen(
                        pairingText = state.pairingText,
                        status = state.status,
                        onPairingTextChange = viewModel::updatePairingText,
                        onConnect = viewModel::connectFromPairingText,
                        onScanned = viewModel::connectFromPairingText
                    )
                } else {
                    CodexWorkspace(
                        state = state,
                        onSend = viewModel::sendPrompt,
                        onStop = viewModel::interrupt,
                        onNewConversation = viewModel::startNewConversation,
                        onSelectThread = viewModel::selectThread,
                        onThreadSearchChange = viewModel::updateThreadSearchTerm,
                        onRefreshThreads = viewModel::refreshThreads,
                        onLoadMoreThreads = viewModel::loadMoreThreads,
                        onRenameThread = viewModel::renameActiveThread,
                        onSelectModel = viewModel::selectModel,
                        onSelectAccessMode = viewModel::selectAccessMode,
                        onSelectReasoningEffort = viewModel::selectReasoningEffort,
                        onSetFastModeEnabled = viewModel::setFastModeEnabled,
                        onSelectTheme = viewModel::selectTheme,
                        onSelectProjectWorkspace = viewModel::selectProjectWorkspace,
                        onRequestProjectPathSuggestions = viewModel::requestProjectPathSuggestions,
                        onSetPlanModeEnabled = viewModel::setPlanModeEnabled,
                        onSetGoalModeEnabled = viewModel::setGoalModeEnabled,
                        onRefreshGoal = viewModel::refreshGoal,
                        onSetGoalObjective = viewModel::setGoalObjective,
                        onClearGoal = viewModel::clearGoal,
                        onAddAttachments = viewModel::addAttachments,
                        onRemoveAttachment = viewModel::removeAttachment,
                        onRefreshModels = viewModel::refreshModels,
                        onSetShowArchivedThreads = viewModel::setShowArchivedThreads,
                        onArchiveActiveThread = viewModel::archiveActiveThread,
                        onUnarchiveThread = viewModel::unarchiveThread,
                        onClearPairing = viewModel::clearPairing,
                        onRecoveryAction = viewModel::handleRecoveryAction,
                        onRespondToPendingApproval = viewModel::respondToPendingApproval,
                        onRespondToPendingInteraction = viewModel::respondToPendingInteraction,
                        onStartPlanImplementation = viewModel::startPlanImplementation,
                        onContinuePlanning = viewModel::continuePlanning,
                        onDismissActivePlan = viewModel::dismissActivePlan,
                        onEditUserMessage = viewModel::editUserMessage,
                        onEditDraftRequestConsumed = viewModel::consumeEditDraftRequest,
                        onSelectWorkflowSection = viewModel::selectWorkflowSection,
                        onRefreshWorkflowSection = { viewModel.refreshWorkflowSection(force = true) },
                        onLoadMoreAutomationApps = viewModel::loadMoreAutomationApps,
                        onLoadMoreMcpServers = viewModel::loadMoreMcpServers,
                        onSendWorkflowPrompt = { prompt ->
                            viewModel.selectWorkflowSection(WorkflowSection.Chat)
                            viewModel.sendPrompt(prompt)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingScreen(
    pairingText: String,
    status: String,
    onPairingTextChange: (String) -> Unit,
    onConnect: () -> Unit,
    onScanned: (String) -> Unit
) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    var pendingScannedCode by remember { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
        cameraDenied = !granted
    }
    var backupExpanded by rememberSaveable { mutableStateOf(pairingText.isNotBlank()) }
    LaunchedEffect(pairingText) {
        if (pairingText.isNotBlank()) {
            backupExpanded = true
        }
    }
    val presentation = buildPairingLoginPresentation(
        pairingText = pairingText,
        status = status,
        backupExpanded = backupExpanded,
        cameraDenied = cameraDenied
    )
    val openScanner = {
        cameraDenied = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanning = true
        } else {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PairingBrandHeader(presentation)
        PairingHero(presentation)
        PairingPrimaryCard(presentation = presentation, onOpenScanner = openScanner)
        PairingBackupCard(
            presentation = presentation,
            pairingText = pairingText,
            onPairingTextChange = onPairingTextChange,
            onConnect = onConnect,
            onToggleExpanded = { backupExpanded = !backupExpanded }
        )
        presentation.warningText?.let { warning ->
            PairingWarning(warning)
        }
        PairingStatusCard(presentation)

        if (scanning) {
            PairingScannerDialog(
                onDismiss = { scanning = false },
                onCodeScanned = { code ->
                    scanning = false
                    pendingScannedCode = code
                }
            )
        }
        pendingScannedCode?.let { scannedCode ->
            AlertDialog(
                onDismissRequest = { pendingScannedCode = null },
                title = { Text("确认连接桌面端") },
                text = {
                    Text(
                        "已识别二维码。确认后将使用这份配对信息连接 CodeRoam。",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                dismissButton = {
                    TextButton(onClick = { pendingScannedCode = null }) {
                        Text("取消连接")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingScannedCode = null
                            onScanned(scannedCode)
                        }
                    ) {
                        Text("确认连接")
                    }
                }
            )
        }
    }
}

@Composable
private fun PairingBrandHeader(presentation: PairingLoginPresentation) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF181A1F)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "C>",
                    color = Color(0xFF7CE0C3),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                presentation.brandTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                presentation.brandSubtitle,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.White.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Text(
                presentation.securityPill,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PairingHero(presentation: PairingLoginPresentation) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            presentation.heroTitle,
            color = TextPrimary,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                lineHeight = 38.sp
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            presentation.heroSubtitle,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun PairingPrimaryCard(
    presentation: PairingLoginPresentation,
    onOpenScanner: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF181A1F)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PairingQrMark(compact = presentation.showJsonInput)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        presentation.primaryTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        presentation.primaryDescription,
                        color = ScannerSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 19.sp
                    )
                }
            }
            PairingLightAction(
                label = presentation.primaryActionLabel,
                enabled = true,
                onClick = onOpenScanner
            )
        }
    }
}

@Composable
private fun PairingQrMark(compact: Boolean) {
    val size = if (compact) 76.dp else 118.dp
    val finderSize = if (compact) 22.dp else 34.dp
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(if (compact) 9.dp else 12.dp),
        color = Color(0xFFF8FAF7)
    ) {
        Box(modifier = Modifier.padding(if (compact) 7.dp else 10.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(finderSize)
                    .border(6.dp, Color(0xFF181A1F), RoundedCornerShape(5.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(finderSize)
                    .border(6.dp, Color(0xFF181A1F), RoundedCornerShape(5.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(finderSize)
                    .border(6.dp, Color(0xFF181A1F), RoundedCornerShape(5.dp))
            )
            Column(
                modifier = Modifier.align(Alignment.Center),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(3) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(3) { column ->
                            val filled = (row + column) % 2 == 0 || row == 1
                            Box(
                                modifier = Modifier
                                    .size(if (compact) 5.dp else 8.dp)
                                    .background(
                                        if (filled) Color(0xFF181A1F) else Color.Transparent,
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingBackupCard(
    presentation: PairingLoginPresentation,
    pairingText: String,
    onPairingTextChange: (String) -> Unit,
    onConnect: () -> Unit,
    onToggleExpanded: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.66f),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        presentation.backupTitle,
                        color = TextPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        presentation.backupDescription,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    if (presentation.showJsonInput) "⌃" else "⌄",
                    color = TextSecondary,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (presentation.showJsonInput) {
                OutlinedTextField(
                    value = pairingText,
                    onValueChange = onPairingTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 148.dp),
                    label = { Text("Pairing JSON") },
                    minLines = 5,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                PairingSurfaceAction(
                    label = presentation.backupActionLabel,
                    enabled = presentation.canConnectWithJson,
                    onClick = onConnect
                )
            }
        }
    }
}

@Composable
private fun PairingWarning(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFFFF8EC),
        border = BorderStroke(1.dp, Color(0xFFF2D6A2))
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            color = WarningText,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun PairingStatusCard(presentation: PairingLoginPresentation) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(0xFF27A95F), CircleShape)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    presentation.statusTitle,
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    presentation.statusDetail,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PairingLightAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) Color(0xFFF4F7F2) else Color(0xFFE4E4DF)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (enabled) TextPrimary else DisabledText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PairingSurfaceAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.46f),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (enabled) TextSecondary else DisabledText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PairingScannerDialog(
    onDismiss: () -> Unit,
    onCodeScanned: (String) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ScannerBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            QrScannerView(
                onCodeScanned = onCodeScanned,
                modifier = Modifier.fillMaxSize()
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
                color = ScannerBackground.copy(alpha = 0.72f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("扫描配对二维码", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("将二维码放入相机画面中央", color = ScannerSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodexWorkspace(
    state: CodexRemoteUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewConversation: () -> Unit,
    onSelectThread: (String) -> Unit,
    onThreadSearchChange: (String) -> Unit,
    onRefreshThreads: () -> Unit,
    onLoadMoreThreads: () -> Unit,
    onRenameThread: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectAccessMode: (AccessMode) -> Unit,
    onSelectReasoningEffort: (ReasoningEffort) -> Unit,
    onSetFastModeEnabled: (Boolean) -> Unit,
    onSelectTheme: (AppTheme) -> Unit,
    onSelectProjectWorkspace: (String) -> Unit,
    onRequestProjectPathSuggestions: (String) -> Unit,
    onSetPlanModeEnabled: (Boolean) -> Unit,
    onSetGoalModeEnabled: (Boolean) -> Unit,
    onRefreshGoal: () -> Unit,
    onSetGoalObjective: (String) -> Unit,
    onClearGoal: () -> Unit,
    onAddAttachments: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onSetShowArchivedThreads: (Boolean) -> Unit,
    onArchiveActiveThread: () -> Unit,
    onUnarchiveThread: (String) -> Unit,
    onClearPairing: () -> Unit,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onRespondToPendingApproval: (ApprovalDecision) -> Unit,
    onRespondToPendingInteraction: (InteractionAction, Map<String, List<String>>) -> Unit,
    onStartPlanImplementation: () -> Unit,
    onContinuePlanning: () -> Unit,
    onDismissActivePlan: () -> Unit,
    onEditUserMessage: (TimelineEntry.Text) -> Unit,
    onEditDraftRequestConsumed: (Long) -> Unit,
    onSelectWorkflowSection: (WorkflowSection) -> Unit,
    onRefreshWorkflowSection: () -> Unit,
    onLoadMoreAutomationApps: () -> Unit,
    onLoadMoreMcpServers: () -> Unit,
    onSendWorkflowPrompt: (String) -> Unit
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showAccessPicker by rememberSaveable { mutableStateOf(false) }
    var showProjectPicker by rememberSaveable { mutableStateOf(false) }
    var showToolMenu by rememberSaveable { mutableStateOf(false) }
    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onAddAttachments(uris)
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp
        val drawerWidth = if (isWide) 292.dp else minOf(maxWidth * 0.82f, 360.dp)

        if (isWide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Sidebar(
                    state = state,
                    modifier = Modifier
                        .width(drawerWidth)
                        .fillMaxHeight(),
                    onNewConversation = {
                        onSelectWorkflowSection(WorkflowSection.Chat)
                        onNewConversation()
                    },
                    onSelectThread = { threadId ->
                        onSelectWorkflowSection(WorkflowSection.Chat)
                        onSelectThread(threadId)
                    },
                    onThreadSearchChange = onThreadSearchChange,
                    onRefreshThreads = onRefreshThreads,
                    onLoadMoreThreads = onLoadMoreThreads,
                    onSelectWorkflowSection = onSelectWorkflowSection,
                    onOpenProjectPicker = { showProjectPicker = true },
                    onOpenSettings = { showSettings = true }
                )
                WorkspaceMainArea(
                    state = state,
                    showMenu = false,
                    onOpenMenu = {},
                    onSend = onSend,
                    onStop = onStop,
                    onRefreshThreads = onRefreshThreads,
                    onRenameThread = onRenameThread,
                    onOpenToolMenu = { showToolMenu = true },
                    onOpenProjectPicker = { showProjectPicker = true },
                    onOpenModelPicker = {
                        showModelPicker = true
                        onRefreshModels()
                    },
                    onOpenAccessPicker = { showAccessPicker = true },
                    onRemoveAttachment = onRemoveAttachment,
                    onRecoveryAction = onRecoveryAction,
                    onRespondToPendingApproval = onRespondToPendingApproval,
                    onRespondToPendingInteraction = onRespondToPendingInteraction,
                    onStartPlanImplementation = onStartPlanImplementation,
                    onContinuePlanning = onContinuePlanning,
                    onDismissActivePlan = onDismissActivePlan,
                    onEditUserMessage = onEditUserMessage,
                    onEditDraftRequestConsumed = onEditDraftRequestConsumed,
                    onSelectWorkflowSection = onSelectWorkflowSection,
                    onRefreshWorkflowSection = onRefreshWorkflowSection,
                    onLoadMoreAutomationApps = onLoadMoreAutomationApps,
                    onLoadMoreMcpServers = onLoadMoreMcpServers,
                    onSendWorkflowPrompt = onSendWorkflowPrompt
                )
            }
        } else {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = SidebarBackground,
                        modifier = Modifier.width(drawerWidth)
                    ) {
                        Sidebar(
                            state = state,
                            modifier = Modifier.fillMaxHeight(),
                            onNewConversation = {
                                onSelectWorkflowSection(WorkflowSection.Chat)
                                onNewConversation()
                                scope.launch { drawerState.close() }
                            },
                            onSelectThread = { threadId ->
                                onSelectWorkflowSection(WorkflowSection.Chat)
                                onSelectThread(threadId)
                                scope.launch { drawerState.close() }
                            },
                            onThreadSearchChange = onThreadSearchChange,
                            onRefreshThreads = onRefreshThreads,
                            onLoadMoreThreads = onLoadMoreThreads,
                            onSelectWorkflowSection = { section ->
                                onSelectWorkflowSection(section)
                                scope.launch { drawerState.close() }
                            },
                            onOpenProjectPicker = {
                                showProjectPicker = true
                                scope.launch { drawerState.close() }
                            },
                            onOpenSettings = {
                                showSettings = true
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
            ) {
                WorkspaceMainArea(
                    state = state,
                    showMenu = true,
                    onOpenMenu = { scope.launch { drawerState.open() } },
                    onSend = onSend,
                    onStop = onStop,
                    onRefreshThreads = onRefreshThreads,
                    onRenameThread = onRenameThread,
                    onOpenToolMenu = { showToolMenu = true },
                    onOpenProjectPicker = { showProjectPicker = true },
                    onOpenModelPicker = {
                        showModelPicker = true
                        onRefreshModels()
                    },
                    onOpenAccessPicker = { showAccessPicker = true },
                    onRemoveAttachment = onRemoveAttachment,
                    onRecoveryAction = onRecoveryAction,
                    onRespondToPendingApproval = onRespondToPendingApproval,
                    onRespondToPendingInteraction = onRespondToPendingInteraction,
                    onStartPlanImplementation = onStartPlanImplementation,
                    onContinuePlanning = onContinuePlanning,
                    onDismissActivePlan = onDismissActivePlan,
                    onEditUserMessage = onEditUserMessage,
                    onEditDraftRequestConsumed = onEditDraftRequestConsumed,
                    onSelectWorkflowSection = onSelectWorkflowSection,
                    onRefreshWorkflowSection = onRefreshWorkflowSection,
                    onLoadMoreAutomationApps = onLoadMoreAutomationApps,
                    onLoadMoreMcpServers = onLoadMoreMcpServers,
                    onSendWorkflowPrompt = onSendWorkflowPrompt
                )
            }
        }
    }
    if (showSettings) {
        SettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onSelectModel = onSelectModel,
            onSelectAccessMode = onSelectAccessMode,
            onSelectReasoningEffort = onSelectReasoningEffort,
            onSetFastModeEnabled = onSetFastModeEnabled,
            onSelectTheme = onSelectTheme,
            onSetShowArchivedThreads = onSetShowArchivedThreads,
            onArchiveActiveThread = onArchiveActiveThread,
            onUnarchiveThread = onUnarchiveThread,
            onClearPairing = onClearPairing
        )
    }
    if (showModelPicker) {
        ModelPickerDialog(
            state = state,
            onDismiss = { showModelPicker = false },
            onSelectModel = { modelId ->
                onSelectModel(modelId)
            },
            onSelectReasoningEffort = onSelectReasoningEffort
        )
    }
    if (showAccessPicker) {
        AccessModePickerDialog(
            state = state,
            onDismiss = { showAccessPicker = false },
            onSelectAccessMode = { mode ->
                onSelectAccessMode(mode)
                showAccessPicker = false
            }
        )
    }
    if (showProjectPicker) {
        ProjectPickerDialog(
            state = state,
            onDismiss = { showProjectPicker = false },
            onRequestPathSuggestions = onRequestProjectPathSuggestions,
            onSelectProject = { cwd ->
                onSelectProjectWorkspace(cwd)
                showProjectPicker = false
            }
        )
    }
    if (showToolMenu) {
        ToolMenuDialog(
            state = state,
            onDismiss = { showToolMenu = false },
            onSetPlanModeEnabled = onSetPlanModeEnabled,
            onSetGoalModeEnabled = { enabled ->
                onSetGoalModeEnabled(enabled)
                showToolMenu = false
            },
            onSetFastModeEnabled = onSetFastModeEnabled,
            onAddAttachmentClick = {
                attachmentLauncher.launch(arrayOf("*/*"))
                showToolMenu = false
            },
            onOpenProjectPicker = {
                showProjectPicker = true
                showToolMenu = false
            },
            onOpenPlugins = {
                onSelectWorkflowSection(WorkflowSection.Plugins)
                showToolMenu = false
            }
        )
    }
}

@Composable
private fun Sidebar(
    state: CodexRemoteUiState,
    modifier: Modifier,
    onNewConversation: () -> Unit,
    onSelectThread: (String) -> Unit,
    onThreadSearchChange: (String) -> Unit,
    onRefreshThreads: () -> Unit,
    onLoadMoreThreads: () -> Unit,
    onSelectWorkflowSection: (WorkflowSection) -> Unit,
    onOpenProjectPicker: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = modifier
            .background(SidebarBackground)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Codex", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            StatusDot(secure = state.secure)
        }

        SidebarAction(
            label = "新对话",
            enabled = true,
            selected = state.activeThreadId == null && state.workflowSection == WorkflowSection.Chat,
            trailing = "+",
            onClick = onNewConversation
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SidebarSearchField(
                value = state.threadSearchTerm,
                onValueChange = onThreadSearchChange,
                modifier = Modifier.weight(1f)
            )
            SidebarIconAction(label = "↻", enabled = true, onClick = onRefreshThreads)
        }

        Text(
            "最近会话",
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val visibleThreads = state.visibleThreads
            if (state.isLoadingThreads && visibleThreads.isEmpty()) {
                item {
                    Text(
                        "正在同步会话...",
                        modifier = Modifier.padding(8.dp),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else if (visibleThreads.isEmpty()) {
                item {
                    Text(
                        "暂无会话",
                        modifier = Modifier.padding(8.dp),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                items(visibleThreads, key = { it.id }) { thread ->
                    ThreadRow(
                        thread = thread,
                        selected = state.activeThreadId == thread.id,
                        onClick = { onSelectThread(thread.id) }
                    )
                }
            }
            if (state.nextThreadCursor != null && visibleThreads.isNotEmpty()) {
                item {
                    TextButton(
                        onClick = onLoadMoreThreads,
                        enabled = !state.isLoadingThreads,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.isLoadingThreads) "加载中..." else "加载更多")
                    }
                }
            }
        }

        SidebarAction(label = "项目 · ${state.projectName}", enabled = true, selected = true, onClick = onOpenProjectPicker)
        SidebarSectionTabs(
            selected = state.workflowSection,
            onSelectWorkflowSection = onSelectWorkflowSection
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.36f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusDot(secure = state.secure)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    state.hostDisplayName,
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state.drawerHostStatusLine,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SidebarIconTextAction(label = "设置", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun SidebarAction(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    trailing: String? = null,
    onClick: () -> Unit
) {
    val color = when {
        !enabled -> DisabledText
        selected -> TextPrimary
        else -> TextSecondary
    }
    val background = if (selected) SurfaceBackground else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.let {
            Text(
                it,
                color = color,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SidebarSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("⌕", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize
                ),
                cursorBrush = SolidColor(TextPrimary),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (value.isBlank()) {
                            Text("搜索会话", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
private fun SidebarIconAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        IconButton(enabled = enabled, onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Text(label, color = if (enabled) TextSecondary else DisabledText, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SidebarIconTextAction(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.62f)
    ) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SidebarSectionTabs(
    selected: WorkflowSection,
    onSelectWorkflowSection: (WorkflowSection) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.36f),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            listOf(
                WorkflowSection.Chat,
                WorkflowSection.Skills,
                WorkflowSection.Plugins,
                WorkflowSection.Automation
            ).forEach { section ->
                val isSelected = selected == section
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) SurfaceBackground else Color.Transparent)
                        .clickable { onSelectWorkflowSection(section) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        section.sidebarLabel(),
                        color = if (isSelected) TextPrimary else TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun WorkflowSection.sidebarLabel(): String = when (this) {
    WorkflowSection.Chat -> "对话"
    WorkflowSection.Skills -> "技能"
    WorkflowSection.Plugins -> "插件"
    WorkflowSection.Automation -> "自动化"
}

@Composable
private fun SidebarCompactAction(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = when {
        !enabled -> DisabledText
        selected -> TextPrimary
        else -> TextSecondary
    }
    val background = if (selected) SurfaceBackground else Color.Transparent
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ThreadRow(
    thread: ThreadSummary,
    selected: Boolean,
    onClick: () -> Unit
) {
    val preview = thread.displayPreviewLine()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SurfaceBackground else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, Color(0x11000000), RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            thread.displayTitle(),
            color = if (selected) TextPrimary else TextSecondary,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (preview.isNotBlank()) {
            Text(
                preview,
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            if (selected) "已同步 · ${thread.displayMetaLine()}" else thread.displayMetaLine(),
            color = DisabledText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WorkspaceMainArea(
    state: CodexRemoteUiState,
    showMenu: Boolean,
    onOpenMenu: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRefreshThreads: () -> Unit,
    onRenameThread: (String) -> Unit,
    onOpenToolMenu: () -> Unit,
    onOpenProjectPicker: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenAccessPicker: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onRespondToPendingApproval: (ApprovalDecision) -> Unit,
    onRespondToPendingInteraction: (InteractionAction, Map<String, List<String>>) -> Unit,
    onStartPlanImplementation: () -> Unit,
    onContinuePlanning: () -> Unit,
    onDismissActivePlan: () -> Unit,
    onEditUserMessage: (TimelineEntry.Text) -> Unit,
    onEditDraftRequestConsumed: (Long) -> Unit,
    onSelectWorkflowSection: (WorkflowSection) -> Unit,
    onRefreshWorkflowSection: () -> Unit,
    onLoadMoreAutomationApps: () -> Unit,
    onLoadMoreMcpServers: () -> Unit,
    onSendWorkflowPrompt: (String) -> Unit
) {
    if (state.workflowSection == WorkflowSection.Chat) {
        ChatWorkArea(
            state = state,
            showMenu = showMenu,
            onOpenMenu = onOpenMenu,
            onSend = onSend,
            onStop = onStop,
            onRefreshThreads = onRefreshThreads,
            onRenameThread = onRenameThread,
            onOpenToolMenu = onOpenToolMenu,
            onOpenProjectPicker = onOpenProjectPicker,
            onOpenModelPicker = onOpenModelPicker,
            onOpenAccessPicker = onOpenAccessPicker,
            onRemoveAttachment = onRemoveAttachment,
            onRecoveryAction = onRecoveryAction,
            onRespondToPendingApproval = onRespondToPendingApproval,
            onRespondToPendingInteraction = onRespondToPendingInteraction,
            onStartPlanImplementation = onStartPlanImplementation,
            onContinuePlanning = onContinuePlanning,
            onDismissActivePlan = onDismissActivePlan,
            onEditUserMessage = onEditUserMessage,
            onEditDraftRequestConsumed = onEditDraftRequestConsumed
        )
    } else {
        WorkflowWorkArea(
            state = state,
            showMenu = showMenu,
            onOpenMenu = onOpenMenu,
            onSelectWorkflowSection = onSelectWorkflowSection,
            onRefreshWorkflowSection = onRefreshWorkflowSection,
            onLoadMoreAutomationApps = onLoadMoreAutomationApps,
            onLoadMoreMcpServers = onLoadMoreMcpServers,
            onSendPrompt = onSendWorkflowPrompt
        )
    }
}

@Composable
private fun ChatWorkArea(
    state: CodexRemoteUiState,
    showMenu: Boolean,
    onOpenMenu: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRefreshThreads: () -> Unit,
    onRenameThread: (String) -> Unit,
    onOpenToolMenu: () -> Unit,
    onOpenProjectPicker: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenAccessPicker: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onRespondToPendingApproval: (ApprovalDecision) -> Unit,
    onRespondToPendingInteraction: (InteractionAction, Map<String, List<String>>) -> Unit,
    onStartPlanImplementation: () -> Unit,
    onContinuePlanning: () -> Unit,
    onDismissActivePlan: () -> Unit,
    onEditUserMessage: (TimelineEntry.Text) -> Unit,
    onEditDraftRequestConsumed: (Long) -> Unit
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val editDraftRequest = state.editDraftRequest
    LaunchedEffect(editDraftRequest?.id) {
        editDraftRequest?.let { request ->
            draft = request.text
            onEditDraftRequestConsumed(request.id)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        TopBar(
            state = state,
            showMenu = showMenu,
            onOpenMenu = onOpenMenu,
            onRefreshThreads = onRefreshThreads,
            onRenameThread = onRenameThread
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (state.isLoadingHistory) {
                Text(
                    "正在加载会话...",
                    modifier = Modifier.align(Alignment.Center),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (state.timelineEntries.isEmpty()) {
                EmptyConversation(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onQuickPrompt = onSend
                )
            } else {
                MessageList(
                    entries = state.timelineEntries,
                    onRecoveryAction = onRecoveryAction,
                    onRespondToPendingApproval = onRespondToPendingApproval,
                    onRespondToPendingInteraction = onRespondToPendingInteraction,
                    onStartPlanImplementation = onStartPlanImplementation,
                    onContinuePlanning = onContinuePlanning,
                    onDismissActivePlan = onDismissActivePlan,
                    onEditUserMessage = onEditUserMessage
                )
            }
        }
        Composer(
            draft = draft,
            state = state,
            onDraftChange = { draft = it },
            onSend = {
                val clean = draft.trim()
                if (clean.isNotEmpty()) {
                    onSend(clean)
                    draft = ""
                }
            },
            onStop = onStop,
            onOpenToolMenu = onOpenToolMenu,
            onOpenProjectPicker = onOpenProjectPicker,
            onOpenModelPicker = onOpenModelPicker,
            onOpenAccessPicker = onOpenAccessPicker,
            onRemoveAttachment = onRemoveAttachment
        )
    }
}

@Composable
private fun TopBar(
    state: CodexRemoteUiState,
    showMenu: Boolean,
    onOpenMenu: () -> Unit,
    onRefreshThreads: () -> Unit,
    onRenameThread: (String) -> Unit
) {
    var editing by rememberSaveable(state.activeThreadId) { mutableStateOf(false) }
    var draftName by rememberSaveable(state.activeThreadId, state.activeThreadTitle) {
        mutableStateOf(state.activeThreadTitle)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showMenu) {
            IconButton(onClick = onOpenMenu, modifier = Modifier.size(40.dp)) {
                Text("☰", color = TextSecondary, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (editing && state.activeThreadId != null) {
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = {
                onRenameThread(draftName)
                editing = false
            }) {
                Text("保存")
            }
            TextButton(onClick = {
                draftName = state.activeThreadTitle
                editing = false
            }) {
                Text("取消")
            }
        } else {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    state.activeThreadTitle,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        state.projectName,
                        modifier = Modifier.weight(1f, fill = false),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("·", color = DisabledText, style = MaterialTheme.typography.labelMedium)
                    Text(
                        state.hostDisplayName,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    StatusDot(secure = state.secure, size = 8.dp)
                }
            }
            if (state.activeThreadId != null) {
                TextButton(
                    onClick = onRefreshThreads,
                    enabled = state.secure && !state.isLoadingHistory
                ) {
                    Text(if (state.isSoftSyncing || state.isLoadingThreads) "同步中" else "同步")
                }
                TextButton(onClick = {
                    draftName = state.activeThreadTitle
                    editing = true
                }) {
                    Text("⋮")
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EmptyConversation(
    state: CodexRemoteUiState,
    modifier: Modifier,
    onQuickPrompt: (String) -> Unit
) {
    val prompt = state.emptyConversationPrompt
    BoxWithConstraints(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(top = maxHeight * 0.24f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                prompt.title,
                color = TextPrimary,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 42.sp
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                prompt.subtitle,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 22.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (prompt.quickActions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    prompt.quickActions.forEach { action ->
                        QuickPromptChip(
                            label = action.label,
                            onClick = { onQuickPrompt(action.prompt) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPromptChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MessageList(
    entries: List<TimelineEntry>,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onRespondToPendingApproval: (ApprovalDecision) -> Unit,
    onRespondToPendingInteraction: (InteractionAction, Map<String, List<String>>) -> Unit,
    onStartPlanImplementation: () -> Unit,
    onContinuePlanning: () -> Unit,
    onDismissActivePlan: () -> Unit,
    onEditUserMessage: (TimelineEntry.Text) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val shouldShowJump by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            layout.totalItemsCount > 0 && lastVisible < layout.totalItemsCount - 2
        }
    }
    LaunchedEffect(entries.size, entries.lastOrNull()?.key) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size + 1)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            itemsIndexed(
                entries,
                key = { index, entry ->
                    "${entry.key}-$index"
                }
            ) { _, entry ->
                TimelineEntryRow(
                    entry = entry,
                    onRecoveryAction = onRecoveryAction,
                    onRespondToPendingApproval = onRespondToPendingApproval,
                    onRespondToPendingInteraction = onRespondToPendingInteraction,
                    onStartPlanImplementation = onStartPlanImplementation,
                    onContinuePlanning = onContinuePlanning,
                    onDismissActivePlan = onDismissActivePlan,
                    onEditUserMessage = onEditUserMessage
                )
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
        if (shouldShowJump) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 12.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.White,
                border = BorderStroke(1.dp, BorderColor)
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(entries.size + 1)
                        }
                    }
                ) {
                    Text("最新")
                }
            }
        }
    }
}

@Composable
private fun TimelineEntryRow(
    entry: TimelineEntry,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onRespondToPendingApproval: (ApprovalDecision) -> Unit,
    onRespondToPendingInteraction: (InteractionAction, Map<String, List<String>>) -> Unit,
    onStartPlanImplementation: () -> Unit,
    onContinuePlanning: () -> Unit,
    onDismissActivePlan: () -> Unit,
    onEditUserMessage: (TimelineEntry.Text) -> Unit
) {
    when (entry) {
        is TimelineEntry.Text -> TimelineTextEntry(entry, onEditUserMessage)
        is TimelineEntry.Reasoning -> TimelineReasoningEntry(entry)
        is TimelineEntry.CommandExecution -> TimelineCommandEntry(entry)
        is TimelineEntry.FileChange -> TimelineSystemLine(title = "文件", body = entry.body)
        is TimelineEntry.Diff -> TimelineDiffEntry(entry)
        is TimelineEntry.ApprovalRequest -> TimelineApprovalEntry(entry.approval, onRespondToPendingApproval)
        is TimelineEntry.InputRequest -> TimelineInputRequestEntry(entry.interaction, onRespondToPendingInteraction)
        is TimelineEntry.PlanReview -> TimelinePlanEntry(
            plan = entry.plan,
            onStartPlanImplementation = onStartPlanImplementation,
            onContinuePlanning = onContinuePlanning,
            onDismissActivePlan = onDismissActivePlan
        )
        is TimelineEntry.RuntimeNotice -> TimelineNoticeEntry(entry.notice)
        is TimelineEntry.SystemNotice -> TimelineSystemLine(title = entry.title, body = entry.body)
        is TimelineEntry.Failure -> TimelineFailureEntry(entry, onRecoveryAction)
    }
}

@Composable
private fun TimelineTextEntry(
    entry: TimelineEntry.Text,
    onEditUserMessage: (TimelineEntry.Text) -> Unit
) {
    val isUser = entry.role == TimelineTextRole.User
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = if (isUser) Modifier.fillMaxWidth(0.86f) else Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!isUser) {
                Text("Codex", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            FormattedMessageText(text = entry.text, isUser = isUser)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                MessageActionIconButton(
                    label = "⧉",
                    onClick = { clipboard.setText(AnnotatedString(entry.text)) }
                )
                if (isUser && !entry.threadId.isNullOrBlank() && !entry.turnId.isNullOrBlank()) {
                    MessageActionIconButton(
                        label = "✎",
                        onClick = { onEditUserMessage(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageActionIconButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    IconButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(32.dp)
    ) {
        Text(
            label,
            color = if (enabled) TextSecondary else DisabledText,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun FormattedMessageText(text: String, isUser: Boolean) {
    val blocks = remember(text) { parseMessageMarkdown(text) }
    val textColor = TextPrimary
    val codeBackground = if (isUser) Color(0xFFDDE9FA) else Color(0xFFF1F1EC)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MessageMarkdownBlock.Paragraph -> {
                    SelectionContainer {
                        Text(
                            buildAnnotatedMessage(block.spans, codeBackground),
                            color = textColor,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 24.sp
                        )
                    }
                }
                is MessageMarkdownBlock.Code -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = codeBackground,
                        border = BorderStroke(1.dp, BorderColor)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            block.language?.takeIf { it.isNotBlank() }?.let { language ->
                                Text(
                                    language,
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            SelectionContainer {
                                Text(
                                    block.code,
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun buildAnnotatedMessage(
    spans: List<MessageMarkdownSpan>,
    codeBackground: Color
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        when (span) {
            is MessageMarkdownSpan.Text -> append(span.text)
            is MessageMarkdownSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(span.text)
            }
            is MessageMarkdownSpan.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                    color = TextPrimary
                )
            ) {
                append(span.text)
            }
            is MessageMarkdownSpan.Link -> withStyle(
                SpanStyle(
                    color = Accent,
                    fontWeight = FontWeight.SemiBold
                )
            ) {
                append(span.label)
            }
        }
    }
}

@Composable
private fun TimelineReasoningEntry(entry: TimelineEntry.Reasoning) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            if (expanded) "思考 · 收起" else "思考 · 展开",
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            entry.body.ifBlank { "正在思考..." },
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
        )
        if (expanded) {
            MessageActionIconButton(
                label = "⧉",
                onClick = { clipboard.setText(AnnotatedString(entry.body)) }
            )
        }
    }
}

@Composable
private fun TimelineCommandEntry(entry: TimelineEntry.CommandExecution) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "已执行 \"${entry.commandLine}\"",
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (expanded && entry.body.isNotBlank()) {
            Text(
                entry.body,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 20,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TimelineSystemLine(title: String, body: String) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(body, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        MessageActionIconButton(
            label = "⧉",
            onClick = { clipboard.setText(AnnotatedString(body)) }
        )
    }
}

@Composable
private fun TimelineDiffEntry(entry: TimelineEntry.Diff) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(if (expanded) "本轮 Diff · 收起" else "本轮 Diff · 展开", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        Text(
            entry.diff.diff,
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = if (expanded) 80 else 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimelineApprovalEntry(
    approval: PendingApproval,
    onRespond: (ApprovalDecision) -> Unit
) {
    InlineActionPanel(title = approval.title, body = approval.body) {
        TextButton(onClick = { onRespond(ApprovalDecision.Accept) }) {
            Text("允许一次")
        }
        TextButton(onClick = { onRespond(ApprovalDecision.AcceptForSession) }) {
            Text("本会话允许")
        }
        TextButton(onClick = { onRespond(ApprovalDecision.Decline) }) {
            Text("拒绝")
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimelineInputRequestEntry(
    interaction: PendingInteraction,
    onRespond: (InteractionAction, Map<String, List<String>>) -> Unit
) {
    val answers = remember(interaction.requestId) { mutableStateMapOf<String, String>() }
    val directQuestion = interaction.questions.singleOrNull()?.takeIf { question ->
        question.options.isNotEmpty() && !question.isOther
    }
    val allQuestionsAnswered = interaction.questions.all { question ->
        answers[question.id]?.isNotBlank() == true
    }
    InlineActionPanel(title = interaction.title, body = interaction.body) {
        if (directQuestion != null) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(directQuestion.question, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                directQuestion.options.forEach { option ->
                    InteractionOptionCard(
                        option = option,
                        selected = false,
                        onClick = {
                            onRespond(
                                InteractionAction.Accept,
                                mapOf(directQuestion.id to listOf(option.label))
                            )
                        }
                    )
                }
                TextButton(onClick = { onRespond(InteractionAction.Cancel, emptyMap()) }) {
                    Text("暂不处理")
                }
            }
        } else {
            interaction.questions.forEach { question ->
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(question.question, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                    if (question.options.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            question.options.forEach { option ->
                                ChoicePill(
                                    label = option.label,
                                    selected = answers[question.id] == option.label,
                                    onClick = { answers[question.id] = option.label }
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = answers[question.id].orEmpty(),
                            onValueChange = { answers[question.id] = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 3,
                            visualTransformation = if (question.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = allQuestionsAnswered,
                    onClick = {
                        onRespond(
                            InteractionAction.Accept,
                            answers.mapValues { listOf(it.value) }
                        )
                    }
                ) {
                    Text("提交")
                }
                TextButton(onClick = { onRespond(InteractionAction.Cancel, emptyMap()) }) {
                    Text("取消")
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimelinePlanEntry(
    plan: ActiveTurnPlan,
    onStartPlanImplementation: () -> Unit,
    onContinuePlanning: () -> Unit,
    onDismissActivePlan: () -> Unit
) {
    PlanReviewCard(
        plan = plan,
        actionsEnabled = true,
        onStartPlanImplementation = onStartPlanImplementation,
        onContinuePlanning = onContinuePlanning,
        onDismissActivePlan = onDismissActivePlan
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimelineNoticeEntry(notice: RuntimeNotice) {
    InlineActionPanel(title = notice.title, body = notice.body, tone = notice.tone()) {}
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimelineFailureEntry(
    entry: TimelineEntry.Failure,
    onRecoveryAction: (RecoveryAction) -> Unit
) {
    InlineActionPanel(title = entry.title, body = entry.detail, tone = MessageTone.Error) {
        val actions = entry.actions.ifEmpty {
            listOf(RecoveryActionUi(RecoveryAction.Dismiss, "忽略"))
        }
        actions.forEach { action ->
            TextButton(onClick = { onRecoveryAction(action.action) }) {
                Text(action.label)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun InlineActionPanel(
    title: String,
    body: String,
    tone: MessageTone = MessageTone.Neutral,
    content: @Composable FlowRowScope.() -> Unit
) {
    val colors = messageToneColors(tone)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.background,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = colors.title, style = MaterialTheme.typography.labelLarge)
            body.takeIf { it.isNotBlank() }?.let {
                Text(it, color = colors.body, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) TextPrimary else Color.White,
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = if (selected) Color.White else TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InteractionOptionCard(
    option: PendingInteractionOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = if (selected) {
        messageToneColors(MessageTone.Warning)
    } else {
        messageToneColors(MessageTone.Neutral)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) colors.background else SurfaceBackground,
        border = BorderStroke(1.dp, if (selected) colors.border else BorderColor)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                option.label,
                color = if (selected) colors.title else TextPrimary,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
            )
            option.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(description, color = if (selected) colors.body else TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WorkflowCards(
    state: CodexRemoteUiState,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onRespondToPendingApproval: (ApprovalDecision) -> Unit,
    onRespondToPendingInteraction: (InteractionAction, Map<String, List<String>>) -> Unit,
    onStartPlanImplementation: () -> Unit,
    onContinuePlanning: () -> Unit,
    onDismissActivePlan: () -> Unit
) {
    if (
        state.recoverableError == null &&
        state.pendingApproval == null &&
        state.pendingInteraction == null &&
        state.turnDiff == null &&
        state.runtimeNotices.isEmpty() &&
        state.activePlan == null
    ) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        state.recoverableError?.let { error ->
            RecoverableErrorCard(error = error, onRecoveryAction = onRecoveryAction)
        }
        state.pendingApproval?.let { approval ->
            PendingApprovalCard(approval = approval, onRespond = onRespondToPendingApproval)
        }
        state.pendingInteraction?.let { interaction ->
            PendingInteractionCard(interaction = interaction, onRespond = onRespondToPendingInteraction)
        }
        state.turnDiff?.let { diff ->
            TurnDiffCard(diff = diff)
        }
        state.runtimeNotices.takeLast(3).forEach { notice ->
            RuntimeNoticeCard(notice = notice)
        }
        state.activePlan?.let { plan ->
            PlanReviewCard(
                plan = plan,
                actionsEnabled = true,
                onStartPlanImplementation = onStartPlanImplementation,
                onContinuePlanning = onContinuePlanning,
                onDismissActivePlan = onDismissActivePlan
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun RecoverableErrorCard(
    error: RecoverableError,
    onRecoveryAction: (RecoveryAction) -> Unit
) {
    val colors = messageToneColors(error.tone())
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.background,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(error.title, color = colors.title, style = MaterialTheme.typography.labelLarge)
            Text(error.detail, color = colors.body, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val actions = error.actions.ifEmpty {
                    listOf(RecoveryActionUi(RecoveryAction.Dismiss, "忽略"))
                }
                actions.forEach { action ->
                    TextButton(onClick = { onRecoveryAction(action.action) }) {
                        Text(action.label)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PendingApprovalCard(
    approval: PendingApproval,
    onRespond: (ApprovalDecision) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(approval.title, color = TextPrimary, style = MaterialTheme.typography.labelLarge)
            Text(approval.body, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = { onRespond(ApprovalDecision.Accept) }) {
                    Text("允许一次")
                }
                TextButton(onClick = { onRespond(ApprovalDecision.AcceptForSession) }) {
                    Text("本会话允许")
                }
                TextButton(onClick = { onRespond(ApprovalDecision.Decline) }) {
                    Text("拒绝")
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TurnDiffCard(diff: TurnDiffSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("本轮 Diff", color = TextPrimary, style = MaterialTheme.typography.labelLarge)
            Text(
                diff.diff,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RuntimeNoticeCard(notice: RuntimeNotice) {
    val colors = messageToneColors(notice.tone())
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.background,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(notice.title, color = colors.title, style = MaterialTheme.typography.labelLarge)
            Text(
                notice.body,
                color = colors.body,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PendingInteractionCard(
    interaction: PendingInteraction,
    onRespond: (InteractionAction, Map<String, List<String>>) -> Unit
) {
    val answers = remember(interaction.requestId) { mutableStateMapOf<String, String>() }
    val directQuestion = interaction.questions.singleOrNull()?.takeIf { question ->
        question.options.isNotEmpty() && !question.isOther
    }
    val allQuestionsAnswered = interaction.questions.all { question ->
        answers[question.id]?.isNotBlank() == true
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(interaction.title, color = TextPrimary, style = MaterialTheme.typography.labelLarge)
            Text(interaction.body, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            interaction.url?.let { url ->
                Text(url, color = TextPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (directQuestion != null) {
                Text(directQuestion.question, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    directQuestion.options.forEach { option ->
                        InteractionOptionCard(
                            option = option,
                            selected = false,
                            onClick = {
                                onRespond(
                                    InteractionAction.Accept,
                                    mapOf(directQuestion.id to listOf(option.label))
                                )
                            }
                        )
                    }
                    TextButton(onClick = { onRespond(InteractionAction.Cancel, emptyMap()) }) {
                        Text("暂不处理")
                    }
                }
            } else {
                interaction.questions.forEach { question ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        question.header?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(question.question, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                        if (question.options.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                question.options.forEach { option ->
                                    val selected = answers[question.id] == option.label
                                    TextButton(
                                        onClick = {
                                            answers[question.id] = option.label
                                        }
                                    ) {
                                        Text(if (selected) "✓ ${option.label}" else option.label)
                                    }
                                }
                            }
                        }
                        if (question.options.isEmpty() || question.isOther) {
                            OutlinedTextField(
                                value = answers[question.id].orEmpty(),
                                onValueChange = { answers[question.id] = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 1,
                                maxLines = 4,
                                visualTransformation = if (question.isSecret) {
                                    PasswordVisualTransformation()
                                } else {
                                    VisualTransformation.None
                                },
                                label = { Text(question.header ?: "输入") }
                            )
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (interaction.questions.isNotEmpty()) {
                        TextButton(
                            enabled = allQuestionsAnswered,
                            onClick = {
                                onRespond(
                                    InteractionAction.Accept,
                                    answers.mapValues { listOf(it.value) }
                                )
                            }
                        ) {
                            Text("提交")
                        }
                    } else if (interaction.kind == PendingInteractionKind.McpElicitation) {
                        TextButton(onClick = { onRespond(InteractionAction.Accept, emptyMap()) }) {
                            Text("已完成")
                        }
                    }
                    TextButton(onClick = { onRespond(InteractionAction.Cancel, emptyMap()) }) {
                        Text("取消")
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PlanReviewCard(
    plan: ActiveTurnPlan,
    actionsEnabled: Boolean,
    onStartPlanImplementation: () -> Unit,
    onContinuePlanning: () -> Unit,
    onDismissActivePlan: () -> Unit
) {
    val actions = defaultPlanReviewActions()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = BorderStroke(1.dp, BorderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(planReviewPromptTitle(), color = TextPrimary, style = MaterialTheme.typography.labelLarge)
            plan.explanation?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            plan.steps.forEach { step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(step.status.shortLabel(), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Text(step.step, modifier = Modifier.weight(1f), color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    PlanReviewActionCard(
                        action = action,
                        enabled = actionsEnabled || action.action == PlanReviewAction.Dismiss,
                        onClick = {
                            when (action.action) {
                                PlanReviewAction.ExecuteTask -> onStartPlanImplementation()
                                PlanReviewAction.ContinuePlanning -> onContinuePlanning()
                                PlanReviewAction.Dismiss -> onDismissActivePlan()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanReviewActionCard(
    action: PlanReviewActionUi,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = if (action.recommended) {
        messageToneColors(MessageTone.Warning)
    } else {
        messageToneColors(MessageTone.Neutral)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (action.recommended) colors.background else SurfaceBackground,
        border = BorderStroke(1.dp, if (action.recommended) colors.border else BorderColor)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    action.label,
                    color = if (enabled) colors.title else DisabledText,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                )
                if (action.recommended) {
                    Text("推荐", color = colors.title, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                action.description,
                color = if (enabled) colors.body else DisabledText,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun TurnPlanStatus.shortLabel(): String = when (this) {
    TurnPlanStatus.Pending -> "待办"
    TurnPlanStatus.InProgress -> "进行"
    TurnPlanStatus.Completed -> "完成"
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun Composer(
    draft: String,
    state: CodexRemoteUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenToolMenu: () -> Unit,
    onOpenProjectPicker: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenAccessPicker: () -> Unit,
    onRemoveAttachment: (String) -> Unit
) {
    val hasUploadingAttachments = state.pendingAttachments.any { it.status == AttachmentUploadStatus.Uploading }
    val canSend = draft.isNotBlank() && state.secure && !hasUploadingAttachments
    val controlLabels = state.composerControlLabels
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state.pendingAttachments.isNotEmpty()) {
            AttachmentChips(
                attachments = state.pendingAttachments,
                onRemoveAttachment = onRemoveAttachment
            )
        }
        state.attachmentUploadError?.let { error ->
            Text(error, color = WarningText, style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InfoChip(controlLabels.model, onClick = onOpenModelPicker)
            InfoChip(controlLabels.reasoning, onClick = onOpenModelPicker)
            InfoChip(controlLabels.access, onClick = onOpenAccessPicker)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(34.dp),
            color = Color.White.copy(alpha = 0.88f),
            border = BorderStroke(1.dp, BorderColor),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RoundActionButton(
                    label = "+",
                    enabled = true,
                    filled = false,
                    onClick = onOpenToolMenu
                )
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 148.dp),
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize
                ),
                cursorBrush = SolidColor(TextPrimary),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (draft.isBlank()) {
                            Text("向 Codex 提问", color = Color(0xFF8B8D84), style = MaterialTheme.typography.bodyLarge)
                        }
                        innerTextField()
                    }
                }
            )
                RoundActionButton(
                    label = if (state.isGenerating) "■" else "↑",
                    enabled = state.isGenerating || canSend,
                    filled = state.isGenerating || canSend,
                    onClick = if (state.isGenerating) onStop else onSend
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AttachmentChips(
    attachments: List<PendingAttachmentUi>,
    onRemoveAttachment: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            val statusLabel = when (attachment.status) {
                AttachmentUploadStatus.Uploading -> "上传中"
                AttachmentUploadStatus.Ready -> if (attachment.isImage) "图片" else "文件"
                AttachmentUploadStatus.Failed -> "失败"
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF3F3EF),
                border = BorderStroke(1.dp, BorderColor)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(statusLabel, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    Text(
                        attachment.displayName,
                        modifier = Modifier.widthIn(max = 180.dp),
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = { onRemoveAttachment(attachment.id) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("×", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    state: CodexRemoteUiState,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectAccessMode: (AccessMode) -> Unit,
    onSelectReasoningEffort: (ReasoningEffort) -> Unit,
    onSetFastModeEnabled: (Boolean) -> Unit,
    onSelectTheme: (AppTheme) -> Unit,
    onSetShowArchivedThreads: (Boolean) -> Unit,
    onArchiveActiveThread: () -> Unit,
    onUnarchiveThread: (String) -> Unit,
    onClearPairing: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.hostDisplayName, color = TextPrimary, style = MaterialTheme.typography.labelLarge)
                    Text(state.status, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    if (state.reconnectMessage != null) {
                        Text(state.reconnectMessage, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }

                ModelOptionsSection(state = state, onSelectModel = onSelectModel)

                ReasoningEffortOptionsSection(state = state, onSelectReasoningEffort = onSelectReasoningEffort)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("快速模式", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.fastModeEnabled, onCheckedChange = onSetFastModeEnabled)
                }

                AccessModeOptionsSection(state = state, onSelectAccessMode = onSelectAccessMode)

                ThemeOptionsSection(state = state, onSelectTheme = onSelectTheme)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("显示归档会话", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = state.showArchivedThreads, onCheckedChange = onSetShowArchivedThreads)
                }

                if (state.activeThreadId != null) {
                    TextButton(onClick = onArchiveActiveThread, modifier = Modifier.fillMaxWidth()) {
                        Text("归档当前会话")
                    }
                }
                state.threads.filter { it.id in state.archivedThreadIds }.forEach { thread ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            thread.displayTitle(),
                            modifier = Modifier.weight(1f),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { onUnarchiveThread(thread.id) }) {
                            Text("恢复")
                        }
                    }
                }

                TextButton(onClick = onClearPairing, modifier = Modifier.fillMaxWidth()) {
                    Text("清除配对")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun ModelPickerDialog(
    state: CodexRemoteUiState,
    onDismiss: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectReasoningEffort: (ReasoningEffort) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行设置") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModelOptionsSection(state = state, onSelectModel = onSelectModel)
                ReasoningEffortOptionsSection(state = state, onSelectReasoningEffort = onSelectReasoningEffort)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun AccessModePickerDialog(
    state: CodexRemoteUiState,
    onDismiss: () -> Unit,
    onSelectAccessMode: (AccessMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("权限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccessModeOptionsSection(state = state, onSelectAccessMode = onSelectAccessMode)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun ProjectPickerDialog(
    state: CodexRemoteUiState,
    onDismiss: () -> Unit,
    onRequestPathSuggestions: (String) -> Unit,
    onSelectProject: (String) -> Unit
) {
    var manualPath by rememberSaveable { mutableStateOf("") }
    val projects = state.selectableProjectWorkspaces()
    val trimmedManualPath = manualPath.trim()
    val cleanManualPath = trimmedManualPath.trimEnd('/').ifEmpty {
        if (trimmedManualPath.startsWith("/")) "/" else ""
    }
    val pathSuggestions = buildProjectPathSuggestions(
        state.projectPathSuggestions,
        prefix = state.projectPathSuggestionPrefix
    ).take(12)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("项目") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = manualPath,
                    onValueChange = {
                        manualPath = it
                        onRequestPathSuggestions(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("指定路径") },
                    placeholder = { Text("/home/user/project") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                if (state.isLoadingProjectPathSuggestions) {
                    Text("正在读取 ${state.projectPathSuggestionPath ?: cleanManualPath}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (state.isValidatingProjectPath) {
                    Text("正在校验项目路径", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                state.projectPathSuggestionError?.let { error ->
                    Text(error, color = WarningText, style = MaterialTheme.typography.bodySmall)
                }
                state.projectPathValidationError?.let { error ->
                    Text(error, color = WarningText, style = MaterialTheme.typography.bodySmall)
                }
                if (pathSuggestions.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = SurfaceBackground,
                        border = BorderStroke(1.dp, BorderColor)
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            pathSuggestions.forEach { suggestion ->
                                ProjectPathSuggestionRow(
                                    suggestion = suggestion,
                                    onClick = {
                                        val selectedPath = suggestion.pathForManualSelection()
                                        manualPath = selectedPath
                                        onRequestPathSuggestions(selectedPath)
                                    }
                                )
                            }
                        }
                    }
                }
                TextButton(
                    enabled = cleanManualPath.isNotBlank() && !state.isValidatingProjectPath,
                    onClick = { onSelectProject(cleanManualPath) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("使用路径并新建对话")
                }
                if (projects.isEmpty()) {
                    Text("暂无可选项目，刷新会话后会显示最近工作区。", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                projects.forEach { project ->
                    SettingRadioRow(
                        label = "${project.name}  ${project.cwd}",
                        selected = state.selectedProjectCwd == project.cwd || (
                            state.selectedProjectCwd == null && state.projectName == project.name
                        ),
                        onClick = { onSelectProject(project.cwd) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun ProjectPathSuggestionRow(
    suggestion: ProjectPathSuggestionUi,
    onClick: () -> Unit
) {
    val enabled = suggestion.isDirectory
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (suggestion.isDirectory) "目录" else "文件",
            color = if (suggestion.isDirectory) Color(0xFF0F766E) else TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            suggestion.path,
            modifier = Modifier.weight(1f),
            color = if (enabled) TextPrimary else TextSecondary,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ToolMenuDialog(
    state: CodexRemoteUiState,
    onDismiss: () -> Unit,
    onSetPlanModeEnabled: (Boolean) -> Unit,
    onSetGoalModeEnabled: (Boolean) -> Unit,
    onSetFastModeEnabled: (Boolean) -> Unit,
    onAddAttachmentClick: () -> Unit,
    onOpenProjectPicker: () -> Unit,
    onOpenPlugins: () -> Unit
) {
    val goalChecked = state.goalModeEnabled || state.activeGoal != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("工具") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolMenuAction(label = "添加照片和文件", enabled = state.secure, onClick = onAddAttachmentClick)
                ToolMenuAction(label = "项目", detail = state.projectName, enabled = true, onClick = onOpenProjectPicker)
                ToolMenuSwitch(
                    label = "计划模式",
                    checked = state.planModeEnabled,
                    onCheckedChange = onSetPlanModeEnabled
                )
                ToolMenuSwitch(
                    label = "追求目标",
                    detail = state.activeGoal?.objective ?: if (goalChecked) "发送后持续执行当前任务" else "发送后自动追求当前任务",
                    checked = goalChecked,
                    onCheckedChange = onSetGoalModeEnabled
                )
                ToolMenuSwitch(
                    label = "快速模式",
                    detail = if (state.fastModeEnabled) "优先速度" else "默认速度",
                    checked = state.fastModeEnabled,
                    onCheckedChange = onSetFastModeEnabled
                )
                ToolMenuAction(label = "插件", enabled = true, onClick = onOpenPlugins)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun ToolMenuAction(
    label: String,
    detail: String? = null,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (enabled) TextPrimary else DisabledText,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        detail?.let {
            Text(
                it,
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ToolMenuSwitch(
    label: String,
    detail: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                label,
                color = if (enabled) TextPrimary else DisabledText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            detail?.let {
                Text(
                    it,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GoalDialog(
    state: CodexRemoteUiState,
    onDismiss: () -> Unit,
    onSetGoalObjective: (String) -> Unit,
    onClearGoal: () -> Unit
) {
    var draft by rememberSaveable(state.activeGoal?.objective) {
        mutableStateOf(state.activeGoal?.objective.orEmpty())
    }
    val hasActiveThread = state.activeThreadId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("追求目标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = hasActiveThread,
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("目标") }
                )
                if (!hasActiveThread) {
                    Text("请先选择或创建会话", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        dismissButton = {
            TextButton(enabled = hasActiveThread || state.activeGoal != null, onClick = onClearGoal) {
                Text("清除")
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
                TextButton(
                    enabled = hasActiveThread && draft.isNotBlank(),
                    onClick = { onSetGoalObjective(draft) }
                ) {
                    Text("保存")
                }
            }
        }
    )
}

@Composable
private fun ModelOptionsSection(
    state: CodexRemoteUiState,
    onSelectModel: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("模型", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        if (state.isLoadingModels) {
            Text("正在加载模型...", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        state.availableModels.forEach { model ->
            SettingRadioRow(
                label = model.label,
                selected = state.selectedModelId == model.id,
                onClick = { onSelectModel(model.id) }
            )
        }
        state.modelLoadError?.let {
            Text(it, color = WarningText, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReasoningEffortOptionsSection(
    state: CodexRemoteUiState,
    onSelectReasoningEffort: (ReasoningEffort) -> Unit
) {
    val supportedEfforts = state.availableModels
        .firstOrNull { it.id == state.selectedModelId }
        ?.supportedReasoningEfforts
        .orEmpty()
        .ifEmpty { DefaultReasoningEfforts }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("推理强度", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        supportedEfforts.forEach { effort ->
            SettingRadioRow(
                label = effort.label,
                selected = state.selectedReasoningEffort == effort,
                onClick = { onSelectReasoningEffort(effort) }
            )
        }
    }
}

@Composable
private fun AccessModeOptionsSection(
    state: CodexRemoteUiState,
    onSelectAccessMode: (AccessMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("权限", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        AccessMode.entries.forEach { mode ->
            SettingRadioRow(
                label = mode.label,
                selected = state.selectedAccessMode == mode,
                onClick = { onSelectAccessMode(mode) }
            )
        }
    }
}

@Composable
private fun ThemeOptionsSection(
    state: CodexRemoteUiState,
    onSelectTheme: (AppTheme) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("主题", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        AppTheme.entries.forEach { theme ->
            SettingRadioRow(
                label = theme.label,
                selected = state.selectedTheme == theme,
                onClick = { onSelectTheme(theme) }
            )
        }
    }
}

@Composable
private fun SettingRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            label,
            color = if (selected) TextPrimary else TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InfoChip(label: String, onClick: (() -> Unit)? = null) {
    val clickableModifier = if (onClick == null) {
        Modifier.heightIn(min = 40.dp)
    } else {
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    }
    Surface(
        modifier = clickableModifier,
        shape = RoundedCornerShape(20.dp),
        color = if (onClick == null) Color.Transparent else Color.White.copy(alpha = 0.68f),
        border = BorderStroke(1.dp, if (onClick == null) Color.Transparent else BorderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label,
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onClick != null) {
                Text(
                    "⌄",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 10.sp
                )
            }
        }
    }
}

@Composable
private fun RoundActionButton(
    label: String,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit
) {
    val container = if (filled && enabled) Accent else Color(0xFFECECE7)
    val content = if (filled && enabled) Color.White else TextSecondary
    Surface(shape = CircleShape, color = container) {
        IconButton(
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier.size(44.dp)
        ) {
            Text(label, color = content, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.User
    val isSystem = message.role == ChatMessage.Role.System
    val isReasoning = message.isReasoningMessage()
    val timeLabel = remember(message.createdAtMs) { formatMessageTime(message.createdAtMs) }
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = when {
            isUser -> Color(0xFFE9F1FF)
            isSystem -> Color(0xFFFFF4E5)
            else -> Color.White
        },
        border = BorderStroke(1.dp, if (isSystem) Color(0xFFF2D6A2) else BorderColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        isUser -> "你"
                        isReasoning -> "思考"
                        isSystem -> "系统"
                        else -> "Codex"
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                timeLabel?.let {
                    Text(it, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (isReasoning) {
                ReasoningMessageBody(message = message)
            } else {
                FormattedMessageText(text = message.text, isUser = isUser)
            }
            MessageActionIconButton(
                label = "⧉",
                onClick = { clipboard.setText(AnnotatedString(message.text)) }
            )
        }
    }
}

@Composable
private fun ReasoningMessageBody(message: ChatMessage) {
    var expanded by rememberSaveable(message.itemId, message.text) { mutableStateOf(false) }
    val body = message.reasoningBody()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            body.ifBlank { "正在思考..." },
            color = TextPrimary,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起" else "展开")
        }
    }
}

private fun formatMessageTime(createdAtMs: Long?): String? =
    createdAtMs?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }

@Composable
private fun StatusDot(secure: Boolean, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(
        modifier = modifier
            .size(size)
            .border(1.dp, BorderColor, CircleShape)
            .background(if (secure) Color(0xFF2F9E44) else Color(0xFFB6B6AE), CircleShape)
    )
}

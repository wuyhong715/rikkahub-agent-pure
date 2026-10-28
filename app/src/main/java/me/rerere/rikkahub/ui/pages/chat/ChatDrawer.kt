package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.AssistantPicker
import me.rerere.rikkahub.ui.components.ui.BackupReminderCard
import me.rerere.rikkahub.ui.components.ui.AssistantAvatar
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.SEAM_SHADOW_WIDTH
import me.rerere.rikkahub.ui.components.ui.conversationDrawerSurface
import me.rerere.rikkahub.ui.components.ui.drawerSeamShadowAlpha
import me.rerere.rikkahub.ui.components.ui.UpdateCard
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.context.Navigator
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.toDp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
    hazeState: HazeState,
    // The sheet's width *and* the distance the page is pushed: the caller derives both from
    // `conversationDrawerWidthFor`, so the sheet and the page card never disagree at the seam.
    drawerWidth: Dp = 320.dp,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val isPlayStore = rememberIsPlayStoreVersion()
    val repo = koinInject<ConversationRepository>()

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    // 批 4 — 「任务」入口上的角标：未结束的任务数（含待你处理的 BLOCKED / REVIEW）。
    val missionBadge by drawerVm.missionBadge.collectAsStateWithLifecycle()
    val folders by drawerVm.folders.collectAsStateWithLifecycle()
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    val archiveFolderId by drawerVm.archiveFolderId.collectAsStateWithLifecycle()
    val protectedFolderId by drawerVm.protectedFolderId.collectAsStateWithLifecycle()
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    // 移动对话状态
    var showMoveToAssistantSheet by remember { mutableStateOf(false) }
    var conversationToMove by remember { mutableStateOf<Conversation?>(null) }
    val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // 文件夹相关状态
    var showMoveToFolderSheet by remember { mutableStateOf(false) }
    var conversationToMoveFolder by remember { mutableStateOf<Conversation?>(null) }
    val folderSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }
    // 与顶部标题点击共享，见 ChatDrawerVM.conversationToRename
    val conversationToRename by drawerVm.conversationToRename.collectAsStateWithLifecycle()

    // Menu popup 状态
    var showMenuPopup by remember { mutableStateOf(false) }

    val updateCheckDisabledUntil = settings.displaySetting.updateCheckDisabledUntilEpochMillis
    var updateChecksEnabled by remember(updateCheckDisabledUntil) {
        mutableStateOf(updateCheckDisabledUntil <= System.currentTimeMillis())
    }
    LaunchedEffect(updateCheckDisabledUntil) {
        while (true) {
            val remaining = updateCheckDisabledUntil - System.currentTimeMillis()
            if (remaining <= 0) {
                updateChecksEnabled = true
                break
            }
            updateChecksEnabled = false
            delay(minOf(remaining, 60 * 60 * 1_000L))
        }
    }

    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val bottomBarHeight = with(LocalDensity.current) { bottomBarHeightPx.toDp() }
    // 上方功能区固定悬浮在顶部，对话列表从其下方滚过；用实测高度给列表留出顶部空间。
    var topAreaHeightPx by remember { mutableStateOf(0) }
    val topAreaHeight = with(LocalDensity.current) { topAreaHeightPx.toDp() }

    // ── 抽屉的视觉语言 ──────────────────────────────────────────────────────────
    // 这一叠的上下关系是「聊天页在上、抽屉在下」：抽屉是被压住的那块底板，聊天页是被推开的
    // 那张盖板（见 PushDrawerContent）。所以抽屉这边刻意不做任何「浮起来」的暗示——
    //   · 直角，没有右侧圆角（圆角只属于上面那张盖板）；
    //   · 面色比聊天页暗一档（conversationDrawerSurface），页面对比之下就亮了起来；
    //   · 右缘不再有高光棱线，而是一道页面投下来的阴影；
    //   · 底色和 PushDrawerContent 的 seamColor 共用同一个值，盖板圆角切掉的那一小块才会
    //     和抽屉连成一片，而不是露出一块颜色不同的独立背景。
    // 抽屉内部的功能卡仍然叠「渐变面板 + 1dp 描边 + 软阴影」三层做厚度——它们是抽屉这张
    // 板子上的卡，和抽屉本身谁是上层并不冲突。
    val scheme = MaterialTheme.colorScheme
    val darkTheme = LocalDarkMode.current
    val drawerSurface = conversationDrawerSurface(darkTheme)
    val drawerShape: Shape = RectangleShape
    val cardShape = RoundedCornerShape(22.dp)
    val drawerBlurStyle = HazeBlurStyle.Material3 {
        blurRadius(26.dp)
    }

    val drawerTopArea: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 搜索是这块抽屉里最常点的东西，所以它单独占一条，而不是和别的图标挤成一排小圆钮。
            DrawerSearchField(
                onClick = { navController.navigate(Screen.MessageSearch) },
            )

            if (updateChecksEnabled && !isPlayStore) {
                UpdateCard(vm)
            }

            BackupReminderCard(
                settings = settings,
                onClick = { navController.navigate(Screen.Backup) },
            )

            // 身份卡：头像 + 昵称（点昵称即改名）吃掉左侧，右侧两个常用入口收成圆形按钮，
            // 与底部那排圆形入口共用一套「浅色圆片 + 细描边 + 微投影」的语言。
            DrawerPanel(shape = cardShape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    UIAvatar(
                        name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                        value = settings.displaySetting.userAvatar,
                        onUpdate = { newAvatar ->
                            vm.updateSettings(
                                settings.copy(
                                    displaySetting = settings.displaySetting.copy(
                                        userAvatar = newAvatar
                                    )
                                )
                            )
                        },
                        modifier = Modifier.size(46.dp),
                    )

                    // 昵称本身就是编辑入口，所以这里不额外留铅笔图标，只把无障碍标签挂在昵称上。
                    Text(
                        text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                onClickLabel = stringResource(R.string.accessibility_edit_nickname),
                            ) {
                                nicknameEditState.open(settings.displaySetting.userNickname)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )

                    DrawerIconButton(
                        icon = HugeIcons.TransactionHistory,
                        contentDescription = stringResource(R.string.chat_page_history),
                        onClick = { navController.navigate(Screen.History) },
                    )

                    DrawerIconButton(
                        icon = HugeIcons.Search01,
                        contentDescription = stringResource(R.string.chat_page_search_chats),
                        onClick = { navController.navigate(Screen.MessageSearch) },
                    )
                }

                DrawerHairline(modifier = Modifier.padding(horizontal = 14.dp))

                FolderBar(
                    protectedFolderId = protectedFolderId,
                    folders = folders,
                    selectedFolderId = selectedFolderId,
                    onSelect = { drawerVm.selectFolder(it) },
                    onCreate = { showCreateFolderDialog = true },
                    onRename = { folderToRename = it },
                    onDelete = { folderToDelete = it },
                )
            }
        }
    }

    ModalDrawerSheet(
        modifier = Modifier
            .width(drawerWidth)
            .clip(drawerShape)
            .hazeBlur(
                input = HazeInput.Sources(hazeState),
                style = drawerBlurStyle,
            )
            // 薄纱画在 sheet 的 modifier 上，而不是内容里：ModalDrawerSheet 的内容会被系统栏
            // 内边距推开，画在内容里的话状态栏那一条没有薄纱，会露出一道颜色不同的横带。
            // 颜色取 drawerSurface 本身，和 PushDrawerContent 的 seamColor 是同一个值；那层压暗
            // 已经含在 drawerSurface 里，这里不再重复叠。
            .background(
                Brush.verticalGradient(
                    listOf(
                        drawerSurface.copy(alpha = if (darkTheme) 0.93f else 0.90f),
                        drawerSurface.copy(alpha = if (darkTheme) 0.98f else 0.96f),
                    )
                )
            ),
        drawerShape = drawerShape,
        drawerContainerColor = Color.Transparent,
        drawerContentColor = scheme.onSurface,
        drawerTonalElevation = 0.dp,
    ) {
        // 列表铺满整块，从上下卡片下方滚过；上方功能区固定悬浮，底部功能区浮在列表之上。
        Box(modifier = Modifier.fillMaxSize()) {
            ConversationList(
                current = current,
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                listState = conversationListState,
                contentPadding = PaddingValues(top = topAreaHeight + 12.dp, bottom = bottomBarHeight + 12.dp),
                modifier = Modifier
                    .fillMaxSize(),
                onClick = {
                    navigateToChatPage(navController, it.id)
                },
                onRename = {
                    drawerVm.requestRenameConversation(it)
                },
                onDelete = {
                    scope.launch {
                        vm.deleteConversation(it).join()
                        conversations.refresh()
                        if (it.id == current.id) {
                            navigateToChatPage(navController)
                        }
                    }
                },
                onPin = {
                    vm.updatePinnedStatus(it)
                },
                onMoveToAssistant = {
                    conversationToMove = it
                    showMoveToAssistantSheet = true
                },
                onMoveToFolder = {
                    conversationToMoveFolder = it
                    showMoveToFolderSheet = true
                }
            )

            // 页面投在抽屉上的阴影：贴着右缘、向右渐深的一窄条。
            // 它画在列表之后、上下卡片之前，于是那两张浮起来的卡片会盖住它——阴影只落在
            // 抽屉的底板上，不会顺着卡片边缘泛出一道灰边。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(SEAM_SHADOW_WIDTH)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                // 与 PushDrawerContent 在页面圆角断口处补的那一条同源同强度。
                                scheme.scrim.copy(alpha = drawerSeamShadowAlpha(darkTheme)),
                            )
                        )
                    )
            )

                // 上方功能区：固定悬浮在抽屉顶部，对话列表从其下方滚过时被玻璃折射虚化。
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .onSizeChanged { topAreaHeightPx = it.height },
                ) {
                    drawerTopArea()
                }

                // 底部功能区：与顶部对称的悬浮卡片，同样避让系统手势导航条。
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                        .navigationBarsPadding()
                        .onSizeChanged { bottomBarHeightPx = it.height },
                ) {
                    DrawerPanel(shape = cardShape) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 助手选择器
                            AssistantPicker(
                                settings = settings,
                                onUpdateSettings = {
                                    val updateJob = vm.updateSettings(it)
                                    scope.launch {
                                        updateJob.join()
                                        val id = if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                                            Uuid.random()
                                        } else {
                                            repo.getConversationsOfAssistant(it.assistantId)
                                                .first()
                                                .firstOrNull()
                                                ?.id ?: Uuid.random()
                                        }
                                        navigateToChatPage(navigator = navController, chatId = id)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                onClickSetting = {
                                    val currentAssistantId = settings.assistantId
                                    navController.navigate(Screen.AssistantDetail(id = currentAssistantId.toString()))
                                }
                            )

                            // 助手身份是“当前上下文”，下面一行是全局导航/动作。一条发丝线把两者
                            // 切开，否则助手卡和五个月亮形入口会糊成同一排按钮。
                            DrawerHairline(modifier = Modifier.padding(horizontal = 6.dp))

                            Row(
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp)
                            ) {
                                DrawerAction(
                                    modifier = Modifier.weight(1f),
                                    icon = { Icon(HugeIcons.LookTop, null) },
                                    label = stringResource(R.string.chat_drawer_nav_assistant),
                                    contentDescription = stringResource(R.string.assistant_page_title),
                                    onClick = { navController.navigate(Screen.Assistant) },
                                )

                                Box(modifier = Modifier.weight(1f)) {
                                    DrawerAction(
                                        modifier = Modifier.fillMaxWidth(),
                                        icon = { Icon(HugeIcons.Sparkles, null) },
                                        label = stringResource(R.string.chat_drawer_nav_more),
                                        contentDescription = stringResource(R.string.menu),
                                        onClick = { showMenuPopup = true },
                                    )
                                    DropdownMenu(
                                        expanded = showMenuPopup,
                                        onDismissRequest = { showMenuPopup = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_page_menu_ai_translator)) },
                                            leadingIcon = { Icon(HugeIcons.LanguageCircle, null) },
                                            onClick = {
                                                showMenuPopup = false
                                                navController.navigate(Screen.Translator)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_page_menu_image_generation)) },
                                            leadingIcon = { Icon(HugeIcons.Image02, null) },
                                            onClick = {
                                                showMenuPopup = false
                                                navController.navigate(Screen.ImageGen)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.chat_page_menu_video_generation)) },
                                            leadingIcon = { Icon(HugeIcons.Video01, null) },
                                            onClick = {
                                                showMenuPopup = false
                                                navController.navigate(Screen.VideoGen)
                                            }
                                        )
                                    }
                                }

                                DrawerAction(
                                    modifier = Modifier.weight(1f),
                                    icon = { Icon(HugeIcons.InLove, null) },
                                    label = stringResource(R.string.favorite_page_title),
                                    contentDescription = stringResource(R.string.favorite_page_title),
                                    onClick = { navController.navigate(Screen.Favorite) },
                                )

                                DrawerAction(
                                    modifier = Modifier.weight(1f),
                                    icon = { Icon(HugeIcons.ChartColumn, null) },
                                    label = stringResource(R.string.chat_drawer_statistics),
                                    contentDescription = stringResource(R.string.chat_drawer_statistics),
                                    onClick = { navController.navigate(Screen.Stats) },
                                )

                                DrawerAction(
                                    modifier = Modifier.weight(1f),
                                    icon = { Icon(HugeIcons.Rocket01, null) },
                                    label = stringResource(R.string.chat_drawer_nav_missions),
                                    // 角标 = 未结束的任务数（ACTIVE + BLOCKED + REVIEW）；0 不画。
                                    // 其中 BLOCKED / REVIEW 是等在用户这边的，角标会换成强调色。
                                    contentDescription = if (missionBadge.open > 0) {
                                        stringResource(
                                            R.string.chat_drawer_nav_missions_badge_desc,
                                            missionBadge.open,
                                            missionBadge.needsUser,
                                        )
                                    } else {
                                        stringResource(R.string.chat_drawer_nav_missions)
                                    },
                                    badgeCount = missionBadge.open,
                                    badgeNeedsAttention = missionBadge.needsUser > 0,
                                    onClick = { navController.navigate(Screen.Workbench()) },
                                )

                                DrawerAction(
                                    modifier = Modifier.weight(1f),
                                    icon = { Icon(HugeIcons.Settings03, null) },
                                    label = stringResource(R.string.settings),
                                    contentDescription = stringResource(R.string.settings),
                                    onClick = { navController.navigate(Screen.Setting) },
                                )
                            }
                        }
                    }
                }
        }
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到文件夹 Bottom Sheet
    if (showMoveToFolderSheet) {
        val doMove: (Uuid?) -> Unit = { folderId ->
            conversationToMoveFolder?.let { conversation ->
                drawerVm.moveConversationToFolder(conversation.id, folderId)
                scope.launch {
                    folderSheetState.hide()
                    showMoveToFolderSheet = false
                    conversationToMoveFolder = null
                    conversations.refresh()
                }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToFolderSheet = false
                conversationToMoveFolder = null
            },
            sheetState = folderSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_folder),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 移出文件夹（未归类）
                Surface(
                    onClick = { doMove(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (conversationToMoveFolder?.folderId == null) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(HugeIcons.Folder01, null)
                        Text(
                            text = stringResource(R.string.chat_page_remove_from_folder),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folders) { folder ->
                        val isCurrent = folder.id == conversationToMoveFolder?.folderId
                        Surface(
                            onClick = { doMove(folder.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (isCurrent) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(HugeIcons.Folder01, null)
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建文件夹对话框
    if (showCreateFolderDialog) {
        var name by remember { mutableStateOf("") }
        var archive by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.chat_page_create_folder)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.chat_page_folder_name)) }
                    )
                    // P2-23 (D7) - mark this folder as the assistant's sub-agent archive.
                    // One folder per assistant; an empty one stays deletable.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_page_folder_purpose_sub_agent),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = archive, onCheckedChange = { archive = it })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.createFolder(name, archive)
                        showCreateFolderDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 重命名文件夹对话框
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        var archive by remember(folder.id) { mutableStateOf(archiveFolderId == folder.id) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.chat_page_rename_folder)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_page_folder_purpose_sub_agent),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = archive, onCheckedChange = { archive = it })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameFolder(folder.id, name, archive)
                        folderToRename = null
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 重命名会话对话框（顶部标题点击与本抽屉的 Rename 菜单项共享同一个目标状态）
    conversationToRename?.let { conversation ->
        var title by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { drawerVm.dismissRenameConversation() },
            title = { Text(stringResource(R.string.chat_page_rename_chat)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            vm.generateTitle(conversation, true)
                            drawerVm.dismissRenameConversation()
                        }
                    ) {
                        Text(stringResource(R.string.chat_page_regenerate_title))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameConversation(conversation.id, title)
                    },
                    enabled = title.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { drawerVm.dismissRenameConversation() }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 删除文件夹确认
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.chat_page_delete_folder)) },
            text = { Text(stringResource(R.string.chat_page_delete_folder_confirm, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            when (drawerVm.deleteFolder(folder.id)) {
                                FolderDeleteOutcome.Deleted -> {
                                    folderToDelete = null
                                    conversations.refresh()
                                }
                                FolderDeleteOutcome.Generating ->
                                    toaster.show(context.getString(R.string.chat_page_delete_folder_generating), type = ToastType.Warning)
                                FolderDeleteOutcome.Protected ->
                                    toaster.show(context.getString(R.string.chat_page_delete_folder_protected), type = ToastType.Warning)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到助手 Bottom Sheet
    if (showMoveToAssistantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToAssistantSheet = false
                conversationToMove = null
            },
            sheetState = bottomSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(settings.assistants, key = { it.id }) { assistant ->
                        AssistantItem(
                            assistant = assistant,
                            isCurrentAssistant = assistant.id == conversationToMove?.assistantId,
                            onClick = {
                                conversationToMove?.let { conversation ->
                                    vm.moveConversationToAssistant(conversation, assistant.id)
                                    scope.launch {
                                        bottomSheetState.hide()
                                        showMoveToAssistantSheet = false
                                        conversationToMove = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerSearchField(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val darkTheme = LocalDarkMode.current
    val hint = stringResource(R.string.chat_drawer_search_hint)
    val shape = RoundedCornerShape(16.dp)
    // 一条内凹的「浅槽」：上深下浅的竖向渐变 + 一圈细描边。它读起来是嵌进抽屉里的，
    // 而不是又一张浮起来的卡片——这样上方功能区才有「底 → 槽 → 卡片」三层纵深。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        scheme.surfaceContainerHigh,
                        scheme.surfaceContainer,
                    )
                )
            )
            .border(
                width = 1.dp,
                color = scheme.outlineVariant.copy(alpha = if (darkTheme) 0.30f else 0.55f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = hint }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Search01,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = scheme.onSurfaceVariant,
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一张「浮起来」的面板：软阴影 + 竖向渐变 + 1dp 描边（上缘是一道高光而不是一条灰线）。
 * 三层叠起来才有厚度；只靠 tonalElevation，深色模式下几乎看不出层次。
 */
@Composable
private fun DrawerPanel(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val darkTheme = LocalDarkMode.current
    val top = if (darkTheme) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest
    val bottom = if (darkTheme) scheme.surfaceContainer else scheme.surfaceContainerLow
    val rimTop = if (darkTheme) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.85f)
    val rimBottom = if (darkTheme) {
        Color.White.copy(alpha = 0.03f)
    } else {
        scheme.outlineVariant.copy(alpha = 0.45f)
    }
    Column(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                clip = false,
                ambientColor = scheme.scrim.copy(alpha = 0.35f),
                spotColor = scheme.scrim.copy(alpha = 0.35f),
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(rimTop, rimBottom)),
                shape = shape,
            ),
        content = content,
    )
}

/** 一条两端渐隐的发丝分隔线，比默认的 divider 更轻。 */
@Composable
private fun DrawerHairline(modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outlineVariant
        .copy(alpha = if (LocalDarkMode.current) 0.35f else 0.55f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, line, Color.Transparent)))
    )
}

/** 圆形快捷入口：浅色圆片 + 细描边 + 微投影，和底部那排圆形入口同一套语言。 */
@Composable
private fun DrawerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val darkTheme = LocalDarkMode.current
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(38.dp)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = if (darkTheme) scheme.surfaceContainerHighest else scheme.surface,
        contentColor = scheme.onSurfaceVariant,
        shadowElevation = 2.dp,
        border = BorderStroke(
            width = 1.dp,
            color = scheme.outlineVariant.copy(alpha = if (darkTheme) 0.35f else 0.60f),
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    contentDescription: String? = null,
    /** 批 4 — 右上角角标（未结束的任务数）；0 表示不画。 */
    badgeCount: Int = 0,
    /** 角标里有没有「等你处理」的成分（BLOCKED / REVIEW）；有则染色强调。 */
    badgeNeedsAttention: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val darkTheme = LocalDarkMode.current
    // 圆形图标 + 常驻文字的“dock”样式：这排入口是全局导航，只有图标时两个齿轮
    // （助手设置 / 设置）分辨不出来，长按才出标签等于没有标签。
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription ?: label
            }
            .padding(vertical = 4.dp)
            .widthIn(min = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BadgedBox(
            badge = {
                if (badgeCount > 0) {
                    // 有 BLOCKED / REVIEW 时换成强调色：数字仍是总数，颜色说明「其中有在等你的」。
                    Badge(
                        containerColor = if (badgeNeedsAttention) scheme.error else scheme.primary,
                        contentColor = if (badgeNeedsAttention) scheme.onError else scheme.onPrimary,
                    ) {
                        Text(text = if (badgeCount > 99) "99+" else badgeCount.toString())
                    }
                }
            },
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = if (darkTheme) scheme.surfaceContainerHighest else scheme.surface,
                contentColor = scheme.onSurface,
                shadowElevation = 2.dp,
                border = BorderStroke(
                    width = 1.dp,
                    color = scheme.outlineVariant.copy(alpha = if (darkTheme) 0.35f else 0.55f),
                ),
            ) {
                Box(
                    modifier = Modifier
                        .padding(11.dp)
                        .size(20.dp),
                ) {
                    icon()
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FolderBar(
    folders: List<Folder>,
    protectedFolderId: Uuid?,
    selectedFolderId: Uuid?,
    onSelect: (Uuid?) -> Unit,
    onCreate: () -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_default),
                selected = selectedFolderId == null,
                onClick = { onSelect(null) },
                onLongClick = {},
            )
        }
        items(folders) { folder ->
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                FolderChip(
                    label = folder.name,
                    icon = HugeIcons.Folder01,
                    selected = selectedFolderId == folder.id,
                    onClick = { onSelect(folder.id) },
                    onLongClick = { menuExpanded = true },
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_rename)) },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = {
                            onRename(folder)
                            menuExpanded = false
                        }
                    )
                    // P2-23 (D7) - the sub-agent archive folder cannot be deleted while it
                    // holds conversations; the option is absent rather than failing on tap.
                    if (folder.id != protectedFolderId) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_delete)) },
                            leadingIcon = { Icon(HugeIcons.Delete01, null) },
                            onClick = {
                                onDelete(folder)
                                menuExpanded = false
                            }
                        )
                    }
                }
            }
        }
        item {
            FolderChip(
                label = stringResource(R.string.chat_page_folder_add),
                icon = HugeIcons.FolderAdd,
                selected = false,
                onClick = onCreate,
                onLongClick = {},
            )
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    Surface(
        shape = CircleShape,
        color = if (selected) accent.copy(alpha = 0.14f) else Color.Transparent,
        contentColor = if (selected) accent else scheme.onSurfaceVariant,
        // 选中态靠「淡色底 + 一圈同色描边」立起来，而不是只换底色：描边让选中的胶囊
        // 在浅色卡片上有一条清晰的边界，视觉上更像一颗按下去的按键。
        border = if (selected) {
            BorderStroke(1.dp, accent.copy(alpha = 0.30f))
        } else {
            null
        },
        modifier = Modifier
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    isCurrentAssistant: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrentAssistant) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isCurrentAssistant) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AssistantAvatar(
                assistant = assistant,
                name = assistant.name,
                onUpdate = {},
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentAssistant) {
                    Text(
                        text = stringResource(R.string.assistant_page_current_assistant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

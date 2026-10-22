package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class SettingsStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    // 这些测试靠卡住读写来排时序，出问题时表现为一直挂着
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private var opened: OpenedStore? = null

    @After
    fun tearDown() {
        runBlocking { opened?.close() }
    }

    // 同一个文件同一时间只能有一个 DataStore，重新打开前先关掉上一个
    private suspend fun open(loadGate: CompletableDeferred<Unit>? = null): OpenedStore {
        opened?.close()
        return OpenedStore(File(temp.root, "settings.preferences_pb"), loadGate).also { opened = it }
    }

    private suspend fun reopened(): Settings = open().store.awaitLoaded()

    @Test
    fun `reading power enabled and exact delay survive disk writes unrelated updates and restart`() = runBlocking {
        val opened = open()
        val store = opened.store
        assertEquals(false, store.awaitLoaded().readingPowerEnabled)
        assertEquals(30, store.settingsFlow.value.readingPowerDelaySeconds)
        store.update { it.copy(readingPowerEnabled = true, readingPowerDelaySeconds = 0) }
        assertEquals(true, opened.disk.data.first()[SettingsStore.READING_POWER_ENABLED])
        assertEquals(0, opened.disk.data.first()[SettingsStore.READING_POWER_DELAY_SECONDS])
        val restarted = open().store
        assertEquals(true, restarted.awaitLoaded().readingPowerEnabled)
        assertEquals(0, restarted.settingsFlow.value.readingPowerDelaySeconds)
        restarted.update { it.copy(titlePrompt = "unrelated") }
        assertEquals(0, reopened().readingPowerDelaySeconds)
        val again = open().store
        again.update { it.copy(readingPowerEnabled = false, readingPowerDelaySeconds = 3600) }
        val disabled = reopened()
        assertEquals(false, disabled.readingPowerEnabled)
        assertEquals(3600, disabled.readingPowerDelaySeconds)
    }

    @Test
    fun `reading power delay is clamped to supported seconds on update and restart`() = runBlocking {
        val store = open().store
        store.update { it.copy(readingPowerDelaySeconds = -1) }
        assertEquals(0, reopened().readingPowerDelaySeconds)
        val restarted = open().store
        restarted.update { it.copy(readingPowerDelaySeconds = Int.MAX_VALUE) }
        assertEquals(3600, reopened().readingPowerDelaySeconds)
        open().disk.edit { it[SettingsStore.READING_POWER_DELAY_SECONDS] = -5 }
        val loaded = open().store
        assertEquals(0, loaded.awaitLoaded().readingPowerDelaySeconds)
        loaded.update { it.copy(titlePrompt = "persist normalized delay") }
        assertEquals(0, reopened().readingPowerDelaySeconds)
    }

    @Test
    fun `app explanation confirmation is written before enabling and survives disk reopen and unrelated changes`() = runBlocking {
        val opened = open()
        val store = opened.store
        assertEquals(false, store.awaitLoaded().readingPowerNoticeAcknowledged)
        // Confirming the app explanation does not claim that Root or the feature is enabled.
        store.update { it.copy(readingPowerNoticeAcknowledged = true) }
        assertEquals(true, opened.disk.data.first()[SettingsStore.READING_POWER_NOTICE_ACKNOWLEDGED])
        assertEquals(false, store.settingsFlow.value.readingPowerEnabled)

        val restarted = open().store
        assertEquals(true, restarted.awaitLoaded().readingPowerNoticeAcknowledged)
        assertEquals(false, restarted.settingsFlow.value.readingPowerEnabled)
        restarted.update { it.copy(readingPowerDelaySeconds = 75, titlePrompt = "unrelated") }
        assertEquals(true, reopened().readingPowerNoticeAcknowledged)
    }

    @Test
    fun `interactive UI custom prompt survives disk writes and restart`() = runBlocking {
        val opened = open()
        val store = opened.store
        assertEquals(null, store.awaitLoaded().interactiveUiPrompt)
        val custom = "主动使用参数探索界面\n保留课堂例题的顺序。"

        store.update { it.copy(interactiveUiPrompt = custom) }

        assertEquals(custom, opened.disk.data.first()[SettingsStore.INTERACTIVE_UI_PROMPT])
        val restarted = open().store
        assertEquals(custom, restarted.awaitLoaded().interactiveUiPrompt)
        restarted.update { it.copy(titlePrompt = "unrelated update") }
        assertEquals(custom, reopened().interactiveUiPrompt)
    }

    @Test
    fun `resetting interactive UI prompt removes disk override and restores default after restart`() = runBlocking {
        open().store.update { it.copy(interactiveUiPrompt = "custom") }
        val restarted = open()
        assertEquals("custom", restarted.store.awaitLoaded().interactiveUiPrompt)

        restarted.store.update { it.copy(interactiveUiPrompt = null) }

        assertFalse(SettingsStore.INTERACTIVE_UI_PROMPT in restarted.disk.data.first())
        assertEquals(null, reopened().interactiveUiPrompt)
    }

    @Test
    fun `legacy custom settings survive loading unrelated updates and restart`() = runBlocking {
        // Values use the keys written by the 2.5.6 custom APK, including non-default nested fields.
        open().disk.edit {
            it[SettingsStore.DISPLAY_SETTING] = """
                {"scrollToBottomOnSend":false,"enableImmersiveMode":true,"disableCursorBlink":true,
                 "inputOpacity":0.5,"photoImportGroupMinutes":12,"exportMarkdownExcludeImages":true,
                 "bookmarkDragSensitivity":1.7,"bookmarkDragRange":0.6,
                 "screenshotServer":{"host":"192.0.2.10","port":5050},
                 "miaomiaoErrorbook":{"phone":"test-account","password":"test-password",
                    "lastGroupId":42,"lastGroupName":"数学","importMode":"BOTH","preferImageLabel":"答案",
                    "includeOfficialQuestionImage":false,"includeOfficialAnswerImage":false,
                    "includePhotoImage":false,"includeQuestionText":false,"includeAnswerText":true,
                    "includeAnalysis":true,"skipWithoutOfficial":true},
                 "paperangPrinter":{"deviceAddress":"00:11:22:33:44:55","deviceName":"N2",
                    "density":77,"grayscale":true,"autoReconnect":false,"feedAfter":5,
                    "printFontScale":1.5,"paperWidthOverride":864}}
            """.trimIndent()
        }
        val store = open().store
        val before = store.awaitLoaded().displaySetting
        assertEquals(ScreenshotServerConfig("192.0.2.10", 5050), before.screenshotServer)
        assertEquals(MiaomiaoImportMode.BOTH, before.miaomiaoErrorbook.importMode)
        assertEquals("test-password", before.miaomiaoErrorbook.password)
        assertEquals(864, before.paperangPrinter.paperWidthOverride)
        assertEquals(false, before.scrollToBottomOnSend)
        assertEquals(true, before.disableCursorBlink)
        store.update { it.copy(titlePrompt = "updated") }
        store.update { it.copy(displaySetting = it.displaySetting.copy(fontSizeRatio = 1.2f)) }
        assertEquals(before.copy(fontSizeRatio = 1.2f), reopened().displaySetting)
    }

    @Test
    fun `bookmark storage survives concurrent settings writes and restart`() = runBlocking {
        val store = open().store
        store.awaitLoaded()
        val node = Uuid.random()
        val bookmark = me.rerere.rikkahub.data.model.MessageBookmark(nodeId = node, label = "重点", scrollOffset = 87)
        val conversation = Uuid.random().toString()
        val updates = listOf(
            launch { store.updateConversationBookmarks { it + (conversation to listOf(bookmark)) } },
            launch { store.update { it.copy(titlePrompt = "preserved") } },
            launch { store.update { it.copy(displaySetting = it.displaySetting.copy(screenshotServer = ScreenshotServerConfig("192.0.2.20", 5001))) } },
        )
        updates.joinAll()
        val restarted = open().store
        val settings = restarted.awaitLoaded()
        assertEquals("preserved", settings.titlePrompt)
        assertEquals("192.0.2.20", settings.displaySetting.screenshotServer.host)
        assertEquals(listOf(bookmark), restarted.conversationBookmarksFlow.first()[conversation])
    }

    @Test
    fun `typing faster than the disk can write loses no keystroke`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.update { it.copy(titlePrompt = "") }
        opened.disk.writeDelayMillis = 20

        val typing = "hello world".map { char ->
            // 和输入框一样：在当前显示的值后面追加，不等上一次写盘
            val shown = store.settingsFlow.value.titlePrompt
            launch(start = CoroutineStart.UNDISPATCHED) {
                store.update { it.copy(titlePrompt = shown + char) }
            }.also { delay(5) }
        }
        typing.joinAll()

        assertEquals("hello world", store.settingsFlow.value.titlePrompt)
        assertEquals("hello world", reopened().titlePrompt)
    }

    @Test
    fun `concurrent updates are all applied`() = runBlocking {
        val store = open().store
        store.update { it.copy(webServerPort = 0) }

        (1..8).map {
            launch(Dispatchers.Default) {
                repeat(50) { store.update { it.copy(webServerPort = it.webServerPort + 1) } }
            }
        }.joinAll()

        assertEquals(400, store.settingsFlow.value.webServerPort)
        assertEquals(400, reopened().webServerPort)
    }

    @Test
    fun `changes made during a slow write are flushed in a single write`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.awaitLoaded()
        val gate = CompletableDeferred<Unit>()
        opened.disk.writeGate = gate

        val first = launch(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(webServerPort = 9001) } }
        while (opened.disk.writes.get() == 0) delay(1)
        val rest = (9002..9050).map { port ->
            launch(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(webServerPort = port) } }
        }
        gate.complete(Unit)
        (rest + first).joinAll()

        assertEquals(2, opened.disk.writes.get())
        assertEquals(9050, store.settingsFlow.value.webServerPort)
        assertEquals(9050, reopened().webServerPort)
    }

    @Test
    fun `fields that did not change are not rewritten`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.update { it.copy(titlePrompt = "first") }
        // 绕过 store 直接改盘上的值：之后的写入如果把没变的字段也重写一遍，它就会被内存里的值盖回去
        opened.disk.edit { it[SettingsStore.OCR_PROMPT] = "on disk" }

        store.update { it.copy(titlePrompt = "second") }

        val stored = opened.disk.data.first()
        assertEquals("second", stored[SettingsStore.TITLE_PROMPT])
        assertEquals("on disk", stored[SettingsStore.OCR_PROMPT])
    }

    @Test
    fun `values repaired while loading are written back by the next update`() = runBlocking {
        // 盘上留着一个越界的下标，读盘时会被收回 0。它必须跟着下一次写入落盘，
        // 否则以后搜索服务变多、这个下标重新落在范围内时，重启后选中的服务会悄悄变掉
        open().disk.edit { it[SettingsStore.SEARCH_SELECTED] = 3 }
        val opened = open()
        val store = opened.store
        assertEquals(0, store.awaitLoaded().searchServiceSelected)

        store.update { it.copy(titlePrompt = "anything") }

        assertEquals(0, opened.disk.data.first()[SettingsStore.SEARCH_SELECTED])
    }

    @Test
    fun `objects that an update did not touch keep their identity`() = runBlocking {
        val store = open().store
        val before = store.awaitLoaded()

        store.update { it.copy(titlePrompt = "anything") }

        val after = store.settingsFlow.value
        assertSame(before.providers, after.providers)
        assertSame(before.assistants, after.assistants)
    }

    @Test
    fun `update issued before settings are loaded is applied on top of the stored settings`() = runBlocking {
        open().store.update { it.copy(titlePrompt = "custom") }

        val gate = CompletableDeferred<Unit>()
        val store = open(loadGate = gate).store
        val assistantId = DEFAULT_ASSISTANTS.last().id
        val update = launch(start = CoroutineStart.UNDISPATCHED) { store.selectAssistant(assistantId) }
        val loadedBeforeGate = !store.settingsFlow.value.init
        gate.complete(Unit)
        update.join()

        assertFalse(loadedBeforeGate)
        assertEquals("custom", store.settingsFlow.value.titlePrompt)
        assertEquals(assistantId, store.settingsFlow.value.assistantId)
        val stored = reopened()
        assertEquals("custom", stored.titlePrompt)
        assertEquals(assistantId, stored.assistantId)
    }

    @Test
    fun `first launch offers the built-in mode injection until the user removes it`() = runBlocking {
        val store = open().store
        assertEquals(DEFAULT_MODE_INJECTIONS, store.awaitLoaded().modeInjections)

        store.update { it.copy(modeInjections = emptyList()) }

        assertEquals(emptyList<Any>(), reopened().modeInjections)
    }

    @Test
    fun `settings are normalized as soon as they are updated`() = runBlocking {
        val store = open().store

        store.update {
            it.copy(providers = emptyList(), favoriteModels = listOf(Uuid.random()), searchServiceSelected = 5)
        }

        val settings = store.settingsFlow.value
        assertEquals(DEFAULT_PROVIDERS.map { it.id }, settings.providers.map { it.id })
        assertEquals(emptyList<Uuid>(), settings.favoriteModels)
        assertEquals(0, settings.searchServiceSelected)
    }

    @Test
    fun `change is still written when its caller is cancelled`() = runBlocking {
        val opened = open()
        val store = opened.store
        store.awaitLoaded()
        val gate = CompletableDeferred<Unit>()
        opened.disk.writeGate = gate

        // 页面关闭时 viewModelScope 里还没写完的修改
        val update = launch(start = CoroutineStart.UNDISPATCHED) { store.update { it.copy(titlePrompt = "kept") } }
        update.cancel()
        gate.complete(Unit)
        update.join()

        assertEquals("kept", reopened().titlePrompt)
    }
}

private class OpenedStore(file: File, loadGate: CompletableDeferred<Unit>?) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val disk = GatedDataStore(PreferenceDataStoreFactory.create(scope = scope) { file }, loadGate)
    val store = SettingsStore(disk, scope)

    suspend fun close() = scope.coroutineContext.job.cancelAndJoin()
}

// 可以卡住或拖慢读写的磁盘，用来确定地制造「内存已经改了、盘还没写完」的时刻
private class GatedDataStore(
    private val delegate: DataStore<Preferences>,
    private val loadGate: CompletableDeferred<Unit>?,
) : DataStore<Preferences> {
    val writes = AtomicInteger()

    @Volatile
    var writeGate: CompletableDeferred<Unit>? = null

    @Volatile
    var writeDelayMillis = 0L

    override val data: Flow<Preferences> = flow {
        loadGate?.await()
        emitAll(delegate.data)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        writes.incrementAndGet()
        writeGate?.await()
        delay(writeDelayMillis)
        return delegate.updateData(transform)
    }
}

package me.rerere.rikkahub.data.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 覆盖 root 守护里最容易错的部分：从 dumpsys 输出解析前台包名。
 *
 * 旧实现的正则 `ActivityRecord\{[^}]*\s([^/\s}]+)` 会捕获行尾的任务号（如 `t1234`）而不是包名，
 * 于是守护把 "t1234" 当成前台应用 —— 永远不等于白名单里的任何包，导致在任何应用上都疯狂按 HOME，
 * 学习桌面因此完全无法进入。这些用例锁定正确行为。
 */
class StudyRootBridgeTest {

    // --- dumpsys activity activities ---

    @Test
    fun `topResumedActivity 取包名而不是任务号`() {
        val dump = "  topResumedActivity=ActivityRecord{f1a2b3c u0 com.tencent.mm/.ui.LauncherUI t1234}"
        assertEquals("com.tencent.mm", StudyRootBridge.parseActivityDump(dump))
    }

    @Test
    fun `冒号分隔的 mResumedActivity 也能解析`() {
        // 旧正则要求 "=" 紧跟字段名，冒号形式直接匹配失败
        val dump = "    mResumedActivity: ActivityRecord{aabbcc u0 me.rerere.rikkahub/.StudyHomeActivity t99}"
        assertEquals("me.rerere.rikkahub", StudyRootBridge.parseActivityDump(dump))
    }

    @Test
    fun `mFocusedApp 形式能解析`() {
        val dump = "  mFocusedApp=ActivityRecord{123 u0 com.foo.bar/com.foo.bar.Main}"
        assertEquals("com.foo.bar", StudyRootBridge.parseActivityDump(dump))
    }

    @Test
    fun `多用户 uid 也能解析`() {
        val dump = "  topResumedActivity=ActivityRecord{9f u10 com.oppo.launcher/.Launcher t3}"
        assertEquals("com.oppo.launcher", StudyRootBridge.parseActivityDump(dump))
    }

    @Test
    fun `多行时优先取 topResumedActivity`() {
        // 分屏/多显示器下会同时列出多个 resumed 记录，必须取 top 的那个
        val dump = """
            mResumedActivity: ActivityRecord{111 u0 com.background.app/.Main t1}
            topResumedActivity=ActivityRecord{222 u0 com.foreground.app/.Main t2}
        """.trimIndent()
        assertEquals("com.foreground.app", StudyRootBridge.parseActivityDump(dump))
    }

    @Test
    fun `没有 top 时回退到第一条 resumed`() {
        val dump = "  mResumedActivity: ActivityRecord{111 u0 com.only.one/.Main t1}"
        assertEquals("com.only.one", StudyRootBridge.parseActivityDump(dump))
    }

    @Test
    fun `无法识别的输出返回 null`() {
        assertNull(StudyRootBridge.parseActivityDump(""))
        assertNull(StudyRootBridge.parseActivityDump("  mResumedActivity: null"))
        assertNull(StudyRootBridge.parseActivityDump("/system/bin/sh: dumpsys: not found"))
    }

    // --- dumpsys window ---

    @Test
    fun `mCurrentFocus 取包名`() {
        val dump = "  mCurrentFocus=Window{abc123 u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI}"
        assertEquals("com.tencent.mm", StudyRootBridge.parseWindowDump(dump))
    }

    @Test
    fun `系统装饰窗口不会被当成前台应用`() {
        // NavigationBar0 / StatusBar 没有 "包名/类名" 结构，必须跳过
        assertNull(StudyRootBridge.parseWindowDump("  mCurrentFocus=Window{d4e5 u0 NavigationBar0}"))
        assertNull(StudyRootBridge.parseWindowDump("  mCurrentFocus=Window{1 u0 StatusBar}"))
    }

    @Test
    fun `window dump 里只有 ActivityRecord 时也能解析`() {
        val dump = "  mFocusedApp=ActivityRecord{7 u0 com.some.app/.Main t8}"
        assertEquals("com.some.app", StudyRootBridge.parseWindowDump(dump))
    }

    // --- cmd role get-role-holders ---

    @Test
    fun `role holder 解析出包名`() {
        assertEquals("com.miui.home", StudyRootBridge.parseRoleHolder("com.miui.home"))
        assertEquals("com.miui.home", StudyRootBridge.parseRoleHolder("  com.miui.home  \n"))
    }

    @Test
    fun `没有 holder 时返回 null`() {
        assertNull(StudyRootBridge.parseRoleHolder(""))
        assertNull(StudyRootBridge.parseRoleHolder("null"))
    }

    @Test
    fun `错误输出不会被当成包名`() {
        // 旧实现用 Regex("[a-zA-Z0-9_.]+") 抓第一个 token，会把 "Error" 之外的词误当包名
        assertNull(StudyRootBridge.parseRoleHolder("Error: unknown role"))
        assertNull(StudyRootBridge.parseRoleHolder("Exception occurred while executing"))
    }

    @Test
    fun `单段名字不是合法包名`() {
        // 包名至少要有一个点，避免把 "Package" 之类的提示词当成结果
        assertNull(StudyRootBridge.parseRoleHolder("Package"))
    }
}

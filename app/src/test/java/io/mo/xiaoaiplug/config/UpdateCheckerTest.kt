package io.mo.xiaoaiplug.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateChecker] 里几段纯逻辑的回归保护。
 *
 * 这几段决定「要不要弹更新窗」和「弹出来长什么样」，都容易被数据里的边角情况坑到：
 *  - tag 前缀 `v`/预发布后缀 → 版本比较错位，把旧版当新版弹（或反之）
 *  - release body 是 JSON null / 满是 markdown 标记 → 弹窗里印出「null」或一堆 `**`
 * 都是跑一遍就能钉住的。
 */
class UpdateCheckerTest {

    @Test
    fun `归一化去掉前缀和预发布后缀`() {
        assertEquals("1.0.5", UpdateChecker.normalizeVersion("v1.0.5"))
        assertEquals("1.0.5", UpdateChecker.normalizeVersion("V1.0.5"))
        assertEquals("1.0.5", UpdateChecker.normalizeVersion("1.0.5-beta.2"))
        assertEquals("1.0.5", UpdateChecker.normalizeVersion("1.0.5+build7"))
        assertEquals("1.0.5", UpdateChecker.normalizeVersion("  v1.0.5  "))
        assertEquals("", UpdateChecker.normalizeVersion(""))
        assertEquals("", UpdateChecker.normalizeVersion("nightly"))
    }

    @Test
    fun `版本比较逐段比数字`() {
        assertTrue(UpdateChecker.compareVersion("1.0.5", "1.0.4") > 0)
        assertTrue(UpdateChecker.compareVersion("1.1.0", "1.0.9") > 0)
        assertTrue(UpdateChecker.compareVersion("2.0.0", "1.9.9") > 0)
        assertTrue(UpdateChecker.compareVersion("1.0.4", "1.0.5") < 0)
    }

    @Test
    fun `段数不等时缺的段按0算`() {
        assertEquals(0, UpdateChecker.compareVersion("1.1", "1.1.0"))
        assertEquals(0, UpdateChecker.compareVersion("1.0.0", "1"))
        assertTrue(UpdateChecker.compareVersion("1.1", "1.0.9") > 0)
    }

    @Test
    fun `十位版本号不被当字符串比`() {
        // 字符串比较会把 "9" 判成比 "10" 大，逐段转 Int 才对
        assertTrue(UpdateChecker.compareVersion("1.10.0", "1.9.0") > 0)
        assertTrue(UpdateChecker.compareVersion("1.0.10", "1.0.9") > 0)
    }

    @Test
    fun `条目解析剥掉列表符号和加粗标记`() {
        val body = """
            - **全新的用户界面 UI 2.0**
            * 提升了 30% 的启动速度
            • 安全增强与 `bug` 修复
        """.trimIndent()
        assertEquals(
            listOf(
                ReleaseNote("全新的用户界面 UI 2.0", NoteKind.ITEM),
                ReleaseNote("提升了 30% 的启动速度", NoteKind.ITEM),
                ReleaseNote("安全增强与 bug 修复", NoteKind.ITEM)
            ),
            UpdateChecker.parseNotes(body)
        )
    }

    @Test
    fun `分组标题保留为SECTION`() {
        val body = """
            ### 🚀 优化与改进
            - 去除多余依赖
            ### 🐛 问题修复
            - 修复某些控制类指令无法被正常接管
        """.trimIndent()
        assertEquals(
            listOf(
                ReleaseNote("🚀 优化与改进", NoteKind.SECTION),
                ReleaseNote("去除多余依赖", NoteKind.ITEM),
                ReleaseNote("🐛 问题修复", NoteKind.SECTION),
                ReleaseNote("修复某些控制类指令无法被正常接管", NoteKind.ITEM)
            ),
            UpdateChecker.parseNotes(body)
        )
    }

    @Test
    fun `缩进子项带更深的层级`() {
        // markdown 惯例 2 空格一级
        val body = "- 新增 4 个系统控制工具：\n  - 读取短信验证码\n  - 抓取日志 cat"
        assertEquals(
            listOf(
                ReleaseNote("新增 4 个系统控制工具：", NoteKind.ITEM, 0),
                ReleaseNote("读取短信验证码", NoteKind.ITEM, 1),
                ReleaseNote("抓取日志 cat", NoteKind.ITEM, 1)
            ),
            UpdateChecker.parseNotes(body)
        )
    }

    @Test
    fun `丢掉引用和围栏内容`() {
        val body = """
            > 引用
            ```
            code
            ```
            ---
            真正的一条
        """.trimIndent()
        assertEquals(listOf(ReleaseNote("真正的一条", NoteKind.ITEM)), UpdateChecker.parseNotes(body))
    }

    @Test
    fun `空body得到空列表`() {
        assertEquals(emptyList<ReleaseNote>(), UpdateChecker.parseNotes(""))
    }

    @Test
    fun `体积格式化`() {
        assertEquals("48 MB", formatSize(48L * 1024 * 1024))
        assertEquals("2.5 MB", formatSize((2.5 * 1024 * 1024).toLong()))
        assertEquals("512 KB", formatSize(512L * 1024))
    }
}

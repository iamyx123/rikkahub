package me.rerere.rikkahub.data.zyb

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖「官方内容在哪」这条容易踩的坑：官方题干/答案图是 HTML 里的 `<img src>`，
 * 不是裸 URL 字段；解析只有结构化的 aiAnalysis。
 */
class ZybClientTest {

    private val client = ZybClient(okhttp3.OkHttpClient())

    /** 一条命中题库的错题：官方图藏在 HTML 里，同时也有用户自拍图。 */
    private fun officialItem() = JSONObject(
        """
        {
          "studyContentId": 144412245,
          "isQuery": true,
          "degreeName": "较难",
          "reasonName": "计算错误",
          "updateTime": "2026-08-20 10:00:00",
          "imgSrc": "https://miaobao-pub.cdn.com/user/photo_1.png",
          "smallImgSrc": "https://miaobao-pub.cdn.com/user/photo_1.png?imageView2/2/w/200",
          "detail": {
            "tid": "abc123",
            "subject": 2,
            "question": {
              "content": "<p>已知椭圆 ${'$'}\\dfrac{x^2}{a^2}+y^2=1${'$'}</p><p><img src=\"https://img.cdnjtzy.com/zyb_q1.jpg\"></p>"
            },
            "answer": {
              "content": "<p><img src=\"https://img.cdnjtzy.com/zyb_a1.jpg\"></p>",
              "contentExtra": "<p>【答案】${'$'}x=1${'$'}</p><p><img src=\"https://img.cdnjtzy.com/zyb_a1.jpg\"></p>"
            },
            "analysis": {
              "aiAnalysis": {
                "answer": "${'$'}x=1${'$'}",
                "general_idea": "先求离心率",
                "step": [
                  {"main_point": "求 a", "analyze": "由题意得", "equation": "${'$'}a=2${'$'}"}
                ]
              }
            }
          }
        }
        """.trimIndent()
    )

    private fun parse(item: JSONObject): ZybClient.ErrItem = client.parseItem(item)

    @Test
    fun `官方题库图从 HTML 的 img src 里抽出来`() {
        val item = parse(officialItem())
        val urls = item.images.map { it.url }
        assertTrue("官方题干图应被抽出", urls.any { it.contains("zyb_q1.jpg") })
        assertTrue("官方答案图应被抽出", urls.any { it.contains("zyb_a1.jpg") })
        assertTrue("自拍原图应保留", urls.any { it.contains("photo_1.png") })
    }

    @Test
    fun `图片按官方优先排序并打对标签`() {
        val item = parse(officialItem())
        assertEquals(ZybClient.LABEL_QUESTION, item.images.first().label)
        val byLabel = item.images.associateBy { it.label }
        assertTrue(byLabel.getValue(ZybClient.LABEL_QUESTION).url.contains("zyb_q1"))
        assertTrue(byLabel.getValue(ZybClient.LABEL_ANSWER).url.contains("zyb_a1"))
        assertEquals(ZybClient.LABEL_PHOTO, byLabel.getValue(ZybClient.LABEL_PHOTO).label)
        // 缩略图与原图同源，不重复计入
        assertEquals(3, item.images.size)
    }

    @Test
    fun `题干文字保留 LaTeX 并用 图N 占位图片位置`() {
        val q = parse(officialItem()).text.question
        assertTrue("应保留行内 LaTeX", q.text.contains("\\dfrac{x^2}{a^2}"))
        assertTrue("图片位置应留占位符", q.text.contains("[图1]"))
        assertEquals(listOf("https://img.cdnjtzy.com/zyb_q1.jpg"), q.images)
    }

    @Test
    fun `结构化 aiAnalysis 渲染成可读解析`() {
        val analysis = parse(officialItem()).text.analysis.text
        assertTrue(analysis.contains("【答案】"))
        assertTrue(analysis.contains("【总体思路】"))
        assertTrue(analysis.contains("【步骤1】求 a"))
        assertTrue(analysis.contains("分析：由题意得"))
        assertTrue(analysis.contains("推导："))
    }

    @Test
    fun `contentExtra 是 content 超集时取更全的那个`() {
        val answer = parse(officialItem()).text.answer
        assertTrue("应取带【答案】抬头的 contentExtra", answer.text.contains("【答案】"))
        // 同一张图不该因为 content/contentExtra 都含它而重复
        assertEquals(1, answer.images.size)
    }

    @Test
    fun `isQuery 为假时只有照片没有官方内容`() {
        val raw = JSONObject(
            """
            {
              "studyContentId": 999,
              "isQuery": false,
              "imgSrc": "https://miaobao-pub.cdn.com/user/only_photo.png",
              "detail": {"tid": "x", "subject": 2}
            }
            """.trimIndent()
        )
        val item = parse(raw)
        assertFalse(item.isQuery)
        assertFalse("没有官方文字也没有官方 CDN 图", item.hasOfficial)
        assertEquals(listOf(ZybClient.LABEL_PHOTO), item.images.map { it.label })
    }

    @Test
    fun `isQuery 是数字 1 时也算识别成功`() {
        val raw = JSONObject("""{"studyContentId": 1, "isQuery": 1, "detail": {}}""")
        assertTrue(parse(raw).isQuery)
    }

    @Test
    fun `只有图片占位符不算有实质文字`() {
        val raw = JSONObject(
            """
            {
              "studyContentId": 2,
              "isQuery": true,
              "detail": {"question": {"content": "<p><img src=\"https://img.cdnjtzy.com/zyb_only.jpg\"></p>"}}
            }
            """.trimIndent()
        )
        val item = parse(raw)
        assertFalse("只有 [图1] 不算有文字", item.text.hasText)
        // 但有官方 CDN 图，仍算拿到了官方内容
        assertTrue(item.hasOfficial)
    }

    @Test
    fun `HTML 里的非官方 CDN 图标记为裁剪而不是官方题干`() {
        val raw = JSONObject(
            """
            {
              "studyContentId": 3,
              "isQuery": true,
              "detail": {"question": {"content": "<p><img src=\"https://miaobao-pub.cdn.com/crop_1.png\"></p>"}}
            }
            """.trimIndent()
        )
        val item = parse(raw)
        assertEquals(listOf(ZybClient.LABEL_CROP), item.images.map { it.label })
        assertFalse(item.hasOfficial)
    }

    @Test
    fun `化学上下标转成纯文本标记`() {
        assertEquals("CO_2 和 x^2", client.htmlToText("<p>CO<sub>2</sub> 和 x<sup>2</sup></p>"))
    }

    @Test
    fun `原图 URL 去掉缩放参数`() {
        assertEquals(
            "https://img.cdnjtzy.com/a.jpg",
            ZybClient.originalUrl("https://img.cdnjtzy.com/a.jpg?imageView2/2/w/500")
        )
    }

    @Test
    fun `旧标签名兼容到新标签`() {
        assertEquals(ZybClient.LABEL_QUESTION, ZybClient.normalizeLabel("题目"))
        assertEquals(ZybClient.LABEL_PHOTO, ZybClient.normalizeLabel("原图"))
        assertEquals(ZybClient.LABEL_ANSWER, ZybClient.normalizeLabel(ZybClient.LABEL_ANSWER))
    }
}

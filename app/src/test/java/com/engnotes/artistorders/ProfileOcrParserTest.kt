package com.engnotes.artistorders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileOcrParserTest {
    private fun line(text: String, top: Int, left: Int = 200) = OcrLine(text, top, left)

    @Test fun parsesProfileByPositionEvenWhenOcrBlocksAreOutOfOrder() {
        val fields = ProfileOcrParser.parse(listOf(
            line("朋友资料", 580, 30), line("10.31恩", 250), line("朋友圈", 820, 30),
            line("昵称:算了", 330), line("微信号: Xin1231TvT", 380), line("地区: 冰岛", 435), line("23:50", 40)
        ), 2026)
        assertEquals("算了", fields.customer)
        assertEquals("Xin1231TvT", fields.wechat)
        assertEquals("2026-10-31", fields.due)
    }

    @Test fun dateStillWorksWithoutNicknameAndSupportsOcrPunctuation() {
        val fields = ProfileOcrParser.parse(listOf(line("12。3", 250), line("微信號：abc_123", 380)), 2026)
        assertEquals("", fields.customer)
        assertEquals("2026-12-03", fields.due)
    }

    @Test fun nicknameCanBeSeparateBlockAndWechatCanBeMissing() {
        val fields = ProfileOcrParser.parse(listOf(line("2月29日", 250), line("昵称：", 330), line("算了", 333, 340)), 2028)
        assertEquals("算了", fields.customer)
        assertEquals("2028-02-29", fields.due)
    }

    @Test fun usesNearbyRowWhenNicknameLabelIsMisread() {
        val fields = ProfileOcrParser.parse(listOf(
            line("10.31恩", 250), line("昵祢: 算了", 330), line("微信号: Xin1231TvT", 380)
        ), 2026)
        assertEquals("算了", fields.customer)
        assertEquals("2026-10-31", fields.due)
    }

    @Test fun ignoresDateAndDistantTextWhenNicknameIsMissing() {
        val fields = ProfileOcrParser.parse(listOf(
            line("10.31恩", 250), line("微信号: Xin1231TvT", 380), line("地区: 冰岛", 435)
        ), 2026)
        assertEquals("", fields.customer)
    }

    @Test fun ignoresInvalidDatesAndNonProfileScreens() {
        assertEquals("", ProfileOcrParser.parse(listOf(line("2.30", 200), line("昵称:算了", 300)), 2026).due)
        assertFalse(ProfileOcrParser.parse(listOf(line("10.31", 100), line("订单 2 张", 200)), 2026).isProfile)
    }
}

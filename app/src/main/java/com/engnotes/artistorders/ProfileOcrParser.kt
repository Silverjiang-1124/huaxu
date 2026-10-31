package com.engnotes.artistorders

import java.time.LocalDate

internal data class OcrLine(val text: String, val top: Int, val left: Int, val height: Int = 0)
internal data class ProfileFields(val isProfile: Boolean, val customer: String, val wechat: String, val due: String)

internal object ProfileOcrParser {
    private val nickname = Regex("昵称|暱稱|昵稱|呢称")
    private val wechat = Regex("微信[号號]|微[信倍][号號]")
    private val region = Regex("地区|地區")
    private val date = Regex("(?<!\\d)(\\d{1,2})\\s*[.．。·/月-]\\s*(\\d{1,2})(?:日)?(?!\\d)")
    private val account = Regex("^[A-Za-z][A-Za-z0-9_-]{2,}")

    fun parse(input: List<OcrLine>, year: Int = LocalDate.now().year): ProfileFields {
        val lines = input.filter { it.text.isNotBlank() }.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
        val nickIndex = lines.indexOfFirst { nickname.containsMatchIn(it.text) }
        val wxIndex = lines.indexOfFirst { wechat.containsMatchIn(it.text) }
        val regionIndex = lines.indexOfFirst { region.containsMatchIn(it.text) }
        val isProfile = nickIndex >= 0 || wxIndex >= 0 ||
            (regionIndex >= 0 && lines.any { it.text.contains("朋友资料") || it.text.contains("朋友圈") })
        if (!isProfile) return ProfileFields(false, "", "", "")

        fun field(index: Int, label: Regex): String {
            if (index < 0) return ""
            val line = lines[index].text
            val match = label.find(line) ?: return ""
            var value = line.substring(match.range.last + 1).trimStart(' ', '\t', ':', '：', '﹕', '︰', '，', ',')
            // ML Kit may place the label and its value in separate blocks on the same row.
            if (value.isBlank() && index + 1 < lines.size) {
                val next = lines[index + 1]
                if (kotlin.math.abs(next.top - lines[index].top) <= 20 && next.left > lines[index].left &&
                    !wechat.containsMatchIn(next.text) && !nickname.containsMatchIn(next.text) && !region.containsMatchIn(next.text)) value = next.text.trim()
            }
            return value.trim()
        }
        val explicitCustomer = field(nickIndex, nickname)
        // When the nickname label is misread, use only a nearby row above the WeChat ID.
        val nearbyCustomer = if (explicitCustomer.isBlank() && wxIndex >= 0) {
            val wxLine = lines[wxIndex]
            val maxGap = maxOf(100, wxLine.height * 4)
            val horizontalSlack = maxOf(60, wxLine.height * 2)
            lines.asSequence().filter { candidate ->
                candidate.top < wxLine.top && wxLine.top - candidate.top <= maxGap &&
                    candidate.left >= wxLine.left - horizontalSlack &&
                    candidate.left <= wxLine.left + maxOf(200, wxLine.height * 8) &&
                    !date.containsMatchIn(candidate.text) && !wechat.containsMatchIn(candidate.text) &&
                    !region.containsMatchIn(candidate.text) &&
                    listOf("朋友资料", "朋友圈", "发消息", "音视频通话").none { it in candidate.text }
            }.sortedWith(compareByDescending<OcrLine> { it.top }.thenByDescending { it.left })
                .map { candidate ->
                    val value = candidate.text.trim()
                    val separator = value.indexOfFirst { it in ":：﹕︰" }
                    if (separator in 0..4) value.substring(separator + 1).trim() else value
                }.firstOrNull { it.isNotBlank() && !nickname.matches(it) }.orEmpty()
        } else ""
        val customer = explicitCustomer.ifBlank { nearbyCustomer }
        val accountValue = field(wxIndex, wechat).replace(" ", "")
        val wx = account.find(accountValue)?.value.orEmpty()
        val firstFieldTop = listOf(nickIndex, wxIndex, regionIndex).filter { it >= 0 }.minOfOrNull { lines[it].top }
        val due = lines.asSequence().filter { firstFieldTop != null && it.top < firstFieldTop }
            .mapNotNull { line ->
                val match = date.find(line.text) ?: return@mapNotNull null
                runCatching { LocalDate.of(year, match.groupValues[1].toInt(), match.groupValues[2].toInt()).toString() }.getOrNull()
            }.firstOrNull().orEmpty()
        return ProfileFields(true, customer, wx, due)
    }
}

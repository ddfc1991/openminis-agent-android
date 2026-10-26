package com.openminis.app.ui.plus

import com.openminis.app.data.model.ThinkingLevel

/** [38.9.15.1] the 「模型」 box on 应用 and the picker's 思考强度 row: one plain line, Chinese level names */
object ModelLine {
    fun levelZh(l: ThinkingLevel): String = when (l) {
        ThinkingLevel.OFF -> "不思考"
        ThinkingLevel.LOW -> "低"
        ThinkingLevel.MEDIUM -> "中"
        ThinkingLevel.HIGH -> "高"
        ThinkingLevel.XHIGH -> "极高"
        ThinkingLevel.MAX -> "最高"
        ThinkingLevel.ULTRA -> "超高"
    }

    /** 「AMD（14 账号）· DeepSeek V4.1 Flash · 高」; blank parts are dropped, the group/model is not repeated */
    fun line(group: String, model: String, level: ThinkingLevel?): String =
        listOfNotNull(group.takeIf { it.isNotBlank() }, model.takeIf { it.isNotBlank() && it != group },
            level?.takeIf { it.isEnabled }?.let { levelZh(it) }).joinToString(" · ")
}

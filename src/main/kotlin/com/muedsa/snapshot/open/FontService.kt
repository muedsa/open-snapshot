package com.muedsa.snapshot.open

import com.muedsa.geometry.EdgeInsets
import com.muedsa.snapshot.SnapshotPNG
import com.muedsa.snapshot.paint.text.TextPainter
import com.muedsa.snapshot.paint.text.TextSpan
import com.muedsa.snapshot.paint.text.TextStyle
import com.muedsa.snapshot.rendering.flex.CrossAxisAlignment
import com.muedsa.snapshot.widget.Padding
import com.muedsa.snapshot.widget.SizedBox
import com.muedsa.snapshot.widget.Column
import com.muedsa.snapshot.widget.text.RichText
import com.muedsa.snapshot.widget.text.Text
import org.jetbrains.skia.Color
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/** 字体预览的选择结果：实际渲染的字体，以及请求中不存在的字体。 */
internal data class FontSelection(
    val selected: List<String>,
    val unknown: List<String>,
)

/**
 * 从可用字体中挑选要渲染的字体：先按请求过滤（大小写不敏感），再按 offset/limit 分页。
 *
 * `limit` 为 0 表示不限制；偏移超出范围时返回空列表。
 */
internal fun selectFontFamilies(
    available: List<String>,
    requested: List<String>,
    offset: Int,
    limit: Int,
): FontSelection {
    val candidates = if (requested.isEmpty()) {
        available
    } else {
        val availableByLowercase = available.associateBy { it.lowercase() }
        val matched = requested.mapNotNull { availableByLowercase[it.lowercase()] }.distinct()
        val unknown = requested.filter { it.lowercase() !in availableByLowercase }
        return FontSelection(
            selected = paginate(available.filter { it in matched }, offset, limit),
            unknown = unknown,
        )
    }
    return FontSelection(selected = paginate(candidates, offset, limit), unknown = emptyList())
}

private fun paginate(families: List<String>, offset: Int, limit: Int): List<String> {
    if (offset >= families.size) return emptyList()
    val fromOffset = families.drop(offset)
    return if (limit > 0) fromOffset.take(limit) else fromOffset
}

object FontService {
    /** 系统可用字体族名称。 */
    fun familyNames(): List<String> = buildList {
        repeat(FontMgr.default.familiesCount) { index ->
            add(FontMgr.default.getFamilyName(index))
        }
    }

    fun listFonts(): String = familyNames().joinToString("\n")

    /** 渲染指定字体的预览图；`families` 为空时渲染全部字体。 */
    fun drawFonts(families: List<String> = familyNames()): ByteArray = SnapshotPNG {
        Padding(padding = EdgeInsets.all(20f)) {
            Column(crossAxisAlignment = CrossAxisAlignment.START) {
                families.forEach { family ->
                    RichText {
                        TextSpan(
                            text = family,
                            style = TextStyle(
                                color = Color.RED,
                                fontSize = 40f,
                                fontStyle = FontStyle.BOLD,
                                fontFamilies = listOf(family),
                            ),
                        )
                        TextSpan(
                            text = "  ($family)",
                            style = TextStyle(color = Color.BLACK, fontSize = 35f),
                        )
                    }
                    Text(
                        text = "原神, 启动！🤣🤣\nGenshin Impact, Launch! 🤣🤣",
                        style = TextStyle(
                            color = Color.BLACK,
                            fontSize = 35f,
                            fontFamilies = listOf(family),
                        ),
                    )
                    SizedBox(height = 20f)
                }
            }
        }
    }

    fun setDefaultFamilyNames(familyNames: List<String>?) {
        TextPainter.DEFAULT_TEXT_STYLE.fontFamilies = familyNames
        familyNames?.firstOrNull()?.let {
            TextPainter.FONT_COLLECTION.setDefaultFontManager(FontMgr.default, it)
        }
    }
}

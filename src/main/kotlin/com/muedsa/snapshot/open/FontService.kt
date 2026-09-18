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

object FontService {
    fun listFonts(): String = buildString {
        repeat(FontMgr.default.familiesCount) { index ->
            if (index > 0) append('\n')
            append(FontMgr.default.getFamilyName(index))
        }
    }

    fun drawFonts(): ByteArray = SnapshotPNG {
        Padding(padding = EdgeInsets.all(20f)) {
            Column(crossAxisAlignment = CrossAxisAlignment.START) {
                repeat(FontMgr.default.familiesCount) { index ->
                    val family = FontMgr.default.getFamilyName(index)
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

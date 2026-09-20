package com.muedsa.snapshot.open

import com.muedsa.geometry.EdgeInsets
import com.muedsa.snapshot.SnapshotPNG
import com.muedsa.snapshot.parser.TrackPos
import com.muedsa.snapshot.paint.text.TextStyle
import com.muedsa.snapshot.rendering.flex.CrossAxisAlignment
import com.muedsa.snapshot.widget.Column
import com.muedsa.snapshot.widget.Container
import com.muedsa.snapshot.widget.Padding
import com.muedsa.snapshot.widget.SizedBox
import com.muedsa.snapshot.widget.text.Text
import org.jetbrains.skia.Color
import org.jetbrains.skia.FontStyle

/** 一行错误摘录：行号、展示文本与截断标记。 */
internal data class ErrorExcerptLine(
    val number: Int,
    val text: String,
    val isErrorLine: Boolean,
    val truncatedHead: Boolean,
    val truncatedTail: Boolean,
)

/** 解析错误的可渲染摘录。caret 为 -1 表示无法定位。 */
internal data class ErrorExcerpt(
    val lines: List<ErrorExcerptLine>,
    val caretLineIndex: Int,
    val caretColumn: Int,
    val location: String,
)

private const val TAB_WIDTH = 4
private const val TRUNCATION_MARK = "…"

/** 裁剪后的展示行：文本、两端截断标记，以及从行首丢弃的字符数。 */
private data class WindowedLine(
    val text: String,
    val truncatedHead: Boolean,
    val truncatedTail: Boolean,
    val droppedHeadChars: Int,
)

/**
 * 把解析错误转换成可渲染的摘录：
 *
 * - 只保留错误行上下 [contextLines] 行，最多 [maxLines] 行；
 * - 展示文本展开制表符、裁剪超长行（错误行围绕 caret 开窗，其余行只截尾）；
 * - caret 列是**展示文本**中的 0 基下标，宽字符下为近似值；
 * - `line`/`column` 缺失时用字符偏移推算，完全无法定位时 caret 为 -1。
 */
internal fun buildErrorExcerpt(
    source: String,
    pos: TrackPos,
    maxLines: Int,
    maxColumns: Int,
    contextLines: Int,
): ErrorExcerpt {
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    val sourceLines = normalized.split('\n')
    val displayLines = sourceLines.map(::expandTabs)
    val allowedLines = maxLines.coerceAtLeast(1)
    val allowedColumns = maxColumns.coerceAtLeast(1)

    val caretLineIndex = resolveLineIndex(sourceLines, pos)
    if (caretLineIndex == null) {
        return ErrorExcerpt(
            lines = displayLines.take(allowedLines).mapIndexed { index, text ->
                val windowed = windowLine(text, caretColumn = -1, allowedColumns, isErrorLine = false)
                ErrorExcerptLine(index + 1, windowed.text, isErrorLine = false, windowed.truncatedHead, windowed.truncatedTail)
            },
            caretLineIndex = -1,
            caretColumn = -1,
            location = locationLabel(pos),
        )
    }

    // 位置以原始文本为准，caret 需要按制表符展开后的列换算。
    val sourceCaretColumn = resolveCaretColumn(sourceLines, caretLineIndex, pos)
    val caretColumnInLine = if (sourceCaretColumn < 0) {
        -1
    } else {
        val line = sourceLines[caretLineIndex]
        expandTabs(line.take(sourceCaretColumn.coerceAtMost(line.length))).length
    }
    val firstLine = (caretLineIndex - contextLines).coerceAtLeast(0)
    val lastLine = (caretLineIndex + contextLines).coerceAtMost(displayLines.lastIndex)

    // 行数超限时优先保留错误行上方的上下文，从下方开始裁剪。
    var windowStart = firstLine
    var windowEnd = lastLine
    while (windowEnd - windowStart + 1 > allowedLines) {
        if (windowEnd > caretLineIndex) windowEnd-- else windowStart++
    }

    var errorLineWindow: WindowedLine? = null
    val lines = (windowStart..windowEnd).map { index ->
        val isErrorLine = index == caretLineIndex
        val windowed = windowLine(displayLines[index], if (isErrorLine) caretColumnInLine else -1, allowedColumns, isErrorLine)
        if (isErrorLine) errorLineWindow = windowed
        ErrorExcerptLine(
            number = index + 1,
            text = windowed.text,
            isErrorLine = isErrorLine,
            truncatedHead = windowed.truncatedHead,
            truncatedTail = windowed.truncatedTail,
        )
    }

    val errorLine = errorLineWindow ?: return ErrorExcerpt(lines, -1, -1, locationLabel(pos))
    val visibleCaret = if (caretColumnInLine < 0) {
        -1
    } else {
        val markOffset = if (errorLine.truncatedHead) TRUNCATION_MARK.length else 0
        (markOffset + caretColumnInLine - errorLine.droppedHeadChars)
            .coerceIn(0, errorLine.text.length)
    }

    return ErrorExcerpt(
        lines = lines,
        caretLineIndex = lines.indexOfFirst { it.isErrorLine },
        caretColumn = visibleCaret,
        location = locationLabel(pos),
    )
}

/** 超长行开窗：错误行围绕 caret 居中，其余行保留行首。 */
private fun windowLine(text: String, caretColumn: Int, maxColumns: Int, isErrorLine: Boolean): WindowedLine {
    if (text.length <= maxColumns) {
        return WindowedLine(text, truncatedHead = false, truncatedTail = false, droppedHeadChars = 0)
    }

    if (!isErrorLine || caretColumn < 0) {
        val contentLength = (maxColumns - TRUNCATION_MARK.length).coerceAtLeast(1)
        return WindowedLine(
            text = text.take(contentLength) + TRUNCATION_MARK,
            truncatedHead = false,
            truncatedTail = true,
            droppedHeadChars = 0,
        )
    }

    // 错误行：先按两端都加省略号计算内容窗口，再看行首是否真的被裁掉。
    var contentLength = (maxColumns - 2 * TRUNCATION_MARK.length).coerceAtLeast(1)
    var headDrop = (caretColumn - contentLength / 2).coerceAtLeast(0)
    headDrop = headDrop.coerceAtMost((text.length - contentLength).coerceAtLeast(0))
    var truncatedHead = headDrop > 0
    if (!truncatedHead) {
        contentLength = (maxColumns - TRUNCATION_MARK.length).coerceAtLeast(1)
    }
    val content = text.substring(headDrop, (headDrop + contentLength).coerceAtMost(text.length))
    val truncatedTail = headDrop + contentLength < text.length
    val text2 = (if (truncatedHead) TRUNCATION_MARK else "") + content + (if (truncatedTail) TRUNCATION_MARK else "")

    return WindowedLine(
        text = text2,
        truncatedHead = truncatedHead,
        truncatedTail = truncatedTail,
        droppedHeadChars = headDrop,
    )
}

private fun expandTabs(line: String): String = buildString(line.length) {
    line.forEach { char -> if (char == '\t') append(" ".repeat(TAB_WIDTH)) else append(char) }
}

private fun resolveLineIndex(lines: List<String>, pos: TrackPos): Int? {
    if (pos.line >= 0) {
        return (pos.line - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    }
    if (pos.pos < 0) return null
    var consumed = 0
    lines.forEachIndexed { lineIndex, line ->
        val lineEnd = consumed + line.length
        if (pos.pos in consumed..lineEnd) return lineIndex
        consumed = lineEnd + 1
    }
    return (lines.size - 1).coerceAtLeast(0)
}

private fun resolveCaretColumn(lines: List<String>, lineIndex: Int, pos: TrackPos): Int {
    if (pos.column >= 0) return (pos.column - 1).coerceAtLeast(0)
    if (pos.pos < 0) return -1
    val lineStart = lines.take(lineIndex).sumOf { it.length + 1 }
    return (pos.pos - lineStart).coerceAtLeast(0)
}

private fun locationLabel(pos: TrackPos): String = when {
    pos.line >= 0 && pos.column >= 0 -> "line ${pos.line}, column ${pos.column}"
    pos.pos >= 0 -> "position ${pos.pos}"
    else -> "unknown position"
}

// ---------- 渲染 ----------

private const val CARD_WIDTH = 860f
private const val CODE_FONT_SIZE = 14f
private const val LINE_NUMBER_WIDTH = 4
private val CODE_PREFIX_GUTTER = "─ "

/** 优先使用等宽字体，缺失时由 Skia 回退；caret 对齐在非等宽字体下为近似效果。 */
private val MONOSPACE_FAMILIES = listOf(
    "DejaVu Sans Mono",
    "Liberation Mono",
    "Noto Sans Mono",
    "Consolas",
    "Courier New",
    "monospace",
)

private val COLOR_CARD_BACKGROUND = Color.makeARGB(255, 250, 250, 250)
private val COLOR_CODE_BACKGROUND = Color.makeARGB(255, 255, 255, 255)
private val COLOR_ERROR_LINE = Color.makeARGB(255, 255, 235, 235)
private val COLOR_TITLE = Color.makeARGB(255, 183, 28, 28)
private val COLOR_MESSAGE = Color.makeARGB(255, 33, 33, 33)
private val COLOR_META = Color.makeARGB(255, 117, 117, 117)

/**
 * 错误卡片渲染器。默认走 [drawParseErrorCard]；测试可替换以验证失败回退路径。
 */
internal var errorImageRenderer: (ErrorExcerpt, String, String) -> ByteArray = ::drawParseErrorCard

/**
 * 渲染解析错误卡片：标题、带行号的源码摘录、caret、错误消息与位置。
 *
 * 图片尺寸由构造保证有界（固定宽度 + 受限行数与列数），不依赖画布限制。
 */
internal fun drawParseErrorCard(
    excerpt: ErrorExcerpt,
    message: String,
    requestId: String,
): ByteArray = SnapshotPNG(background = COLOR_CARD_BACKGROUND) {
    Padding(padding = EdgeInsets.all(16f)) {
        Container(width = CARD_WIDTH, color = COLOR_CARD_BACKGROUND) {
            Column(crossAxisAlignment = CrossAxisAlignment.START) {
                Text(
                    text = "Snapshot DSL error",
                    style = TextStyle(color = COLOR_TITLE, fontSize = 24f, fontStyle = FontStyle.BOLD),
                )
                SizedBox(height = 10f)
                Container(color = COLOR_CODE_BACKGROUND, padding = EdgeInsets.all(12f)) {
                    Column(crossAxisAlignment = CrossAxisAlignment.START) {
                        excerpt.lines.forEachIndexed { index, line ->
                            val text = gutter(line.number) + line.text
                            if (line.isErrorLine) {
                                Container(color = COLOR_ERROR_LINE) {
                                    codeText(text)
                                }
                                if (excerpt.caretColumn >= 0) {
                                    Container(color = COLOR_ERROR_LINE) {
                                        codeText(gutter(null) + " ".repeat(excerpt.caretColumn) + "^")
                                    }
                                }
                            } else {
                                codeText(text)
                            }
                        }
                    }
                }
                SizedBox(height = 10f)
                Text(
                    text = message.take(MAX_MESSAGE_LENGTH),
                    style = TextStyle(color = COLOR_MESSAGE, fontSize = 18f),
                )
                SizedBox(height = 4f)
                Text(
                    text = "${excerpt.location} · requestId $requestId",
                    style = TextStyle(color = COLOR_META, fontSize = 14f),
                )
            }
        }
    }
}

private const val MAX_MESSAGE_LENGTH = 300

private fun gutter(lineNumber: Int?): String {
    val number = lineNumber?.toString().orEmpty().padStart(LINE_NUMBER_WIDTH)
    return "$number $CODE_PREFIX_GUTTER"
}

private fun com.muedsa.snapshot.widget.ChildSlot.codeText(value: String) {
    Text(
        text = value,
        style = TextStyle(color = COLOR_MESSAGE, fontSize = CODE_FONT_SIZE, fontFamilies = MONOSPACE_FAMILIES),
    )
}

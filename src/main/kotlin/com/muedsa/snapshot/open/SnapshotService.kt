package com.muedsa.snapshot.open

import com.muedsa.snapshot.parser.Element
import com.muedsa.snapshot.parser.ParseException
import com.muedsa.snapshot.parser.Parser
import com.muedsa.snapshot.drawRenderBox
import com.muedsa.snapshot.rendering.box.BoxConstraints
import io.ktor.http.ContentType
import org.jetbrains.skia.Surface
import java.io.StringReader
import kotlin.math.ceil

data class RenderLimits(
    val maxWidth: Int = 4096,
    val maxHeight: Int = 4096,
    val maxPixels: Long = 16_777_216,
    val maxDocumentElements: Int = 4096,
    val maxDocumentDepth: Int = 128,
    val image: RenderImageLimits = RenderImageLimits(),
)

data class RenderImageLimits(
    val maxCount: Int = 10,
    val maxEncodedBytes: Int = 5 * 1024 * 1024,
    val maxWidth: Int = 4096,
    val maxHeight: Int = 4096,
    val maxPixels: Long = 16_777_216,
    val maxTotalPixels: Long = 16_777_216,
)

data class SnapshotResult(
    val bytes: ByteArray,
    val contentType: ContentType,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SnapshotResult) return false

        if (!bytes.contentEquals(other.bytes)) return false
        if (contentType != other.contentType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}

object SnapshotService {
    fun render(source: String, limits: RenderLimits = RenderLimits()): SnapshotResult {
        require(source.isNotBlank()) { "Snapshot request body must not be empty" }
        val imageBudget = ImageResourceBudget(limits.image.maxCount, limits.image.maxTotalPixels)
        val dataUriImageDecoder = LimitedDataUriImageDecoder(
            maxEncodedBytes = limits.image.maxEncodedBytes,
            maxImageWidth = limits.image.maxWidth,
            maxImageHeight = limits.image.maxHeight,
            maxImagePixels = limits.image.maxPixels,
            budget = imageBudget,
        )
        return try {
            val element = Parser(dataUriImageDecoder = dataUriImageDecoder).parse(StringReader(source))
            validateDocumentStructure(element, limits.maxDocumentElements, limits.maxDocumentDepth)
            val widget = element.createWidget()
            val rootRenderBox = widget.createRenderBox()
            rootRenderBox.layout(BoxConstraints())
            val rootSize = rootRenderBox.definiteSize
            require(!rootSize.isEmpty) { "Layout size is empty" }
            require(!rootSize.isInfinite) { "Layout size is infinite" }
            val width = ceil(rootSize.width).toInt()
            val height = ceil(rootSize.height).toInt()
            require(width <= limits.maxWidth) {
                "Render width $width exceeds maximum ${limits.maxWidth}"
            }
            require(height <= limits.maxHeight) {
                "Render height $height exceeds maximum ${limits.maxHeight}"
            }
            require(width.toLong() * height.toLong() <= limits.maxPixels) {
                "Render pixel count ${width.toLong() * height.toLong()} exceeds maximum ${limits.maxPixels}"
            }
            val contentType = when (element.format.name.lowercase()) {
                "jpeg", "jpg" -> ContentType.Image.JPEG
                "webp" -> ContentType.parse("image/webp")
                else -> ContentType.Image.PNG
            }
            val surface = Surface.makeRasterN32Premul(width, height)
            try {
                surface.canvas.drawRenderBox(rootRenderBox, element.background, element.debug)
                surface.flushAndSubmit()
                val image = surface.makeImageSnapshot()
                try {
                    SnapshotResult(image.encodeToData(format = element.format)!!.bytes, contentType)
                } finally {
                    image.close()
                }
            } finally {
                surface.close()
            }
        } finally {
            dataUriImageDecoder.close()
        }
    }

    /** 在递归构建 Widget 前迭代检查树规模，避免深层输入触发栈溢出。 */
    private fun validateDocumentStructure(root: Element, maxElements: Int, maxDepth: Int) {
        val pending = ArrayDeque<Pair<Element, Int>>()
        pending.addLast(root to 1)
        var elements = 0
        while (pending.isNotEmpty()) {
            val (element, depth) = pending.removeLast()
            elements++
            require(elements <= maxElements) {
                "Document contains more than $maxElements elements"
            }
            require(depth <= maxDepth) {
                "Document nesting depth $depth exceeds maximum $maxDepth"
            }
            element.children.asReversed().forEach { child ->
                pending.addLast(child to depth + 1)
            }
        }
    }

    fun formatError(error: Throwable, source: String): String {
        if (error !is ParseException) {
            return error.message ?: error::class.simpleName ?: "Snapshot rendering failed"
        }
        val position = error.pos.pos.coerceIn(0, source.length)
        val start = (position - 30).coerceAtLeast(0)
        val end = (position + 30).coerceAtMost(source.length)
        return "${error.message ?: "Invalid snapshot document"} at position $position near: " +
            source.substring(start, end)
    }
}

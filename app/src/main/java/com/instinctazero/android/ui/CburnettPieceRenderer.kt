package com.instinctazero.android.ui

import android.util.Base64
import android.util.Xml
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

private data class SvgStyle(
    val fill: Color? = Color.Black,
    val stroke: Color? = null,
    val strokeWidth: Float = 1f,
    val evenOdd: Boolean = false,
    val cap: StrokeCap = StrokeCap.Butt,
    val join: StrokeJoin = StrokeJoin.Miter,
)

private data class SvgShape(
    val path: Path,
    val style: SvgStyle,
)

private val parsedPieces: Map<Char, List<SvgShape>> by lazy {
    CburnettPieceData.encoded.mapValues { (_, encoded) -> parseSvg(encoded) }
}

/** Draws the exact Cburnett SVG geometry without WebView or device font dependencies. */
internal fun DrawScope.drawCburnettPiece(piece: Char, center: Offset, squareSize: Float) {
    val shapes = parsedPieces[piece] ?: return
    val scale = squareSize / 45f
    withTransform({
        translate(center.x - squareSize / 2f, center.y - squareSize / 2f)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        shapes.forEach { shape ->
            shape.style.fill?.let { drawPath(shape.path, it) }
            shape.style.stroke?.let {
                drawPath(
                    path = shape.path,
                    color = it,
                    style = Stroke(
                        width = shape.style.strokeWidth,
                        cap = shape.style.cap,
                        join = shape.style.join,
                    ),
                )
            }
        }
    }
}

private fun parseSvg(encoded: String): List<SvgShape> {
    val xml = Base64.decode(encoded, Base64.DEFAULT).toString(Charsets.UTF_8)
    val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
    val styles = ArrayDeque<SvgStyle>().apply { addLast(SvgStyle()) }
    val shapes = mutableListOf<SvgShape>()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "svg", "g" -> styles.addLast(parser.readStyle(styles.last()))
                "path" -> {
                    val data = parser.getAttributeValue(null, "d")
                    if (!data.isNullOrBlank()) {
                        val style = parser.readStyle(styles.last())
                        val path = PathParser().parsePathString(data).toPath().apply {
                            fillType = if (style.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
                        }
                        shapes += SvgShape(path, style)
                    }
                }
                "circle" -> {
                    val style = parser.readStyle(styles.last())
                    val cx = parser.getAttributeValue(null, "cx")?.toFloatOrNull() ?: 0f
                    val cy = parser.getAttributeValue(null, "cy")?.toFloatOrNull() ?: 0f
                    val r = parser.getAttributeValue(null, "r")?.toFloatOrNull() ?: 0f
                    val path = Path().apply {
                        addOval(Rect(cx - r, cy - r, cx + r, cy + r))
                        fillType = if (style.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero
                    }
                    shapes += SvgShape(path, style)
                }
            }
            XmlPullParser.END_TAG -> if (parser.name == "g" || parser.name == "svg") {
                if (styles.size > 1) styles.removeLast()
            }
        }
        event = parser.next()
    }
    return shapes
}

private fun XmlPullParser.readStyle(parent: SvgStyle): SvgStyle = parent.copy(
    fill = colorAttribute("fill", parent.fill),
    stroke = colorAttribute("stroke", parent.stroke),
    strokeWidth = getAttributeValue(null, "stroke-width")?.toFloatOrNull() ?: parent.strokeWidth,
    evenOdd = when (getAttributeValue(null, "fill-rule")) {
        "evenodd" -> true
        "nonzero" -> false
        else -> parent.evenOdd
    },
    cap = when (getAttributeValue(null, "stroke-linecap")) {
        "round" -> StrokeCap.Round
        "square" -> StrokeCap.Square
        "butt" -> StrokeCap.Butt
        else -> parent.cap
    },
    join = when (getAttributeValue(null, "stroke-linejoin")) {
        "round" -> StrokeJoin.Round
        "bevel" -> StrokeJoin.Bevel
        "miter" -> StrokeJoin.Miter
        else -> parent.join
    },
)

private fun XmlPullParser.colorAttribute(name: String, inherited: Color?): Color? =
    when (val raw = getAttributeValue(null, name)) {
        null -> inherited
        "none" -> null
        "#000", "#000000" -> Color.Black
        "#fff", "#ffffff" -> Color.White
        "#ececec" -> Color(0xFFECECEC)
        else -> runCatching { Color(android.graphics.Color.parseColor(raw)) }.getOrNull() ?: inherited
    }

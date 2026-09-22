package com.lyrix.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * Renders a lyric line to the exact pixel format the ESP32's
 * /bitmap endpoint expects: a packed 1-bit-per-pixel 128x52
 * monochrome bitmap, hex-encoded, MSB-first per byte, row by row.
 *
 * This exists because the ESP32 has no way to correctly shape
 * Bangla conjuncts/matras on its own - Android's StaticLayout
 * already does that shaping for us, so we render here on the
 * phone and just stream the resulting pixels over.
 */
object BanglaTextRenderer {

    const val WIDTH = 128
    const val HEIGHT = 52

    private const val BYTES_PER_ROW = (WIDTH + 7) / 8      // 16
    private const val TOTAL_BYTES = BYTES_PER_ROW * HEIGHT // 832

    /**
     * True if [text] contains any character from the Bengali
     * Unicode block (U+0980-U+09FF). Used to decide whether a
     * line needs this bitmap path, or can just use the ESP32's
     * own built-in ASCII renderer via /display.
     */
    fun isBangla(text: String): Boolean =
        text.any {
            it.code in 0x0980..0x09FF
        }

    /**
     * Renders [text] - word-wrapped and centered using Android's
     * own text layout - and returns it as a hex string ready to
     * POST straight to /bitmap.
     *
     * Tune textSize / maxLines below to taste once you see it on
     * the actual OLED; these defaults fit ~2 lines in the 52px
     * tall text area.
     */
    fun renderToHex(text: String): String {

        val bitmap =
            Bitmap.createBitmap(
                WIDTH,
                HEIGHT,
                Bitmap.Config.ARGB_8888
            )

        val canvas =
            Canvas(bitmap)

        canvas.drawColor(Color.BLACK) // background = OFF pixels

        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {

                color = Color.WHITE

                textSize = 18f

                // Swap this for a bundled Bangla-friendly font
                // (e.g. Noto Sans Bengali added as an asset/res
                // font) if the system default looks rough.
                typeface = Typeface.DEFAULT
            }

        // StaticLayout gives us real word-wrap plus correct
        // Bangla shaping - this is the whole point of rendering
        // on the phone instead of the ESP32.
        val layout =
            StaticLayout.Builder
                .obtain(
                    text,
                    0,
                    text.length,
                    paint,
                    WIDTH
                )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(WIDTH)
                .build()

        // Vertically center the (possibly multi-line) block.
        val top =
            ((HEIGHT - layout.height) / 2)
                .coerceAtLeast(0)

        canvas.save()
        canvas.translate(0f, top.toFloat())
        layout.draw(canvas)
        canvas.restore()

        // ---- pack to 1bpp, MSB-first per byte, row by row ----
        val packed = ByteArray(TOTAL_BYTES)

        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {

                val on =
                    Color.red(bitmap.getPixel(x, y)) > 127

                if (on) {

                    val byteIndex =
                        y * BYTES_PER_ROW + (x / 8)

                    val bitIndex =
                        7 - (x % 8)

                    packed[byteIndex] =
                        (packed[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                }
            }
        }

        val hex = StringBuilder(TOTAL_BYTES * 2)

        for (b in packed) {
            hex.append(String.format("%02x", b))
        }

        return hex.toString()
    }
}

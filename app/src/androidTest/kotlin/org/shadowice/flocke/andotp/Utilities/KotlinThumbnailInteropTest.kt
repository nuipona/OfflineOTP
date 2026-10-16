package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.TypedValue
import androidx.appcompat.content.res.AppCompatResources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.shadowice.flocke.andotp.R

/** Real platform rendering and lookup contracts for the converted thumbnail helpers. */
@RunWith(AndroidJUnit4::class)
class KotlinThumbnailInteropTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun lookupAndResourceGettersKeepTheirContracts() {
        for (thumbnail in EntryThumbnail.EntryThumbnails.values()) {
            assertSame(thumbnail, EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(thumbnail.name))
            assertSame(EntryThumbnail.AssetType.Vector, thumbnail.assetType)
            assertNotNull(context.resources.getResourceName(thumbnail.resource))
        }
        assertSame(
            EntryThumbnail.EntryThumbnails.GitHub,
            EntryThumbnail.EntryThumbnails.valueOfIgnoreCase("gItHuB")
        )
        assertSame(
            EntryThumbnail.EntryThumbnails.GitHub,
            EntryThumbnail.EntryThumbnails.valueOfFuzzy("GitHub Inc.")
        )
        assertSame(
            EntryThumbnail.EntryThumbnails.Git,
            EntryThumbnail.EntryThumbnails.valueOfFuzzy("GitHub and Git")
        )
        for (unknown in arrayOf("MyGitHubAccount", "GitHub_extra", "")) {
            try {
                EntryThumbnail.EntryThumbnails.valueOfFuzzy(unknown)
                fail("Expected a whole-word lookup failure")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(null)
            fail("Expected an exact lookup failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun defaultTilesKeepIssuerPrecedencePlaceholderAndIntegerCentering() {
        for (names in arrayOf(
            arrayOf("acme", "other"), arrayOf("", "alice"), arrayOf(null, "7alice"),
            arrayOf("éclair", "other"), arrayOf("!alice", "other"), arrayOf("", ""),
            arrayOf("negative-hash-key", "other"), arrayOf("😀", "other")
        )) {
            val issuer = names[0]
            val label = requireNotNull(names[1])
            val source = if (issuer.isNullOrEmpty()) label else issuer
            val actual = EntryThumbnail.getThumbnailGraphic(
                context, issuer, label, 65, EntryThumbnail.EntryThumbnails.Default
            )
            val expected = referenceTile(source, 65)
            try {
                assertNotNull(actual)
                assertTrue("Tile pixels changed", expected.sameAs(actual))
            } finally {
                expected.recycle()
                actual?.recycle()
            }
        }
    }

    @Test
    fun nonPositiveDefaultSizesStillUseTheLauncherFallback() {
        val expected = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_round)
        try {
            for (size in intArrayOf(0, -1)) {
                val actual = EntryThumbnail.getThumbnailGraphic(
                    context, "issuer", "label", size, EntryThumbnail.EntryThumbnails.Default
                )
                try {
                    if (expected == null) assertNull(actual) else {
                        assertNotNull(actual)
                        assertTrue(expected.sameAs(actual))
                    }
                } finally {
                    actual?.recycle()
                }
            }
        } finally {
            expected?.recycle()
        }
    }

    @Test
    fun bundledVectorsKeepTheirIntrinsicSizeAndBounds() {
        for (thumbnail in arrayOf(
            EntryThumbnail.EntryThumbnails.GitHub,
            EntryThumbnail.EntryThumbnails.Google
        )) {
            val drawable: Drawable = requireNotNull(
                AppCompatResources.getDrawable(context, thumbnail.resource)
            )
            val expected = Bitmap.createBitmap(
                drawable.minimumWidth, drawable.minimumHeight, Bitmap.Config.ARGB_8888
            )
            drawable.setBounds(0, 0, drawable.minimumWidth, drawable.minimumHeight)
            drawable.draw(Canvas(expected))
            val actual = EntryThumbnail.getThumbnailGraphic(
                context, "issuer", "label", 65, thumbnail
            )
            try {
                assertNotNull(actual)
                assertTrue(expected.sameAs(actual))
            } finally {
                expected.recycle()
                actual?.recycle()
            }
        }
    }

    /** Independent, fixed pre-conversion drawing recipe; uses only platform APIs. */
    private fun referenceTile(source: String, size: Int): Bitmap {
        var letter = '?'
        if (source.isNotEmpty() &&
            (Character.isAlphabetic(source.codePointAt(0)) || Character.isDigit(source[0]))
        ) {
            letter = source[0]
        }
        val text = charArrayOf(Character.toUpperCase(letter))
        val colors: TypedArray = context.resources.obtainTypedArray(R.array.letter_tile_colors)
        val color: Int
        try {
            color = colors.getColor(Math.abs(source.hashCode()) % 8, Color.BLACK)
        } finally {
            colors.recycle()
        }
        val scale = TypedValue()
        context.resources.getValue(R.dimen.tile_letter_font_size_scale, scale, true)
        val paint = TextPaint()
        paint.typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.isAntiAlias = true
        paint.textSize = scale.float * size
        val bounds = Rect()
        paint.getTextBounds(text, 0, 1, bounds)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        canvas.drawText(
            text, 0, 1, size / 2f,
            size / 2f + (bounds.bottom - bounds.top) / 2f, paint
        )
        return bitmap
    }
}

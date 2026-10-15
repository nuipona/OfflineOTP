package org.shadowice.flocke.andotp.Utilities;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.TypedValue;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.shadowice.flocke.andotp.R;

import static org.junit.Assert.*;

/** Real platform rendering and Java call sites; the desktop sandbox cannot verify either. */
@RunWith(AndroidJUnit4.class)
public class KotlinThumbnailInteropTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void javaLookupAndResourceGettersKeepTheirContracts() {
        for (EntryThumbnail.EntryThumbnails thumbnail : EntryThumbnail.EntryThumbnails.values()) {
            assertSame(thumbnail, EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(thumbnail.name()));
            assertSame(EntryThumbnail.AssetType.Vector, thumbnail.getAssetType());
            assertNotNull(context.getResources().getResourceName(thumbnail.getResource()));
        }
        assertSame(EntryThumbnail.EntryThumbnails.GitHub,
                EntryThumbnail.EntryThumbnails.valueOfIgnoreCase("gItHuB"));
        assertSame(EntryThumbnail.EntryThumbnails.GitHub,
                EntryThumbnail.EntryThumbnails.valueOfFuzzy("GitHub Inc."));
        assertSame(EntryThumbnail.EntryThumbnails.Git,
                EntryThumbnail.EntryThumbnails.valueOfFuzzy("GitHub and Git"));
        for (String unknown : new String[] { "MyGitHubAccount", "GitHub_extra", "" }) {
            try {
                EntryThumbnail.EntryThumbnails.valueOfFuzzy(unknown);
                fail("Expected a whole-word lookup failure");
            } catch (IllegalArgumentException expected) { }
        }
        try {
            EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(null);
            fail("Expected an exact lookup failure");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void defaultTilesKeepIssuerPrecedencePlaceholderAndIntegerCentering() {
        // Odd dimensions pin Java's integer division. Include Unicode, negative hashes, empty
        // names and a punctuation-prefixed name (must not skip forward to an alphabetic char).
        for (String[] names : new String[][] {
                { "acme", "other" }, { "", "alice" }, { null, "7alice" },
                { "éclair", "other" }, { "!alice", "other" }, { "", "" },
                { "negative-hash-key", "other" }, { "😀", "other" }
        }) {
            String source = names[0] == null || names[0].isEmpty() ? names[1] : names[0];
            Bitmap actual = EntryThumbnail.getThumbnailGraphic(context, names[0], names[1], 65,
                    EntryThumbnail.EntryThumbnails.Default);
            Bitmap expected = referenceTile(source, 65);
            try {
                assertNotNull(actual);
                assertTrue("Tile pixels changed", expected.sameAs(actual));
            } finally {
                expected.recycle();
                if (actual != null) actual.recycle();
            }
        }
    }

    @Test public void nonPositiveDefaultSizesStillUseTheLauncherFallback() {
        Bitmap expected = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher_round);
        try {
            for (int size : new int[] { 0, -1 }) {
                Bitmap actual = EntryThumbnail.getThumbnailGraphic(context, "issuer", "label", size,
                        EntryThumbnail.EntryThumbnails.Default);
                try {
                    // BitmapFactory may return null for the adaptive launcher XML on API 28+.
                    // Preserve that existing nullable fallback rather than silently changing it.
                    if (expected == null) assertNull(actual);
                    else {
                        assertNotNull(actual);
                        assertTrue(expected.sameAs(actual));
                    }
                } finally {
                    if (actual != null) actual.recycle();
                }
            }
        } finally {
            if (expected != null) expected.recycle();
        }
    }

    @Test public void bundledVectorsKeepTheirIntrinsicSizeAndBounds() {
        for (EntryThumbnail.EntryThumbnails thumbnail : new EntryThumbnail.EntryThumbnails[] {
                EntryThumbnail.EntryThumbnails.GitHub, EntryThumbnail.EntryThumbnails.Google
        }) {
            Drawable drawable = AppCompatResources.getDrawable(context, thumbnail.getResource());
            assertNotNull(drawable);
            Bitmap expected = Bitmap.createBitmap(drawable.getMinimumWidth(), drawable.getMinimumHeight(),
                    Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, drawable.getMinimumWidth(), drawable.getMinimumHeight());
            drawable.draw(new Canvas(expected));
            // The requested letter-tile size must not resize branded vector icons.
            Bitmap actual = EntryThumbnail.getThumbnailGraphic(context, "issuer", "label", 65, thumbnail);
            try {
                assertNotNull(actual);
                assertTrue(expected.sameAs(actual));
            } finally {
                expected.recycle();
                if (actual != null) actual.recycle();
            }
        }
    }

    /** Independent, fixed pre-conversion drawing recipe; uses only platform APIs. */
    private Bitmap referenceTile(String source, int size) {
        char letter = '?';
        if (!source.isEmpty() && (Character.isAlphabetic(source.codePointAt(0))
                || Character.isDigit(source.charAt(0)))) {
            letter = source.charAt(0);
        }
        char[] text = { Character.toUpperCase(letter) };
        TypedArray colors = context.getResources().obtainTypedArray(R.array.letter_tile_colors);
        int color;
        try {
            color = colors.getColor(Math.abs(source.hashCode()) % 8, Color.BLACK);
        } finally {
            colors.recycle();
        }
        TypedValue scale = new TypedValue();
        context.getResources().getValue(R.dimen.tile_letter_font_size_scale, scale, true);
        TextPaint paint = new TextPaint();
        paint.setTypeface(Typeface.create("sans-serif-light", Typeface.BOLD));
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setAntiAlias(true);
        paint.setTextSize(scale.getFloat() * size);
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, 1, bounds);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(color);
        canvas.drawText(text, 0, 1, size / 2, size / 2 + (bounds.bottom - bounds.top) / 2, paint);
        return bitmap;
    }
}

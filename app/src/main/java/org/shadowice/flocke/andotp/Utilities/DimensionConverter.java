/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2017-2020 Richy HBM
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Utilities;

import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts dimension strings such as "12dp" from the preferences to pixels. */
public class DimensionConverter {

    // Maps dimension unit suffixes ("dp", "sp", ...) to TypedValue constants
    /** Lookup table for the supported unit suffixes. */
    public static final Map<String, Integer> dimensionConstantLookup = initDimensionConstantLookup();
    private static Map<String, Integer> initDimensionConstantLookup() {
        Map<String, Integer> m = new HashMap<String, Integer>();
        m.put("px", TypedValue.COMPLEX_UNIT_PX);
        m.put("dip", TypedValue.COMPLEX_UNIT_DIP);
        m.put("dp", TypedValue.COMPLEX_UNIT_DIP);
        m.put("sp", TypedValue.COMPLEX_UNIT_SP);
        m.put("pt", TypedValue.COMPLEX_UNIT_PT);
        m.put("in", TypedValue.COMPLEX_UNIT_IN);
        m.put("mm", TypedValue.COMPLEX_UNIT_MM);
        return Collections.unmodifiableMap(m);
    }
    // Pattern for values like "12dp" or "1.5sp"
    private static final Pattern DIMENSION_PATTERN = Pattern.compile("^\\s*(\\d+(\\.\\d+)*)\\s*([a-zA-Z]+)\\s*$");

    /** Like {@link TypedValue#complexToDimensionPixelSize}, but parses a string such as "12dp". */
    public static int stringToDimensionPixelSize(String dimension, DisplayMetrics metrics) {
        // Mimics TypedValue.complexToDimensionPixelSize(int, DisplayMetrics)
        InternalDimension internalDimension = stringToInternalDimension(dimension);
        final float value = internalDimension.value;
        final float f = TypedValue.applyDimension(internalDimension.unit, value, metrics);
        final int res = (int)(f+0.5f);
        if (res != 0) return res;
        if (value == 0) return 0;
        if (value > 0) return 1;
        return -1;
    }

    /** Like {@link TypedValue#complexToDimension}, but parses a string such as "12dp". */
    public static float stringToDimension(String dimension, DisplayMetrics metrics) {
        // Mimics TypedValue.complexToDimension(int, DisplayMetrics)
        InternalDimension internalDimension = stringToInternalDimension(dimension);
        return TypedValue.applyDimension(internalDimension.unit, internalDimension.value, metrics);
    }

    /** Parses a dimension string; throws {@link NumberFormatException} if it is invalid. */
    private static InternalDimension stringToInternalDimension(String dimension) {
        Matcher matcher = DIMENSION_PATTERN.matcher(dimension);
        if (matcher.matches()) {
            float value = Float.valueOf(matcher.group(1)).floatValue();
            String unit = matcher.group(3).toLowerCase();
            Integer dimensionUnit = dimensionConstantLookup.get(unit);
            if (dimensionUnit == null) {
                throw new NumberFormatException();
            } else {
                return new InternalDimension(value, dimensionUnit);
            }
        } else {
            throw new NumberFormatException();
        }        
    }

    /** A parsed dimension: its value and the matching TypedValue unit. */
    private static class InternalDimension {
        float value;
        int unit;

        public InternalDimension(float value, int unit) {
            this.value = value;
            this.unit = unit;
        }
    }
}
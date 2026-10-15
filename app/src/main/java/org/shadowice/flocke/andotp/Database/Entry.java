/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2015 Bruno Bierbaumer
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

package org.shadowice.flocke.andotp.Database;

import android.net.Uri;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Hex;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail;
import org.shadowice.flocke.andotp.Utilities.TokenCalculator;

import org.shadowice.flocke.andotp.Utilities.InputPolicy;
import org.shadowice.flocke.andotp.Utilities.OtpUriParser;
import org.shadowice.flocke.andotp.Utilities.OtpParseResult;
import org.shadowice.flocke.andotp.Utilities.ValidatedOtp;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** One OTP entry: its secret, type and display/sort metadata, serialized to JSON and otpauth:// URIs. */
public class Entry {
    /** The OTP algorithms supported by andOTP. */
    public enum OTPType {
        TOTP, HOTP, MOTP, STEAM
    }

    private static final OTPType DEFAULT_TYPE = OTPType.TOTP;
    private static final int DEFAULT_PERIOD = 30;
    // Token placeholder shown while an mOTP entry has no PIN
    private static final String MOTP_NO_PIN_CODE = "PINREQ";

    private static final String JSON_SECRET = "secret";
    private static final String JSON_ISSUER = "issuer";
    private static final String JSON_LABEL = "label";
    private static final String JSON_PERIOD = "period";
    private static final String JSON_COUNTER = "counter";
    private static final String JSON_DIGITS = "digits";
    private static final String JSON_TYPE = "type";
    private static final String JSON_ALGORITHM = "algorithm";
    private static final String JSON_TAGS = "tags";
    private static final String JSON_THUMBNAIL = "thumbnail";
    private static final String JSON_LAST_USED = "last_used";
    private static final String JSON_USED_FREQUENCY = "used_frequency";

    private OTPType type = OTPType.TOTP;
    private int period = TokenCalculator.TOTP_DEFAULT_PERIOD;
    private int digits = TokenCalculator.TOTP_DEFAULT_DIGITS;
    private TokenCalculator.HashAlgorithm algorithm = TokenCalculator.DEFAULT_ALGORITHM;
    private byte[] secret;
    private long counter;
    private String issuer;
    private String label;
    private String currentOTP;
    private String prevOTP;
    private boolean visible = false;
    private Runnable hideTask = null;
    private long last_update = 0;
    private long last_used = 0;
    private long used_frequency = 0;
    public List<String> tags = new ArrayList<>();
    private EntryThumbnail.EntryThumbnails thumbnail = EntryThumbnail.EntryThumbnails.Default;
    private static final int COLOR_DEFAULT = 0;
    public static final int COLOR_RED = 1;
    private static final int EXPIRY_TIME = 8;               // Seconds before the period ends to highlight the token
    private int color = COLOR_DEFAULT;
    private String pin = "";
    private long listId = 0;

    public Entry(){}

    public Entry(OTPType type, String secret, int period, int digits, String issuer, String label, TokenCalculator.HashAlgorithm algorithm, List<String> tags) {
        this.type = type;
        this.secret = decodeSecret(secret);
        this.period = period;
        this.digits = digits;
        this.issuer = issuer;
        this.label = label;
        this.algorithm = algorithm;
        this.tags = tags;
        setThumbnailFromIssuer(issuer);
        validate();
    }

    public Entry(OTPType type, String secret, long counter, int digits, String issuer, String label, TokenCalculator.HashAlgorithm algorithm, List<String> tags) {
        this.type = type;
        this.secret = decodeSecret(secret);
        this.counter = counter;
        this.digits = digits;
        this.issuer = issuer;
        this.label = label;
        this.algorithm = algorithm;
        this.tags = tags;
        setThumbnailFromIssuer(issuer);
        validate();
    }

    public Entry(OTPType type, String secret, String issuer, String label, List<String> tags) {
        this.type = type;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.label = label;
        this.tags = tags;
        this.period = TokenCalculator.TOTP_DEFAULT_PERIOD;
        setThumbnailFromIssuer(issuer);
        validate();
    }

    /** Legacy Java adapter: URI policy and explicit outcomes live in the Kotlin parser. */
    public Entry(String contents) throws Exception {
        OtpParseResult result = OtpUriParser.parse(contents);
        if (result instanceof OtpParseResult.Failure) {
            throw new IllegalArgumentException("Invalid OTP URI: " + ((OtpParseResult.Failure) result).getError().name());
        }
        if (!(result instanceof OtpParseResult.Success)) throw new IllegalStateException("Unknown OTP parser result");
        ValidatedOtp value = ((OtpParseResult.Success) result).getValue();
        try {
            this.type = OTPType.valueOf(value.getKind().name());
            this.secret = value.copySecret();
            this.period = value.getPeriod();
            this.digits = value.getDigits();
            this.counter = value.getCounter();
            this.issuer = value.getIssuer();
            this.label = value.getLabel();
            this.algorithm = TokenCalculator.HashAlgorithm.valueOf(value.getAlgorithm().name());
            this.tags = new ArrayList<>(value.getTags());
            setThumbnailFromIssuer(issuer);
            validate();
        } catch (RuntimeException error) {
            destroy();
            throw error;
        } finally { value.close(); }
    }

    public Entry (JSONObject jsonObj)
            throws Exception {
        try {
            this.secret = decodeSecret(string(jsonObj, JSON_SECRET));
            this.label = string(jsonObj, JSON_LABEL);

            // Older backups did not store the issuer separately.
            this.issuer = jsonObj.has(JSON_ISSUER) ? string(jsonObj, JSON_ISSUER) : "";

            this.type = jsonObj.has(JSON_TYPE) ? OTPType.valueOf(jsonObj.getString(JSON_TYPE)) : DEFAULT_TYPE;
            this.period = jsonObj.has(JSON_PERIOD) ? Math.toIntExact(integer(jsonObj, JSON_PERIOD)) : DEFAULT_PERIOD;
            if (this.type == OTPType.HOTP && !jsonObj.has(JSON_COUNTER)) throw new IllegalArgumentException("Missing HOTP counter");
            this.counter = jsonObj.has(JSON_COUNTER) ? integer(jsonObj, JSON_COUNTER) : 0;
            this.digits = jsonObj.has(JSON_DIGITS) ? Math.toIntExact(integer(jsonObj, JSON_DIGITS))
                    : (type == OTPType.STEAM ? TokenCalculator.STEAM_DEFAULT_DIGITS : TokenCalculator.TOTP_DEFAULT_DIGITS);
            this.algorithm = jsonObj.has(JSON_ALGORITHM)
                    ? TokenCalculator.HashAlgorithm.valueOf(jsonObj.getString(JSON_ALGORITHM)) : TokenCalculator.DEFAULT_ALGORITHM;
            if (jsonObj.has(JSON_TAGS)) {
                JSONArray array = jsonObj.getJSONArray(JSON_TAGS);
                if (array.length() > InputPolicy.MAX_TAGS) throw new IllegalArgumentException("Too many tags");
                for (int i = 0; i < array.length(); i++) {
                    Object tag = array.get(i);
                    if (!(tag instanceof String)) throw new IllegalArgumentException("Invalid tag");
                    tags.add((String) tag);
                }
            }

            try {
                this.thumbnail = EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(jsonObj.getString(JSON_THUMBNAIL));
            } catch(Exception e) {
                this.thumbnail = EntryThumbnail.EntryThumbnails.Default;
            }

            try {
                this.last_used = jsonObj.getLong(JSON_LAST_USED);
            } catch (Exception e) {
                this.last_used = 0;
            }

            try {
                this.used_frequency = jsonObj.getLong(JSON_USED_FREQUENCY);
            } catch (Exception e) {
                this.used_frequency = 0;
            }
            validate();
        } catch (Exception error) {
            destroy();
            throw error;
        }
    }

    private static String string(JSONObject object, String name) throws JSONException {
        Object value = object.get(name);
        if (!(value instanceof String)) throw new IllegalArgumentException("Invalid entry text");
        return (String) value;
    }

    private static long integer(JSONObject object, String name) throws JSONException {
        String value = object.get(name).toString();
        if (!value.matches("-?[0-9]+")) throw new IllegalArgumentException("Invalid integer");
        return Long.parseLong(value);
    }

    private static byte[] decodeSecret(String encoded) {
        if (encoded == null || encoded.length() > InputPolicy.MAX_SECRET_BYTES * 2) throw new IllegalArgumentException("Invalid secret");
        String normalized = encoded.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z2-7]+={0,6}")) throw new IllegalArgumentException("Invalid Base32 secret");
        byte[] bytes = new Base32().decode(normalized);
        String canonical = new Base32().encodeAsString(bytes);
        if (!(normalized.equals(canonical) || normalized.equals(canonical.replace("=", "")))) {
            throw new IllegalArgumentException("Noncanonical Base32 secret");
        }
        return bytes;
    }

    public void validate() {
        if (type == null || algorithm == null || secret == null || secret.length == 0
                || secret.length > InputPolicy.MAX_SECRET_BYTES) throw new IllegalArgumentException("Invalid entry");
        InputPolicy.tokenParameters(period, digits, counter);
        if (type == OTPType.STEAM && digits != TokenCalculator.STEAM_DEFAULT_DIGITS) throw new IllegalArgumentException("Invalid Steam digits");
        InputPolicy.text(issuer, InputPolicy.MAX_TEXT_CHARS);
        InputPolicy.text(label, InputPolicy.MAX_TEXT_CHARS);
        InputPolicy.text(pin, InputPolicy.MAX_TAG_CHARS);
        if (tags == null || tags.size() > InputPolicy.MAX_TAGS) throw new IllegalArgumentException("Invalid tags");
        for (String tag : tags) {
            if (tag == null) throw new IllegalArgumentException("Invalid tag");
            InputPolicy.text(tag, InputPolicy.MAX_TAG_CHARS);
        }
    }

    /** Best-effort cleanup of owned arrays/references, including entries held by views. */
    public void destroy() {
        if (secret != null) Arrays.fill(secret, (byte) 0);
        secret = null;
        currentOTP = prevOTP = pin = issuer = label = "";
        tags = new ArrayList<>();
        visible = false;
        hideTask = null;
    }

    /** Serializes this entry to the JSON object used in the database and in backups. */
    public JSONObject toJSON() throws JSONException {
        JSONObject jsonObj = new JSONObject();
        jsonObj.put(JSON_SECRET, new String(new Base32().encode(getSecret())));
        jsonObj.put(JSON_ISSUER, getIssuer());
        jsonObj.put(JSON_LABEL, getLabel());
        jsonObj.put(JSON_DIGITS, getDigits());
        jsonObj.put(JSON_TYPE, getType().toString());
        jsonObj.put(JSON_ALGORITHM, algorithm.toString());
        jsonObj.put(JSON_THUMBNAIL, getThumbnail().name());
        jsonObj.put(JSON_LAST_USED, getLastUsed());
        jsonObj.put(JSON_USED_FREQUENCY, getUsedFrequency() );

        if (type == OTPType.TOTP)
            jsonObj.put(JSON_PERIOD, getPeriod());
        else if (type == OTPType.HOTP)
            jsonObj.put(JSON_COUNTER, getCounter());

        JSONArray tagsArray = new JSONArray();
        for(String tag : tags){
            tagsArray.put(tag);
        }
        jsonObj.put(JSON_TAGS, tagsArray);

        return jsonObj;
    }

    /** Serializes this entry to an otpauth:// URI (null if the type is unsupported). */
    public Uri toUri() {
        String type;
        switch (this.type) {
            case TOTP:
                type = "totp";
                break;
            case HOTP:
                type = "hotp";
                break;
            case STEAM:
                type = "steam";
                break;
            case MOTP:
                type = "motp";
                break;
            default:
                return null;
        }
        Uri.Builder builder = new Uri.Builder()
                .scheme("otpauth")
                .authority(type)
                .appendPath(this.label);

        if (this.type == OTPType.MOTP)
            builder.appendQueryParameter("secret", new String(this.secret));
        else
             builder.appendQueryParameter("secret", new Base32().encodeAsString(this.secret));

        if (this.issuer != null) {
            builder.appendQueryParameter("issuer", this.issuer);
        }
        switch (this.type) {
            case HOTP:
                builder.appendQueryParameter("counter", Long.toString(this.counter));
            case TOTP:
                if (this.period != TokenCalculator.TOTP_DEFAULT_PERIOD)
                    builder.appendQueryParameter("period", Integer.toString(this.period));
                break;
        }
        if (this.digits != TokenCalculator.TOTP_DEFAULT_DIGITS) {
            builder.appendQueryParameter("digits", Integer.toString(this.digits));
        }
        if (this.algorithm != TokenCalculator.DEFAULT_ALGORITHM) {
            builder.appendQueryParameter("algorithm", this.algorithm.name());
        }
        for (String tag : this.tags) {
            builder.appendQueryParameter("tags", tag);
        }
        return builder.build();
    }

    public boolean isTimeBased() {
        return type == OTPType.TOTP || type == OTPType.STEAM || type == OTPType.MOTP;
    }

    public boolean isCounterBased() { return type == OTPType.HOTP; }

    public OTPType getType() {
        return type;
    }

    public void setType(OTPType type) {
        this.type = type;
    }

    public byte[] getSecret() {
        return secret == null ? null : secret.clone();
    }

    /** The secret as stored/displayed: Base32, or the raw secret for mOTP. */
    public String getSecretEncoded() {
        if (type == OTPType.MOTP)
            return new String(secret);
        else
            return new String(new Base32().encode(secret));
    }

    public void setSecret(byte[] secret) {
        this.secret = secret == null ? null : secret.clone();
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer, boolean updateThumbnail) {
        this.issuer = issuer;

        if (updateThumbnail && issuer != null)
            setThumbnailFromIssuer(issuer);
        validate();
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getPeriod() {
        return period;
    }

    public void setPeriod(int period) {
        this.period = period;
    }

    public long getCounter() {
        return counter;
    }

    public void setCounter(long counter) {
        this.counter = counter;
    }

    public int getDigits() {
        return digits;
    }

    public void setDigits(int digits) {
        this.digits = digits;
    }

    public List<String> getTags() { return tags; }

    public void setTags(List<String> tags) { this.tags = tags; }

    public EntryThumbnail.EntryThumbnails getThumbnail() { return thumbnail; }

    public void setThumbnail( EntryThumbnail.EntryThumbnails value) { thumbnail = value; }

    public TokenCalculator.HashAlgorithm getAlgorithm() {
        return this.algorithm;
    }

    public boolean hasNonDefaultPeriod() {
        return this.period != TokenCalculator.TOTP_DEFAULT_PERIOD;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean value) {
        this.visible = value;
    }

    public void setHideTask(Runnable newTask) {
        this.hideTask = newTask;
    }

    public Runnable getHideTask() {
        return this.hideTask;
    }

    public long getLastUsed() {
        return this.last_used;
    }

    public void setLastUsed(long value) {
        this.last_used = value;
    }

    public long getUsedFrequency() {
        return used_frequency;
    }

    public void setUsedFrequency(long value) {
        this.used_frequency = value;
    }

    public String getCurrentOTP() {
        return currentOTP;
    }

    public String getPrevOTP() {
        return prevOTP;
    }

    public String getPin() {
        return pin;
    }

    public void setPin(String pin) {
        this.pin = pin;
    }

    public long getListId() {
        return listId;
    }

    public void setListId(long newId) {
        listId = newId;
    }

    /** Recalculates the token ({@code updateNow} forces a time-based refresh); returns whether one was produced. */
    public boolean updateOTP(boolean updateNow) {
        if (secret == null) return false;
        validate();
        if (type == OTPType.TOTP || type == OTPType.STEAM || type == OTPType.MOTP) {
            long time = System.currentTimeMillis() / 1000;
            long counter = time / this.getPeriod();

            if (updateNow || counter > last_update) {
                // Keep the previous token so it does not have to be recalculated
                if (currentOTP != null && !currentOTP.isEmpty())
                    prevOTP = currentOTP;
                else
                    prevOTP = "";

                switch (type) {
                    case TOTP:
                        currentOTP = TokenCalculator.TOTP_RFC6238(secret, period, digits, algorithm, 0);

                        if (prevOTP == null || prevOTP.isEmpty())
                            prevOTP = TokenCalculator.TOTP_RFC6238(secret, period, digits, algorithm, -1);

                        break;
                    case STEAM:
                        currentOTP = TokenCalculator.TOTP_Steam(secret, period, digits, algorithm, 0);

                        if (prevOTP == null || prevOTP.isEmpty())
                            prevOTP = TokenCalculator.TOTP_Steam(secret, period, digits, algorithm, -1);

                        break;
                    case MOTP:
                        String currentPin = this.getPin();

                        if (currentPin.isEmpty()) {
                            currentOTP = MOTP_NO_PIN_CODE;
                        } else {
                            currentOTP = TokenCalculator.MOTP(currentPin, new String(this.secret), time, 0);

                            if (prevOTP == null || prevOTP.isEmpty())
                                prevOTP = TokenCalculator.MOTP(currentPin, new String(this.secret), time, -1);
                        }

                        break;
                }

                last_update = counter;
                setColor(COLOR_DEFAULT);
                return true;
            } else {
                return false;
            }
        } else if (type == OTPType.HOTP) {
            currentOTP = TokenCalculator.HOTP(secret, counter, digits, algorithm);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Returns true the first time the token is within {@link #EXPIRY_TIME} seconds of expiring,
     * switching the entry to the expiring color. {@link #updateOTP(boolean)} resets that color.
     */
    public boolean hasColorChanged() {
        if(color == COLOR_DEFAULT){
            long time = System.currentTimeMillis() / 1000;
            if ((time % getPeriod()) > (getPeriod() - EXPIRY_TIME)) {
                setColor(COLOR_RED);
                return true;
            }
        }
        return false;
    }

    /** Sets the thumbnail by exact, then by fuzzy issuer match. */
    private void setThumbnailFromIssuer(String issuer) {
        try {
            this.thumbnail = EntryThumbnail.EntryThumbnails.valueOfIgnoreCase(issuer);
        } catch(Exception e) {
            try {
                this.thumbnail = EntryThumbnail.EntryThumbnails.valueOfFuzzy(issuer);
            } catch(Exception e2) {
                this.thumbnail = EntryThumbnail.EntryThumbnails.Default;
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;

        if (o == null || getClass() != o.getClass())
            return false;

        Entry entry = (Entry) o;

        return type == entry.type &&
                period == entry.period &&
                counter == entry.counter &&
                digits == entry.digits &&
                algorithm == entry.algorithm &&
                Arrays.equals(secret, entry.secret) &&
                Objects.equals(label, entry.label) &&
                Objects.equals(issuer, entry.issuer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, period, counter, digits, algorithm, secret, label, issuer);
    }

    public void setColor(int color) {
        this.color = color;
    }

    public int getColor() {
        return color;
    }

    /** True if the secret is valid Base32 (or hex for mOTP). */
    public static boolean validateSecret(String secret, OTPType type) {
        try {
            if (type == OTPType.MOTP)
                Hex.decodeHex(secret);
            else
                decodeSecret(secret);
        } catch (Exception e) {
            return false;
        }

        return true;
    }

}

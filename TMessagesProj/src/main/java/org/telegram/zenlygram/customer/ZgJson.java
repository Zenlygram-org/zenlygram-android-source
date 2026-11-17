/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.customer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * JSON as the cloud client's pure models read it: a parsed object or array as plain values (Map, List, String,
 * Integer / Long, Double, Boolean, null), which {@code ConsentManifest.fromTree}, {@code ConsentEvent.fromTree} and
 * their kin validate. Pure Java over org.json, so it runs on the JVM tests too.
 */
final class ZgJson {

    private ZgJson() {
    }

    /** The plain tree of a JSON object or array text, or null when it is not one. */
    static Object parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            final String trimmed = text.trim();
            if (trimmed.startsWith("{")) {
                return plain(new JSONObject(trimmed));
            }
            if (trimmed.startsWith("[")) {
                return plain(new JSONArray(trimmed));
            }
        } catch (Exception unreadable) {
            // An unreadable value reads as absent.
        }
        return null;
    }

    static Object plain(Object value) {
        if (value instanceof JSONObject) {
            final JSONObject object = (JSONObject) value;
            final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                final String key = keys.next();
                map.put(key, plain(object.opt(key)));
            }
            return map;
        }
        if (value instanceof JSONArray) {
            final JSONArray array = (JSONArray) value;
            final ArrayList<Object> list = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                list.add(plain(array.opt(i)));
            }
            return list;
        }
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        // org.json off the device may hand a whole number as BigInteger / BigDecimal; the models take Integer / Long.
        if (value instanceof BigInteger) {
            final BigInteger number = (BigInteger) value;
            return number.bitLength() < 64 ? (Object) number.longValue() : (Object) number.doubleValue();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).doubleValue();
        }
        return value;
    }
}

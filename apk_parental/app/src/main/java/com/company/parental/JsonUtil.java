package com.company.parental;

import org.json.JSONArray;

import java.util.HashSet;
import java.util.Set;

/** Helper konversi JSON <-> Set. */
public final class JsonUtil {
    private JsonUtil() {}

    public static Set<String> toStringSet(JSONArray arr) {
        Set<String> out = new HashSet<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, null);
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static JSONArray toJsonArray(Set<String> set) {
        JSONArray a = new JSONArray();
        for (String s : set) a.put(s);
        return a;
    }
}

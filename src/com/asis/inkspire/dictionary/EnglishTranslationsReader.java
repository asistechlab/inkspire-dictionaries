package com.asis.inkspire.dictionary;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Разбор английского Викисловаря (kaikki.org, статьи английских слов) ради поля {@code translations}:
 * по английскому слову — его переводы на один выбранный язык. Файл читается потоком, в памяти остаются
 * только статьи, где есть переводы на этот язык. Результат — статьи «English → язык» для {@link DictionaryBuilder}.
 */
public final class EnglishTranslationsReader {

    private static final int MAX_WORD = 40;

    public final List<TeiParser.Entry> entries = new ArrayList<>();

    private final Set<String> codes = new LinkedHashSet<>();

    /** @param lang код языка перевода; китайский в переводах может быть записан как {@code zh} или {@code cmn}. */
    public EnglishTranslationsReader(String lang) {
        codes.add(lang);
        if ("zh".equals(lang)) {
            codes.add("cmn");
        }
    }

    public EnglishTranslationsReader read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        for (String line; (line = r.readLine()) != null; ) {
            // Дешёвый отбор до разбора JSON: нужны только строки с переводами на нужный язык.
            if (!line.contains("\"translations\"") || !hasCode(line)) {
                continue;
            }
            JsonObject o;
            try {
                o = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException e) {
                continue;
            }
            readEntry(o);
        }
        return this;
    }

    private boolean hasCode(String line) {
        for (String c : codes) {
            if (line.contains("\"lang_code\": \"" + c + "\"") || line.contains("\"lang_code\":\"" + c + "\"")) {
                return true;
            }
        }
        return false;
    }

    private void readEntry(JsonObject o) {
        if (!"en".equals(str(o, "lang_code"))) {
            return;
        }
        String word = str(o, "word");
        String pos = KaikkiReader.pos(str(o, "pos"));
        if (word.isEmpty() || pos == null || word.indexOf(' ') >= 0 && word.length() > MAX_WORD) {
            return;
        }
        Map<String, LinkedHashSet<String>> bySense = new LinkedHashMap<>();
        for (JsonElement te : arr(o, "translations")) {
            if (!te.isJsonObject()) {
                continue;
            }
            JsonObject t = te.getAsJsonObject();
            if (!codes.contains(str(t, "lang_code")) && !codes.contains(str(t, "code"))) {
                continue;
            }
            String w = str(t, "word").trim();
            if (w.isEmpty() || w.length() > MAX_WORD) {
                continue;
            }
            bySense.computeIfAbsent(str(t, "sense"), k -> new LinkedHashSet<>()).add(w);
        }
        if (bySense.isEmpty()) {
            return;
        }
        TeiParser.Entry e = new TeiParser.Entry();
        e.orth = word;
        e.pos = pos;
        for (Map.Entry<String, LinkedHashSet<String>> x : bySense.entrySet()) {
            TeiParser.Sense s = new TeiParser.Sense();
            s.translations.addAll(x.getValue());
            s.hint = x.getKey();
            e.senses.add(s);
        }
        entries.add(e);
    }

    private static String str(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static JsonArray arr(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}

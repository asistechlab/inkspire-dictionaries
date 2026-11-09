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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Разбор выгрузки английского Викисловаря по русскому языку (kaikki.org, wiktextract; JSONL, по статье в строке).
 * Даёт две вещи: статьи с английскими значениями (для слов, которых нет в FreeDict) и формы слов
 * («ушла» → «уйти»): из таблиц форм статей и из статей-форм («form of»).
 */
public final class KaikkiReader {

    static final int MAX_GLOSS = 100;
    private static final int MAX_SENSES = 4;

    /** Статьи со значениями — в виде статей TEI, чтобы их собирал тот же {@link DictionaryBuilder}. */
    public final List<TeiParser.Entry> entries = new ArrayList<>();
    /** Форма (ключ) → начальные формы (ключи). */
    public final Map<String, Set<String>> forms = new HashMap<>();

    private final String lang;

    /** Русский язык (для словарей, вшитых в приложение). */
    public KaikkiReader() {
        this("ru");
    }

    /** @param lang код языка статей в выгрузке ({@code lang_code}): ru, fr, es, de… */
    public KaikkiReader(String lang) {
        this.lang = lang;
    }

    /** Для русского — только кириллические ключи; для остальных языков — любые. */
    private boolean accepts(String key) {
        return !"ru".equals(lang) || Keys.isRussian(key);
    }

    public KaikkiReader read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        for (String line; (line = r.readLine()) != null; ) {
            if (line.isEmpty()) {
                continue;
            }
            JsonObject o;
            try {
                o = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException e) {
                continue; // оборванная строка — пропускаем
            }
            if (!lang.equals(str(o, "lang_code"))) {
                continue;
            }
            readEntry(o);
        }
        return this;
    }

    private void readEntry(JsonObject o) {
        String word = str(o, "word");
        String lemmaKey = Keys.normalize(word);
        if (lemmaKey.isEmpty() || !accepts(lemmaKey)) {
            return;
        }
        String pos = pos(str(o, "pos"));
        if (pos != null && pos.equals("character") && !isCjk(str(o, "lang_code"))) {
            pos = null; // буквы алфавита — не слова
        }
        if (pos == null) {
            return;
        }
        TeiParser.Entry entry = new TeiParser.Entry();
        entry.orth = word;
        entry.pos = pos;
        JsonArray formList = arr(o, "forms");
        for (JsonElement fe : formList) {
            JsonObject f = fe.getAsJsonObject();
            String form = str(f, "form");
            Set<String> tags = tags(f);
            if (tags.contains("canonical") && Keys.normalize(form).equals(lemmaKey)) {
                entry.orth = form; // с ударением: «уйти́»
                continue;
            }
            if (tags.contains("romanization") || tags.contains("table-tags") || tags.contains("inflection-template")
                    || tags.contains("class") || form.indexOf(' ') >= 0) {
                continue;
            }
            addForm(form, lemmaKey);
        }
        entry.pron = reading(o, word);
        for (JsonElement se : arr(o, "senses")) {
            JsonObject s = se.getAsJsonObject();
            JsonArray formOf = arr(s, "form_of");
            if (formOf.size() > 0 || arr(s, "alt_of").size() > 0) {
                for (JsonElement fo : formOf) {
                    addForm(word, Keys.normalize(str(fo.getAsJsonObject(), "word")));
                }
                continue;
            }
            JsonArray glosses = arr(s, "glosses");
            if (glosses.size() == 0 || entry.senses.size() >= MAX_SENSES) {
                continue;
            }
            String gloss = glosses.get(glosses.size() - 1).getAsString().trim();
            if (gloss.isEmpty()) {
                continue;
            }
            if (gloss.length() > MAX_GLOSS) {
                int cut = gloss.lastIndexOf(' ', MAX_GLOSS - 1);
                gloss = gloss.substring(0, cut > MAX_GLOSS / 2 ? cut : MAX_GLOSS - 1) + "…";
            }
            TeiParser.Sense sense = new TeiParser.Sense();
            sense.translations.add(gloss);
            entry.senses.add(sense);
        }
        if (!entry.senses.isEmpty()) {
            entries.add(entry);
        }
    }

    /**
     * Чтение слова: японский — кана и ромадзи («あう (au)»), китайский — пиньинь, корейский — романизация.
     * Для остальных языков пусто (у них есть IPA, но карточке он не нужен).
     */
    static String reading(JsonObject o, String word) {
        String code = str(o, "lang_code");
        JsonArray sounds = arr(o, "sounds");
        if (code.equals("zh") || code.equals("cmn")) {
            for (JsonElement e : sounds) {
                JsonObject s = e.getAsJsonObject();
                Set<String> t = tags(s);
                if (t.contains("Pinyin") && t.contains("Mandarin") && !str(s, "zh_pron").isEmpty()) {
                    return str(s, "zh_pron").trim();
                }
            }
            return "";
        }
        if (code.equals("ko")) {
            for (JsonElement e : sounds) {
                String r = str(e.getAsJsonObject(), "romanization");
                if (!r.isEmpty()) {
                    return r.trim();
                }
            }
            return "";
        }
        if (!code.equals("ja")) {
            return "";
        }
        String kana = "";
        for (JsonElement e : sounds) {
            JsonObject s = e.getAsJsonObject();
            String other = str(s, "other").trim();
            if (!other.isEmpty() && !s.has("note") && isKana(other)) {
                kana = other;
                break;
            }
        }
        String romaji = "";
        for (JsonElement fe : arr(o, "forms")) {
            JsonObject f = fe.getAsJsonObject();
            if (tags(f).contains("romanization") && romaji.isEmpty()) {
                romaji = str(f, "form").trim();
            }
            if (kana.isEmpty() && tags(f).contains("canonical") && f.has("ruby")) {
                kana = fromRuby(str(f, "form"), f.get("ruby"));
            }
        }
        if (kana.isEmpty() || kana.equals(word)) {
            kana = "";
        }
        if (kana.isEmpty()) {
            return romaji;
        }
        return romaji.isEmpty() ? kana : kana + " (" + romaji + ")";
    }

    private static boolean isCjk(String code) {
        return code.equals("ja") || code.equals("zh") || code.equals("cmn");
    }

    private static boolean isKana(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= 'ぁ' && c <= 'ヿ')) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /** «会う» + [[«会», «あ»]] → «あう»; пусто, если не удалось. */
    private static String fromRuby(String form, JsonElement ruby) {
        if (ruby == null || !ruby.isJsonArray()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        int pos = 0;
        for (JsonElement p : ruby.getAsJsonArray()) {
            if (!p.isJsonArray() || p.getAsJsonArray().size() < 2) {
                return "";
            }
            String base = p.getAsJsonArray().get(0).getAsString();
            String kana = p.getAsJsonArray().get(1).getAsString();
            int at = form.indexOf(base, pos);
            if (at < 0) {
                return "";
            }
            b.append(form, pos, at).append(kana);
            pos = at + base.length();
        }
        b.append(form.substring(pos));
        String r = b.toString();
        return isKana(r) ? r : "";
    }

    private void addForm(String form, String lemmaKey) {
        String key = Keys.normalize(form);
        if (key.isEmpty() || lemmaKey.isEmpty() || key.equals(lemmaKey) || !accepts(key)) {
            return;
        }
        forms.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(lemmaKey);
    }

    /**
     * Файл форм в формате {@link DictionaryFile}: ключ — форма, заглавное слово — начальные формы через «|».
     * Берутся только формы, чьи начальные формы есть в словаре {@code dict}.
     */
    public byte[] buildForms(DictionaryFile dict, String meta) throws IOException {
        TreeMap<String, String> rows = new TreeMap<>();
        for (Map.Entry<String, Set<String>> e : forms.entrySet()) {
            StringBuilder lemmas = new StringBuilder();
            for (String lemma : e.getValue()) {
                if (dict.find(lemma) != null) {
                    if (lemmas.length() > 0) {
                        lemmas.append('|');
                    }
                    lemmas.append(lemma);
                }
            }
            if (lemmas.length() > 0 && e.getKey().length() <= DictionaryBuilder.MAX_KEY) {
                rows.put(e.getKey(), lemmas.toString());
            }
        }
        return DictionaryBuilder.buildRaw(rows, meta);
    }

    /** Часть речи Викисловаря → обозначение FreeDict; null — не слово (символ, сокращение…). */
    static String pos(String p) {
        switch (p) {
            case "noun": return "n";
            case "verb": return "v";
            case "adj": return "adj";
            case "adv": return "adv";
            case "name": return "pn";
            case "pron": return "pronoun";
            case "prep": return "preposition";
            case "conj": return "conjunction";
            case "particle": return "particle";
            case "intj": return "interjection";
            case "num": return "numeral";
            case "phrase": return "phraseologicalUnit";
            case "proverb": return "proverb";
            case "det": return "determiner";
            case "participle": return "participle";
            case "prefix": return "prefix";
            case "suffix": return "suffix";
            case "character": return "character"; // китайский и японский: отдельный иероглиф
            default: return null;
        }
    }

    private static Set<String> tags(JsonObject o) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement t : arr(o, "tags")) {
            out.add(t.getAsString());
        }
        return out;
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

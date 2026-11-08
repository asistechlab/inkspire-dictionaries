package com.asis.inkspire.dictionary;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Сборка компактного словаря из статей TEI. Статьи с одним ключом (разный регистр, разные части речи)
 * сливаются; обратный словарь добавляет слова, которых нет в прямом (например, «бежать» из статьи
 * «run» английского словаря, если в русском её нет).
 */
public final class DictionaryBuilder {

    static final int MAX_SENSES = 8;
    static final int MAX_TRANSLATIONS = 6;
    static final int MAX_HINT = 140;
    static final int MAX_KEY = 80;
    private static final int MAX_INVERTED = 8;

    private static final class Record {
        String headword;
        final Map<String, GroupDraft> groups = new LinkedHashMap<>();
    }

    private static final class GroupDraft {
        final String pos;
        String pron = "";
        final List<DictEntry.Sense> senses = new ArrayList<>();
        final Set<List<String>> seen = new HashSet<>();

        GroupDraft(String pos) {
            this.pos = pos;
        }

        /** Ключи всех переводов группы: значение без подсказки из уже показанных слов не добавляется. */
        final Set<String> known = new HashSet<>();

        void add(List<String> translations, String hint) {
            // «волна́» и «волна» — один перевод: остаётся первый (обычно с ударением).
            List<String> tr = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (String t : translations) {
                if (tr.size() < MAX_TRANSLATIONS && keys.add(Keys.normalize(t))) {
                    tr.add(t);
                }
            }
            String h = shorten(hint);
            if (tr.isEmpty() || senses.size() >= MAX_SENSES || !seen.add(tr)
                    || (h.isEmpty() && known.containsAll(keys))) {
                return;
            }
            known.addAll(keys);
            senses.add(new DictEntry.Sense(tr, h));
        }
    }

    private final TreeMap<String, Record> records = new TreeMap<>();

    /** Прямые статьи. {@code keepHints} — сохранять толкования (нужны, только если они на языке читателя). */
    public DictionaryBuilder addEntries(List<TeiParser.Entry> entries, boolean keepHints) {
        for (TeiParser.Entry e : entries) {
            String key = Keys.normalize(e.orth);
            if (key.isEmpty() || key.length() > MAX_KEY) {
                continue;
            }
            Record r = records.get(key);
            if (r == null) {
                r = new Record();
                r.headword = e.orth;
                records.put(key, r);
            } else if (startsUpper(r.headword) && !startsUpper(e.orth)) {
                r.headword = e.orth; // «bill» важнее, чем «Bill»
            }
            GroupDraft g = r.groups.computeIfAbsent(e.pos, GroupDraft::new);
            if (g.pron.isEmpty()) {
                g.pron = e.pron;
            }
            for (TeiParser.Sense s : e.senses) {
                g.add(s.translations, keepHints ? s.hint : "");
            }
        }
        return this;
    }

    /**
     * Обратные статьи: каждое однословное значение обратного словаря становится ключом, переводы — его
     * заглавные слова. Берутся только ключи, которых нет среди прямых статей; имена собственные — нет.
     */
    public DictionaryBuilder addInverted(List<TeiParser.Entry> reverse) {
        Map<String, Map<String, LinkedHashSet<String>>> extra = new TreeMap<>();
        Map<String, String> heads = new HashMap<>();
        for (TeiParser.Entry e : reverse) {
            if ("pn".equals(e.pos)) {
                continue;
            }
            for (TeiParser.Sense s : e.senses) {
                for (String t : s.translations) {
                    if (t.indexOf(' ') >= 0) {
                        continue;
                    }
                    String key = Keys.normalize(t);
                    if (key.isEmpty() || key.length() > MAX_KEY || records.containsKey(key)) {
                        continue;
                    }
                    heads.putIfAbsent(key, t);
                    LinkedHashSet<String> words = extra.computeIfAbsent(key, k -> new LinkedHashMap<>())
                            .computeIfAbsent(e.pos, p -> new LinkedHashSet<>());
                    if (words.size() < MAX_INVERTED) {
                        words.add(e.orth);
                    }
                }
            }
        }
        for (Map.Entry<String, Map<String, LinkedHashSet<String>>> x : extra.entrySet()) {
            Record r = new Record();
            r.headword = heads.get(x.getKey());
            for (Map.Entry<String, LinkedHashSet<String>> p : x.getValue().entrySet()) {
                GroupDraft g = new GroupDraft(p.getKey());
                g.add(new ArrayList<>(p.getValue()), "");
                r.groups.put(p.getKey(), g);
            }
            records.put(x.getKey(), r);
        }
        return this;
    }

    /**
     * Дополнительные статьи (Викисловарь через kaikki.org) — только для слов, которых ещё нет: статьи FreeDict
     * важнее. Добавлять до {@link #addInverted}, иначе их место займут обратные статьи.
     */
    public DictionaryBuilder addMissing(List<TeiParser.Entry> extra) {
        List<TeiParser.Entry> fresh = new ArrayList<>();
        Set<String> before = new HashSet<>(records.keySet());
        for (TeiParser.Entry e : extra) {
            if (!before.contains(Keys.normalize(e.orth))) {
                fresh.add(e);
            }
        }
        return addEntries(fresh, false);
    }

    public int size() {
        return records.size();
    }

    /** Файл словаря: см. {@link DictionaryFile}. */
    public byte[] build(String meta) throws IOException {
        TreeMap<String, String> bodies = new TreeMap<>();
        for (Map.Entry<String, Record> e : records.entrySet()) {
            List<DictEntry.Group> groups = new ArrayList<>();
            // Имена собственные — после обычных слов («bill» раньше, чем «Bill»).
            for (int pass = 0; pass < 2; pass++) {
                for (GroupDraft g : e.getValue().groups.values()) {
                    if (("pn".equals(g.pos) ? 1 : 0) == pass && !g.senses.isEmpty()) {
                        groups.add(new DictEntry.Group(g.pos, g.pron, g.senses));
                    }
                }
            }
            bodies.put(e.getKey(), new DictEntry(e.getKey(), e.getValue().headword, groups).encode());
        }
        return buildRaw(bodies, meta);
    }

    /** Файл из готовых тел записей (ключ → тело); у файла форм тело — только заглавное слово. */
    static byte[] buildRaw(TreeMap<String, String> bodies, String meta) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[bodies.size()];
        int i = 0;
        for (Map.Entry<String, String> e : bodies.entrySet()) {
            offsets[i++] = data.size();
            data.write(e.getKey().getBytes(StandardCharsets.UTF_8));
            data.write('\t');
            data.write(e.getValue().getBytes(StandardCharsets.UTF_8));
            data.write('\n');
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.size() + offsets.length * 4 + 64);
        DataOutputStream d = new DataOutputStream(out);
        d.write(DictionaryFile.MAGIC);
        byte[] m = meta.getBytes(StandardCharsets.UTF_8);
        d.writeInt(m.length);
        d.write(m);
        d.writeInt(offsets.length);
        for (int o : offsets) {
            d.writeInt(o);
        }
        data.writeTo(d);
        d.flush();
        return out.toByteArray();
    }

    private static boolean startsUpper(String s) {
        return !s.isEmpty() && Character.isUpperCase(s.charAt(0));
    }

    private static String shorten(String hint) {
        if (hint == null) {
            return "";
        }
        String h = hint.trim();
        if (h.length() <= MAX_HINT) {
            return h;
        }
        int cut = h.lastIndexOf(' ', MAX_HINT - 1);
        return h.substring(0, cut > MAX_HINT / 2 ? cut : MAX_HINT - 1) + "…";
    }
}

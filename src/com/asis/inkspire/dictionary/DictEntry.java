package com.asis.inkspire.dictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Статья словаря: заглавное слово и группы по частям речи, в группе — значения (переводы и подсказка).
 * В файле статья хранится одной строкой с управляющими разделителями ({@link #encode()}).
 */
public final class DictEntry {

    private static final char GROUP = '\u001C';
    private static final char FIELD = '\u001F';
    private static final char SENSE = '\u001E';
    private static final char TRANSLATION = '\u001D';
    private static final char HINT = '\u001A';

    /** Значение: переводы и необязательная подсказка (толкование). */
    public static final class Sense {
        public final List<String> translations;
        public final String hint;

        public Sense(List<String> translations, String hint) {
            this.translations = Collections.unmodifiableList(new ArrayList<>(translations));
            this.hint = hint == null ? "" : hint;
        }
    }

    /** Группа значений одной части речи. */
    public static final class Group {
        /** Часть речи как в FreeDict: n, v, adj, adv, pn, … (может быть пустой). */
        public final String pos;
        /** Произношение (МФА) или пусто. */
        public final String pron;
        public final List<Sense> senses;

        public Group(String pos, String pron, List<Sense> senses) {
            this.pos = pos == null ? "" : pos;
            this.pron = pron == null ? "" : pron;
            this.senses = Collections.unmodifiableList(new ArrayList<>(senses));
        }
    }

    public final String key;
    public final String headword;
    public final List<Group> groups;

    public DictEntry(String key, String headword, List<Group> groups) {
        this.key = key;
        this.headword = headword;
        this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
    }

    /** Первое произношение среди групп или пусто. */
    public String pron() {
        for (Group g : groups) {
            if (!g.pron.isEmpty()) {
                return g.pron;
            }
        }
        return "";
    }

    String encode() {
        StringBuilder b = new StringBuilder(clean(headword));
        for (Group g : groups) {
            b.append(GROUP).append(clean(g.pos)).append(FIELD).append(clean(g.pron)).append(FIELD);
            for (int s = 0; s < g.senses.size(); s++) {
                Sense sense = g.senses.get(s);
                if (s > 0) {
                    b.append(SENSE);
                }
                for (int t = 0; t < sense.translations.size(); t++) {
                    if (t > 0) {
                        b.append(TRANSLATION);
                    }
                    b.append(clean(sense.translations.get(t)));
                }
                if (!sense.hint.isEmpty()) {
                    b.append(HINT).append(clean(sense.hint));
                }
            }
        }
        return b.toString();
    }

    static DictEntry decode(String key, String body) {
        String[] parts = split(body, GROUP);
        List<Group> groups = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String[] f = split(parts[i], FIELD);
            List<Sense> senses = new ArrayList<>();
            if (f.length > 2 && !f[2].isEmpty()) {
                for (String s : split(f[2], SENSE)) {
                    String hint = "";
                    int h = s.indexOf(HINT);
                    if (h >= 0) {
                        hint = s.substring(h + 1);
                        s = s.substring(0, h);
                    }
                    List<String> tr = new ArrayList<>();
                    Collections.addAll(tr, split(s, TRANSLATION));
                    senses.add(new Sense(tr, hint));
                }
            }
            groups.add(new Group(f[0], f.length > 1 ? f[1] : "", senses));
        }
        return new DictEntry(key, parts[0], groups);
    }

    /** Управляющие символы (разделители файла) и переводы строк — в пробел. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c < 0x20 ? ' ' : c);
        }
        return b.toString().trim();
    }

    private static String[] split(String s, char sep) {
        List<String> out = new ArrayList<>();
        int from = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == sep) {
                out.add(s.substring(from, i));
                from = i + 1;
            }
        }
        return out.toArray(new String[0]);
    }
}

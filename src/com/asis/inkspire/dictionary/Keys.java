package com.asis.inkspire.dictionary;

import java.text.Normalizer;

/** Ключ поиска в словаре: одинаково для статей при сборке и для слова из книги. */
public final class Keys {

    private Keys() {
    }

    /**
     * Строчные буквы, «ё» → «е», без знаков ударения и мягких переносов, пробелы схлопнуты,
     * знаки препинания по краям сняты («“Hello,”» → «hello»).
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String d = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(d.length());
        boolean space = false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c == '\u0301' || c == '\u0300' || c == '\u00AD' || c == '\u200B') {
                continue;
            }
            if (Character.isWhitespace(c) || Character.isISOControl(c) || c == '\u00A0') {
                space = b.length() > 0;
                continue;
            }
            if (c == '\u2010' || c == '\u2011') {
                c = '-';
            }
            if (space) {
                b.append(' ');
                space = false;
            }
            b.append(c);
        }
        // Та же свёртка, что WordForms.fold в приложении: строчные, «ё» → «е», типографский апостроф → обычный.
        String w = Normalizer.normalize(b, Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT)
                .replace('ё', 'е').replace('’', '\'');
        int start = 0;
        int end = w.length();
        while (start < end && !Character.isLetterOrDigit(w.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(w.charAt(end - 1))) {
            end--;
        }
        return w.substring(start, end);
    }

    /** Есть ли в тексте кириллица (больше, чем латиницы). */
    public static boolean isRussian(String text) {
        int cyr = 0;
        int lat = 0;
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(text.charAt(i));
            if (block == Character.UnicodeBlock.CYRILLIC) {
                cyr++;
            } else if (block == Character.UnicodeBlock.BASIC_LATIN || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT) {
                if (Character.isLetter(text.charAt(i))) {
                    lat++;
                }
            }
        }
        return cyr > lat;
    }
}

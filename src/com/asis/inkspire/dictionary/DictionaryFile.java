package com.asis.inkspire.dictionary;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Словарь на диске без загрузки в память: «INKDICT1», длина и текст описания, число статей, смещения
 * статей (int), затем статьи «ключ\tтело\n», отсортированные по ключу ({@link String#compareTo}).
 * Поиск — двоичный по ключам, файл отображается в память ({@link FileChannel#map}).
 */
public final class DictionaryFile {

    static final byte[] MAGIC = "INKDICT1".getBytes(StandardCharsets.US_ASCII);

    private final ByteBuffer buf;
    private final String meta;
    private final int count;
    private final int indexPos;
    private final int dataPos;

    private DictionaryFile(ByteBuffer buf) throws IOException {
        this.buf = buf;
        if (buf.limit() < MAGIC.length + 8) {
            throw new IOException("Файл словаря повреждён");
        }
        byte[] magic = new byte[MAGIC.length];
        for (int i = 0; i < magic.length; i++) {
            magic[i] = buf.get(i);
        }
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Не файл словаря");
        }
        int metaLen = buf.getInt(MAGIC.length);
        int pos = MAGIC.length + 4;
        if (metaLen < 0 || (long) pos + metaLen + 4 > buf.limit()) {
            throw new IOException("Файл словаря повреждён");
        }
        meta = new String(bytes(pos, pos + metaLen), StandardCharsets.UTF_8);
        pos += metaLen;
        count = buf.getInt(pos);
        indexPos = pos + 4;
        dataPos = indexPos + count * 4;
        if (count < 0 || (long) indexPos + count * 4L > buf.limit()) {
            throw new IOException("Файл словаря повреждён");
        }
        for (int i = 0; i < count; i++) {
            int s = buf.getInt(indexPos + i * 4);
            if (s < 0 || (long) dataPos + s > buf.limit()) {
                throw new IOException("Файл словаря повреждён");
            }
        }
    }

    public static DictionaryFile open(File file) throws IOException {
        try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            return new DictionaryFile(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()));
        }
    }

    public static DictionaryFile of(byte[] data) throws IOException {
        return new DictionaryFile(ByteBuffer.wrap(data));
    }

    /** Описание (источник и версия). */
    public String meta() {
        return meta;
    }

    public int size() {
        return count;
    }

    /** Статья по уже нормализованному ключу ({@link Keys#normalize}) или null. */
    public DictEntry find(String key) {
        int i = lowerBound(key);
        if (i < count && keyAt(i).equals(key)) {
            return entryAt(i);
        }
        return null;
    }

    /** Ключи, начинающиеся с {@code prefix}, по порядку, не больше {@code limit}. */
    public List<String> keysWithPrefix(String prefix, int limit) {
        List<String> out = new ArrayList<>();
        for (int i = lowerBound(prefix); i < count && out.size() < limit; i++) {
            String k = keyAt(i);
            if (!k.startsWith(prefix)) {
                break;
            }
            out.add(k);
        }
        return out;
    }

    private int lowerBound(String key) {
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keyAt(mid).compareTo(key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private int start(int i) {
        return dataPos + buf.getInt(indexPos + i * 4);
    }

    private int end(int i) {
        return i + 1 < count ? start(i + 1) : buf.limit();
    }

    private int tab(int s, int e) {
        int t = s;
        while (t < e && buf.get(t) != '\t') {
            t++;
        }
        return t;
    }

    String keyAt(int i) {
        int s = start(i);
        return new String(bytes(s, tab(s, end(i))), StandardCharsets.UTF_8);
    }

    DictEntry entryAt(int i) {
        int s = start(i);
        int e = end(i);
        int t = tab(s, e);
        int bodyEnd = e > t + 1 && buf.get(e - 1) == '\n' ? e - 1 : e;
        String key = new String(bytes(s, t), StandardCharsets.UTF_8);
        String body = t + 1 <= bodyEnd ? new String(bytes(t + 1, bodyEnd), StandardCharsets.UTF_8) : "";
        return DictEntry.decode(key, body);
    }

    private byte[] bytes(int from, int to) {
        byte[] b = new byte[Math.max(0, to - from)];
        for (int i = 0; i < b.length; i++) {
            b[i] = buf.get(from + i);
        }
        return b;
    }
}

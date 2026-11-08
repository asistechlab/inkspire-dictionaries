package com.asis.inkspire.dictionary;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Сборка скачиваемого словаря «язык → английский/русский» из выгрузки Викисловаря kaikki.org:
 * {@code PackTool <код языка, fr> <en|ru> <выгрузка .jsonl.gz> <папка вывода>}
 * → {@code <код>-<en|ru>.dict.gz} (слово → значения) и {@code <код>-<en|ru>-forms.dict.gz} (форма → начальные формы).
 * Для {@code en} берётся английское издание Викисловаря по языку (значения по-английски), для {@code ru} —
 * русское издание {@code ru-extract} (значения по-русски). В приложении не используется: запускается на GitHub Actions
 * в репозитории словарей.
 */
public final class PackTool {

    private PackTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            throw new IllegalArgumentException("Нужно: <код языка> <en|ru> <kaikki .jsonl.gz> <папка вывода>");
        }
        String lang = args[0];
        String target = args[1];
        if (!"en".equals(target) && !"ru".equals(target)) {
            throw new IllegalArgumentException("Язык значений: en или ru");
        }
        File out = new File(args[3]);
        String meta = "Wiktionary (" + ("en".equals(target) ? "English" : "Russian") + " edition) via kaikki.org"
                + " (wiktextract), CC BY-SA 4.0 / GFDL; " + lang + "-" + target;
        KaikkiReader kaikki = new KaikkiReader(lang);
        try (InputStream in = new GZIPInputStream(new FileInputStream(args[2]), 1 << 16)) {
            kaikki.read(in);
        }
        String name = lang + "-" + target;
        System.out.println(name + ": " + kaikki.entries.size() + " статей, " + kaikki.forms.size() + " форм");
        Files.createDirectories(out.toPath());
        byte[] dict = new DictionaryBuilder().addMissing(kaikki.entries).build(meta);
        write(new File(out, name + ".dict.gz"), dict);
        write(new File(out, name + "-forms.dict.gz"), kaikki.buildForms(DictionaryFile.of(dict), meta + " forms"));
    }

    private static void write(File f, byte[] data) throws IOException {
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(f.toPath()))) {
            o.write(data);
        }
        System.out.println(f.getName() + ": " + DictionaryFile.of(data).size() + " ключей, " + data.length
                + " байт (до сжатия), " + f.length() + " байт (gz)");
    }
}

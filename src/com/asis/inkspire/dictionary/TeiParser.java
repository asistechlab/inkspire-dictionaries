package com.asis.inkspire.dictionary;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Разбор словаря FreeDict в формате TEI P5 (выгрузки WikDict): entry → form/orth, pron; gramGrp/pos;
 * sense → cit[type=trans]/quote (переводы) и вложенный def (толкование). DTD не загружается.
 */
public final class TeiParser {

    /** Ссылки викиразметки в переводах: «[[процент|проце́нтов]]» → «проце́нтов». */
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)]]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Статья TEI как есть (до сборки словаря). */
    public static final class Entry {
        public String orth = "";
        public String pos = "";
        public String pron = "";
        public final List<Sense> senses = new ArrayList<>();
    }

    /** Значение статьи TEI. */
    public static final class Sense {
        public final List<String> translations = new ArrayList<>();
        public String hint = "";
    }

    private TeiParser() {
    }

    public static List<Entry> parse(InputStream in) throws IOException {
        Handler handler = new Handler();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            trySet(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            trySet(factory, "http://xml.org/sax/features/external-general-entities", false);
            trySet(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            trySet(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            SAXParser parser = factory.newSAXParser();
            org.xml.sax.XMLReader reader = parser.getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            reader.setContentHandler(handler);
            reader.parse(new InputSource(in));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Словарь TEI не разобран: " + e.getMessage(), e);
        }
        return handler.entries;
    }

    /** Текст перевода без викиразметки и лишних пробелов. */
    public static String cleanQuote(String quote) {
        String s = WIKI_LINK.matcher(quote).replaceAll("$1");
        s = s.replace("[[", "").replace("]]", "");
        return SPACES.matcher(s).replaceAll(" ").trim();
    }

    private static void trySet(SAXParserFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // Разбор Android/JVM может не знать признака — DTD всё равно отсекает EntityResolver.
        }
    }

    private static final class Handler extends DefaultHandler {
        final List<Entry> entries = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private Entry entry;
        private Sense sense;
        private int senseDepth;
        private boolean inTrans;
        private boolean inForm;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            text.setLength(0);
            switch (qName) {
                case "entry":
                    entry = new Entry();
                    senseDepth = 0;
                    break;
                case "form":
                    inForm = true;
                    break;
                case "sense":
                    senseDepth++;
                    if (entry != null && senseDepth == 1) {
                        sense = new Sense();
                    }
                    break;
                case "cit":
                    inTrans = "trans".equals(atts.getValue("type"));
                    break;
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (entry == null) {
                return;
            }
            String t = SPACES.matcher(text).replaceAll(" ").trim();
            switch (qName) {
                case "orth":
                    if (inForm && entry.orth.isEmpty()) {
                        entry.orth = t;
                    }
                    break;
                case "pron":
                    if (inForm && entry.pron.isEmpty()) {
                        entry.pron = t;
                    }
                    break;
                case "form":
                    inForm = false;
                    break;
                case "pos":
                    if (entry.pos.isEmpty()) {
                        entry.pos = t;
                    }
                    break;
                case "quote":
                    if (inTrans && sense != null) {
                        String q = cleanQuote(t);
                        if (!q.isEmpty() && !sense.translations.contains(q)) {
                            sense.translations.add(q);
                        }
                    }
                    break;
                case "cit":
                    inTrans = false;
                    break;
                case "def":
                    if (sense != null && sense.hint.isEmpty()) {
                        sense.hint = t;
                    }
                    break;
                case "sense":
                    if (senseDepth == 1 && sense != null) {
                        if (!sense.translations.isEmpty()) {
                            entry.senses.add(sense);
                        }
                        sense = null;
                    }
                    senseDepth--;
                    break;
                case "entry":
                    if (!entry.orth.isEmpty() && !entry.senses.isEmpty()) {
                        entries.add(entry);
                    }
                    entry = null;
                    break;
                default:
                    break;
            }
            text.setLength(0);
        }
    }
}

package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * The ATP's official order of play (protennislive.com posting/YEAR/ID/op.pdf), read without a PDF library.
 * The file is printed by Chrome (Skia): every text run is a glyph-id string whose meaning sits in its font's
 * ToUnicode CMap, and each cell of the table is a Form XObject placed with a transform. Courts are columns;
 * each slot of a column starts with a label ("Starts At 11:00 AM", "Followed By", "Not Before 7:00 PM",
 * "After Suitable Rest") and the players' names follow under it. A match is found by both surnames in one
 * slot, so names split by kerning ("HAL" + "YS") still match once the slot's text is joined without spaces.
 *
 * Only what the sheet says is reported: a start time, a not-before time, or "after the previous match".
 */
final class OrderOfPlay {

    enum Kind { START, NOT_BEFORE, AFTER }

    static final class Slot {
        final Kind kind;
        /** Local to the tournament; null for AFTER. */
        final LocalTime time;
        /** The slot's text, upper case, letters only. */
        final String names;

        Slot(Kind kind, LocalTime time, String names) {
            this.kind = kind;
            this.time = time;
            this.names = names;
        }
    }

    /** The day the sheet is for (in the tournament's own calendar), or null if the header was not found. */
    final LocalDate day;
    final List<Slot> slots;

    private OrderOfPlay(LocalDate day, List<Slot> slots) {
        this.day = day;
        this.slots = slots;
    }

    /** The slot holding both players, or null. Surnames are compared upper case, letters only. */
    Slot find(String surnameA, String surnameB) {
        String a = letters(surnameA), b = letters(surnameB);
        if (a.length() < 2 || b.length() < 2) return null;
        for (Slot s : slots) if (s.names.contains(a) && s.names.contains(b)) return s;
        return null;
    }

    static String letters(String s) {
        StringBuilder out = new StringBuilder();
        String up = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).toUpperCase(Locale.ROOT);
        for (int i = 0; i < up.length(); i++) {
            char c = up.charAt(i);
            if (c >= 'A' && c <= 'Z') out.append(c);
        }
        return out.toString();
    }

    // ---- parsing -------------------------------------------------------------------------------------------

    private static final class Run {
        final double x, y;
        final String text;

        Run(double x, double y, String text) {
            this.x = x;
            this.y = y;
            this.text = text;
        }
    }

    private static final Pattern OBJ = Pattern.compile("(\\d+) 0 obj(.*?)endobj", Pattern.DOTALL);
    private static final Pattern TOK = Pattern.compile(
            "<([0-9A-Fa-f]*)>|\\[(.*?)\\]|/([^\\s/<>\\[\\]()]+)|(-?\\d*\\.\\d+|-?\\d+)|([A-Za-z'\"*]+)", Pattern.DOTALL);
    private static final Pattern HEX = Pattern.compile("<([0-9A-Fa-f]*)>");
    private static final Pattern REF = Pattern.compile("/(\\w+) (\\d+) 0 R");
    private static final Pattern HEADER = Pattern.compile(
            "ORDER OF PLAY\\s*-\\s*[A-Z]+\\s*,\\s*([A-Z]+ \\d{1,2}, \\d{4})");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(AM|PM)");

    private final Map<Integer, String> objs = new HashMap<>();
    private final Map<Integer, Map<Integer, String>> cmaps = new HashMap<>();

    /** Parses the sheet; never throws on a malformed file, it just finds nothing. */
    static OrderOfPlay parse(byte[] pdf) {
        try {
            return new OrderOfPlay(null, null).read(pdf);
        } catch (RuntimeException e) {
            return new OrderOfPlay(null, new ArrayList<>());
        }
    }

    private OrderOfPlay read(byte[] pdf) {
        // ISO-8859-1 keeps one char per byte, so the binary streams survive the regexes untouched.
        String all = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher m = OBJ.matcher(all);
        while (m.find()) objs.put(Integer.parseInt(m.group(1)), m.group(2));
        List<List<Run>> pages = new ArrayList<>();
        for (Map.Entry<Integer, String> e : objs.entrySet()) {
            String o = e.getValue();
            if (!Pattern.compile("/Type\\s*/Page[^s]").matcher(o).find()) continue;
            Matcher c = Pattern.compile("/Contents\\s*(\\d+) 0 R").matcher(o);
            if (!c.find()) continue;
            String content = stream(objs.get(Integer.parseInt(c.group(1))));
            List<Run> out = new ArrayList<>();
            run(o, content, new double[]{1, 0, 0, 1, 0, 0}, out, 0);
            pages.add(out);
        }
        LocalDate day = null;
        List<Slot> slots = new ArrayList<>();
        for (List<Run> page : pages) {
            StringBuilder text = new StringBuilder();
            for (Run r : page) text.append(r.text);
            Matcher h = HEADER.matcher(text.toString().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ")
                    .replace("PLA Y", "PLAY").replace("TUESDA Y", "TUESDAY").replace("WEDNESDA Y", "WEDNESDAY")
                    .replace("THURSDA Y", "THURSDAY").replace("FRIDA Y", "FRIDAY").replace("SATURDA Y", "SATURDAY")
                    .replace("SUNDA Y", "SUNDAY").replace("MONDA Y", "MONDAY"));
            if (day == null && h.find()) {
                try {
                    day = LocalDate.parse(h.group(1), DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
                            .withResolverStyle(java.time.format.ResolverStyle.SMART));
                } catch (RuntimeException ignored) {
                    try {
                        String s = h.group(1).charAt(0) + h.group(1).substring(1).toLowerCase(Locale.ROOT);
                        day = LocalDate.parse(s, DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH));
                    } catch (RuntimeException ignoredToo) {
                        // no date: the caller then trusts nothing on the sheet
                    }
                }
            }
            slots.addAll(slots(page));
        }
        return new OrderOfPlay(day, slots);
    }

    /** Labels and the names under them, column by column. */
    private static List<Slot> slots(List<Run> page) {
        List<Run> labels = new ArrayList<>();
        for (Run r : page) {
            // A label starts a run: "Starts ", "Followed By", "Not Before 3:00 PM", "AFTER SUIT", ...
            String t = r.text.trim().toUpperCase(Locale.ROOT);
            if (t.startsWith("STARTS") || t.startsWith("FOLLOWED") || t.startsWith("NOT BEFORE")
                    || t.startsWith("AFTER SUIT")) {
                labels.add(r);
            }
        }
        // Runs of one label share x and y (Skia splits "Starts " / "At 11:00 " / "AM"): merge them.
        List<double[]> pos = new ArrayList<>();
        List<StringBuilder> texts = new ArrayList<>();
        for (Run r : page) {
            for (int i = 0; i < labels.size(); i++) {
                Run l = labels.get(i);
                if (Math.abs(r.y - l.y) < 0.5 && Math.abs(r.x - l.x) < 0.5) {
                    int k = indexOf(pos, l.x, l.y);
                    if (k < 0) {
                        pos.add(new double[]{l.x, l.y});
                        texts.add(new StringBuilder());
                        k = pos.size() - 1;
                    }
                    if (texts.get(k).indexOf(r.text) < 0 || r == l) texts.get(k).append(r.text);
                    break;
                }
            }
        }
        // Column width: the gap between label columns, found from the label x positions.
        List<Slot> out = new ArrayList<>();
        for (int k = 0; k < pos.size(); k++) {
            double lx = pos.get(k)[0], ly = pos.get(k)[1];
            // The next label below in the same column ends this slot.
            double nextY = Double.MAX_VALUE;
            for (double[] p : pos) if (Math.abs(p[0] - lx) < 40 && p[1] > ly + 1 && p[1] < nextY) nextY = p[1];
            double left = lx - 55, right = lx + 75;
            StringBuilder names = new StringBuilder();
            for (Run r : page) {
                if (r.y > ly + 1 && r.y < nextY - 1 && r.x >= left && r.x < right) names.append(r.text);
            }
            String label = texts.get(k).toString().toUpperCase(Locale.ROOT);
            Kind kind;
            LocalTime time = null;
            Matcher tm = TIME.matcher(label);
            if (label.startsWith("FOLLOWED") || label.startsWith("AFTER")) {
                kind = Kind.AFTER;
            } else if (tm.find()) {
                int hour = Integer.parseInt(tm.group(1)) % 12 + ("PM".equals(tm.group(3)) ? 12 : 0);
                time = LocalTime.of(hour, Integer.parseInt(tm.group(2)));
                kind = label.startsWith("NOT BEFORE") ? Kind.NOT_BEFORE : Kind.START;
            } else {
                continue;
            }
            out.add(new Slot(kind, time, letters(names.toString())));
        }
        return out;
    }

    private static int indexOf(List<double[]> pos, double x, double y) {
        for (int i = 0; i < pos.size(); i++) if (pos.get(i)[0] == x && pos.get(i)[1] == y) return i;
        return -1;
    }

    private String stream(String o) {
        if (o == null) return "";
        int s = o.indexOf("stream");
        if (s < 0) return "";
        int start = s + 6;
        if (start < o.length() && o.charAt(start) == '\r') start++;
        if (start < o.length() && o.charAt(start) == '\n') start++;
        int end = o.lastIndexOf("endstream");
        if (end < start) return "";
        byte[] raw = o.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
        Inflater inf = new Inflater();
        inf.setInput(raw);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length * 4);
        byte[] buf = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                bos.write(buf, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            return "";
        } finally {
            inf.end();
        }
        return new String(bos.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private Map<Integer, String> cmap(int n) {
        Map<Integer, String> cached = cmaps.get(n);
        if (cached != null) return cached;
        Map<Integer, String> mp = new HashMap<>();
        String cm = stream(objs.get(n));
        Matcher blk = Pattern.compile("beginbfchar(.*?)endbfchar", Pattern.DOTALL).matcher(cm);
        Pattern pair = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>");
        while (blk.find()) {
            Matcher p = pair.matcher(blk.group(1));
            while (p.find()) mp.put(Integer.parseInt(p.group(1), 16), utf16(p.group(2)));
        }
        blk = Pattern.compile("beginbfrange(.*?)endbfrange", Pattern.DOTALL).matcher(cm);
        Pattern range = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>");
        while (blk.find()) {
            Matcher p = range.matcher(blk.group(1));
            while (p.find()) {
                int a = Integer.parseInt(p.group(1), 16), z = Integer.parseInt(p.group(2), 16);
                int u = Integer.parseInt(p.group(3), 16);
                for (int g = a; g <= z && g - a < 4096; g++) mp.put(g, String.valueOf((char) (u + g - a)));
            }
        }
        cmaps.put(n, mp);
        return mp;
    }

    private static String utf16(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return new String(b, StandardCharsets.UTF_16BE);
    }

    /** The <<...>> after /key, nested dictionaries included. */
    private static String dictBlock(String src, String key) {
        int i = src.indexOf("/" + key);
        if (i < 0) return null;
        int j = src.indexOf("<<", i);
        if (j < 0) return null;
        int depth = 0;
        for (int k = j; k < src.length() - 1; k++) {
            if (src.startsWith("<<", k)) {
                depth++;
                k++;
            } else if (src.startsWith(">>", k)) {
                depth--;
                k++;
                if (depth == 0) return src.substring(j, k + 1);
            }
        }
        return null;
    }

    private void run(String resSrc, String content, double[] ctm, List<Run> out, int depth) {
        Matcher rm = Pattern.compile("/Resources (\\d+) 0 R").matcher(resSrc);
        String res = rm.find() ? objs.getOrDefault(Integer.parseInt(rm.group(1)), "") : dictBlock(resSrc, "Resources");
        if (res == null) res = "";
        Map<String, Map<Integer, String>> fonts = new HashMap<>();
        Map<String, Integer> xobj = new HashMap<>();
        String fb = dictBlock(res, "Font");
        if (fb != null) {
            Matcher f = REF.matcher(fb);
            while (f.find()) {
                String fo = objs.getOrDefault(Integer.parseInt(f.group(2)), "");
                Matcher t = Pattern.compile("/ToUnicode (\\d+) 0 R").matcher(fo);
                fonts.put(f.group(1), t.find() ? cmap(Integer.parseInt(t.group(1))) : new HashMap<>());
            }
        }
        String xb = dictBlock(res, "XObject");
        if (xb != null) {
            Matcher x = REF.matcher(xb);
            while (x.find()) xobj.put(x.group(1), Integer.parseInt(x.group(2)));
        }
        List<double[]> stack = new ArrayList<>();
        List<Double> nums = new ArrayList<>();
        List<String> strs = new ArrayList<>();
        Map<Integer, String> cur = new HashMap<>();
        double[] tm = {1, 0, 0, 1, 0, 0};
        String name = null;
        Matcher m = TOK.matcher(content);
        while (m.find()) {
            if (m.group(4) != null) {
                try {
                    nums.add(Double.parseDouble(m.group(4)));
                } catch (NumberFormatException e) {
                    nums.add(0d);
                }
                continue;
            }
            if (m.group(3) != null) {
                name = m.group(3);
                continue;
            }
            if (m.group(1) != null) {
                strs.add(m.group(1));
                continue;
            }
            if (m.group(2) != null) {
                StringBuilder sb = new StringBuilder();
                Matcher hx = HEX.matcher(m.group(2));
                while (hx.find()) sb.append(hx.group(1));
                strs.add(sb.toString());
                continue;
            }
            String op = m.group(5);
            switch (op) {
                case "q":
                    stack.add(ctm);
                    break;
                case "Q":
                    if (!stack.isEmpty()) ctm = stack.remove(stack.size() - 1);
                    break;
                case "cm":
                    if (nums.size() >= 6) ctm = mul(last6(nums), ctm);
                    break;
                case "Tf":
                    Map<Integer, String> f = fonts.get(name);
                    cur = f != null ? f : new HashMap<>();
                    break;
                case "Tm":
                    if (nums.size() >= 6) tm = last6(nums);
                    break;
                case "Tj":
                case "TJ":
                    if (!strs.isEmpty()) {
                        String h = strs.get(strs.size() - 1);
                        StringBuilder txt = new StringBuilder();
                        for (int i = 0; i + 4 <= h.length(); i += 4) {
                            String ch = cur.get(Integer.parseInt(h.substring(i, i + 4), 16));
                            txt.append(ch != null ? ch : "?");
                        }
                        double[] p = mul(tm, ctm);
                        // Page space is y-up; the slots read top-down, so flip.
                        out.add(new Run(Math.round(p[4] * 10) / 10.0, -Math.round(p[5] * 10) / 10.0, txt.toString()));
                    }
                    break;
                case "Do":
                    Integer ref = xobj.get(name);
                    if (ref != null && depth < 6) {
                        String xo = objs.getOrDefault(ref, "");
                        if (!Pattern.compile("/Subtype\\s*/Image").matcher(xo).find()) {
                            Matcher mm = Pattern.compile("/Matrix\\s*\\[([^\\]]*)\\]").matcher(xo);
                            double[] xm = {1, 0, 0, 1, 0, 0};
                            if (mm.find()) {
                                String[] v = mm.group(1).trim().split("\\s+");
                                if (v.length == 6) for (int i = 0; i < 6; i++) xm[i] = Double.parseDouble(v[i]);
                            }
                            run(xo, stream(xo), mul(xm, ctm), out, depth + 1);
                        }
                    }
                    break;
                default:
                    break;
            }
            nums.clear();
            strs.clear();
        }
    }

    private static double[] last6(List<Double> nums) {
        double[] a = new double[6];
        for (int i = 0; i < 6; i++) a[i] = nums.get(nums.size() - 6 + i);
        return a;
    }

    private static double[] mul(double[] a, double[] b) {
        return new double[]{a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
                a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
                a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]};
    }
}

package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Home sport calendar (sport_calendar): the month under the clock with a ball or a flag under the days that
 * have tennis or an F1 race, and this week's list beside it, one rounded pill per day down its left side. Drawn
 * on one canvas, at the positions MainActivity hands over from the clock, so it lines up with the header.
 * Sizes follow the approved mockup in px of a 1080p screen at density 2 (1 dp = 2 px there).
 */
final class SportCalendarView extends View {

    private final float dp;
    private final Paint header = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint weekday = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digitToday = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint time = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timeSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillNum = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** A finished race's winner code, "VER": the time's size in the title's weight. */
    private final Paint code = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private SportFeed.Data data = SportFeed.Data.EMPTY;
    private float gridLeft, gridTop, listLeft, listTop, listRight;
    private boolean anchored;
    private long pageStart = SystemClock.elapsedRealtime();
    /** Seconds per page when the week does not fit. */
    private static final long PAGE_MS = 15_000;
    private int pages = 1;
    /** sport_f1_podium: a finished race also lists P2 and P3 after its name. */
    private boolean podium;

    SportCalendarView(Context ctx, Typeface regular, Typeface medium) {
        super(ctx);
        dp = getResources().getDisplayMetrics().density;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        style(header, medium, 9.5f, 0x99FFFFFF, 0.2f);
        style(weekday, medium, 7.5f, 0x66FFFFFF, 0.1f);
        style(digit, regular, 11f, 0xBFFFFFFF, 0f);
        style(digitToday, medium, 11f, 0xFFFFFFFF, 0f);
        style(time, regular, 12.5f, 0xFFFFFFFF, 0f);
        style(timeSmall, medium, 7.5f, 0x8CFFFFFF, 0.1f);
        style(soft, regular, 11f, 0x8CFFFFFF, 0f);
        style(title, medium, 12f, 0xFFFFFFFF, 0f);
        style(sub, regular, 12f, 0x80FFFFFF, 0f);
        style(pillNum, regular, 10.5f, 0xB3FFFFFF, 0f);
        style(code, medium, 12.5f, 0xFFFFFFFF, 0.04f);
        digit.setTextAlign(Paint.Align.CENTER);
        digitToday.setTextAlign(Paint.Align.CENTER);
        weekday.setTextAlign(Paint.Align.CENTER);
        pillNum.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
    }

    private void style(Paint p, Typeface tf, float sizeDp, int color, float spacing) {
        p.setTypeface(tf);
        p.setTextSize(sizeDp * dp);
        p.setColor(color);
        p.setLetterSpacing(spacing);
    }

    void setPodium(boolean on) {
        podium = on;
        invalidate();
    }

    void setData(SportFeed.Data d) {
        data = d != null ? d : SportFeed.Data.EMPTY;
        pageStart = SystemClock.elapsedRealtime();
        invalidate();
    }

    /** Where the grid's left glyph edge and top go, and the list's box, in this view's pixels. */
    void anchor(float gridLeft, float gridTop, float listLeft, float listTop, float listRight) {
        if (anchored && this.gridLeft == gridLeft && this.gridTop == gridTop && this.listLeft == listLeft
                && this.listTop == listTop && this.listRight == listRight) return;
        this.gridLeft = gridLeft;
        this.gridTop = gridTop;
        this.listLeft = listLeft;
        this.listTop = listTop;
        this.listRight = listRight;
        anchored = true;
        invalidate();
    }

    /** Called once a minute and on each page turn: a new day, a new page. */
    void tick() {
        invalidate();
    }

    long pageMs() {
        return pages > 1 ? PAGE_MS : 0;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!anchored) return;
        LocalDate today = LocalDate.now();
        float gridBottom = drawMonth(c, today);
        drawList(c, today, gridBottom);
    }

    // ---- month ---------------------------------------------------------------------------------------------

    private static final float CELL_W = 26, CELL_H = 20;   // dp: 52 x 40 px

    private float drawMonth(Canvas c, LocalDate today) {
        float cw = CELL_W * dp, ch = CELL_H * dp;
        // A centred two-digit number starts about 8 dp in from its cell's edge: start the grid 8 dp left of the
        // glyph edge, so the first column's digits line up with the clock and the month name above them.
        float x0 = gridLeft - 8 * dp;
        float headerBase = gridTop - header.ascent();
        String month = today.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase(Locale.ROOT)
                + " " + today.getYear();
        c.drawText(month, gridLeft, headerBase, header);
        float y = headerBase + header.descent() + 3 * dp;
        // Weekday letters
        String[] wd = {"M", "T", "W", "T", "F", "S", "S"};
        float wdH = 12 * dp;
        for (int i = 0; i < 7; i++) {
            c.drawText(wd[i], colCenter(x0, cw, i), y + wdH / 2 - (weekday.ascent() + weekday.descent()) / 2, weekday);
        }
        y += wdH;
        LocalDate first = today.withDayOfMonth(1);
        LocalDate start = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate last = today.with(TemporalAdjusters.lastDayOfMonth());
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int weeks = 0;
        for (LocalDate d = start; !d.isAfter(last); d = d.plusWeeks(1)) weeks++;
        for (int w = 0; w < weeks; w++) {
            LocalDate rowStart = start.plusWeeks(w);
            float top = y + w * ch;
            if (rowStart.equals(monday)) {
                fill.setColor(0x12FFFFFF);
                rect.set(x0, top, x0 + 7 * cw, top + ch);
                c.drawRoundRect(rect, 6 * dp, 6 * dp, fill);
            }
            for (int i = 0; i < 7; i++) {
                LocalDate d = rowStart.plusDays(i);
                float cx = colCenter(x0, cw, i);
                boolean inMonth = d.getMonth() == today.getMonth();
                // The next month's days after the last one stay blank, as in the approved layout.
                if (d.isAfter(last)) continue;
                boolean isToday = d.equals(today);
                if (isToday) {
                    fill.setColor(0x33FFFFFF);
                    rect.set(cx - cw / 2, top, cx + cw / 2, top + ch);
                    c.drawRoundRect(rect, 6 * dp, 6 * dp, fill);
                    stroke.setColor(0x4DFFFFFF);
                    stroke.setStrokeWidth(0.75f * dp);
                    rect.inset(0.4f * dp, 0.4f * dp);
                    c.drawRoundRect(rect, 6 * dp, 6 * dp, stroke);
                }
                Paint p = isToday ? digitToday : digit;
                int base = p.getColor();
                if (!inMonth) p.setColor(0x38FFFFFF);
                else if (d.isBefore(today)) p.setColor(0x66FFFFFF);
                // Digit in the cell's upper part (line box 12 dp), the icons right under it.
                float baseline = top + 12 * dp / 2 - (p.ascent() + p.descent()) / 2;
                c.drawText(String.valueOf(d.getDayOfMonth()), cx, baseline, p);
                p.setColor(base);
                String m = inMonth ? data.marks.get(d) : null;
                if (m != null && !m.isEmpty()) {
                    float iy = top + 12.5f * dp;
                    float s = 7 * dp;
                    boolean both = m.length() > 1;
                    float gap = 1.5f * dp;
                    float total = both ? s * 2 + gap : s;
                    float ix = cx - total / 2;
                    for (int k = 0; k < m.length(); k++) {
                        float sz = m.charAt(k) == 'f' ? 8 * dp : s;
                        if (m.charAt(k) == 't') drawBall(c, ix, iy, sz);
                        else drawFlag(c, ix, iy - 0.5f * dp, sz);
                        ix += s + gap;
                    }
                }
            }
        }
        return y + weeks * ch;
    }

    private static float colCenter(float x0, float cw, int i) {
        return x0 + cw * i + cw / 2;
    }

    // ---- list ----------------------------------------------------------------------------------------------

    private static final float ROW_H = 17.5f, GROUP_GAP = 3, PILL_W = 22, ICON_W = 22, TIME_W = 80;   // dp

    private static final class Row {
        final SportFeed.Item item;
        final String time, title, sub;
        final boolean dim;
        /** The result drawn in the time column instead of the time: a tennis score or an F1 winner, else "". */
        final String res;
        /** Between the bold title and the dim rest: " · ", or " " for "Alcaraz d. Ruud". */
        final String joiner;

        Row(SportFeed.Item item, String time, String title, String sub, boolean dim) {
            this(item, time, title, sub, dim, "", " · ");
        }

        Row(SportFeed.Item item, String time, String title, String sub, boolean dim, String res, String joiner) {
            this.item = item;
            this.time = time;
            this.title = title;
            this.sub = sub;
            this.dim = dim;
            this.res = res;
            this.joiner = joiner;
        }
    }

    /** A row for an item: its result when it has one, its time otherwise ("played" once the day is over). */
    private Row row(SportFeed.Item i, boolean past) {
        if (!i.res.isEmpty()) {
            if (i.kind == 'f') {
                String[] p = i.res.split(" ");
                String sub = i.sub;
                if (podium && p.length >= 3) sub = "P2 " + p[1] + " · P3 " + p[2];
                return new Row(i, "", i.title, sub, past, p[0], " · ");
            }
            int d = i.title.indexOf(" d. ");
            if (d > 0) {
                // The winner bold, "d. Loser" with the dim part; the round short (QF) so the score row has room.
                return new Row(i, "", i.title.substring(0, d), i.title.substring(d + 1) + " · " + shortRound(i.sub),
                        past, i.res, " ");
            }
        }
        return new Row(i, past ? "played" : i.time, i.title, i.sub, past);
    }

    /** The week's rows by day; a tournament with more than four matches in a day shows three and "+N more".
     *  Finished matches and races show their result (SportFeed.Item.res), or "played" until it comes in. */
    private Map<LocalDate, List<Row>> groups(LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        Map<LocalDate, List<SportFeed.Item>> byDay = new LinkedHashMap<>();
        for (SportFeed.Item i : data.items) {
            if (i.day.isBefore(monday) || i.day.isAfter(sunday)) continue;
            byDay.computeIfAbsent(i.day, k -> new ArrayList<>()).add(i);
        }
        Map<LocalDate, List<Row>> out = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, List<SportFeed.Item>> e : byDay.entrySet()) {
            LocalDate d = e.getKey();
            boolean past = d.isBefore(today);
            List<Row> rows = new ArrayList<>();
            Map<String, List<SportFeed.Item>> byTournament = new LinkedHashMap<>();
            for (SportFeed.Item i : e.getValue()) {
                if (i.kind == 'f') {
                    rows.add(row(i, past));
                    continue;
                }
                String t = i.sub.split(" · ")[0];
                byTournament.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
            }
            for (Map.Entry<String, List<SportFeed.Item>> t : byTournament.entrySet()) {
                List<SportFeed.Item> list = t.getValue();
                int shown = list.size() > 4 ? 3 : list.size();
                for (int k = 0; k < shown; k++) rows.add(row(list.get(k), past));
                if (list.size() > shown) {
                    rows.add(new Row(list.get(shown), "", "", "+ " + (list.size() - shown) + " more · " + t.getKey(), past));
                }
            }
            rows.sort((a, b) -> Integer.compare(order(a), order(b)));
            out.put(d, rows);
        }
        return out;
    }

    private static int order(Row r) {
        return r.title.isEmpty() ? Integer.MAX_VALUE : r.item.order;
    }

    private void drawList(Canvas c, LocalDate today, float bottom) {
        Map<LocalDate, List<Row>> groups = groups(today);
        if (groups.isEmpty()) {
            soft.setAlpha(140);
            c.drawText("No races or matches this week", listLeft, listTop - soft.ascent(), soft);
            soft.setColor(0x8CFFFFFF);
            pages = 1;
            return;
        }
        float rowH = ROW_H * dp, gap = GROUP_GAP * dp;
        // Pages of whole days, the page with today first when the week does not fit.
        List<List<LocalDate>> pageList = new ArrayList<>();
        List<LocalDate> cur = new ArrayList<>();
        float used = 0, room = bottom - listTop;
        for (Map.Entry<LocalDate, List<Row>> e : groups.entrySet()) {
            float h = e.getValue().size() * rowH + gap;
            if (!cur.isEmpty() && used + h - gap > room) {
                pageList.add(cur);
                cur = new ArrayList<>();
                used = 0;
            }
            cur.add(e.getKey());
            used += h;
        }
        if (!cur.isEmpty()) pageList.add(cur);
        pages = pageList.size();
        int page = 0;
        if (pages > 1) {
            // Start the rotation at the page holding today (or the next day with something).
            int todayPage = 0;
            search:
            for (int p = 0; p < pages; p++) {
                for (LocalDate d : pageList.get(p)) {
                    if (!d.isBefore(today)) {
                        todayPage = p;
                        break search;
                    }
                }
            }
            long elapsed = SystemClock.elapsedRealtime() - pageStart;
            page = (int) ((todayPage + elapsed / PAGE_MS) % pages);
        }
        float y = listTop;
        float pillW = PILL_W * dp;
        float iconX = listLeft + pillW + 8 * dp;
        float timeX = iconX + ICON_W * dp;
        // A long score (four or five sets) widens the time column for the whole page, so the names stay in one
        // straight column instead of jumping from row to row.
        float statusW = TIME_W * dp;
        for (LocalDate d : pageList.get(page)) {
            for (Row row : groups.get(d)) {
                if (!row.res.isEmpty()) statusW = Math.max(statusW, resultWidth(row) + STATUS_GAP * dp);
            }
        }
        float textX = timeX + statusW;
        for (LocalDate d : pageList.get(page)) {
            List<Row> rows = groups.get(d);
            float h = rows.size() * rowH;
            boolean isToday = d.equals(today);
            boolean past = d.isBefore(today);
            fill.setColor(isToday ? 0x21FFFFFF : 0x12FFFFFF);
            rect.set(listLeft, y + 0.5f * dp, listLeft + pillW, y + h - 0.5f * dp);
            float r = pillW / 2;
            c.drawRoundRect(rect, r, r, fill);
            if (isToday) {
                stroke.setColor(0x38FFFFFF);
                stroke.setStrokeWidth(0.75f * dp);
                rect.inset(0.4f * dp, 0.4f * dp);
                c.drawRoundRect(rect, r, r, stroke);
            }
            pillNum.setColor(isToday ? 0xFFFFFFFF : past ? 0x66FFFFFF : 0xB3FFFFFF);
            pillNum.setTypeface(isToday ? title.getTypeface() : sub.getTypeface());
            c.drawText(String.valueOf(d.getDayOfMonth()), listLeft + pillW / 2,
                    y + rowH / 2 - (pillNum.ascent() + pillNum.descent()) / 2, pillNum);
            for (Row row : rows) {
                float mid = y + rowH / 2;
                int alpha = row.dim ? 140 : 255;
                if (!row.title.isEmpty() || row.time.isEmpty()) {
                    float s = row.item.kind == 'f' ? 10.5f * dp : 10 * dp;
                    if (row.item.kind == 'f') drawFlag(c, iconX, mid - s / 2, s);
                    else drawBall(c, iconX, mid - s / 2, s);
                }
                if (row.res.isEmpty()) drawTime(c, row.time, timeX, mid, alpha);
                else drawResult(c, row, timeX, mid, row.dim ? RESULT_DIM_ALPHA : 255);
                float maxW = listRight - textX;
                String t = row.title.isEmpty() ? "" : row.title;
                String s = (t.isEmpty() ? "" : row.joiner) + row.sub;
                title.setAlpha(alpha);
                sub.setAlpha(row.dim ? 110 : 255 * 0x80 / 0xFF);
                float tw = title.measureText(t);
                if (tw > maxW) {
                    t = fit(t, title, maxW);
                    tw = title.measureText(t);
                }
                float base = mid - (title.ascent() + title.descent()) / 2;
                c.drawText(t, textX, base, title);
                float sw = maxW - tw;
                if (sw > 8 * dp) {
                    String ss = fit(s, sub, sw);
                    c.drawText(ss, textX + tw, base, sub);
                }
                title.setAlpha(255);
                sub.setColor(0x80FFFFFF);
                y += rowH;
            }
            y += gap;
        }
        if (pages > 1) {
            String marker = (page + 1) + " / " + pages;
            timeSmall.setTextAlign(Paint.Align.RIGHT);
            c.drawText(marker, listRight, bottom - timeSmall.descent(), timeSmall);
            timeSmall.setTextAlign(Paint.Align.LEFT);
        }
    }

    private static final String[][] ROUNDS = {{"Quarterfinals", "QF"}, {"Semifinals", "SF"}, {"Round of 16", "R16"},
            {"Final", "F"}};

    private static String shortRound(String s) {
        for (String[] r : ROUNDS) {
            if (s.endsWith(" · " + r[0]) || s.equals(r[0])) return s.substring(0, s.length() - r[0].length()) + r[1];
        }
        return s;
    }

    /** The text cut to width: first the round written short ("· Final" -> "· F"), then an ellipsis at the end. */
    private static String fit(String s, Paint p, float w) {
        if (p.measureText(s) <= w) return s;
        s = shortRound(s);
        if (p.measureText(s) <= w) return s;
        int n = p.breakText(s, true, Math.max(0, w - p.measureText("…")), null);
        return n <= 0 ? "" : s.substring(0, n).replaceAll("\\s+$", "") + "…";
    }

    // ---- results -------------------------------------------------------------------------------------------

    /** dp between the result and the names when a long score widens the time column. */
    private static final float STATUS_GAP = 10;
    /** Above this width (dp) a score drops its tie-break points: 7-6 instead of 7-6(5). */
    private static final float SCORE_MAX_W = 118;
    /** A past day's result stays brighter than its dimmed names: it is what the row is for. */
    private static final int RESULT_DIM_ALPHA = 200;

    private float resultWidth(Row row) {
        return drawResult(null, row, 0, 0, 255);
    }

    /**
     * Draws a result at x (or only measures it when c is null) and returns its width. F1: "P1" small and dim, like
     * "NB", then the winner's code. Tennis: the sets in the time's font, tie-break points smaller and dim right
     * after their set, "ret." and "w/o" dim; tie-breaks are left out when the whole score would not fit.
     */
    private float drawResult(Canvas c, Row row, float x, float mid, int alpha) {
        float base = mid - (time.ascent() + time.descent()) / 2;
        if (row.item.kind == 'f') {
            float w = timeSmall.measureText("P1") + 3 * dp;
            if (c != null) {
                timeSmall.setAlpha(alpha * 0x8C / 0xFF);
                c.drawText("P1", x, base, timeSmall);
                timeSmall.setColor(0x8CFFFFFF);
                code.setAlpha(alpha);
                c.drawText(row.res, x + w, base, code);
                code.setAlpha(255);
            }
            return w + code.measureText(row.res);
        }
        boolean tb = scoreWidth(row.res, true) <= SCORE_MAX_W * dp;
        if (c == null) return scoreWidth(row.res, tb);
        time.setAlpha(alpha);
        soft.setAlpha(alpha * 0x8C / 0xFF);
        float w = drawScore(c, row.res, tb, x, base);
        time.setAlpha(255);
        soft.setColor(0x8CFFFFFF);
        return w;
    }

    private float scoreWidth(String res, boolean tb) {
        return drawScore(null, res, tb, 0, 0);
    }

    private float drawScore(Canvas c, String res, boolean tb, float x0, float base) {
        float x = x0, gap = 5 * dp;
        String[] sets = res.split(" ");
        for (int i = 0; i < sets.length; i++) {
            String set = sets[i];
            if (i > 0) x += gap;
            if (set.equals("ret.") || set.equals("w/o")) {
                if (c != null) c.drawText(set, x, base, soft);
                x += soft.measureText(set);
                continue;
            }
            int b = set.indexOf('(');
            String games = b > 0 ? set.substring(0, b) : set;
            if (c != null) c.drawText(games, x, base, time);
            x += time.measureText(games);
            if (b > 0 && tb) {
                // The tie-break points without brackets, small and raised a little: 7-6⁵.
                String pts = set.substring(b + 1, set.length() - 1);
                x += 0.5f * dp;
                if (c != null) c.drawText(pts, x, base - 4 * dp, soft);
                x += soft.measureText(pts);
            }
        }
        return x - x0;
    }

    private void drawTime(Canvas c, String t, float x, float mid, int alpha) {
        if (t == null || t.isEmpty()) return;
        if (t.startsWith("NB ")) {
            timeSmall.setAlpha(alpha * 0x8C / 0xFF);
            c.drawText("NB", x, mid - (time.ascent() + time.descent()) / 2, timeSmall);
            float w = timeSmall.measureText("NB") + 3 * dp;
            time.setAlpha(alpha);
            c.drawText(t.substring(3), x + w, mid - (time.ascent() + time.descent()) / 2, time);
            timeSmall.setColor(0x8CFFFFFF);
            time.setAlpha(255);
            return;
        }
        if (Character.isDigit(t.charAt(0))) {
            time.setAlpha(alpha);
            c.drawText(t, x, mid - (time.ascent() + time.descent()) / 2, time);
            time.setAlpha(255);
        } else {
            soft.setAlpha(alpha * 0x8C / 0xFF);
            c.drawText(t, x, mid - (soft.ascent() + soft.descent()) / 2, soft);
            soft.setColor(0x8CFFFFFF);
        }
    }

    // ---- icons (the mockup's shapes, on a 20-unit box) -----------------------------------------------------

    private void drawBall(Canvas c, float x, float y, float size) {
        float k = size / 20f;
        icon.setStyle(Paint.Style.FILL);
        icon.setColor(0xFFCFE04F);
        c.drawCircle(x + 10 * k, y + 10 * k, 9 * k, icon);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeWidth(1.6f * k);
        icon.setColor(Color.argb(217, 255, 255, 255));
        path.reset();
        // Seams: arcs of radius 9 through (3.2, 4.2) and (3.2, 15.8), and their mirror image.
        rect.set(x + (-3.68f - 9) * k, y + 1 * k, x + (-3.68f + 9) * k, y + 19 * k);
        path.arcTo(rect, -40.1f, 80.2f, true);
        rect.set(x + (23.68f - 9) * k, y + 1 * k, x + (23.68f + 9) * k, y + 19 * k);
        path.arcTo(rect, -139.9f, -80.2f, true);
        c.drawPath(path, icon);
        icon.setStyle(Paint.Style.FILL);
    }

    private void drawFlag(Canvas c, float x, float y, float size) {
        float k = size / 20f;
        icon.setStyle(Paint.Style.FILL);
        icon.setColor(Color.argb(204, 255, 255, 255));
        rect.set(x + 2 * k, y + 1.5f * k, x + 3.6f * k, y + 18.5f * k);
        c.drawRoundRect(rect, 0.8f * k, 0.8f * k, icon);
        float fx = x + 4.2f * k, fy = y + 2.5f * k;
        icon.setColor(Color.WHITE);
        c.drawRect(fx, fy, fx + 14 * k, fy + 10 * k, icon);
        icon.setColor(0xFF111111);
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < 5; j++) {
                if ((i + j) % 2 == 1) c.drawRect(fx + i * 2 * k, fy + j * 2 * k, fx + (i + 1) * 2 * k, fy + (j + 1) * 2 * k, icon);
            }
        }
    }
}

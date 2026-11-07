package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Data for the Home sport calendar (sport_calendar): this week's F1 races and tennis matches, and which days of
 * the month have something on. Fetched on a background thread, kept in files/sport.json so Home shows the last
 * good data without a network.
 *
 * Sources, all free and keyless: Jolpica (F1 calendar), ESPN's public scoreboard JSON (tennis draws and times),
 * and the ATP's order of play PDF on protennislive.com for "Not before" and "Followed by". Times are shown in
 * the TV's time zone.
 *
 * Rules (owner's): F1 main race only. Tennis singles only, never qualifying or doubles. Men at every listed
 * tournament, women only at the Slams, the Olympics and cups that have them. Matches from the round of 16 on,
 * all matches at the ATP Finals and at cups.
 */
final class SportFeed {

    private static final String TAG = "TvHomeSport";
    static final String CACHE = "sport.json";
    /** Bump when the rules or the marks change: an older cache is then refetched at once. */
    static final int CACHE_VERSION = 3;
    static final String OVERRIDE = "sport_tournaments.json";
    /** How old the cache may be before Home asks for a new one. */
    static final long STALE_MS = 3 * 60 * 60 * 1000L;
    /** Coming back to Home (or the TV waking on it) refetches data older than this. */
    static final long RESUME_STALE_MS = 60 * 60 * 1000L;
    /** The tournament list from ESPN's core API: refetched once a week. */
    private static final long SEASON_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final String UA = "Mozilla/5.0 (Linux; Android 11) TvHome";

    /** One line of the list. */
    static final class Item {
        final LocalDate day;
        /** 't' tennis, 'f' F1. */
        final char kind;
        /** Sort key inside the day: local minutes, or 24*60+ for "time TBA". */
        final int order;
        /** "20:00", "NB 20:00", "after match", "time TBA". */
        final String time;
        /** Bold part: "Shelton vs Norrie", "Brazilian GP", "Quarterfinals". */
        final String title;
        /** Dim part: "Paris Masters · R16", "Brazil · Interlagos". */
        final String sub;
        /**
         * The result once there is one, else "". Tennis: the sets from the winner's side, the loser's tie-break
         * points in brackets, then "ret." or alone "w/o" ("7-6(5) 3-6 6-2", "6-3 6-7(6) 1-4 ret."); the title is
         * then "Winner d. Loser". F1: the podium's driver codes, "VER HAM ANT".
         */
        final String res;

        Item(LocalDate day, char kind, int order, String time, String title, String sub) {
            this(day, kind, order, time, title, sub, "");
        }

        Item(LocalDate day, char kind, int order, String time, String title, String sub, String res) {
            this.day = day;
            this.kind = kind;
            this.order = order;
            this.time = time;
            this.title = title;
            this.sub = sub;
            this.res = res != null ? res : "";
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("day", day.toString()).put("kind", String.valueOf(kind)).put("order", order)
                    .put("time", time).put("title", title).put("sub", sub).put("res", res);
        }

        static Item from(JSONObject o) {
            return new Item(LocalDate.parse(o.optString("day")), o.optString("kind", "t").charAt(0), o.optInt("order"),
                    o.optString("time"), o.optString("title"), o.optString("sub"), o.optString("res"));
        }
    }

    /** What Home draws: the week's items and the month's marks ("t", "f" or "tf" per day). */
    static final class Data {
        final long fetchedAt;
        final List<Item> items;
        final Map<LocalDate, String> marks;
        final int version;

        Data(long fetchedAt, List<Item> items, Map<LocalDate, String> marks) {
            this(fetchedAt, items, marks, CACHE_VERSION);
        }

        Data(long fetchedAt, List<Item> items, Map<LocalDate, String> marks, int version) {
            this.fetchedAt = fetchedAt;
            this.items = items;
            this.marks = marks;
            this.version = version;
        }

        static final Data EMPTY = new Data(0, Collections.emptyList(), Collections.emptyMap(), 0);

        /**
         * True while a race or match of the last two days is over but its result has not come in yet: Home then
         * refetches sooner (RESULT_STALE_MS), so the winner and the score show up without anyone doing anything.
         */
        boolean awaitingResults(ZoneId zone, long now) {
            LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            for (Item i : items) {
                if (!i.res.isEmpty() || i.day.isBefore(today.minusDays(1)) || i.day.isAfter(today)) continue;
                if (i.kind == 't' && i.time.equals("played")) return true;
                if (i.kind == 'f' && i.order < 24 * 60) {
                    long start = i.day.atStartOfDay(zone).plusMinutes(i.order).toInstant().toEpochMilli();
                    if (now > start + RACE_MS) return true;
                }
            }
            return false;
        }
    }

    /** A race is taken as over this long after its start; then its result is looked for. */
    static final long RACE_MS = 2 * 60 * 60 * 1000L;
    /** How often Home looks again while a finished event has no result yet. */
    static final long RESULT_STALE_MS = 20 * 60 * 1000L;

    private final Context ctx;
    private final ZoneId zone;

    SportFeed(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.zone = ZoneId.systemDefault();
    }

    // ---- cache ---------------------------------------------------------------------------------------------

    Data load() {
        File f = new File(ctx.getFilesDir(), CACHE);
        if (!f.exists()) return Data.EMPTY;
        try {
            JSONObject o = new JSONObject(new String(read(new java.io.FileInputStream(f)), StandardCharsets.UTF_8));
            List<Item> items = new ArrayList<>();
            JSONArray a = o.optJSONArray("items");
            if (a != null) for (int i = 0; i < a.length(); i++) items.add(Item.from(a.getJSONObject(i)));
            Map<LocalDate, String> marks = new HashMap<>();
            JSONObject m = o.optJSONObject("marks");
            if (m != null) {
                java.util.Iterator<String> it = m.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    marks.put(LocalDate.parse(k), m.optString(k));
                }
            }
            return new Data(o.optLong("fetched"), items, marks, o.optInt("v"));
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "cache unreadable: " + e);
            return Data.EMPTY;
        }
    }

    private void save(Data d, JSONObject extra) throws IOException, JSONException {
        JSONObject o = extra != null ? extra : new JSONObject();
        o.put("fetched", d.fetchedAt);
        o.put("v", CACHE_VERSION);
        JSONArray a = new JSONArray();
        for (Item i : d.items) a.put(i.json());
        o.put("items", a);
        JSONObject m = new JSONObject();
        for (Map.Entry<LocalDate, String> e : d.marks.entrySet()) m.put(e.getKey().toString(), e.getValue());
        o.put("marks", m);
        File tmp = new File(ctx.getFilesDir(), CACHE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(ctx.getFilesDir(), CACHE))) throw new IOException("rename failed");
    }

    // ---- fetch ---------------------------------------------------------------------------------------------

    /**
     * Fetches everything and writes the cache. Each source fails on its own: a dead source keeps its part of
     * the old cache instead of emptying the calendar. Returns the new data, or null when nothing could be fetched.
     * Blocking; call off the main thread.
     */
    Data refresh() {
        // Test switch: with this file in the app's external files dir the fetch acts as if there were no network
        // (adb shell touch /sdcard/Android/data/com.gathin.tvhome/files/sport_offline), so the cache path can be
        // checked without taking the TV, and adb with it, off the Wi-Fi.
        if (new File(ctx.getExternalFilesDir(null), "sport_offline").exists()) {
            Log.i(TAG, "offline test switch present: not fetching, Home keeps the cache");
            return null;
        }
        LocalDate today = LocalDate.now(zone);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.with(TemporalAdjusters.lastDayOfMonth());
        Data old = load();
        JSONObject oldRaw = readCacheRaw();

        List<Item> items = new ArrayList<>();
        Map<LocalDate, String> marks = new HashMap<>();
        boolean any = false;

        // F1
        List<Item> f1 = null;
        try {
            f1 = f1(today.getYear(), monthStart, monthEnd, monday, sunday, marks);
            any = true;
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "F1 failed: " + e);
        }
        if (f1 == null) {
            f1 = new ArrayList<>();
            for (Item i : old.items) if (i.kind == 'f') f1.add(i);
            for (Map.Entry<LocalDate, String> e : old.marks.entrySet()) if (e.getValue().contains("f")) addMark(marks, e.getKey(), 'f');
        }
        items.addAll(f1);

        // Tennis
        JSONObject tournamentsJson = tournaments();
        List<Item> tennis = null;
        try {
            tennis = tennis(tournamentsJson, today, monday, sunday);
            any = true;
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "tennis failed: " + e);
        }
        if (tennis == null) {
            tennis = new ArrayList<>();
            for (Item i : old.items) if (i.kind == 't' && !i.day.isBefore(monday) && !i.day.isAfter(sunday)) tennis.add(i);
        } else {
            // A tournament that ended earlier this week is no longer on today's scoreboard: keep its days from
            // the cache.
            Set<LocalDate> covered = new HashSet<>();
            for (Item i : tennis) covered.add(i.day);
            for (Item i : old.items) {
                if (i.kind == 't' && !i.day.isBefore(monday) && i.day.isBefore(today) && !covered.contains(i.day)) tennis.add(i);
            }
        }
        items.addAll(tennis);
        items.addAll(cups(tournamentsJson, monday, sunday));
        for (Item i : items) if (i.kind == 't') addMark(marks, i.day, 't');

        // Month marks for the other weeks: tournament spans from ESPN's season list (weekly), narrowed to the days
        // the round of 16 on usually falls on.
        JSONObject season = null;
        try {
            season = season(tournamentsJson, today.getYear(), oldRaw);
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "season failed: " + e);
            season = oldRaw != null ? oldRaw.optJSONObject("season") : null;
        }
        if (season != null) seasonMarks(season, monthStart, monthEnd, monday, sunday, marks);
        cupMarks(tournamentsJson, monthStart, monthEnd, marks);

        if (!any) return null;
        Collections.sort(items, (a, b) -> a.day.equals(b.day) ? Integer.compare(a.order, b.order) : a.day.compareTo(b.day));
        Data d = new Data(System.currentTimeMillis(), items, marks);
        Log.i(TAG, "refreshed: " + items.size() + " rows this week, " + marks.size() + " marked days");
        try {
            JSONObject extra = new JSONObject();
            if (season != null) extra.put("season", season);
            save(d, extra);
        } catch (IOException | JSONException e) {
            Log.w(TAG, "cache not written: " + e);
        }
        return d;
    }

    private JSONObject readCacheRaw() {
        File f = new File(ctx.getFilesDir(), CACHE);
        if (!f.exists()) return null;
        try {
            return new JSONObject(new String(read(new java.io.FileInputStream(f)), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static void addMark(Map<LocalDate, String> marks, LocalDate d, char k) {
        String s = marks.getOrDefault(d, "");
        if (s.indexOf(k) < 0) {
            s = k == 't' ? "t" + s : s + "f";
            marks.put(d, s);
        }
    }

    // ---- F1 ------------------------------------------------------------------------------------------------

    private static final Map<String, String> CIRCUITS = new HashMap<>();

    static {
        String[] c = {
                "albert_park", "Albert Park", "shanghai", "Shanghai", "suzuka", "Suzuka", "bahrain", "Sakhir",
                "jeddah", "Jeddah", "miami", "Miami", "imola", "Imola", "monaco", "Monaco", "catalunya", "Barcelona",
                "villeneuve", "Montreal", "red_bull_ring", "Red Bull Ring", "silverstone", "Silverstone",
                "spa", "Spa", "hungaroring", "Hungaroring", "zandvoort", "Zandvoort", "monza", "Monza",
                "madring", "Madring", "baku", "Baku", "marina_bay", "Marina Bay", "americas", "COTA",
                "rodriguez", "Mexico City", "interlagos", "Interlagos", "vegas", "Las Vegas Strip",
                "losail", "Lusail", "yas_marina", "Yas Marina"};
        for (int i = 0; i < c.length; i += 2) CIRCUITS.put(c[i], c[i + 1]);
    }

    private List<Item> f1(int year, LocalDate monthStart, LocalDate monthEnd, LocalDate monday, LocalDate sunday,
                          Map<LocalDate, String> marks) throws IOException, JSONException {
        List<Item> out = new ArrayList<>();
        Set<Integer> years = new HashSet<>();
        years.add(year);
        years.add(monthEnd.getYear());
        years.add(sunday.getYear());
        for (int y : years) {
            JSONObject o = new JSONObject(get("https://api.jolpi.ca/ergast/f1/" + y + "/races/?format=json&limit=100"));
            JSONArray races = o.getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
            for (int i = 0; i < races.length(); i++) {
                JSONObject r = races.getJSONObject(i);
                String date = r.optString("date"), time = r.optString("time", "");
                if (date.isEmpty()) continue;
                ZonedDateTime local;
                boolean timed = !time.isEmpty();
                if (timed) {
                    local = Instant.parse(date + "T" + (time.endsWith("Z") ? time : time + "Z")).atZone(zone);
                } else {
                    local = LocalDate.parse(date).atStartOfDay(zone);
                }
                LocalDate day = local.toLocalDate();
                if (!day.isBefore(monthStart) && !day.isAfter(monthEnd)) addMark(marks, day, 'f');
                if (day.isBefore(monday) || day.isAfter(sunday)) continue;
                JSONObject circuit = r.optJSONObject("Circuit");
                String country = "", track = "";
                if (circuit != null) {
                    JSONObject loc = circuit.optJSONObject("Location");
                    if (loc != null) country = loc.optString("country");
                    track = CIRCUITS.getOrDefault(circuit.optString("circuitId"), circuit.optString("circuitName"));
                }
                addMark(marks, day, 'f');
                String name = r.optString("raceName").replace("Grand Prix", "GP").trim();
                int minutes = timed ? local.getHour() * 60 + local.getMinute() : 24 * 60;
                String shown = timed ? String.format(Locale.ROOT, "%02d:%02d", local.getHour(), local.getMinute()) : "time TBA";
                // The podium once the race should be over; until Jolpica has it the row keeps its time or "played".
                String res = "";
                if (System.currentTimeMillis() > local.toInstant().toEpochMilli() + (timed ? RACE_MS : 24 * 60 * 60 * 1000L)) {
                    try {
                        res = podium(new JSONObject(get("https://api.jolpi.ca/ergast/f1/" + y + "/" + r.optString("round")
                                + "/results/?format=json&limit=3")));
                    } catch (IOException | JSONException | RuntimeException e) {
                        Log.w(TAG, "F1 result for " + name + ": " + e);
                    }
                }
                out.add(new Item(day, 'f', minutes, shown, name, country + (track.isEmpty() ? "" : " · " + track), res));
            }
        }
        return out;
    }

    /**
     * The podium from a Jolpica results answer as driver codes, "VER HAM ANT", or "" when it has no results yet.
     * The code is the source's own three-letter code; a driver without one gets the first three letters of the
     * family name.
     */
    static String podium(JSONObject o) throws JSONException {
        JSONArray races = o.getJSONObject("MRData").getJSONObject("RaceTable").optJSONArray("Races");
        if (races == null || races.length() == 0) return "";
        JSONArray results = races.getJSONObject(0).optJSONArray("Results");
        if (results == null) return "";
        String[] p = new String[3];
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            int pos = r.optInt("position");
            if (pos < 1 || pos > 3) continue;
            JSONObject d = r.optJSONObject("Driver");
            if (d == null) continue;
            String code = d.optString("code");
            if (code.isEmpty()) {
                String fam = d.optString("familyName").replaceAll("[^\\p{L}]", "");
                code = fam.substring(0, Math.min(3, fam.length()));
            }
            p[pos - 1] = code.toUpperCase(Locale.ROOT);
        }
        if (p[0] == null || p[0].isEmpty()) return "";
        StringBuilder s = new StringBuilder(p[0]);
        for (int i = 1; i < 3; i++) if (p[i] != null && !p[i].isEmpty()) s.append(' ').append(p[i]);
        return s.toString();
    }

    // ---- tennis --------------------------------------------------------------------------------------------

    private JSONObject tournaments() {
        File f = new File(ctx.getExternalFilesDir(null), OVERRIDE);
        try {
            if (f.exists()) return new JSONObject(new String(read(new java.io.FileInputStream(f)), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "override unreadable, using the built-in list: " + e);
        }
        try {
            return new JSONObject(new String(read(ctx.getAssets().open(OVERRIDE)), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "built-in list unreadable: " + e);
            return new JSONObject();
        }
    }

    /** The listed tournament an ESPN event name belongs to, or null. */
    private static JSONObject match(JSONObject list, String eventName) {
        JSONArray a = list.optJSONArray("tournaments");
        if (a == null) return null;
        String n = eventName.toLowerCase(Locale.ROOT);
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            JSONArray m = t != null ? t.optJSONArray("match") : null;
            if (m == null) continue;
            for (int j = 0; j < m.length(); j++) if (n.contains(m.optString(j).toLowerCase(Locale.ROOT))) return t;
        }
        return null;
    }

    private static final class Match {
        LocalDate day;
        ZonedDateTime when;
        boolean timeValid;
        String court = "";
        String a, b;
        String aLast, bLast;
        int roundSize;
        String roundName;
        boolean women;
        JSONObject tournament;
        String timeLabel;
        int order;
        /** Index (0 or 1) of the winner in a, b, or -1; and the score from the winner's side. */
        int winner = -1;
        String res = "";
    }

    private List<Item> tennis(JSONObject list, LocalDate today, LocalDate monday, LocalDate sunday)
            throws IOException, JSONException {
        // One scoreboard per day the week still has, from today; the past days of the week come from the cache's
        // earlier runs. ESPN returns the events that are on that day, each with its whole draw, so one call usually
        // covers the rest of the week. Day by day only while new listed events turn up.
        // Today, then the day after the listed events end (a new event that week), then Sunday (an event that
        // starts at the weekend): three calls at most.
        Map<String, JSONObject> events = new LinkedHashMap<>();
        // Yesterday too: a tournament that ended yesterday is gone from today's scoreboard, and its last matches
        // may have ended after the previous refresh, without a score in the cache.
        LocalDate yesterday = today.minusDays(1);
        if (!yesterday.isBefore(monday)) {
            try {
                probe(list, yesterday, events, yesterday);
            } catch (IOException | JSONException e) {
                Log.w(TAG, "yesterday's scoreboard: " + e);
            }
        }
        LocalDate covered = probe(list, today, events, today);
        if (covered.isBefore(sunday)) covered = probe(list, covered.plusDays(1), events, covered);
        if (covered.isBefore(sunday)) probe(list, sunday, events, covered);

        List<Match> matches = new ArrayList<>();
        Map<String, List<Match>> courtDay = new HashMap<>();   // tournament|court|day -> matches, for "after match"
        for (JSONObject e : events.values()) {
            JSONObject t = match(list, e.optString("name"));
            String tier = t.optString("tier");
            boolean allRounds = tier.equals("finals") || tier.equals("cup");
            JSONArray groups = e.optJSONArray("groupings");
            if (groups == null) continue;
            for (int g = 0; g < groups.length(); g++) {
                JSONObject grp = groups.getJSONObject(g);
                String gname = grp.optJSONObject("grouping") != null ? grp.getJSONObject("grouping").optString("displayName") : "";
                JSONArray comps = grp.optJSONArray("competitions");
                if (comps == null) continue;
                boolean singles = gname.contains("Singles");
                boolean women = gname.startsWith("Women");
                // Every match on a court counts for the order, doubles included.
                for (int c = 0; c < comps.length(); c++) {
                    JSONObject comp = comps.getJSONObject(c);
                    String court = comp.optJSONObject("venue") != null ? comp.getJSONObject("venue").optString("court") : "";
                    ZonedDateTime when = espnTime(comp.optString("date"));
                    if (when == null || court.isEmpty() || !comp.optBoolean("timeValid")) continue;
                    Match m = new Match();
                    m.when = when;
                    m.day = when.withZoneSameInstant(tz(t)).toLocalDate();
                    String key = t.optString("label") + "|" + court + "|" + m.day;
                    courtDay.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
                }
                if (!singles || (women && !t.optBoolean("women"))) continue;
                Map<String, Integer> roundSize = new HashMap<>();
                for (int c = 0; c < comps.length(); c++) {
                    JSONObject r = comps.getJSONObject(c).optJSONObject("round");
                    String id = r != null ? r.optString("id") : "";
                    roundSize.merge(id, 1, Integer::sum);
                }
                for (int c = 0; c < comps.length(); c++) {
                    JSONObject comp = comps.getJSONObject(c);
                    JSONObject r = comp.optJSONObject("round");
                    String rname = r != null ? r.optString("displayName") : "";
                    if (rname.toLowerCase(Locale.ROOT).contains("qualif")) continue;
                    int size = roundSize.getOrDefault(r != null ? r.optString("id") : "", 99);
                    if (!allRounds && size > 8) continue;
                    String state = comp.optJSONObject("status") != null && comp.getJSONObject("status").optJSONObject("type") != null
                            ? comp.getJSONObject("status").getJSONObject("type").optString("name") : "";
                    ZonedDateTime when = espnTime(comp.optString("date"));
                    if (when == null) continue;
                    Match m = new Match();
                    m.tournament = t;
                    m.when = when;
                    m.timeValid = comp.optBoolean("timeValid");
                    m.day = m.timeValid ? when.withZoneSameInstant(zone).toLocalDate()
                            : when.withZoneSameInstant(ZoneId.of("America/New_York")).toLocalDate();
                    if (m.day.isBefore(monday) || m.day.isAfter(sunday)) continue;
                    m.court = comp.optJSONObject("venue") != null ? comp.getJSONObject("venue").optString("court") : "";
                    JSONArray cs = comp.optJSONArray("competitors");
                    m.a = player(cs, 0);
                    m.b = player(cs, 1);
                    m.aLast = surname(m.a);
                    m.bLast = surname(m.b);
                    m.roundSize = size;
                    m.roundName = roundName(rname, size, allRounds);
                    m.women = women;
                    if (state.equals("STATUS_FINAL") || state.equals("STATUS_RETIRED") || state.equals("STATUS_WALKOVER")) {
                        m.timeLabel = "done";
                        int[] w = new int[1];
                        m.res = score(cs, state, w);
                        m.winner = w[0];
                    }
                    matches.add(m);
                }
            }
        }

        // The official order of play, for ATP events that have one, at most one sheet per tournament per refresh.
        Map<String, OrderOfPlay> sheets = new HashMap<>();
        for (Match m : matches) {
            int atp = m.tournament.optInt("atp");
            String label = m.tournament.optString("label");
            if (atp <= 0 || m.women || sheets.containsKey(label)) continue;
            OrderOfPlay op = null;
            try {
                byte[] pdf = getBytes("https://www.protennislive.com/posting/" + m.day.getYear() + "/" + atp + "/op.pdf");
                op = OrderOfPlay.parse(pdf);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "order of play for " + label + ": " + e);
            }
            sheets.put(label, op);
        }

        // Times.
        for (Match m : matches) {
            if ("done".equals(m.timeLabel)) {
                m.order = minutes(m.when);
                continue;
            }
            ZoneId tz = tz(m.tournament);
            LocalDate tournamentDay = m.when.withZoneSameInstant(tz).toLocalDate();
            OrderOfPlay op = sheets.get(m.tournament.optString("label"));
            OrderOfPlay.Slot slot = null;
            if (op != null && op.day != null && m.timeValid && op.day.equals(tournamentDay)) slot = op.find(m.aLast, m.bLast);
            if (slot != null) {
                if (slot.kind == OrderOfPlay.Kind.AFTER) {
                    m.timeLabel = "after match";
                    m.order = minutes(m.when);
                } else {
                    ZonedDateTime local = ZonedDateTime.of(LocalDateTime.of(op.day, slot.time), tz).withZoneSameInstant(zone);
                    m.day = local.toLocalDate();
                    m.order = local.getHour() * 60 + local.getMinute();
                    String hm = String.format(Locale.ROOT, "%02d:%02d", local.getHour(), local.getMinute());
                    m.timeLabel = slot.kind == OrderOfPlay.Kind.NOT_BEFORE ? "NB " + hm : hm;
                }
            } else if (m.timeValid && !m.court.isEmpty()) {
                // No sheet: the first match on its court that day starts at ESPN's time; the others only follow.
                List<Match> same = courtDay.getOrDefault(m.tournament.optString("label") + "|" + m.court + "|" + tournamentDay,
                        Collections.emptyList());
                boolean first = true;
                for (Match o : same) if (o.when.isBefore(m.when)) { first = false; break; }
                ZonedDateTime local = m.when.withZoneSameInstant(zone);
                m.order = local.getHour() * 60 + local.getMinute();
                m.timeLabel = first ? String.format(Locale.ROOT, "%02d:%02d", local.getHour(), local.getMinute()) : "after match";
            } else {
                m.order = 24 * 60 + 1;
                m.timeLabel = "time TBA";
            }
        }

        // Rows: known pairs one by one; matches whose players are not known yet, one row per day and round.
        List<Item> out = new ArrayList<>();
        Map<String, Integer> pending = new LinkedHashMap<>();
        Map<String, Match> pendingFirst = new HashMap<>();
        for (Match m : matches) {
            String sub = m.tournament.optString("label") + (m.women ? " · Women" : "") + " · " + m.roundName;
            if (m.a.equals("TBD") || m.b.equals("TBD")) {
                String key = m.day + "|" + sub;
                pending.merge(key, 1, Integer::sum);
                pendingFirst.putIfAbsent(key, m);
                continue;
            }
            if ("done".equals(m.timeLabel)) {
                if (m.winner >= 0 && !m.res.isEmpty()) {
                    String w = m.winner == 0 ? m.aLast : m.bLast, l = m.winner == 0 ? m.bLast : m.aLast;
                    out.add(new Item(m.day, 't', m.order, "played", w + " d. " + l, sub, m.res));
                } else {
                    out.add(new Item(m.day, 't', m.order, "played", m.aLast + " vs " + m.bLast, sub));
                }
            } else {
                out.add(new Item(m.day, 't', m.order, m.timeLabel, m.aLast + " vs " + m.bLast, sub));
            }
        }
        for (Map.Entry<String, Integer> e : pending.entrySet()) {
            Match m = pendingFirst.get(e.getKey());
            int n = e.getValue();
            String title = m.roundName;
            String sub = m.tournament.optString("label") + (m.women ? " · Women" : "")
                    + (n > 1 ? " · " + n + " matches" : "");
            out.add(new Item(m.day, 't', 24 * 60 + 2, "time TBA", title, sub));
        }
        return out;
    }

    /** Adds the listed events ESPN has on that day; returns the latest day they (or covered) reach. */
    private LocalDate probe(JSONObject list, LocalDate day, Map<String, JSONObject> events, LocalDate covered)
            throws IOException, JSONException {
        JSONObject sb = new JSONObject(get("https://site.api.espn.com/apis/site/v2/sports/tennis/atp/scoreboard?dates="
                + day.format(DateTimeFormatter.BASIC_ISO_DATE)));
        LocalDate latest = day.isAfter(covered) ? day : covered;
        JSONArray ev = sb.optJSONArray("events");
        if (ev == null) return latest;
        for (int i = 0; i < ev.length(); i++) {
            JSONObject e = ev.getJSONObject(i);
            if (match(list, e.optString("name")) == null) continue;
            events.put(e.optString("id"), e);
            LocalDate end = espnDay(e.optString("endDate"));
            if (end != null && end.isAfter(latest)) latest = end;
        }
        return latest;
    }

    /**
     * A finished match's score from ESPN's competitors, from the winner's side: "7-6(5) 3-6 6-2", with " ret." when
     * the loser retired, "w/o" for a walkover. winner[0] gets the winner's index in ESPN's order (as player()
     * reads it: "order" 1, then 2), or -1 with "" when ESPN does not say who won.
     */
    static String score(JSONArray cs, String state, int[] winner) {
        winner[0] = -1;
        if (cs == null || cs.length() < 2) return "";
        JSONObject[] side = new JSONObject[2];
        for (int i = 0; i < 2; i++) {
            side[i] = cs.optJSONObject(i);
            for (int k = 0; k < cs.length(); k++) {
                JSONObject x = cs.optJSONObject(k);
                if (x != null && x.optInt("order") == i + 1) side[i] = x;
            }
            if (side[i] == null) return "";
        }
        int w = side[0].optBoolean("winner") ? 0 : side[1].optBoolean("winner") ? 1 : -1;
        if (w < 0) return "";
        winner[0] = w;
        if (state.equals("STATUS_WALKOVER")) return "w/o";
        JSONArray ws = side[w].optJSONArray("linescores"), ls = side[1 - w].optJSONArray("linescores");
        int n = Math.min(ws != null ? ws.length() : 0, ls != null ? ls.length() : 0);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) {
            JSONObject a = ws.optJSONObject(i), b = ls.optJSONObject(i);
            if (a == null || b == null) continue;
            int ga = a.optInt("value"), gb = b.optInt("value");
            if (s.length() > 0) s.append(' ');
            s.append(ga).append('-').append(gb);
            // A tie-break set: the loser of the set's points in brackets, 7-6(5).
            if (Math.abs(ga - gb) == 1 && Math.max(ga, gb) == 7 && a.has("tiebreak") && b.has("tiebreak")) {
                s.append('(').append(Math.min(a.optInt("tiebreak"), b.optInt("tiebreak"))).append(')');
            }
        }
        if (s.length() == 0) return state.equals("STATUS_RETIRED") ? "ret." : "";
        if (state.equals("STATUS_RETIRED")) s.append(" ret.");
        return s.toString();
    }

    private static String roundName(String espn, int size, boolean allRounds) {
        if (size == 1) return "Final";
        if (size == 2) return "Semifinals";
        if (size == 4) return "Quarterfinals";
        if (size == 8 && !allRounds) return "Round of 16";
        return espn;
    }

    private static String player(JSONArray cs, int i) {
        if (cs == null || cs.length() <= i) return "TBD";
        JSONObject c = cs.optJSONObject(i);
        // ESPN lists the home side first ("order" 1); keep its order.
        for (int k = 0; k < cs.length(); k++) {
            JSONObject x = cs.optJSONObject(k);
            if (x != null && x.optInt("order") == i + 1) c = x;
        }
        JSONObject a = c != null ? c.optJSONObject("athlete") : null;
        String n = a != null ? a.optString("displayName", "TBD") : "TBD";
        return n.isEmpty() ? "TBD" : n;
    }

    /** "Alex de Minaur" -> "de Minaur", "Felix Auger-Aliassime" -> "Auger-Aliassime", "Ben Shelton" -> "Shelton". */
    static String surname(String full) {
        if (full == null || full.equals("TBD")) return "TBD";
        String[] w = full.trim().split("\\s+");
        if (w.length == 1) return w[0];
        int i = w.length - 1;
        while (i > 1 && Character.isLowerCase(w[i - 1].charAt(0))) i--;   // de, van, da, del
        StringBuilder s = new StringBuilder();
        for (int k = i; k < w.length; k++) s.append(k > i ? " " : "").append(w[k]);
        // Double surnames written as two words ("Davidovich Fokina", "Carreno Busta") keep the last two words.
        if (i == w.length - 1 && w.length >= 3 && DOUBLE.contains(w[w.length - 2] + " " + w[w.length - 1])) {
            return w[w.length - 2] + " " + w[w.length - 1];
        }
        return s.toString();
    }

    private static final Set<String> DOUBLE = new HashSet<>(java.util.Arrays.asList(
            "Davidovich Fokina", "Carreno Busta", "Bautista Agut", "Mpetshi Perricard", "Barrios Vera", "Haddad Maia", "Van Assche"));

    private static ZoneId tz(JSONObject t) {
        try {
            return ZoneId.of(t.optString("tz", "UTC"));
        } catch (RuntimeException e) {
            return ZoneId.of("UTC");
        }
    }

    private int minutes(ZonedDateTime t) {
        ZonedDateTime l = t.withZoneSameInstant(zone);
        return l.getHour() * 60 + l.getMinute();
    }

    /** ESPN writes "2026-11-03T18:00Z" (no seconds). */
    private static ZonedDateTime espnTime(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            if (s.length() == 17 && s.endsWith("Z")) s = s.substring(0, 16) + ":00Z";
            return Instant.parse(s).atZone(ZoneId.of("UTC"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** An ESPN event boundary ("2026-11-09T04:59Z") as the US Eastern calendar day ESPN means by it. */
    private static LocalDate espnDay(String s) {
        ZonedDateTime t = espnTime(s);
        return t == null ? null : t.minusMinutes(1).withZoneSameInstant(ZoneId.of("America/New_York")).toLocalDate();
    }

    // ---- cups (by hand) ------------------------------------------------------------------------------------

    private static List<Item> cups(JSONObject list, LocalDate monday, LocalDate sunday) {
        List<Item> out = new ArrayList<>();
        JSONArray a = list.optJSONArray("cups");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c == null) continue;
            LocalDate d;
            try {
                d = LocalDate.parse(c.optString("date"));
            } catch (RuntimeException e) {
                continue;
            }
            if (d.isBefore(monday) || d.isAfter(sunday)) continue;
            out.add(new Item(d, 't', 24 * 60 + 3, c.optString("time", "time TBA"), c.optString("stage", c.optString("label")),
                    c.optString("label")));
        }
        return out;
    }

    private static void cupMarks(JSONObject list, LocalDate from, LocalDate to, Map<LocalDate, String> marks) {
        JSONArray a = list.optJSONArray("cups");
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            try {
                LocalDate d = LocalDate.parse(c.optString("date"));
                if (!d.isBefore(from) && !d.isAfter(to)) addMark(marks, d, 't');
            } catch (RuntimeException ignored) {
                // a row without a usable date
            }
        }
    }

    // ---- the month: tournament spans from ESPN's season list -----------------------------------------------

    /** {"at": ms, "events": [{"name", "start", "end"}]} for the listed tournaments of the year, cached a week. */
    private JSONObject season(JSONObject list, int year, JSONObject oldRaw) throws IOException, JSONException {
        JSONObject cached = oldRaw != null ? oldRaw.optJSONObject("season") : null;
        if (cached != null && cached.optInt("year") == year
                && System.currentTimeMillis() - cached.optLong("at") < SEASON_MS) return cached;
        JSONArray outEvents = new JSONArray();
        for (String league : new String[]{"atp"}) {
            JSONObject idx = new JSONObject(get("https://sports.core.api.espn.com/v2/sports/tennis/leagues/" + league
                    + "/events?dates=" + year + "&limit=200"));
            JSONArray items = idx.optJSONArray("items");
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                String ref = items.getJSONObject(i).optString("$ref").replace("http://", "https://");
                try {
                    JSONObject e = new JSONObject(get(ref));
                    JSONObject t = match(list, e.optString("name"));
                    if (t == null) continue;
                    outEvents.put(new JSONObject().put("name", e.optString("name")).put("label", t.optString("label"))
                            .put("tier", t.optString("tier")).put("start", e.optString("date")).put("end", e.optString("endDate")));
                } catch (IOException | JSONException e) {
                    Log.w(TAG, "season event " + ref + ": " + e);
                }
            }
        }
        return new JSONObject().put("year", year).put("at", System.currentTimeMillis()).put("events", outEvents);
    }

    /**
     * Marks for the weeks other than this one: the days from the round of 16 on, estimated from the tournament's
     * span (a Slam's last 8 days, a two-week Masters' last 5, a one-week event's last 4, the ATP Finals all week).
     * This week's marks come from the real matches.
     */
    private static void seasonMarks(JSONObject season, LocalDate from, LocalDate to, LocalDate monday, LocalDate sunday,
                                    Map<LocalDate, String> marks) {
        JSONArray a = season.optJSONArray("events");
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            LocalDate start = espnDay(e.optString("start")), end = espnDay(e.optString("end"));
            if (start == null || end == null) continue;
            // espnDay takes a minute off, which turns a start at 04:00Z into the day before: put it back.
            start = start.plusDays(1);
            // ESPN dates the ATP Finals from the Saturday before; play starts on Sunday.
            if (e.optString("tier").equals("finals")) start = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            long span = ChronoUnit.DAYS.between(start, end) + 1;
            String tier = e.optString("tier");
            int last = tier.equals("finals") || tier.equals("cup") ? (int) span
                    : tier.equals("slam") ? 8 : span >= 10 ? 5 : 4;
            for (LocalDate d = end.minusDays(last - 1); !d.isAfter(end); d = d.plusDays(1)) {
                if (d.isBefore(from) || d.isAfter(to)) continue;
                if (!d.isBefore(monday) && !d.isAfter(sunday)) continue;   // this week: real matches only
                addMark(marks, d, 't');
            }
        }
    }

    // ---- http ----------------------------------------------------------------------------------------------

    private static String get(String url) throws IOException {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Encoding", "identity");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " " + url);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 8 * 1024 * 1024) throw new IOException("response too large");
            }
            return out.toByteArray();
        }
    }
}

package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Results for the sport calendar, from answers shaped like ESPN's and Jolpica's (taken from real 2026 matches). */
@RunWith(RobolectricTestRunner.class)
public class SportResultsTest {

    /** One ESPN competitor: order, winner, then games and tie-break points per set (tb -1 = none). */
    private static JSONObject side(int order, boolean winner, int... sets) throws Exception {
        JSONArray ls = new JSONArray();
        for (int i = 0; i < sets.length; i += 2) {
            JSONObject s = new JSONObject().put("value", (double) sets[i]);
            if (sets[i + 1] >= 0) s.put("tiebreak", sets[i + 1]);
            ls.put(s);
        }
        return new JSONObject().put("order", order).put("winner", winner).put("linescores", ls);
    }

    private static String score(String state, int[] w, JSONObject... cs) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject c : cs) a.put(c);
        return SportFeed.score(a, state, w);
    }

    @Test
    public void straightSetsFromTheWinnersSide() throws Exception {
        int[] w = new int[1];
        // Paris 2026 SF: Ruud listed first, Alcaraz won 6-4 6-4.
        String s = score("STATUS_FINAL", w, side(1, false, 4, -1, 4, -1), side(2, true, 6, -1, 6, -1));
        assertEquals("6-4 6-4", s);
        assertEquals(1, w[0]);
    }

    @Test
    public void tieBreakShowsTheLosersPoints() throws Exception {
        int[] w = new int[1];
        // Paris 2026 QF: Alcaraz d. Fritz 6-7(10) 7-5 6-3.
        String s = score("STATUS_FINAL", w, side(1, true, 6, 10, 7, -1, 6, -1), side(2, false, 7, 12, 5, -1, 3, -1));
        assertEquals("6-7(10) 7-5 6-3", s);
        assertEquals(0, w[0]);
    }

    @Test
    public void retirementKeepsTheScoreSoFar() throws Exception {
        int[] w = new int[1];
        // Paris 2026 R1: Khachanov d. Shapovalov 6-3 6-7(6) 1-4 ret.
        String s = score("STATUS_RETIRED", w, side(1, true, 6, -1, 6, 6, 1, -1), side(2, false, 3, -1, 7, 8, 4, -1));
        assertEquals("6-3 6-7(6) 1-4 ret.", s);
    }

    @Test
    public void walkover() throws Exception {
        int[] w = new int[1];
        assertEquals("w/o", score("STATUS_WALKOVER", w, side(1, true), side(2, false)));
        assertEquals(0, w[0]);
    }

    @Test
    public void noWinnerNoScore() throws Exception {
        int[] w = new int[1];
        assertEquals("", score("STATUS_FINAL", w, side(1, false, 6, -1), side(2, false, 4, -1)));
        assertEquals(-1, w[0]);
    }

    private static JSONObject jolpica(String... codes) throws Exception {
        JSONArray results = new JSONArray();
        for (int i = 0; i < codes.length; i++) {
            JSONObject d = new JSONObject().put("familyName", "Hülkenberg");
            if (!codes[i].isEmpty()) d.put("code", codes[i]);
            results.put(new JSONObject().put("position", String.valueOf(i + 1)).put("Driver", d));
        }
        JSONArray races = new JSONArray();
        if (codes.length > 0) races.put(new JSONObject().put("Results", results));
        return new JSONObject().put("MRData", new JSONObject().put("RaceTable", new JSONObject().put("Races", races)));
    }

    @Test
    public void podiumCodes() throws Exception {
        // Mexico City 2026.
        assertEquals("VER HAM ANT", SportFeed.podium(jolpica("VER", "HAM", "ANT")));
    }

    @Test
    public void podiumWithoutACodeUsesTheFamilyName() throws Exception {
        assertEquals("VER HÜL ANT", SportFeed.podium(jolpica("VER", "", "ANT")));
    }

    @Test
    public void noResultsYet() throws Exception {
        assertEquals("", SportFeed.podium(jolpica()));
    }
}

package com.gathin.tvhome;

import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Typeface;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;

/**
 * Draws the sport calendar's week list to PNG files on the PC, over a screenshot of Home taken from the TV, so a
 * change can be looked at before it goes to the TV. Off unless asked for, as it uses the network:
 *
 *   ./gradlew testReleaseUnitTest --tests '*SportPreviewTest' -Dsport.preview=/some/dir
 *
 * The dir holds home.png (adb screencap of Home, 1920x1080); the previews are written next to it.
 * The positions are the ones MainActivity gives the view on the TCL 65C735 (1080p, density 2).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SportPreviewTest {

    private static final int L = 590, T = 140, R = 1390, B = 532;   // the list's area on the screenshot
    private File dir;
    private boolean allSets;
    private Context ctx;
    private Typeface regular, medium;

    @Before
    public void setUp() {
        String d = System.getProperty("sport.preview", "");
        assumeFalse("set -Dsport.preview=<dir> to draw the previews", d.isEmpty());
        dir = new File(d);
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Nicosia"));
        ctx = RuntimeEnvironment.getApplication();
        File font = new File("src/main/res/font/outfit.ttf");
        regular = new Typeface.Builder(font).setFontVariationSettings("'wght' 400").build();
        medium = new Typeface.Builder(font).setFontVariationSettings("'wght' 500").build();
    }

    /** This week as the TV would fetch it now, plus last Sunday's race (the latest real result) on Monday. */
    @Test
    public void realWeek() throws Exception {
        SportFeed.Data live = new SportFeed(ctx).refresh();
        if (live == null) throw new AssertionError("nothing fetched");
        String mexico = SportFeed.podium(new JSONObject(get(
                "https://api.jolpi.ca/ergast/f1/2026/19/results/?format=json&limit=3")));
        List<SportFeed.Item> items = new ArrayList<>(live.items);
        items.add(0, new SportFeed.Item(monday(), 'f', 22 * 60, "22:00", "Mexico City GP",
                "Mexico · Mexico City", mexico));
        SportFeed.Data d = new SportFeed.Data(live.fetchedAt, items, live.marks);
        draw(d, false, "real-winner.png");
        draw(d, true, "real-podium.png");
    }

    /** A Slam-like week: five sets with tie-breaks, four sets, a retirement, a walkover, and a race. */
    @Test
    public void slamWeek() throws Exception {
        LocalDate mon = monday();
        List<SportFeed.Item> items = new ArrayList<>();
        String ao = "Australian Open · ";
        items.add(new SportFeed.Item(mon, 't', 9 * 60, "played", "Sinner d. Alcaraz", ao + "Final",
                "6-7(5) 7-6(8) 6-4 3-6 7-6(10)"));
        items.add(new SportFeed.Item(mon, 't', 10 * 60, "played", "Djokovic d. Zverev", ao + "Semifinals",
                "7-5 4-6 6-3 7-6(4)"));
        items.add(new SportFeed.Item(mon, 't', 11 * 60, "played", "Khachanov d. Shapovalov", ao + "Quarterfinals",
                "6-3 6-7(6) 1-4 ret."));
        items.add(new SportFeed.Item(mon.plusDays(1), 't', 9 * 60, "played", "Alcaraz d. Berrettini", ao + "Round of 16",
                "w/o"));
        items.add(new SportFeed.Item(mon.plusDays(1), 't', 11 * 60, "played", "Fritz d. Paul", ao + "Round of 16",
                "6-4 6-0"));
        items.add(new SportFeed.Item(mon.plusDays(2), 'f', 17 * 60, "17:00", "Brazilian GP", "Brazil · Interlagos",
                "NOR VER LEC"));
        items.add(new SportFeed.Item(mon.plusDays(6), 't', 10 * 60 + 30, "10:30", "Sinner vs Medvedev", ao + "Final"));
        SportFeed.Data d = new SportFeed.Data(System.currentTimeMillis(), items, new HashMap<>());
        draw(d, false, "slam-winner.png");
        draw(d, true, "slam-podium.png");
        allSets = true;
        draw(d, true, "slam-all-sets.png");
        allSets = false;
    }

    private static LocalDate monday() {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private void draw(SportFeed.Data d, boolean podium, String name) throws Exception {
        Bitmap shot = BitmapFactory.decodeFile(new File(dir, "home.png").getPath());
        Bitmap bmp = shot.copy(Bitmap.Config.ARGB_8888, true);
        // Wipe the old list: the background is a smooth gradient, so blend each column from above to below it.
        for (int x = L; x < R; x++) {
            int a = shot.getPixel(x, T - 4), b = shot.getPixel(x, B + 4);
            for (int y = T; y < B; y++) {
                float f = (y - T) / (float) (B - T);
                bmp.setPixel(x, y, mix(a, b, f));
            }
        }
        SportCalendarView v = new SportCalendarView(ctx, regular, medium);
        v.setPodium(podium);
        v.allSets = allSets;
        v.setData(d);
        v.measure(1920, 1080);
        v.layout(0, 0, 1920, 1080);
        v.anchor(115, 233, 600, 158, 1370);
        Bitmap clean = bmp.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(clean);
        c.save();
        c.clipRect(L, T, R, B);
        v.draw(c);
        c.restore();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            clean.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        // The grid too, without the screenshot, to check the positions against it.
        Bitmap full = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
        full.eraseColor(0xFF161616);
        v.draw(new Canvas(full));
        try (FileOutputStream out = new FileOutputStream(new File(dir, "full-" + name))) {
            full.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private static int mix(int a, int b, float f) {
        int r = 0xFF000000;
        for (int s = 0; s <= 16; s += 8) {
            int ca = (a >> s) & 0xFF, cb = (b >> s) & 0xFF;
            r |= Math.round(ca + (cb - ca) * f) << s;
        }
        return r;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Mozilla/5.0 TvHome preview");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

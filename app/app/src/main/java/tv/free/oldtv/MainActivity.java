package tv.free.oldtv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Old-TV style live channel player.
 * Up/Down = next/previous channel, OK = channel list, hold OK = favourite,
 * Left/Right in the list = jump a group, hold Right on TV = start-on-power-on toggle.
 */
public class MainActivity extends Activity {

    static final String LIST_URL = "https://raw.githubusercontent.com/znxare/free-tv-channels/main/channels.json";
    private static final int AMBER = 0xFFF5A623;
    private static final long OSD_MS = 3500, SKIP_MS = 8000;
    private static final String FAV_GROUP = "Favourites";

    static final class Channel {
        final int n; final String name, group, url;
        Channel(int n, String name, String group, String url) { this.n = n; this.name = name; this.group = group; this.url = url; }
    }

    private final List<Channel> channels = new ArrayList<>();
    private final List<Integer> rows = new ArrayList<>();   // list rows: favourites first, then every channel (indexes into channels)
    private int favCount;
    private final Set<String> favs = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private SharedPreferences prefs;
    private int current, sel, dir = 1, skipped;
    private boolean okLongPressed, rightLongPressed;

    private SurfaceView surface;
    private View osd, hint;
    private TextView osdNum, osdName, osdGroup, message, toast;
    private ListView list;
    private ChannelAdapter adapter;

    private final Runnable hideOsd = () -> { osd.setVisibility(View.GONE); hint.setVisibility(View.GONE); };
    private final Runnable hideToast = () -> toast.setVisibility(View.GONE);
    private final Runnable skip = this::skipDeadChannel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        favs.addAll(prefs.getStringSet("favs", new HashSet<>()));
        parseInto(channels, readLocalList());
        buildUi();
        String last = prefs.getString("lastName", null);
        current = Math.max(0, indexOf(last));
        rebuildRows();
        refreshListFromInternet();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Small buffers keep memory low, a short start buffer and a low first-bitrate guess make
        // channels start fast; quality then climbs to what the Wi-Fi can carry.
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(4000, 15000, 800, 2000)
                .build();
        DefaultBandwidthMeter meter = new DefaultBandwidthMeter.Builder(this)
                .setInitialBitrateEstimate(1_500_000)
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(load).setBandwidthMeter(meter).build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) { ui.removeCallbacks(skip); skipped = 0; showMessage(null); }
            }
            @Override public void onPlayerError(PlaybackException e) {
                ui.removeCallbacks(skip);
                ui.postDelayed(skip, 700);   // a moment to show "off-air" before moving on
                showMessage("Channel off-air…");
            }
        });
        if (!channels.isEmpty()) tune(current);
        else showMessage("Couldn't load the channel list.");
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
    }

    // ---- channel list: cached copy → built-in copy, refreshed from the internet in the background ----

    private File cacheFile() { return new File(getFilesDir(), "channels.json"); }

    private String readLocalList() {
        try {
            File f = cacheFile();
            if (f.length() > 2) return readAll(new java.io.FileInputStream(f));
        } catch (Exception ignored) { }
        try { return readAll(getAssets().open("channels.json")); } catch (Exception e) { return "[]"; }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int r;
            while ((r = s.read(b)) > 0) out.write(b, 0, r);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean parseInto(List<Channel> out, String json) {
        try {
            JSONArray a = new JSONArray(json);
            List<Channel> tmp = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String url = o.getString("url");
                if (url.contains("youtube.com")) continue;   // the light app plays direct streams only
                tmp.add(new Channel(o.getInt("n"), o.getString("name"), o.getString("group"), url));
            }
            if (tmp.isEmpty()) return false;
            out.clear(); out.addAll(tmp);
            return true;
        } catch (Exception e) { return false; }
    }

    private void refreshListFromInternet() {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(LIST_URL).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                String json = readAll(c.getInputStream());
                List<Channel> fresh = new ArrayList<>();
                if (!parseInto(fresh, json)) return;
                try (FileOutputStream f = new FileOutputStream(cacheFile())) { f.write(json.getBytes(StandardCharsets.UTF_8)); }
                ui.post(() -> applyNewList(fresh));
            } catch (Exception ignored) { /* offline: keep the list we have */ }
        }).start();
    }

    private void applyNewList(List<Channel> fresh) {
        if (sameList(fresh)) return;
        String playing = channels.isEmpty() ? null : channels.get(current).name;
        channels.clear(); channels.addAll(fresh);
        int i = indexOf(playing);
        rebuildRows();
        if (i >= 0) current = i;               // same channel keeps playing
        else tune(Math.min(current, channels.size() - 1));
        flash("Channel list updated: " + channels.size() + " channels");
    }

    private boolean sameList(List<Channel> other) {
        if (other.size() != channels.size()) return false;
        for (int i = 0; i < other.size(); i++) {
            Channel a = other.get(i), b = channels.get(i);
            if (a.n != b.n || !a.name.equals(b.name) || !a.url.equals(b.url) || !a.group.equals(b.group)) return false;
        }
        return true;
    }

    private int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).name.equals(name)) return i;
        return -1;
    }

    private void rebuildRows() {
        rows.clear();
        for (int i = 0; i < channels.size(); i++) if (favs.contains(channels.get(i).name)) rows.add(i);
        favCount = rows.size();
        for (int i = 0; i < channels.size(); i++) rows.add(i);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // ---- tuning ----

    private void tune(int index) {
        if (channels.isEmpty() || player == null) return;
        int n = channels.size();
        current = (index % n + n) % n;
        prefs.edit().putString("lastName", channels.get(current).name).apply();
        showOsd();
        showMessage("Tuning…");
        ui.removeCallbacks(skip);
        ui.postDelayed(skip, SKIP_MS);
        player.setMediaItem(MediaItem.fromUri(Uri.parse(channels.get(current).url)));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /** Channel didn't start in time or failed: move on in the same direction, like an old TV with no signal. */
    private void skipDeadChannel() {
        if (++skipped >= channels.size()) {
            ui.removeCallbacks(skip);
            showMessage("No channels are playing. Check the internet connection.");
            skipped = 0;
            return;
        }
        flash(channels.get(current).name + " is off-air, skipping");
        tune(current + dir);
    }

    // ---- remote: every button handled here, so one press never does two things ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean ours = ok || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || (code == KeyEvent.KEYCODE_BACK && listOpen());
        if (!ours || channels.isEmpty()) return super.dispatchKeyEvent(e);
        boolean open = listOpen();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        // OK acts on release, so holding it can mean "favourite" instead.
        if (ok) {
            if (down && e.getRepeatCount() == 0) okLongPressed = false;
            else if (down && (e.isLongPress() || e.getRepeatCount() >= 8) && !okLongPressed) { okLongPressed = true; toggleFavourite(open ? rows.get(sel) : current); }
            else if (!down && !okLongPressed) { if (open) { closeList(); tune(rows.get(sel)); } else openList(); }
            return true;
        }
        // Hold Right while watching = start-on-power-on switch.
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && !open) {
            if (down && e.getRepeatCount() == 0) rightLongPressed = false;
            else if (down && (e.isLongPress() || e.getRepeatCount() >= 8) && !rightLongPressed) { rightLongPressed = true; toggleAutostart(); }
            else if (!down && !rightLongPressed) showOsd();
            return true;
        }
        if (!down) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (open) moveSel(sel - 1); else { dir = 1; skipped = 0; tune(current + 1); }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (open) moveSel(sel + 1); else { dir = -1; skipped = 0; tune(current - 1); }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (open) jumpGroup(-1); else showOsd();
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                jumpGroup(1);
                break;
            case KeyEvent.KEYCODE_BACK:
                closeList();
                break;
        }
        return true;
    }

    private void toggleFavourite(int index) {
        String name = channels.get(index).name;
        boolean added = favs.add(name);
        if (!added) favs.remove(name);
        prefs.edit().putStringSet("favs", new HashSet<>(favs)).apply();
        int keep = listOpen() ? rows.get(sel) : -1;
        rebuildRows();
        if (keep >= 0) sel = Math.min(rows.size() - 1, favCount + keep);   // stay on the same channel in the main list
        flash(added ? "★ " + name + " added to Favourites" : name + " removed from Favourites");
    }

    private void toggleAutostart() {
        boolean on = !prefs.getBoolean("autostart", false);
        prefs.edit().putBoolean("autostart", on).apply();
        flash(on ? "Start on power-on: ON" : "Start on power-on: OFF");
    }

    private boolean listOpen() { return list.getVisibility() == View.VISIBLE; }

    private void openList() {
        sel = favCount + current;
        adapter.notifyDataSetChanged();
        list.setVisibility(View.VISIBLE);
        list.setSelectionFromTop(sel, list.getHeight() / 3);
        ui.removeCallbacks(hideOsd);
        osd.setVisibility(View.GONE);
        hint.setVisibility(View.GONE);
    }

    private void closeList() { list.setVisibility(View.GONE); }

    private void moveSel(int to) {
        int n = rows.size();
        sel = (to % n + n) % n;
        adapter.notifyDataSetChanged();
        int first = list.getFirstVisiblePosition(), last = list.getLastVisiblePosition();
        if (sel <= first || sel >= last) list.setSelectionFromTop(sel, sel <= first ? 0 : list.getHeight() * 2 / 3);
    }

    private String rowGroup(int r) { return r < favCount ? FAV_GROUP : channels.get(rows.get(r)).group; }

    /** Jump the highlight to the first row of the next/previous group. */
    private void jumpGroup(int d) {
        int n = rows.size(), i = sel;
        String g = rowGroup(sel);
        while (i >= 0 && i < n && rowGroup(i).equals(g)) i += d;
        if (i < 0) i = n - 1;
        if (i >= n) i = 0;
        if (d < 0) { String pg = rowGroup(i); while (i > 0 && rowGroup(i - 1).equals(pg)) i--; }
        sel = i;
        adapter.notifyDataSetChanged();
        list.setSelectionFromTop(sel, 0);
    }

    // ---- on-screen display ----

    private void showOsd() {
        Channel c = channels.get(current);
        osdNum.setText(String.valueOf(c.n));
        osdName.setText((favs.contains(c.name) ? "★ " : "") + c.name);
        osdGroup.setText(c.group);
        osd.setVisibility(View.VISIBLE);
        hint.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideOsd);
        ui.postDelayed(hideOsd, OSD_MS);
    }

    private void showMessage(String text) {
        message.setText(text == null ? "" : text);
        message.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void flash(String text) {
        toast.setText(text);
        toast.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 2500);
    }

    // ---- views (built in code: no layout files, keeps the app tiny) ----

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable panel(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        message = text(22, 0xFFBBBBBB, false);
        message.setGravity(Gravity.CENTER);
        root.addView(message, new FrameLayout.LayoutParams(-1, -1));

        // Channel banner, top-left.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(panel(0xB8000000, 8));
        box.setPadding(dp(16), dp(10), dp(20), dp(10));
        osdNum = text(40, AMBER, true);
        osdNum.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        box.addView(osdNum);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(16), 0, dp(16), 0);
        osdName = text(24, Color.WHITE, true);
        osdGroup = text(14, 0xFFBBBBBB, false);
        names.addView(osdName);
        names.addView(osdGroup);
        box.addView(names);
        TextView live = text(12, Color.WHITE, true);
        live.setText("LIVE");
        live.setBackground(panel(0xFFE24B4A, 4));
        live.setPadding(dp(8), dp(2), dp(8), dp(2));
        box.addView(live);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(40), dp(32), 0, 0);
        osd = box;
        root.addView(box, lp);

        // Short notices (favourite added, channel skipped, list updated), top-right.
        toast = text(18, Color.WHITE, false);
        toast.setBackground(panel(0xD0000000, 8));
        toast.setPadding(dp(16), dp(10), dp(16), dp(10));
        toast.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        tp.setMargins(0, dp(32), dp(40), 0);
        root.addView(toast, tp);

        // Key hint, bottom.
        TextView h = text(14, 0xFFDDDDDD, false);
        h.setText("▲ ▼  channel      OK  list      hold OK  favourite      ◀ ▶  jump group (in list)      hold ▶  start on power-on");
        h.setBackgroundColor(0xB8000000);
        h.setPadding(dp(40), dp(10), dp(40), dp(12));
        hint = h;
        root.addView(h, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Channel list over the left third; the activity moves the highlight itself.
        list = new ListView(this);
        list.setBackgroundColor(0xF0080A0C);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setFocusable(false);
        list.setVerticalScrollBarEnabled(false);
        list.setVisibility(View.GONE);
        adapter = new ChannelAdapter();
        list.setAdapter(adapter);
        root.addView(list, new FrameLayout.LayoutParams((int) (getResources().getDisplayMetrics().widthPixels * 0.36f), -1, Gravity.START));

        setContentView(root);
        hideOsd.run();
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return channels.get(rows.get(p)); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View v, ViewGroup parent) {
            // Row = optional group heading, then [number | name]; only the [number | name] line highlights.
            LinearLayout row;
            if (v == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                TextView band = text(12, 0xFF8A857C, true);
                band.setPadding(dp(20), dp(12), dp(20), dp(2));
                LinearLayout line = new LinearLayout(MainActivity.this);
                line.setPadding(dp(12), dp(6), dp(20), dp(6));
                TextView num = text(18, AMBER, true);
                num.setGravity(Gravity.END);
                TextView name = text(18, Color.WHITE, false);
                name.setPadding(dp(16), 0, 0, 0);
                name.setSingleLine(true);
                line.addView(num, new LinearLayout.LayoutParams(dp(36), -2));
                line.addView(name);
                row.addView(band);
                row.addView(line);
            } else row = (LinearLayout) v;
            int ci = rows.get(p);
            Channel c = channels.get(ci);
            TextView band = (TextView) row.getChildAt(0);
            LinearLayout line = (LinearLayout) row.getChildAt(1);
            TextView num = (TextView) line.getChildAt(0), name = (TextView) line.getChildAt(1);
            boolean first = p == 0 || !rowGroup(p - 1).equals(rowGroup(p));
            band.setVisibility(first ? View.VISIBLE : View.GONE);
            band.setText(rowGroup(p).toUpperCase());
            num.setText(String.valueOf(c.n));
            name.setText((favs.contains(c.name) ? "★ " : "") + c.name);
            boolean on = p == sel;
            line.setBackgroundColor(on ? AMBER : Color.TRANSPARENT);
            num.setTextColor(on ? 0xFF201300 : AMBER);
            name.setTextColor(on ? 0xFF201300 : Color.WHITE);
            name.setTypeface(ci == current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            return row;
        }
    }
}

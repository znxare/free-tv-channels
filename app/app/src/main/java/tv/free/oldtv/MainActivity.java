package tv.free.oldtv;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
import androidx.media3.common.VideoSize;
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
 * Remote: Up/Down = next/previous channel, OK = channel list, hold OK = favourite,
 * Left/Right in the list = jump a group, hold Right on TV = start-on-power-on toggle.
 * Touch (phones, tablets): swipe up/down = next/previous channel, swipe right = channel list,
 * tap = info and buttons, double-tap = fit/fill the screen, hold = favourite.
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
    private boolean touch;        // phone or tablet: show touch hints and on-screen buttons
    private boolean fill;         // double-tap: crop the picture to fill the screen instead of fitting it
    private float videoAspect;    // width / height of the playing picture, 0 until known

    private FrameLayout root;
    private SurfaceView surface;
    private View osd, buttons;
    private TextView osdNum, osdName, osdGroup, message, toast, hint;
    private ListView list;
    private ChannelAdapter adapter;

    private final Runnable hideOsd = () -> {
        osd.setVisibility(View.GONE); hint.setVisibility(View.GONE); buttons.setVisibility(View.GONE);
    };
    private final Runnable hideToast = () -> toast.setVisibility(View.GONE);
    private final Runnable skip = this::skipDeadChannel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("tv", MODE_PRIVATE);
        favs.addAll(prefs.getStringSet("favs", new HashSet<>()));
        fill = prefs.getBoolean("fill", false);
        UiModeManager mode = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        boolean tv = mode != null && mode.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        touch = !tv && getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
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
            @Override public void onVideoSizeChanged(VideoSize v) {
                if (v.width > 0 && v.height > 0) { videoAspect = v.width * v.pixelWidthHeightRatio / v.height; layoutVideo(); }
            }
        });
        if (!channels.isEmpty()) tune(current);
        else showMessage("Couldn't load the channel list.");
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        // Phones: hide the status and navigation bars; a swipe from the edge brings them back for a moment.
        if (focused) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
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
        hideOsd.run();
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

    // ---- touch: phones and tablets ----

    private void zap(int d) { dir = d; skipped = 0; tune(current + d); }

    private void favouriteCurrent() {
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        toggleFavourite(current);
        showOsd();
    }

    private void toggleFill() {
        fill = !fill;
        prefs.edit().putBoolean("fill", fill).apply();
        layoutVideo();
        flash(fill ? "Picture: fill the screen" : "Picture: fit the screen");
    }

    /** Size the picture to its real shape: fit inside the screen, or crop to fill it. */
    private void layoutVideo() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        int vw = w, vh = h;
        if (videoAspect > 0) {
            boolean wider = videoAspect > (float) w / h;
            if (wider != fill) { vw = w; vh = Math.round(w / videoAspect); }
            else { vh = h; vw = Math.round(h * videoAspect); }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width == vw && lp.height == vh) return;
        lp.width = vw; lp.height = vh;
        surface.setLayoutParams(lp);
    }

    /** Switch hints and buttons to touch the first time someone touches the screen. */
    private void useTouch() {
        if (touch) return;
        touch = true;
        hint.setText(hintText());
    }

    private String hintText() {
        return touch
                ? "swipe ▲ ▼  channel      swipe ▶  list      tap  info      double-tap  fit / fill      hold  favourite"
                : "▲ ▼  channel      OK  list      hold OK  favourite      ◀ ▶  jump group (in list)      hold ▶  start on power-on";
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) { return true; }

        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            if (listOpen()) closeList();
            else if (osd.getVisibility() == View.VISIBLE) hideOsd.run();
            else showOsd();
            return true;
        }

        @Override public boolean onDoubleTap(MotionEvent e) { if (!listOpen()) toggleFill(); return true; }

        @Override public void onLongPress(MotionEvent e) { if (!listOpen() && !channels.isEmpty()) favouriteCurrent(); }

        @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
            if (a == null || channels.isEmpty()) return false;
            float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
            if (Math.max(Math.abs(dx), Math.abs(dy)) < dp(50)) return false;
            if (Math.abs(dy) > Math.abs(dx)) {
                if (listOpen()) return false;
                zap(dy < 0 ? 1 : -1);                 // swipe up = next channel, like ▲ on the remote
            } else if (dx > 0) {
                if (!listOpen()) openList();          // swipe right = list slides in from the left
            } else closeList();
            return true;
        }
    }

    private TextView roundButton(String label, String description, View.OnClickListener click) {
        TextView b = text(22, Color.WHITE, true);
        b.setText(label);
        b.setContentDescription(description);
        b.setGravity(Gravity.CENTER);
        b.setBackground(panel(0xB8000000, 28));
        b.setOnClickListener(v -> { click.onClick(v); if (osd.getVisibility() == View.VISIBLE) showOsd(); });
        return b;
    }

    // ---- on-screen display ----

    private void showOsd() {
        Channel c = channels.get(current);
        osdNum.setText(String.valueOf(c.n));
        osdName.setText((favs.contains(c.name) ? "★ " : "") + c.name);
        osdGroup.setText(c.group);
        osd.setVisibility(View.VISIBLE);
        hint.setVisibility(View.VISIBLE);
        buttons.setVisibility(touch && !listOpen() ? View.VISIBLE : View.GONE);
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
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) root.post(this::layoutVideo);
        });
        GestureDetector gestures = new GestureDetector(this, new Gestures());
        root.setOnTouchListener((v, e) -> { useTouch(); return gestures.onTouchEvent(e); });

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

        // Key or gesture hint, bottom.
        hint = text(14, 0xFFDDDDDD, false);
        hint.setText(hintText());
        hint.setBackgroundColor(0xB8000000);
        hint.setPadding(dp(40), dp(10), dp(40), dp(12));
        root.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // Touch buttons, right edge: shown with the banner on phones and tablets.
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int size = dp(56), gap = dp(8);
        View[] bs = {
                roundButton("▲", "Next channel", v -> zap(1)),
                roundButton("☰", "Channel list", v -> openList()),
                roundButton("★", "Favourite", v -> favouriteCurrent()),
                roundButton("▼", "Previous channel", v -> zap(-1)),
        };
        for (View b : bs) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(size, size);
            bp.setMargins(0, gap, 0, gap);
            col.addView(b, bp);
        }
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        cp.setMargins(0, 0, dp(32), 0);
        buttons = col;
        root.addView(col, cp);

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
        // Touch: tap a row to watch it, hold a row to add or remove it from Favourites.
        list.setOnItemClickListener((p, v, pos, id) -> { sel = pos; closeList(); dir = 1; skipped = 0; tune(rows.get(pos)); });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            sel = pos;
            list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            toggleFavourite(rows.get(pos));
            return true;
        });
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

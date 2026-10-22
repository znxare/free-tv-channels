package tv.free.oldtv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens Free TV when the projector powers on, if the viewer switched that on (hold ▶ in the app). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!context.getSharedPreferences("tv", Context.MODE_PRIVATE).getBoolean("autostart", false)) return;
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(open); } catch (Exception ignored) { /* the system may block it */ }
    }
}

package savickas_ignas.win10notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by isavi on 2017-06-14.
 */

public class NotificationDismissedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int notificationId = intent.getIntExtra("notificationId", 0);
        Intent intent1 = new Intent("NOTIFICATION_DELETED");
        intent1.putExtra("notificationId", notificationId);
        context.sendBroadcast(intent1);
    }
}
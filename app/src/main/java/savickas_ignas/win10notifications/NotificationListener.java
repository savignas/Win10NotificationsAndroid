package savickas_ignas.win10notifications;

import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.widget.Toast;


public class NotificationListener extends NotificationListenerService {

    @Override
    public void onCreate()
    {
        Toast.makeText(this, "Notification listener started", Toast.LENGTH_SHORT).show();
        super.onCreate();
    }

    @Override
    public void onDestroy()
    {
        Toast.makeText(this, "Notification listener stopped", Toast.LENGTH_SHORT).show();
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification statusBarNotification)
    {
        Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_ACTION);
        intent.putExtra("onNotificationPosted", statusBarNotification.getPackageName());
        sendBroadcast(intent);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification statusBarNotification)
    {
        Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_ACTION);
        intent.putExtra("onNotificationRemoved", statusBarNotification.getPackageName());
        sendBroadcast(intent);
    }
}

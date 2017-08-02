package savickas_ignas.win10notifications;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.widget.Toast;


public class NotificationListener extends NotificationListenerService {

    private SharedPreferences sharedPreferences;

    @Override
    public void onCreate()
    {
        super.onCreate();
        sharedPreferences = getSharedPreferences("DEVICE", Context.MODE_PRIVATE);
        Toast.makeText(this, "Notification listener started", Toast.LENGTH_SHORT).show();
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("NOTIFICATION_LISTENER", true);
        editor.apply();
        Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_STATE);
        intent.putExtra("notificationListenerState", true);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        Toast.makeText(this, "Notification listener stopped", Toast.LENGTH_SHORT).show();
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("NOTIFICATION_LISTENER", false);
        editor.apply();
        Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_STATE);
        intent.putExtra("notificationListenerState", false);
        sendBroadcast(intent);
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

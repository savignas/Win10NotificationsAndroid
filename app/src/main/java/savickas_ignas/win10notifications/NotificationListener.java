package savickas_ignas.win10notifications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.widget.Toast;

import java.util.Set;


public class NotificationListener extends NotificationListenerService {

    private SharedPreferences sharedPreferences;
    private SharedPreferences defaultSharedPreferences;

    private final BroadcastReceiver mNotificationCancel = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(Constants.NOTIFICATION_LISTENER_CANCELED_ACTION)) {
                String key = intent.getStringExtra("key");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cancelNotification(key);
                }
                else {
                    String[] keyParts = key.split("|", -1);
                    cancelNotification(keyParts[0], keyParts[1], Integer.parseInt(keyParts[2]));
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sharedPreferences = getSharedPreferences("DEVICE", Context.MODE_PRIVATE);
        defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        registerReceiver(mNotificationCancel, new IntentFilter(Constants.NOTIFICATION_LISTENER_CANCELED_ACTION));
        Toast.makeText(this, "Notification listener started", Toast.LENGTH_SHORT).show();
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("NOTIFICATION_LISTENER", true);
        editor.apply();
        Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_STATE);
        intent.putExtra("notificationListenerState", true);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mNotificationCancel);
        Toast.makeText(this, "Notification listener stopped", Toast.LENGTH_SHORT).show();
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("NOTIFICATION_LISTENER", false);
        editor.apply();
        Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_STATE);
        intent.putExtra("notificationListenerState", false);
        sendBroadcast(intent);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification statusBarNotification) {
        sendNotification(statusBarNotification, Constants.NOTIFICATION_LISTENER_POSTED_ACTION);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification statusBarNotification) {
        sendNotification(statusBarNotification, Constants.NOTIFICATION_LISTENER_REMOVED_ACTION);
    }

    private void sendNotification(StatusBarNotification statusBarNotification, String action)
    {
        Set<String> apps = defaultSharedPreferences.getStringSet("apps_list", null);
        String appPackageName = statusBarNotification.getPackageName();
        if (apps != null) {
            for (String app: apps) {
                if (app.equals(appPackageName)) {
                    Intent intent = new Intent(action);
                    String key;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        key = statusBarNotification.getKey();
                    }
                    else {
                        key = statusBarNotification.getPackageName() + "|" +
                                statusBarNotification.getTag() + "|" +
                                statusBarNotification.getId();
                        intent.putExtra("key", key);
                    }
                    intent.putExtra("key", key);
                    intent.putExtra("title", statusBarNotification.getNotification().extras.getString("android.title"));
                    intent.putExtra("text", statusBarNotification.getNotification().extras.getString("android.text"));
                    sendBroadcast(intent);
                    break;
                }
            }
        }
    }
}

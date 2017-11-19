package savickas_ignas.win10notifications;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.widget.Toast;

import java.util.Objects;
import java.util.Set;


public class NotificationListener extends NotificationListenerService {

    private SharedPreferences sharedPreferences;
    private SharedPreferences defaultSharedPreferences;
    private PackageManager packageManager;

    private final BroadcastReceiver mNotificationListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            assert action != null;
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
            else if (action.equals(Constants.NOTIFICATION_LISTENER_GET_ALL_ACTION)) {
                StatusBarNotification[] statusBarNotifications = getActiveNotifications();
                for (StatusBarNotification statusBarNotification : statusBarNotifications) {
                    sendNotification(statusBarNotification, Constants.NOTIFICATION_LISTENER_POSTED_ACTION);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sharedPreferences = getSharedPreferences("DEVICE", Context.MODE_PRIVATE);
        defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        packageManager = getPackageManager();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.NOTIFICATION_LISTENER_CANCELED_ACTION);
        intentFilter.addAction(Constants.NOTIFICATION_LISTENER_GET_ALL_ACTION);
        registerReceiver(mNotificationListener, intentFilter);
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
        unregisterReceiver(mNotificationListener);
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

    private void sendNotification(StatusBarNotification statusBarNotification, String action) {
        Set<String> apps = defaultSharedPreferences.getStringSet("apps_list", null);
        if (apps == null) {
            return;
        }
        for (String app: apps) {
            String appPackageName = statusBarNotification.getPackageName();
            if (app.equals(appPackageName)) {
                Notification notification = statusBarNotification.getNotification();
                CharSequence text = notification.extras.getCharSequence("android.text");
                if (text == null) {
                    action = Constants.NOTIFICATION_LISTENER_REMOVED_ACTION;
                }
                Intent intent = new Intent(action);
                String key;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    key = statusBarNotification.getKey();
                }
                else {
                    key = appPackageName + "|" +
                            statusBarNotification.getTag() + "|" +
                            statusBarNotification.getId();
                }
                intent.putExtra("key", key);
                if (Objects.equals(action, Constants.NOTIFICATION_LISTENER_POSTED_ACTION)) {
                    ApplicationInfo applicationInfo;
                    CharSequence appName = "";
                    try {
                        applicationInfo = packageManager.getApplicationInfo(appPackageName, 0);
                        appName = packageManager.getApplicationLabel(applicationInfo);
                    } catch (PackageManager.NameNotFoundException ignored) {}
                    intent.putExtra("appName", appName);
                    intent.putExtra("packageName", appPackageName);
                    intent.putExtra("title", notification.extras.getCharSequence("android.title"));
                    intent.putExtra("text", text);
                    if (notification.contentIntent != null) {
                        intent.putExtra("contentIntent", notification.contentIntent);
                    }
                }
                sendBroadcast(intent);
                break;
            }
        }
    }
}

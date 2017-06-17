package savickas_ignas.win10notifications;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.NotificationCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import static android.graphics.Color.WHITE;

public class MainActivity extends AppCompatActivity {

    private NotificationManager notificationManager;
    private BluetoothChatFragment fragment;
    private Intent intent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        notificationManager = (NotificationManager) getSystemService(
                NOTIFICATION_SERVICE);
        registerReceiver(broadcastReceiver, new IntentFilter("NOTIFICATION_DELETED"));
        intent = new Intent(this, ForegroundService.class);
        startService(intent);

        if (savedInstanceState == null) {
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            fragment = new BluetoothChatFragment();
            transaction.replace(R.id.sample_content_fragment, fragment);
            transaction.commit();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(broadcastReceiver);
    }

    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int notificationId = intent.getIntExtra("notificationId", 0);
            fragment.sendMessage(Integer.toString(notificationId));
        }
    };

    /**
     * Send a sample notification using the NotificationCompat API.
     */
    public void showNotification(String appName, int notificationId, String notification) {
        Intent intent = new Intent(this, NotificationDismissedReceiver.class);
        intent.putExtra("notificationId", notificationId);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, notificationId, intent, 0);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentTitle(appName);
        builder.setContentText(notification);
        builder.setDeleteIntent(pendingIntent);
        builder.setColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        builder.setLights(WHITE, 1000, 1000);
        builder.setAutoCancel(true);
        notificationManager.notify(notificationId, builder.build());
    }

    /**
     * Send a sample notification using the NotificationCompat API.
     */
    public void cancelNotification(int notificationId) {
        notificationManager.cancel(notificationId);
    }
}
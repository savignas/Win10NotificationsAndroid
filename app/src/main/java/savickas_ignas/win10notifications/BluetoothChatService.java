package savickas_ignas.win10notifications;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.NotificationCompat;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;

import static android.graphics.Color.WHITE;

public class BluetoothChatService extends Service {

    // Unique UUID for this application
    private static final UUID MY_UUID_SECURE =
            UUID.fromString("d2811b95-f5dd-4f84-b817-6becb507d786");

    // Member fields
    private final BluetoothAdapter mAdapter;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;
    private int mNewState;
    private Handler mHandler;
    private final IBinder mBinder = new MyBinder();
    private String mConnectedDeviceName = null;
    private NotificationManager notificationManager;
    private BluetoothDevice device;
    private boolean wasConnected = false;
    private boolean connecting = false;
    private boolean notConnected = true;
    private boolean connected = false;
    private final Handler handlerReconnect = new Handler();
    private final Handler handlerNotification = new Handler();
    private final int reconnectTime = 15000;

    private SharedPreferences defaultSharedPreferences;
    private String callerPhoneNumber;

    private boolean fullBattery;
    private boolean powerConnected;

    private Queue<String> messages = new LinkedList<>();

    private final Handler handlerSendMessage = new Handler();

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device
    public static final int STATE_NO_BLUETOOTH = 4;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            assert action != null;
            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        mState = STATE_NO_BLUETOOTH;
                        updateUserInterfaceTitle();
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        stop();
                        break;
                    case  BluetoothAdapter.STATE_ON:
                        mState = STATE_NONE;
                        start();
                        if (wasConnected)
                        {
                            connect(device);
                        }
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        break;
                }
            }
        }
    };

    private final BroadcastReceiver mNotificationAction = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            assert action != null;
            switch (action) {
                case Constants.NOTIFICATION_LISTENER_POSTED_ACTION: {
                    String key = intent.getStringExtra("key");
                    CharSequence appName = intent.getCharSequenceExtra("appName");
                    String packageName = intent.getStringExtra("packageName");
                    CharSequence title = intent.getCharSequenceExtra("title");
                    CharSequence text = intent.getCharSequenceExtra("text");
                    sendMessage("1;" + key + ";" + appName + ";" + packageName + ";" + title + ";" + text);
                    break;
                }
                case Constants.NOTIFICATION_LISTENER_REMOVED_ACTION: {
                    String key = intent.getStringExtra("key");
                    sendMessage("0;" + key);
                    break;
                }
                case Constants.NOTIFICATION_DELETED_ACTION: {
                    int notificationId = intent.getIntExtra("notificationId", 0);
                    sendMessage("0;" + Integer.toString(notificationId));
                    break;
                }
                case Telephony.Sms.Intents.SMS_RECEIVED_ACTION: {
                    boolean readSmsEnabled = defaultSharedPreferences.getBoolean("read_sms_enabled", false);
                    if (readSmsEnabled) {
                        Bundle bundle = intent.getExtras();
                        if (bundle != null) {
                            SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
                            String phoneNumber = null;
                            StringBuilder message = new StringBuilder();
                            for (SmsMessage currentMessage : messages) {
                                phoneNumber = currentMessage.getDisplayOriginatingAddress();
                                message.append(currentMessage.getDisplayMessageBody());
                            }
                            String contactName = getContactName(context, phoneNumber);
                            sendMessage("1;" + phoneNumber + "_sms" + ";" + contactName + ";" + message);
                        }
                    }
                    break;
                }
                case "android.intent.action.PHONE_STATE": {
                    boolean readStateEnabled = defaultSharedPreferences.getBoolean("read_state_enabled", false);
                    if (readStateEnabled) {
                        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                        if (state != null && state.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
                            callerPhoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                            String contactName = getContactName(context, callerPhoneNumber);
                            sendMessage("1;" + callerPhoneNumber + "_call" + ";" + contactName);
                        } else if (state != null && state.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
                            if (callerPhoneNumber != null) {
                                sendMessage("0;" + callerPhoneNumber + "_call");
                                callerPhoneNumber = null;
                            }
                        } else if (state != null && state.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
                            sendMessage("0;" + callerPhoneNumber + "_call");
                            callerPhoneNumber = null;
                        }
                    }
                    break;
                }
                case Intent.ACTION_BATTERY_LOW: {
                    boolean batteryWarningEnabled = defaultSharedPreferences.getBoolean("battery_warning_enabled", false);
                    if (batteryWarningEnabled) {
                        sendMessage("1;low_battery;Low Battery;low_battery;Low battery;Your device is on low battery!");
                    }
                    break;
                }
                case Intent.ACTION_BATTERY_CHANGED: {
                    boolean batteryWarningEnabled = defaultSharedPreferences.getBoolean("battery_warning_enabled", false);
                    if (batteryWarningEnabled) {
                        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        if (status == BatteryManager.BATTERY_STATUS_FULL && !fullBattery && powerConnected) {
                            fullBattery = true;
                            sendMessage("1;full_battery;Full Battery;full_battery;Full battery;Your device is fully charged!");
                        }
                    }
                    break;
                }
                case Intent.ACTION_POWER_CONNECTED: {
                    boolean batteryWarningEnabled = defaultSharedPreferences.getBoolean("battery_warning_enabled", false);
                    if (batteryWarningEnabled) {
                        sendMessage("0;low_battery");
                        powerConnected = true;
                    }
                    break;
                }
                case Intent.ACTION_POWER_DISCONNECTED: {
                    boolean batteryWarningEnabled = defaultSharedPreferences.getBoolean("battery_warning_enabled", false);
                    if (batteryWarningEnabled) {
                        sendMessage("0;full_battery");
                        fullBattery = false;
                        powerConnected = false;
                    }
                    break;
                }
            }
        }
    };

    /**
     * Constructor. Prepares a new BluetoothChat session.
     */
    public BluetoothChatService() {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mNewState = mState;
    }

    /*public BroadcastReceiver getFragmentReceiver() {
        return fragmentReceiver;
    }

    public void setFragmentReceiver(BroadcastReceiver receiver) {
        this.fragmentReceiver = receiver;
    }*/

    private synchronized void updateUserInterfaceTitle() {
        mNewState = mState;

        if (mHandler != null) {
            // Give the new state to the Handler so the UI Activity can update
            mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, mNewState, -1).sendToTarget();
        }
        switch (mNewState) {
            case STATE_CONNECTED:
                setForegroundNotification(getString(R.string.title_connected_to, mConnectedDeviceName));
                if (!connected) {
                   sendMessages();
                    Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_GET_ALL_ACTION);
                    sendBroadcast(intent);
                }
                connecting = false;
                connected = true;
                break;
            case STATE_CONNECTING:
                if (!connecting && wasConnected)
                {
                    setForegroundNotification(getString(R.string.title_connecting));
                    connecting = true;
                }
                break;
            case STATE_LISTEN:
            case STATE_NONE:
                if (!connecting && !wasConnected && notConnected)
                {
                    setForegroundNotification(getString(R.string.title_not_connected));
                    notConnected = false;
                }
                else if (wasConnected && !connecting)
                {
                    setForegroundNotification(getString(R.string.title_connecting));
                    connecting = true;
                }
                handlerSendMessage.removeCallbacksAndMessages(null);
                connected = false;
                break;
            case STATE_NO_BLUETOOTH:
                setForegroundNotification(getString(R.string.title_no_bluetooth));
                connecting = false;
                handlerSendMessage.removeCallbacksAndMessages(null);
                connected = false;
        }
    }

    /**
     * Return the current connection state.
     */
    public synchronized int getState() {
        return mState;
    }

    public synchronized void setWasConnected() {
        this.wasConnected = false;
    }

    public synchronized void setNotConnected() {
        this.notConnected = true;
    }

    /**
     * Start the chat service. Specifically start AcceptThread to begin a
     * session in listening (server) mode. Called by the Activity onResume()
     */
    public synchronized void start() {

        if (mConnectedThread != null) {
            if (mHandler != null) {
                // Send the name of the connected device back to the UI Activity
                Message msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
                Bundle bundle = new Bundle();
                bundle.putString(Constants.DEVICE_NAME, mConnectedDeviceName);
                msg.setData(bundle);
                mHandler.sendMessage(msg);
            }
        }

        if (mAdapter == null || !mAdapter.isEnabled())
        {
            mState = STATE_NO_BLUETOOTH;
        }

        updateUserInterfaceTitle();
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     *
     * @param device The BluetoothDevice to connect
     */
    public synchronized void connect(BluetoothDevice device) {

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        updateUserInterfaceTitle();

        this.device = device;
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     *
     * @param socket The BluetoothSocket on which the connection was made
     * @param device The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice
            device) {

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();
        wasConnected = true;

        if (mHandler != null) {
            // Send the name of the connected device back to the UI Activity
            Message msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
            Bundle bundle = new Bundle();
            bundle.putString(Constants.DEVICE_NAME, device.getName());
            msg.setData(bundle);
            mHandler.sendMessage(msg);
        }

        mConnectedDeviceName = device.getName();

        updateUserInterfaceTitle();
        showNotification(getString(R.string.app_name), Constants.INFO_NOTIFICATION_ID, getString(R.string.title_connected_to, mConnectedDeviceName), getString(R.string.app_name), Notification.PRIORITY_MIN);
        dismissNotification(false);
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        mState = STATE_NONE;
        connecting = false;

        updateUserInterfaceTitle();
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     *
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed() {
        mState = STATE_NONE;
        updateUserInterfaceTitle();

        // Start the service over to restart listening mode
        BluetoothChatService.this.start();

        if (wasConnected && mState != STATE_NO_BLUETOOTH)
        {
            handlerReconnect.postDelayed(new Runnable() {
                @Override
                public void run() {
                    BluetoothChatService.this.connect(device);
                }
            }, reconnectTime);
        }
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost() {
        if (mHandler != null) {
            // Send a failure message back to the Activity
            Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
            Bundle bundle = new Bundle();
            bundle.putString(Constants.TOAST, "Device connection was lost");
            msg.setData(bundle);
            mHandler.sendMessage(msg);
        }

        mState = STATE_NONE;
        updateUserInterfaceTitle();
        showNotification(getString(R.string.app_name), Constants.INFO_NOTIFICATION_ID, getString(R.string.title_disconnected_from, mConnectedDeviceName), getString(R.string.app_name), Notification.PRIORITY_MIN);
        dismissNotification(true);

        // Start the service over to restart listening mode
        BluetoothChatService.this.start();

        if (wasConnected && mState != STATE_NO_BLUETOOTH)
        {
            handlerReconnect.postDelayed(new Runnable() {
                @Override
                public void run() {
                    BluetoothChatService.this.connect(device);
                }
            }, reconnectTime);
        }
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                tmp = device.createRfcommSocketToServiceRecord(
                        MY_UUID_SECURE);
            } catch (IOException ignored) {
            }
            mmSocket = tmp;
            mState = STATE_CONNECTING;
        }

        public void run() {

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException ignored) {
                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothChatService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice);
        }

        void cancel() {
            try {
                mmSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException ignored) {
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            mState = STATE_CONNECTED;
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            // Keep listening to the InputStream while connected
            while (mState == STATE_CONNECTED) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);
                    String readMessage = new String(buffer, 0, bytes);
                    String[] messageParts = readMessage.split(";", -1);
                    if (Objects.equals(messageParts[0], "1")) {
                        if (!Objects.equals(messageParts[4], "")) {
                            showNotification(messageParts[3], Integer.parseInt(messageParts[1]), messageParts[4], messageParts[2], Notification.PRIORITY_DEFAULT);
                        }
                        else {
                            showNotification(messageParts[2], Integer.parseInt(messageParts[1]), messageParts[3], messageParts[2], Notification.PRIORITY_DEFAULT);
                        }
                    }
                    else if (Objects.equals(messageParts[0], "0")) {
                        try {
                            cancelNotification(Integer.parseInt(messageParts[1]));
                        }
                        catch (Exception ex) {
                            if (messageParts[1].startsWith("+")) {
                                SmsManager smsManager = SmsManager.getDefault();
                                String phoneNumber = messageParts[1].substring(0, 12);
                                if (messageParts[1].endsWith("sms")) {
                                    try {
                                        smsManager.sendTextMessage(phoneNumber, null, messageParts[2], null, null);
                                    } catch (Exception ignored) {}
                                } else if (messageParts[1].endsWith("call")) {
                                    try {
                                        endCall();
                                        smsManager.sendTextMessage(phoneNumber, null, messageParts[2], null, null);
                                    } catch (Exception ignored) {}
                                }
                            } else {
                                Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_CANCELED_ACTION);
                                intent.putExtra("key", messageParts[1]);
                                sendBroadcast(intent);
                            }
                        }
                    }
                    if (mHandler != null) {
                        // Send the obtained bytes to the UI Activity
                        mHandler.obtainMessage(Constants.MESSAGE_READ, bytes, -1, buffer)
                                .sendToTarget();
                    }
                } catch (IOException e) {
                    connectionLost();
                    break;
                }
            }
        }

        private void endCall() throws Exception {
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            assert telephonyManager != null;
            Class<?> telephonyClass = Class.forName(telephonyManager.getClass().getName());
            Method GetITelephonyMethod = telephonyClass.getDeclaredMethod("getITelephony");
            GetITelephonyMethod.setAccessible(true);
            Object telephonyInterface = GetITelephonyMethod.invoke(telephonyManager);
            Class<?> telephonyInterfaceClass = Class.forName(telephonyInterface.getClass().getName());
            Method endCallMethod = telephonyInterfaceClass.getDeclaredMethod("endCall");
            endCallMethod.invoke(telephonyInterface);
        }

        /**
         * Write to the connected OutStream.
         *
         * @param buffer The bytes to write
         */
        void write(byte[] buffer) {
            try {
                int bufferLength = buffer.length;
                byte[] bufferLengthBytes = ByteBuffer.allocate(4).putInt(bufferLength).array();
                int bufferLengthBytesLength = bufferLengthBytes.length;
                byte[] newBuffer = new byte[bufferLengthBytesLength + bufferLength];
                System.arraycopy(bufferLengthBytes, 0, newBuffer, 0, bufferLengthBytesLength);
                System.arraycopy(buffer, 0, newBuffer, bufferLengthBytesLength, bufferLength);
                mmOutStream.write(newBuffer);

                if (mHandler != null) {
                    // Share the sent message back to the UI Activity
                    mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, buffer)
                            .sendToTarget();
                }
            } catch (IOException ignored) {
            }
        }

        void cancel() {
            try {
                mmSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void setForegroundNotification(String contextText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification = new Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contextText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setTicker(getString(R.string.app_name))
                .setPriority(Notification.PRIORITY_MIN)
                .build();

        startForeground(Constants.SRV_NOTIFICATION_ID, notification);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(
                NOTIFICATION_SERVICE);
        Toast.makeText(this, "service starting", Toast.LENGTH_LONG).show();
        /*SharedPreferences sharedPreferences = getSharedPreferences("DEVICE", Context.MODE_PRIVATE);
        String name = sharedPreferences.getString("DEVICE_NAME", getString(R.string.title_not_connected));*/

        setForegroundNotification(getString(R.string.title_not_connected));

        IntentFilter intentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBroadcastReceiver, intentFilter);

        intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.NOTIFICATION_LISTENER_POSTED_ACTION);
        intentFilter.addAction(Constants.NOTIFICATION_LISTENER_REMOVED_ACTION);
        intentFilter.addAction(Constants.NOTIFICATION_DELETED_ACTION);
        intentFilter.addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
        intentFilter.addAction("android.intent.action.PHONE_STATE");
        intentFilter.addAction(Intent.ACTION_BATTERY_LOW);
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        intentFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        intentFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(mNotificationAction, intentFilter);

        defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.getAction() != null && intent.getAction().equals(Constants.STOP_FOREGROUND_ACTION)) {
            Toast.makeText(this, "service stopped", Toast.LENGTH_LONG).show();
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHandler != null) {
            mHandler = null;
        }

        unregisterReceiver(mBroadcastReceiver);
        unregisterReceiver(mNotificationAction);
    }

    class MyBinder extends Binder {
        BluetoothChatService getService() {
            return BluetoothChatService.this;
        }
    }

    public void setHandler(Handler handler) {
        mHandler = handler;
    }

    /**
     * Send a sample notification using the NotificationCompat API.
     */
    public void showNotification(String title, int notificationId, String text, String appName, int priority) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        if (priority != Notification.PRIORITY_MIN)
        {
            Intent intent = new Intent(Constants.NOTIFICATION_DELETED_ACTION);
            intent.putExtra("notificationId", notificationId);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, notificationId, intent, 0);
            builder.setDeleteIntent(pendingIntent);
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
        }

        builder.setSmallIcon(R.drawable.ic_notification);
        builder.setContentTitle(title);
        builder.setContentText(text);
        builder.setSubText(appName);
        builder.setColor(ContextCompat.getColor(this, R.color.colorPrimary));
        builder.setLights(WHITE, 1000, 1000);
        builder.setAutoCancel(true);
        builder.setPriority(priority);
        notificationManager.notify(notificationId, builder.build());
    }

    private void dismissNotification(final boolean removeAll)
    {
        handlerNotification.postDelayed(new Runnable() {
            @Override
            public void run() {
                cancelNotification(Constants.INFO_NOTIFICATION_ID);
                if (removeAll) {
                    notificationManager.cancelAll();
                }
            }
        }, 5000);
    }

    /**
     * Send a sample notification using the NotificationCompat API.
     */
    public void cancelNotification(int notificationId) {
        notificationManager.cancel(notificationId);
    }

    /**
     * Sends a message.
     *
     * @param message A string of text to send.
     */
    private void sendMessage(String message) {
        // Check that we're actually connected before trying anything
        if (!connected) {
            return;
        }

        // Check that there's actually something to send
        if (message.length() > 0) {
            messages.offer(message);
        }
    }

    private void sendMessages() {
        handlerSendMessage.postDelayed(new Runnable(){
            public void run(){
                if (!messages.isEmpty() && connected) {
                    String message = messages.poll();
                    byte[] send = message.getBytes();
                    write(send);
                    Log.i("sendMessages", message);
                }
                handlerSendMessage.postDelayed(this, 500);
            }
        }, 500);
    }

    private String getContactName(Context context, String phoneNumber) {
        String contactName = phoneNumber;
        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
            Cursor cursor = cr.query(uri, new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (cursor == null) {
                return null;
            }
            if(cursor.moveToFirst()) {
                contactName = cursor.getString(cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME));
            }

            if(!cursor.isClosed()) {
                cursor.close();
            }
        } catch (Exception ignored) {
        }
        return contactName;
    }
}

package savickas_ignas.win10notifications;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
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
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.Telephony;
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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
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
    private boolean powerConnected = true;

    private Queue<String> messages = new LinkedList<>();
    private Map<String, PendingIntent> notificationContentIntents = new HashMap<>();

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
                    PendingIntent pendingIntent = intent.getParcelableExtra("contentIntent");
                    if (pendingIntent != null) {
                        notificationContentIntents.put(key, pendingIntent);
                        sendMessage(key, title, text, appName, packageName, "intent");
                    }
                    sendMessage(key, title, text, appName, packageName);
                    break;
                }
                case Constants.NOTIFICATION_LISTENER_REMOVED_ACTION: {
                    String key = intent.getStringExtra("key");
                    sendMessage(key);
                    notificationContentIntents.remove(key);
                    break;
                }
                case Constants.NOTIFICATION_DELETED_ACTION: {
                    int notificationId = intent.getIntExtra("notificationId", 0);
                    sendMessage(Integer.toString(notificationId));
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
                            sendMessage(phoneNumber + "_sms", contactName, message.toString());
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
                            sendMessage(callerPhoneNumber + "_call", contactName);
                        } else if (state != null && state.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
                            if (callerPhoneNumber != null) {
                                sendMessage(callerPhoneNumber + "_call");
                                callerPhoneNumber = null;
                            }
                        } else if (state != null && state.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
                            sendMessage(callerPhoneNumber + "_call");
                            callerPhoneNumber = null;
                        }
                    }
                    break;
                }
                case Intent.ACTION_BATTERY_LOW: {
                    boolean batteryWarningEnabled = defaultSharedPreferences.getBoolean("battery_warning_enabled", false);
                    if (batteryWarningEnabled) {
                        sendMessage("low_battery", "Low battery","Your device is on low battery!", "Low Battery", "low_battery");
                    }
                    break;
                }
                case Intent.ACTION_BATTERY_CHANGED: {
                    boolean batteryWarningEnabled = defaultSharedPreferences.getBoolean("battery_warning_enabled", false);
                    if (batteryWarningEnabled) {
                        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        if (status == BatteryManager.BATTERY_STATUS_FULL && !fullBattery && powerConnected && connected) {
                            fullBattery = true;
                            sendMessage("full_battery", "Full battery", "Your device is fully charged!", "Full Battery", "full_battery");
                        }
                    }
                    break;
                }
                case Intent.ACTION_POWER_CONNECTED: {
                    boolean batteryWarningEnabled = defaultSharedPreferences.getBoolean("battery_warning_enabled", false);
                    if (batteryWarningEnabled) {
                        sendMessage("low_battery");
                        powerConnected = true;
                    }
                    break;
                }
                case Intent.ACTION_POWER_DISCONNECTED: {
                    boolean batteryWarningEnabled = defaultSharedPreferences.getBoolean("battery_warning_enabled", false);
                    if (batteryWarningEnabled) {
                        sendMessage("full_battery");
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
                fullBattery = false;
                powerConnected = true;
                break;
            case STATE_NO_BLUETOOTH:
                setForegroundNotification(getString(R.string.title_no_bluetooth));
                connecting = false;
                handlerSendMessage.removeCallbacksAndMessages(null);
                connected = false;
                notConnected = true;
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
        //showNotification(getString(R.string.app_name), Constants.INFO_NOTIFICATION_ID, getString(R.string.title_connected_to, mConnectedDeviceName), getString(R.string.app_name), Notification.PRIORITY_MIN);
        showDeviceConnectionNotification(getString(R.string.title_connected_to, mConnectedDeviceName));
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
        //showNotification(getString(R.string.app_name), Constants.INFO_NOTIFICATION_ID, getString(R.string.title_disconnected_from, mConnectedDeviceName), getString(R.string.app_name), Notification.PRIORITY_MIN);
        showDeviceConnectionNotification(getString(R.string.title_disconnected_from, mConnectedDeviceName));
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
                    StringBuilder notificationText = new StringBuilder();
                    for (int i=6; i<messageParts.length; i++) {
                        notificationText.append(messageParts[i]);
                        if (i + 1 < messageParts.length) {
                            notificationText.append(';');
                        }
                    }
                    String[] textParts = new String[6];
                    int location = 0;
                    for (int i=0; i<textParts.length; i++) {
                        textParts[i] = notificationText.substring(location, location + Integer.parseInt(messageParts[i]));
                        location += Integer.parseInt(messageParts[i]);
                    }
                    Type action = Type.valueOf(Integer.parseInt(textParts[0]));
                    String id = textParts[1];
                    String  titleText = textParts[2];
                    String bodyText = textParts[3];
                    String appName = textParts[4];
                    String colorHex = '#' + textParts[5];
                    if (action == Type.Add) {
                        if (!Objects.equals(bodyText, "")) {
                            showWindowsNotification(titleText, Integer.parseInt(id), bodyText, appName, colorHex);
                        }
                        else {
                            showWindowsNotification(appName, Integer.parseInt(id), titleText, appName, colorHex);
                        }
                    } else if (action == Type.Remove) {
                        try {
                            cancelNotification(Integer.parseInt(id));
                        }
                        catch (Exception ex) {
                            if (id.startsWith("+")) {
                                SmsManager smsManager = SmsManager.getDefault();
                                String phoneNumber = id.substring(0, 12);
                                if (id.endsWith("sms")) {
                                    try {
                                        smsManager.sendTextMessage(phoneNumber, null, titleText, null, null);
                                    } catch (Exception ignored) {}
                                } else if (id.endsWith("call")) {
                                    try {
                                        endCall();
                                        smsManager.sendTextMessage(phoneNumber, null, titleText, null, null);
                                    } catch (Exception ignored) {}
                                }
                            } else {
                                notificationContentIntents.remove(id);
                                Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_CANCELED_ACTION);
                                intent.putExtra("key", id);
                                sendBroadcast(intent);
                            }
                        }
                    } else if (action == Type.Open) {
                        PendingIntent pendingIntent = notificationContentIntents.get(id);
                        try {
                            pendingIntent.send();
                        } catch (PendingIntent.CanceledException ignored) {}
                        notificationContentIntents.remove(id);
                        Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_CANCELED_ACTION);
                        intent.putExtra("key", id);
                        sendBroadcast(intent);
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

        private synchronized void endCall() throws Exception {
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


    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannel(String channelId, CharSequence channelName, int importance) {
        NotificationChannel notificationChannel = new NotificationChannel(channelId, channelName, importance);
        notificationChannel.enableLights(true);
        notificationChannel.enableVibration(true);
        notificationManager.createNotificationChannel(notificationChannel);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannel(String channelId, CharSequence channelName, int importance, int color) {
        NotificationChannel notificationChannel = new NotificationChannel(channelId, channelName, importance);
        notificationChannel.enableLights(true);
        notificationChannel.setLightColor(color);
        notificationChannel.enableVibration(true);
        notificationManager.createNotificationChannel(notificationChannel);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannels() {
        createNotificationChannel(Constants.FOREGROUND_SERVICE_CHANNEL_ID, "Foreground Service", NotificationManager.IMPORTANCE_MIN);
        createNotificationChannel(Constants.DEVICE_CONNECTION_CHANNEL_ID, "Device Connection", NotificationManager.IMPORTANCE_LOW);
        createNotificationChannel(Constants.WINDOWS_NOTIFICATIONS_CHANNEL_ID, "Windows Notifications", NotificationManager.IMPORTANCE_DEFAULT, WHITE);
    }

    public synchronized void setForegroundNotification(String contextText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, Constants.FOREGROUND_SERVICE_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_MIN);
        }
        builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contextText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setTicker(getString(R.string.app_name));

        startForeground(Constants.SRV_NOTIFICATION_ID, builder.build());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(
                NOTIFICATION_SERVICE);
        Toast.makeText(this, "service starting", Toast.LENGTH_LONG).show();
        /*SharedPreferences sharedPreferences = getSharedPreferences("DEVICE", Context.MODE_PRIVATE);
        String name = sharedPreferences.getString("DEVICE_NAME", getString(R.string.title_not_connected));*/

        createNotificationChannels();

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

    public synchronized void setHandler(Handler handler) {
        mHandler = handler;
    }

    /**
     * Send a sample notification using the NotificationCompat API.
     */
    public synchronized void showWindowsNotification(String title, int notificationId, String text, String appName, String colorHex) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, Constants.WINDOWS_NOTIFICATIONS_CHANNEL_ID)
                    .setColor(Color.parseColor(colorHex)).setColorized(true);
        } else {
            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .setLights(Color.parseColor(colorHex), 1000, 1000);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setColor(Color.parseColor(colorHex));
            }
        }

        Intent intent = new Intent(Constants.NOTIFICATION_DELETED_ACTION);
        intent.putExtra("notificationId", notificationId);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, notificationId, intent, 0);
        builder.setDeleteIntent(pendingIntent);

        builder.setSmallIcon(R.drawable.ic_notification);
        builder.setContentTitle(title);
        builder.setContentText(text);
        builder.setSubText(appName);
        builder.setAutoCancel(true);
        notificationManager.notify(notificationId, builder.build());
    }

    public synchronized void showDeviceConnectionNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, Constants.DEVICE_CONNECTION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_notification);
        builder.setContentTitle(getString(R.string.app_name));
        builder.setContentText(text);
        builder.setSubText(getString(R.string.app_name));
        notificationManager.notify(Constants.INFO_NOTIFICATION_ID, builder.build());
    }

    private synchronized void dismissNotification(final boolean removeAll)
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
    public synchronized void cancelNotification(int notificationId) {
        notificationManager.cancel(notificationId);
    }

    private synchronized String generateMessage(Type type, String key, String title, String text, String appName, String packageName, String contentIntent) {
        return Integer.toString(type.getValue()).length() + ";" + key.length() + ";" + title.length() + ";" + text.length() + ";" + appName.length() + ";" + packageName.length() + ";" + contentIntent.length() + ";" +
                Integer.toString(type.getValue()) + key + appName + packageName + title + text + contentIntent;
    }

    private synchronized void sendMessage(String key, CharSequence title, CharSequence text, CharSequence appName, String packageName, String contentIntent) {
        String message = generateMessage(Type.Add, key, title.toString(), text.toString(), appName.toString(), packageName, contentIntent);
        addMessage(message);
    }

    private synchronized void sendMessage(String key, CharSequence title, CharSequence text, CharSequence appName, String packageName) {
        String message = generateMessage(Type.Add, key, title.toString(), text.toString(), appName.toString(), packageName, "");
        addMessage(message);
    }

    private synchronized void sendMessage(String key, String contactName, String textMessage) {
        String message = generateMessage(Type.Add, key, contactName, textMessage, "", "", "");
        addMessage(message);
    }

    private synchronized void sendMessage(String key, String contactName) {
        String message = generateMessage(Type.Add, key, contactName, "", "", "", "");
        addMessage(message);
    }

    private synchronized void sendMessage(String key) {
        String message = generateMessage(Type.Remove, key, "", "", "", "", "");
        addMessage(message);
    }

    /**
     * Sends a message.
     *
     * @param message A string of text to send.
     */
    private synchronized void addMessage(String message) {
        // Check that we're actually connected before trying anything
        if (!connected) {
            return;
        }

        // Check that there's actually something to send
        if (message.length() > 0) {
            messages.offer(message);
        }
    }

    private synchronized void sendMessages() {
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

    private synchronized String getContactName(Context context, String phoneNumber) {
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

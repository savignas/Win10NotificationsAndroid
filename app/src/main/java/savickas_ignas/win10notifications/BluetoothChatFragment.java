package savickas_ignas.win10notifications;

import android.app.Activity;
import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.ActionBar;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * This fragment controls Bluetooth to communicate with other devices.
 */
public class BluetoothChatFragment extends Fragment implements ServiceConnection {

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_SELECT_DEFAULT_DEVICE = 2;
    private static final int REQUEST_ENABLE_BT = 3;

    // Layout Views
    private ListView mConversationView;
    private Button mServiceStopButton;
    private Menu menu;

    /**
     * Name of the connected device
     */
    private String mConnectedDeviceName = null;

    /**
     * Array adapter for the conversation thread
     */
    private ArrayAdapter<String> mConversationArrayAdapter;

    /**
     * String buffer for outgoing messages
     */
    private StringBuffer mOutStringBuffer;

    /**
     * Local Bluetooth adapter
     */
    private BluetoothAdapter mBluetoothAdapter = null;

    /**
     * Member object for the chat services
     */
    private BluetoothChatService mChatService = null;

    private boolean connected = false;

    private SharedPreferences sharedPreferences;
    private SharedPreferences defaultSharedPreferences;

    private String callerPhoneNumber;

    private boolean serviceBound;

    private final BroadcastReceiver mNotificationAction = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

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
                            for (SmsMessage currentMessage : messages) {
                                String phoneNumber = currentMessage.getDisplayOriginatingAddress();
                                String message = currentMessage.getDisplayMessageBody();
                                String contactName = getContactName(context, phoneNumber);
                                sendMessage("1;" + phoneNumber + "_sms" + ";" + contactName + ";" + message);
                            }
                        }
                    }
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
                }
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPreferences = getActivity().getSharedPreferences("DEVICE", Context.MODE_PRIVATE);
        defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        setHasOptionsMenu(true);
        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            FragmentActivity activity = getActivity();
            Toast.makeText(activity, "Bluetooth is not available", Toast.LENGTH_LONG).show();
        }
    }


    @Override
    public void onStart() {
        super.onStart();
        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        if (mBluetoothAdapter != null) {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
                // Otherwise, setup the chat session
            }
        }
        setupChat();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            getActivity().unbindService(BluetoothChatFragment.this);
            serviceBound = false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mChatService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
                // Start the Bluetooth chat services
                mChatService.start();
            }
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_bluetooth_chat, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        mConversationView = (ListView) view.findViewById(R.id.in);
        mServiceStopButton = (Button) view.findViewById(R.id.button_service_stop);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (mChatService == null) {
            BluetoothChatService.MyBinder myBinder = (BluetoothChatService.MyBinder) binder;
            mChatService = myBinder.getService();
            mChatService.setHandler(mHandler);
            mChatService.start();
            if (mChatService.getFragmentReceiver() == null) {
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(Constants.NOTIFICATION_LISTENER_POSTED_ACTION);
                intentFilter.addAction(Constants.NOTIFICATION_LISTENER_REMOVED_ACTION);
                intentFilter.addAction(Constants.NOTIFICATION_DELETED_ACTION);
                intentFilter.addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION);
                intentFilter.addAction("android.intent.action.PHONE_STATE");
                mChatService.registerReceiver(mNotificationAction, intentFilter);
                mChatService.setFragmentReceiver(mNotificationAction);
            }

        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (mChatService != null) {
            BroadcastReceiver receiver = mChatService.getFragmentReceiver();
            mChatService.unregisterReceiver(receiver);
            mChatService = null;
        }
    }

    /**
     * Set up the UI and background operations for chat.
     */
    private void setupChat() {

        // Initialize the array adapter for the conversation thread
        mConversationArrayAdapter = new ArrayAdapter<>(getActivity(), R.layout.message);

        mConversationView.setAdapter(mConversationArrayAdapter);

        mServiceStopButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                View view = getView();
                if (null != view) {
                    if (mChatService != null) {
                        BroadcastReceiver receiver = mChatService.getFragmentReceiver();
                        mChatService.unregisterReceiver(receiver);
                        Intent stopIntent = new Intent(getActivity(), BluetoothChatService.class);
                        stopIntent.setAction(Constants.STOP_FOREGROUND_ACTION);
                        getActivity().startService(stopIntent);
                        mChatService.stop();
                        mChatService.setWasConnected(false);
                        mChatService.setNotConnected(true);
                        if (serviceBound) {
                            getActivity().unbindService(BluetoothChatFragment.this);
                            serviceBound = false;
                        }
                        mChatService = null;
                        MenuItem item = menu.findItem(R.id.secure_connect);
                        String name = sharedPreferences.getString("DEVICE_NAME", "");
                        item.setTitle(getString(R.string.secure_connect_to, name));
                        menu.setGroupEnabled(0, false);
                    }
                }
            }
        });

        // Initialize the BluetoothChatService to perform bluetooth connections
        Intent startIntent = new Intent(getActivity(), BluetoothChatService.class);
        if (mChatService == null) {
            getActivity().startService(startIntent);
        }
        if (!serviceBound) {
            serviceBound = getActivity().bindService(startIntent, BluetoothChatFragment.this, Context.BIND_AUTO_CREATE);
        }

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");
    }

    /**
     * Makes this device discoverable for 300 seconds (5 minutes).
     */
    private void ensureDiscoverable() {
        if (mBluetoothAdapter.getScanMode() !=
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivity(discoverableIntent);
        }
    }

    /**
     * Sends a message.
     *
     * @param message A string of text to send.
     */
    private void sendMessage(String message) {
        if (mChatService != null) {
            // Check that we're actually connected before trying anything
            if (!connected) {
                return;
            }

            // Check that there's actually something to send
            if (message.length() > 0) {
                // Get the message bytes and tell the BluetoothChatService to write
                byte[] send = message.getBytes();
                mChatService.write(send);

                // Reset out string buffer to zero and clear the edit text field
                mOutStringBuffer.setLength(0);
            }
        }
        else {
            Activity activity = getActivity();
            if (activity != null) {
                Toast.makeText(activity, R.string.service_not_started, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Updates the status on the action bar.
     *
     * @param resId a string resource ID
     */
    private void setStatus(int resId) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = ((AppCompatActivity) activity).getSupportActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(resId);
    }

    /**
     * Updates the status on the action bar.
     *
     * @param subTitle status
     */
    private void setStatus(CharSequence subTitle) {
        FragmentActivity activity = getActivity();
        if (null == activity) {
            return;
        }
        final ActionBar actionBar = ((AppCompatActivity) activity).getSupportActionBar();
        if (null == actionBar) {
            return;
        }
        actionBar.setSubtitle(subTitle);
    }

    /**
     * The Handler that gets information back from the BluetoothChatService
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            FragmentActivity activity = getActivity();
            switch (msg.what) {
                case Constants.MESSAGE_STATE_CHANGE:
                    switch (msg.arg1) {
                        case BluetoothChatService.STATE_CONNECTED:
                            setStatus(mChatService.getString(R.string.title_connected_to, mConnectedDeviceName));
                            if (menu != null) {
                                MenuItem item = menu.findItem(R.id.secure_connect);
                                item.setTitle(mChatService.getString(R.string.secure_disconnect_from, mConnectedDeviceName));
                                menu.setGroupEnabled(0, true);
                            }
                            mConversationArrayAdapter.clear();
                            Intent intent = new Intent(Constants.NOTIFICATION_LISTENER_GET_ALL_ACTION);
                            mChatService.sendBroadcast(intent);
                            connected = true;
                            break;
                        case BluetoothChatService.STATE_CONNECTING:
                            setStatus(R.string.title_connecting);
                            break;
                        case BluetoothChatService.STATE_LISTEN:
                        case BluetoothChatService.STATE_NONE:
                            setStatus(R.string.title_not_connected);
                            String name = sharedPreferences.getString("DEVICE_NAME", "");
                            if (menu != null && mChatService != null) {
                                MenuItem item = menu.findItem(R.id.secure_connect);
                                item.setTitle(mChatService.getString(R.string.secure_connect_to, name));
                                menu.setGroupEnabled(0, true);
                            }
                            connected = false;
                            break;
                        case BluetoothChatService.STATE_NO_BLUETOOTH:
                            setStatus(R.string.title_no_bluetooth);
                            if (menu != null) {
                                name = sharedPreferences.getString("DEVICE_NAME", "");
                                MenuItem item = menu.findItem(R.id.secure_connect);
                                item.setTitle(mChatService.getString(R.string.secure_connect_to, name));
                                menu.setGroupEnabled(0, true); // false
                            }
                            connected = false;
                    }
                    break;
                case Constants.MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    mConversationArrayAdapter.add("Me: " + writeMessage);
                    break;
                case Constants.MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    String[] messageParts = readMessage.split(";", -1);
                    mConversationArrayAdapter.add(mConnectedDeviceName + ": " + readMessage);
                    if (Objects.equals(messageParts[0], "1")) {
                        if (!Objects.equals(messageParts[4], "")) {
                            mChatService.showNotification(messageParts[3], Integer.parseInt(messageParts[1]), messageParts[4], messageParts[2], Notification.PRIORITY_DEFAULT);
                        }
                        else {
                            mChatService.showNotification(messageParts[2], Integer.parseInt(messageParts[1]), messageParts[3], messageParts[2], Notification.PRIORITY_DEFAULT);
                        }
                    }
                    else if (Objects.equals(messageParts[0], "0")) {
                        try {
                            mChatService.cancelNotification(Integer.parseInt(messageParts[1]));
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
                                mChatService.sendBroadcast(intent);
                            }
                        }
                    }
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    break;
                case Constants.MESSAGE_TOAST:
                    if (null != activity) {
                        Toast.makeText(activity, msg.getData().getString(Constants.TOAST),
                                Toast.LENGTH_SHORT).show();
                    }
                    break;
            }
        }
    };

    private void endCall() throws Exception {
        TelephonyManager telephonyManager = (TelephonyManager) mChatService.getSystemService(Context.TELEPHONY_SERVICE);
        Class<?> telephonyClass = Class.forName(telephonyManager.getClass().getName());
        Method GetITelephonyMethod = telephonyClass.getDeclaredMethod("getITelephony");
        GetITelephonyMethod.setAccessible(true);
        Object telephonyInterface = GetITelephonyMethod.invoke(telephonyManager);
        Class<?> telephonyInterfaceClass = Class.forName(telephonyInterface.getClass().getName());
        Method endCallMethod = telephonyInterfaceClass.getDeclaredMethod("endCall");
        endCallMethod.invoke(telephonyInterface);
    }

    private void getAddress(Intent intent) {
        String address = intent.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        String name = intent.getStringExtra(DeviceListActivity.EXTRA_DEVICE_NAME);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("DEVICE_ADDRESS", address);
        editor.putString("DEVICE_NAME", name);
        editor.apply();
        MenuItem item = menu.findItem(R.id.secure_connect);
        item.setTitle(getString(R.string.secure_connect_to, name));
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_SELECT_DEFAULT_DEVICE:
                // When DeviceListActivity returns with a default device to connect
                if (resultCode == Activity.RESULT_OK) {
                    getAddress(data);
                }
                break;
            case REQUEST_CONNECT_DEVICE_SECURE:
                if (resultCode == Activity.RESULT_OK) {
                    getAddress(data);
                    connectDevice();
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode != Activity.RESULT_OK) {
                    // User did not enable Bluetooth or an error occurred
                    Toast.makeText(getActivity(), R.string.bt_not_enabled,
                            Toast.LENGTH_SHORT).show();
                }
        }
    }

    /**
     * Establish connection with other divice
     */
    private void connectDevice() {
        // Get the device MAC address
        String address = sharedPreferences.getString("DEVICE_ADDRESS", "");
        if (Objects.equals(address, "")) {
            Intent defaultIntent = new Intent(getActivity(), DeviceListActivity.class);
            startActivityForResult(defaultIntent, REQUEST_CONNECT_DEVICE_SECURE);
            return;
        }
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        if (mChatService != null) {
            mChatService.connect(device);
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        this.menu = menu;
        inflater.inflate(R.menu.bluetooth_chat, menu);
        String name = sharedPreferences.getString("DEVICE_NAME", "");
        if (!Objects.equals(name, "")) {
            MenuItem item = menu.findItem(R.id.secure_connect);
            if (!connected) {
                item.setTitle(getString(R.string.secure_connect_to, name));
            }
            else {
                item.setTitle(getString(R.string.secure_disconnect_from, name));
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.secure_connect: {
                if (!connected) {
                    connectDevice();
                }
                else {
                    if (mChatService != null) {
                        mChatService.stop();
                        connected = false;
                        mChatService.setWasConnected(false);
                        mChatService.setNotConnected(true);
                    }
                }
                return true;
            }
            case R.id.discoverable: {
                // Ensure this device is discoverable by others
                ensureDiscoverable();
                return true;
            }
            case R.id.select_default: {
                Intent defaultIntent = new Intent(getActivity(), DeviceListActivity.class);
                startActivityForResult(defaultIntent, REQUEST_SELECT_DEFAULT_DEVICE);
                return true;
            }
            case R.id.settings: {
                Intent defaultIntent = new Intent(getActivity(), SettingsActivity.class);
                startActivity(defaultIntent);
            }
        }
        return false;
    }

    public String getContactName(Context context, String phoneNumber) {
        String contactName = null;
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

            return contactName;
        } catch (Exception e) {
            return phoneNumber;
        }
    }
}

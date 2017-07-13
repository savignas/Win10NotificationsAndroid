package savickas_ignas.win10notifications;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.ActionBar;
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
    private Button mTestSendButton;
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

    private boolean mIsBound;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
            } else if (mChatService == null) {
                setupChat(true);
            }
        }
        else {
            setupChat(false);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        /*if (mChatService != null) {
            mChatService.stop();
        }
        if (mIsBound) {
            getActivity().unbindService(this);
            mIsBound = false;
        }*/
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
        mTestSendButton = (Button) view.findViewById(R.id.button_send_test);
        mServiceStopButton = (Button) view.findViewById(R.id.button_service_stop);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        BluetoothChatService.MyBinder myBinder = (BluetoothChatService.MyBinder) binder;
        mChatService = myBinder.getService();
        mChatService.setHandler(mHandler);
        mChatService.start();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mChatService = null;
    }

    /**
     * Set up the UI and background operations for chat.
     */
    private void setupChat(boolean enabled) {

        // Initialize the array adapter for the conversation thread
        mConversationArrayAdapter = new ArrayAdapter<>(getActivity(), R.layout.message);

        mConversationView.setAdapter(mConversationArrayAdapter);

        // Initialize the send button with a listener that for click events
        mTestSendButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Send a message using content of the edit text widget
                View view = getView();
                if (null != view) {
                    sendMessage("TESTAS");
                }
            }
        });

        mServiceStopButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                View view = getView();
                if (null != view) {
                    Intent stopIntent = new Intent(getActivity(), BluetoothChatService.class);
                    stopIntent.setAction(Constants.STOP_FOREGROUND);
                    getActivity().startService(stopIntent);
                    mIsBound = false;
                    if (mChatService != null) {
                        mChatService.stop();
                    }
                    getActivity().unbindService(BluetoothChatFragment.this);
                }
            }
        });

        if (enabled) {
            // Initialize the BluetoothChatService to perform bluetooth connections
            Intent startIntent = new Intent(getActivity(), BluetoothChatService.class);
            getActivity().startService(startIntent);
            getActivity().bindService(startIntent, BluetoothChatFragment.this, Context.BIND_AUTO_CREATE);
            mIsBound = true;
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
    public void sendMessage(String message) {
        if (mChatService != null) {
            // Check that we're actually connected before trying anything
            if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
                Toast.makeText(getActivity(), R.string.not_connected, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(getActivity(), R.string.bt_not_enabled, Toast.LENGTH_SHORT).show();
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
                            setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));
                            mConversationArrayAdapter.clear();
                            break;
                        case BluetoothChatService.STATE_CONNECTING:
                            setStatus(R.string.title_connecting);
                            break;
                        case BluetoothChatService.STATE_LISTEN:
                        case BluetoothChatService.STATE_NONE:
                            setStatus(R.string.title_not_connected);
                            break;
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
                    String[] messageParts = readMessage.split(";");
                    mConversationArrayAdapter.add(mConnectedDeviceName + ": " + readMessage);
                    if (Objects.equals(messageParts[0], "1"))
                    {
                        mChatService.showNotification(messageParts[2], Integer.parseInt(messageParts[1]), messageParts[3]);
                    }
                    else
                    {
                        mChatService.cancelNotification(Integer.parseInt(messageParts[1]));
                    }
                    break;
                case Constants.MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
                    if (null != activity) {
                        Toast.makeText(activity, "Connected to "
                                + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    }
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

    private void getAddress(Intent intent)
    {
        String address = intent.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        String name = intent.getStringExtra(DeviceListActivity.EXTRA_DEVICE_NAME);
        SharedPreferences sharedPreferences = getActivity().getSharedPreferences("DEVICE", Context.MODE_PRIVATE);
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
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    setupChat(true);
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Toast.makeText(getActivity(), R.string.bt_not_enabled,
                            Toast.LENGTH_SHORT).show();
                    setupChat(false);
                }
        }
    }

    /**
     * Establish connection with other divice
     */
    private void connectDevice() {
        // Get the device MAC address
        SharedPreferences sharedPreferences = getActivity().getSharedPreferences("DEVICE", Context.MODE_PRIVATE);
        String address = sharedPreferences.getString("DEVICE_ADDRESS", "");
        if (Objects.equals(address, "")) {
            Intent defaultIntent = new Intent(getActivity(), DeviceListActivity.class);
            startActivityForResult(defaultIntent, REQUEST_CONNECT_DEVICE_SECURE);
            return;
        }
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
        mChatService.connect(device);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        this.menu = menu;
        inflater.inflate(R.menu.bluetooth_chat, menu);
        SharedPreferences sharedPreferences = getActivity().getSharedPreferences("DEVICE", Context.MODE_PRIVATE);
        String name = sharedPreferences.getString("DEVICE_NAME", "");
        if (!Objects.equals(name, "")) {
            MenuItem item = menu.findItem(R.id.secure_connect);
            item.setTitle(getString(R.string.secure_connect_to, name));
        }
        if (mBluetoothAdapter == null) {
            menu.setGroupEnabled(0, false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.secure_connect: {
                connectDevice();
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
        }
        return false;
    }

}

package savickas_ignas.win10notifications;

/**
 * Created by isavi on 2017-02-06.
 */

public interface Constants {
    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    public static final String STOP_FOREGROUND = "stopforeground";

    public static final int NOTIFICATION_ID = 30000;
    public static final int INFO_NOTIFICATION_ID = 30001;
}

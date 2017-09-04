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

    public static final String STOP_FOREGROUND_ACTION = "win10notifications.STOP_FOREGROUND";

    public static final int SRV_NOTIFICATION_ID = 30000;
    public static final int INFO_NOTIFICATION_ID = 30001;

    public static final String NOTIFICATION_DELETED_ACTION = "win10notifications.NOTIFICATION_DELETED";
    public static final String NOTIFICATION_LISTENER_POSTED_ACTION = "win10notifications.NOTIFICATION_LISTENER_POSTED";
    public static final String NOTIFICATION_LISTENER_REMOVED_ACTION = "win10notifications.NOTIFICATION_LISTENER_REMOVED";
    public static final String NOTIFICATION_LISTENER_CANCELED_ACTION = "win10notifications.NOTIFICATION_LISTENER_CANCELED";
    public static final String NOTIFICATION_LISTENER_STATE = "win10notifications.NOTIFICATION_LISTENER_STATE";

    public static final int MY_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION = 10;
}

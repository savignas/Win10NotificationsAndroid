package savickas_ignas.win10notifications;


import android.Manifest;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.SwitchPreference;
import android.support.annotation.NonNull;
import android.support.v7.app.ActionBar;
import android.preference.PreferenceFragment;
import android.view.MenuItem;
import android.support.v4.app.NavUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A {@link PreferenceActivity} that presents a set of application settings. On
 * handset devices, settings are presented as a single list. On tablets,
 * settings are split by category, with category headers shown to the left of
 * the list of settings.
 * <p>
 * See <a href="http://developer.android.com/design/patterns/settings.html">
 * Android Design: Settings</a> for design guidelines and the <a
 * href="http://developer.android.com/guide/topics/ui/settings.html">Settings
 * API Guide</a> for more information on developing a Settings UI.
 */
public class SettingsActivity extends AppCompatPreferenceActivity {

    private static SharedPreferences sharedPreferences;

    private static void goToNotificationListenerSettings(Context context)
    {
        Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
        context.startActivity(intent);
    }

    private static Preference.OnPreferenceChangeListener sSendNotificationsPreferenceChangeListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object value) {
            goToNotificationListenerSettings(preference.getContext());
            return false;
        }
    };

    /**
     * Helper method to determine if the device has an extra-large screen. For
     * example, 10" tablets are extra-large.
     */
    private static boolean isXLargeTablet(Context context) {
        return (context.getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }

    /**
     * Binds a preference's summary to its value. More specifically, when the
     * preference's value is changed, its summary (line of text below the
     * preference title) is updated to reflect the value. The summary is also
     * immediately updated upon calling this method. The exact display format is
     * dependent on the type of preference.
     */

    private static void preferenceChangeListener(Preference preference) {
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(sSendNotificationsPreferenceChangeListener);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupActionBar();
        sharedPreferences = getSharedPreferences("DEVICE", Context.MODE_PRIVATE);
    }

    /**
     * Set up the {@link android.app.ActionBar}, if the API is available.
     */
    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            // Show the Up button in the action bar.
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            if (!super.onMenuItemSelected(featureId, item)) {
                NavUtils.navigateUpFromSameTask(this);
            }
            return true;
        }
        return super.onMenuItemSelected(featureId, item);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onIsMultiPane() {
        return isXLargeTablet(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void onBuildHeaders(List<Header> target) {
        loadHeadersFromResource(R.xml.pref_headers, target);
    }

    /**
     * This method stops fragment injection in malicious applications.
     * Make sure to deny any unknown fragments here.
     */
    protected boolean isValidFragment(String fragmentName) {
        return PreferenceFragment.class.getName().equals(fragmentName)
                || GeneralPreferenceFragment.class.getName().equals(fragmentName)
                || SendNotificationsPreferenceFragment.class.getName().equals(fragmentName);
    }

    /**
     * This fragment shows general preferences only. It is used when the
     * activity is showing a two-pane settings UI.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static class GeneralPreferenceFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_general);
            setHasOptionsMenu(true);
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            int id = item.getItemId();
            if (id == android.R.id.home) {
                startActivity(new Intent(getActivity(), SettingsActivity.class));
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
    }

    /**
     * This fragment shows notification preferences only. It is used when the
     * activity is showing a two-pane settings UI.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static class SendNotificationsPreferenceFragment extends PreferenceFragment {
        private SwitchPreference readSMSSwitchPreference;
        private SwitchPreference readStateSwitchPreference;

        private final BroadcastReceiver mNotificationListenerState = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                if (action.equals(Constants.NOTIFICATION_LISTENER_STATE)) {
                    boolean state = intent.getBooleanExtra("notificationListenerState", false);
                    SwitchPreference switchPreference = (SwitchPreference) findPreference("send_notifications_enabled");
                    switchPreference.setChecked(state);
                }
            }
        };

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_send_notifications);
            setHasOptionsMenu(true);
            getActivity().registerReceiver(mNotificationListenerState, new IntentFilter(Constants.NOTIFICATION_LISTENER_STATE));

            boolean state = sharedPreferences.getBoolean("NOTIFICATION_LISTENER", false);
            SwitchPreference sendNotificationsSwitchPreference = (SwitchPreference) findPreference("send_notifications_enabled");
            sendNotificationsSwitchPreference.setChecked(state);

            readSMSSwitchPreference = (SwitchPreference) findPreference("read_sms_enabled");

            readSMSSwitchPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if ((boolean) newValue) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (preference.getContext().checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                                    != PackageManager.PERMISSION_GRANTED ||
                                    preference.getContext().checkSelfPermission(Manifest.permission.READ_CONTACTS)
                                            != PackageManager.PERMISSION_GRANTED) {
                                final String[] permissions = new String[] {Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.READ_CONTACTS};
                                requestPermissions(permissions,
                                        Constants.MY_PERMISSIONS_RECEIVE_SMS);
                                return false;
                            }
                        }
                    }
                    return true;
                }
            });

            readStateSwitchPreference = (SwitchPreference) findPreference("read_state_enabled");

            readStateSwitchPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if ((boolean) newValue) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (preference.getContext().checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                                    != PackageManager.PERMISSION_GRANTED ||
                                    preference.getContext().checkSelfPermission(Manifest.permission.READ_CONTACTS)
                                            != PackageManager.PERMISSION_GRANTED) {
                                final String[] permissions = new String[] {Manifest.permission.READ_PHONE_STATE,
                                        Manifest.permission.READ_CONTACTS};
                                requestPermissions(permissions,
                                        Constants.MY_PERMISSIONS_INCOMING_CALL);
                                return false;
                            }
                        }
                    }
                    return true;
                }
            });

            preferenceChangeListener(sendNotificationsSwitchPreference);

            final PackageManager pm = getActivity().getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            final List<ResolveInfo> apps = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA);
            Collections.sort(apps, new Comparator<ResolveInfo>() {
                @Override
                public int compare(ResolveInfo o1, ResolveInfo o2) {
                    return o1.loadLabel(pm).toString().compareTo(o2.loadLabel(pm).toString());
                }
            });

            List<CharSequence> appsNames = new ArrayList<>();
            List<CharSequence> appsPackageNames = new ArrayList<>();
            for (ResolveInfo app: apps)
            {
                if (!app.activityInfo.packageName.equals("savickas_ignas.win10notifications"))
                {
                    CharSequence name = app.loadLabel(pm);
                    CharSequence packageName = app.activityInfo.packageName;
                    appsNames.add(name);
                    appsPackageNames.add(packageName);
                }
            }

            MultiSelectListPreference appsListView = (MultiSelectListPreference) findPreference("apps_list");
            appsListView.setEntries(appsNames.toArray(new CharSequence[]{}));
            appsListView.setEntryValues(appsPackageNames.toArray(new CharSequence[]{}));
        }

        @Override
        public void onResume() {
            super.onResume();
            boolean state = sharedPreferences.getBoolean("NOTIFICATION_LISTENER", false);
            SwitchPreference switchPreference = (SwitchPreference) findPreference("send_notifications_enabled");
            switchPreference.setChecked(state);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            getActivity().unregisterReceiver(mNotificationListenerState);
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            int id = item.getItemId();
            if (id == android.R.id.home) {
                startActivity(new Intent(getActivity(), SettingsActivity.class));
                return true;
            }
            return super.onOptionsItemSelected(item);
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
            switch (requestCode) {
                case Constants.MY_PERMISSIONS_RECEIVE_SMS: {
                    // If request is cancelled, the result arrays are empty.
                    if (grantResults.length > 0
                            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        // permission was granted, yay! Do the
                        // contacts-related task you need to do.
                        readSMSSwitchPreference.setChecked(true);
                    } else {
                        readSMSSwitchPreference.setChecked(false);
                    }
                    break;
                }
                case Constants.MY_PERMISSIONS_INCOMING_CALL: {
                    if (grantResults.length > 0
                            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        // permission was granted, yay! Do the
                        // contacts-related task you need to do.
                        readStateSwitchPreference.setChecked(true);
                    } else {
                        readStateSwitchPreference.setChecked(false);
                    }
                    break;
                }

                // other 'case' lines to check for other
                // permissions this app might request
            }
        }
    }
}

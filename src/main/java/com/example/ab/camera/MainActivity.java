package com.example.ab.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaActionSound;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.support.v13.app.ActivityCompat;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

import cc.mvdan.accesspoint.WifiApControl;

public class MainActivity extends Activity {
    public static TextToSpeech levitan;

    private static final String TAG = MainActivity.class.getName();
    private final static int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_WRITE_SETTINGS = 2;
    private static final int TTS_CHECK_CODE = 3;
    ToggleButton toggleButtonService;
    CheckBox checkBoxFaceDetection;
    TextView textFaceDetection;
    CheckBox checkBoxLowLight;
    TextView textLowLight;
    CheckBox checkBoxPreview;
    TextView textPreview;
    CheckBox checkBoxCnn;
    TextView textCnn;
    CheckBox checkBoxRecording;
    TextView textRecording0;
    TextView textRecording1;
    TextView textCounter0;
    TextView textCounter1;

    private final BroadcastReceiver mServiceListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra(CameraService.EXTRA_ID);
            String msg = intent.getStringExtra(CameraService.EXTRA_MESSAGE);
            if (id != null) {
                switch (id) {
                    case "counter0":
                        textCounter0.setText(msg);
                        break;
                    case "counter1":
                        textCounter1.setText(msg);
                        break;
                    case "face":
                        textFaceDetection.setText(msg);
                        break;
                    case "recording on0":
                        textRecording0.setText(msg);
                        break;
                    case "recording off0":
                        textRecording0.setText("");
                        break;
                    case "recording on1":
                        textRecording1.setText(msg);
                        break;
                    case "recording off1":
                        textRecording1.setText("");
                        break;
                    default:
                        Log.e(TAG, "unknown id " + id);
                        break;
                }
            }
        }
    };
    private String[] mPermissions;
    private ServiceInfo[] mServices;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == TTS_CHECK_CODE) {
            if (resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
                // success, create the TTS instance
                levitan = new TextToSpeech(this, status -> {
                    Log.e(TAG, "status " + status);
                });
            } else {
                // missing data, install it
                Intent installIntent = new Intent();
                installIntent.setAction(
                        TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                startActivity(installIntent);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Intent checkIntent = new Intent();
        checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
        startActivityForResult(checkIntent, TTS_CHECK_CODE);

        levitan = new TextToSpeech(getApplicationContext(), status -> {
            if(status != TextToSpeech.ERROR) {
                levitan.setLanguage(Locale.US);
            }
        });

        // sounds
        l(TAG, "Initializing sounds...");
        toggleButtonService = (ToggleButton) findViewById(R.id.toggleButtonService);
        toggleButtonService.setOnClickListener(v -> {
            boolean on = ((ToggleButton) v).isChecked();
            if (on) {
                startServices();
            } else {
                stopServices();
            }
        });
        toggleButtonService.setFocusable(true);
        toggleButtonService.setFocusableInTouchMode(true);
        toggleButtonService.requestFocus();
        checkBoxFaceDetection = (CheckBox) findViewById(R.id.checkBoxFaceDetection_0);
        textFaceDetection = (TextView) findViewById(R.id.textFaceDetection_0);
        checkBoxLowLight = (CheckBox) findViewById(R.id.checkBoxLowLight_0);
        textLowLight = (TextView) findViewById(R.id.textLowLight_0);
        checkBoxPreview = (CheckBox) findViewById(R.id.checkBoxPreview);
        textPreview = (TextView) findViewById(R.id.textPreview_0);
        checkBoxCnn = (CheckBox) findViewById(R.id.checkBoxCnn_0);
        textCnn = (TextView) findViewById(R.id.textCnn_0);
        checkBoxRecording = (CheckBox) findViewById(R.id.checkBoxRecording_0);
        textRecording0 = (TextView) findViewById(R.id.textRecording_0);
        textRecording1 = (TextView) findViewById(R.id.textRecording_1);
        textCounter0 = (TextView) findViewById(R.id.counter0);
        textCounter1 = (TextView) findViewById(R.id.counter1);

        IntentFilter filter = new IntentFilter();
        String packageName = getPackageName();
        try {
            PackageInfo packageInfo = getApplication()
                    .getPackageManager()
                    .getPackageInfo(packageName, PackageManager.GET_SERVICES);
            mServices = packageInfo.services;
            for (ServiceInfo serviceInfo : mServices) {
                String name = serviceInfo.name;
                filter.addAction(name);
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        try {
            PackageInfo packageInfo = getApplication()
                    .getPackageManager()
                    .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS);
            mPermissions = packageInfo.requestedPermissions;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        getApplication().registerReceiver(mServiceListener, filter);

        startServices();
    }

    public void l(String TAG, String msg) {
        levitan.speak(msg, TextToSpeech.QUEUE_FLUSH, null);
    }
    
    private void startServices() {
        if (!hasAllPermissionsGranted()) {
            requestPermissions();
        } else {
            setApMode(true);
            for (ServiceInfo serviceInfo : mServices) {
                String name = serviceInfo.name;
                try {
                    Class clazz = getClassLoader().loadClass(name);
                    Uri data = null;
                    if (name.contains(CameraService.class.getSimpleName())) {
                        data = Uri.parse("camera://start?sticky=1&fd=1");
                    }
                    startService(new Intent("start", data, this, clazz));
                    toggleButtonService.setChecked(true);
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                    new MediaActionSound().play(MediaActionSound.START_VIDEO_RECORDING);
                }
            }
            new MediaActionSound().play(MediaActionSound.START_VIDEO_RECORDING);
        }
    }

    private void stopServices() {
        l(TAG, "Stopping services");
        for (ServiceInfo serviceInfo : mServices) {
            String name = serviceInfo.name;
            try {
                Class clazz = getClassLoader().loadClass(name);
                stopService(new Intent(this, clazz));
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        new MediaActionSound().play(MediaActionSound.STOP_VIDEO_RECORDING);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(mServiceListener);
        } catch (Exception e) {
            //ignore
            e.printStackTrace();
        }
        l(TAG, "onDestroy()");
        //stopServices();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NotNull String[] permissions, int[] grantResults) {
        if ((requestCode == REQUEST_WRITE_SETTINGS || requestCode == REQUEST_PERMISSIONS) && permissions != null && permissions.length > 0) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    if (permissions[i].equals(Manifest.permission.WRITE_SETTINGS)) {
                        l(TAG, "onRequestPermissionsResult(): we need permission WRITE_SETTINGS");
                        Toast.makeText(this, R.string.request_permission, Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    } else {
                        l(TAG, "onRequestPermissionsResult(): we need permission " + permissions[i]);
                        Toast.makeText(this, R.string.request_permission, Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                }
            }
            l(TAG, "onRequestPermissionsResult(): we have permissions, starting service...");
            startServices();
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    /*
     * Requests permissions necessary to use camera and save pictures.
     */
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                new AlertDialog.Builder(this)
                        .setMessage("Allow reading/writing the system settings? Necessary to set up access points.")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivityForResult(intent, REQUEST_WRITE_SETTINGS);
                            }
                        }).show();
                return;
            }
        }
        if (shouldShowRationale()) {
            permissionConfirmationDialog(this);
        } else {
            ActivityCompat.requestPermissions(this, mPermissions, REQUEST_PERMISSIONS);
        }
    }

    /*
     * Whether to show UI with rationale for requesting the permissions.
     */
    private boolean shouldShowRationale() {
        for (String permission : mPermissions) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                return true;
            }
        }
        return false;
    }

    /*
     * Tells whether all the necessary permissions are granted to this app.
     *
     * @return True if all the required permissions are granted.
     */
    private boolean hasAllPermissionsGranted() {
        // special check for write settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            return false;
        }

        for (String permission : mPermissions) {
            if (android.support.v4.app.ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                if (!permission.equals(Manifest.permission.WRITE_SETTINGS)) {
                    return false;
                }
            }
        }

        return true;
    }


    /**
     * A dialog that explains about the necessary permissions.
     */
    private void permissionConfirmationDialog(Activity ctx) {
        new AlertDialog.Builder(ctx)
                .setMessage(R.string.request_permission)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        ActivityCompat.requestPermissions(MainActivity.this, mPermissions, REQUEST_PERMISSIONS))
                .setNegativeButton(android.R.string.cancel,
                        (dialog, which) -> finish())
                .create()
                .show();
    }

    public void setApMode(boolean on) {
        WifiApControl apControl = WifiApControl.getInstance(this);
        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.SSID = "Nauto2-ab";
//        wifiConfiguration.preSharedKey = "SomeKey";
        wifiConfiguration.hiddenSSID = false;
//        wifiConfiguration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
//        wifiConfiguration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
//        wifiConfiguration.allowedKeyManagement.set(4);
//        wifiConfiguration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
//        wifiConfiguration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        boolean err = apControl.setEnabled(wifiConfiguration, on);
        if (!err) {
            l(TAG, "unable to set AP mode");
        }
    }
}

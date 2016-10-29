package com.example.ab.camera;

import android.app.Application;
import android.speech.tts.TextToSpeech;
import android.util.Log;

/**
 * Created by ab on 10/29/16.
 */

public class App extends Application {
    public static TextToSpeech levitan;

    public static void l(String TAG, String msg) {
        Log.d(TAG, msg);
    }

    public static void e(String TAG, String msg) {
        Log.e(TAG, msg);

    }

    public static void s(String TAG, String msg) {
        MainActivity.levitan.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null);
    }
}

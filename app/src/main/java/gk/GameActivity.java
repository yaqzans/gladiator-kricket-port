package gk;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import defpackage.GC;

public class GameActivity extends Activity {
    private static GC midlet;
    private AudioManager audioManager;
    private Object focusRequest;   // AudioFocusRequest on API 26+

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // headphones pulled out mid-match: pause it rather than blast the speaker
            Host.interrupt();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        GameView view = new GameView(this);
        setContentView(view);
        Host.attach(this, view, getIntent().getBooleanExtra("autoplay", false));
        Host.debugAim = getIntent().getIntExtra("aim", 0);
        hideSystemBars(view);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        requestAudioFocus();

        if (midlet == null) {
            Host.armRestore();
            midlet = new GC();
            // new e() prepares five MediaPlayers and parses the map; keep it off the UI thread
            new Thread(midlet::startApp, "gk-start").start();
        }
    }

    private void hideSystemBars(View view) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = view.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars(getWindow().getDecorView());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(noisy);
        Host.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        requestAudioFocus();
        Host.resume();
    }

    /**
     * A call, an alarm or another app's sound can take the audio while the game stays on
     * screen (a call often arrives as a banner, which does not pause the activity). Pause the
     * match then, the way an incoming call did on the original phone.
     */
    private void requestAudioFocus() {
        AudioManager.OnAudioFocusChangeListener l = change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                Host.interrupt();
            } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                Host.interruptionOver();
            }
        };
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(l)
                        .build();
            }
            audioManager.requestAudioFocus((AudioFocusRequest) focusRequest);
        } else {
            audioManager.requestAudioFocus(l, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    /** Back = right soft key (pause in a match, back in menus). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) {
                Host.press(Host.KEY_RSK);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Host.release(Host.KEY_RSK);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}

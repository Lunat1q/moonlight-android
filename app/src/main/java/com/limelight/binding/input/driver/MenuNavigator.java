package com.limelight.binding.input.driver;

import android.app.Instrumentation;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.limelight.nvstream.input.ControllerPacket;

// Android never sees driver-owned controllers (e.g. the Steam Controller) as gamepads, so while
// no stream is using them, turn their D-pad, left stick, A and B into key presses for our own UI.
// ponytail: no key repeat while held; add a repeat timer if scrolling long lists gets tedious.
class MenuNavigator {
    private static final float STICK_THRESHOLD = 0.5f;
    private static final int[][] KEYS = {
            {ControllerPacket.UP_FLAG, KeyEvent.KEYCODE_DPAD_UP},
            {ControllerPacket.DOWN_FLAG, KeyEvent.KEYCODE_DPAD_DOWN},
            {ControllerPacket.LEFT_FLAG, KeyEvent.KEYCODE_DPAD_LEFT},
            {ControllerPacket.RIGHT_FLAG, KeyEvent.KEYCODE_DPAD_RIGHT},
            {ControllerPacket.A_FLAG, KeyEvent.KEYCODE_DPAD_CENTER},
            {ControllerPacket.B_FLAG, KeyEvent.KEYCODE_BACK},
    };

    private final SparseIntArray pressed = new SparseIntArray(); // controller id -> flags
    private final Instrumentation instrumentation = new Instrumentation();
    private HandlerThread thread;
    private Handler handler;

    // Driver threads call this with the raw driver state (stick Y is down-positive)
    synchronized void update(int controllerId, int buttons, float leftStickX, float leftStickY) {
        int state = buttons;
        if (leftStickX < -STICK_THRESHOLD) state |= ControllerPacket.LEFT_FLAG;
        if (leftStickX > STICK_THRESHOLD) state |= ControllerPacket.RIGHT_FLAG;
        if (leftStickY < -STICK_THRESHOLD) state |= ControllerPacket.UP_FLAG;
        if (leftStickY > STICK_THRESHOLD) state |= ControllerPacket.DOWN_FLAG;

        int changed = pressed.get(controllerId) ^ state;
        pressed.put(controllerId, state);
        for (int[] key : KEYS) {
            if ((changed & key[0]) != 0) {
                inject(key[1], (state & key[0]) != 0);
            }
        }
    }

    // Release whatever a controller was holding, e.g. when it disconnects or a stream takes over
    synchronized void release(int controllerId) {
        update(controllerId, 0, 0, 0);
        pressed.delete(controllerId);
    }

    synchronized void releaseAll() {
        while (pressed.size() > 0) {
            release(pressed.keyAt(0));
        }
    }

    void inject(int keyCode, boolean down) { // package-private for MenuNavigatorTest
        if (handler == null) {
            // sendKeySync blocks and must stay off the main thread
            thread = new HandlerThread("MenuNavigator");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        handler.post(() -> {
            try {
                // Only lands in our own windows; fails when another app is focused, which is fine
                instrumentation.sendKeySync(event);
            } catch (RuntimeException ignored) {
            }
        });
    }

    synchronized void quit() {
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }
}

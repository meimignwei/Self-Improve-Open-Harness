package com.openharness.extensions.voice;

import com.openharness.extensions.state.AppStateStore;

/**
 * Voice input mode toggle.
 * Java equivalent of Python voice/voice_mode.py.
 */
public class VoiceMode {

    /**
     * Toggles voice mode on the application state.
     * @return true if toggled successfully, false if voice is unavailable.
     */
    public static boolean toggle(AppStateStore state) {
        VoiceDiagnostics diag = VoiceDiagnostics.inspect();
        if (!diag.available()) return false;
        state.set(state.get().withVoiceEnabled(!state.get().voiceEnabled()));
        return true;
    }
}

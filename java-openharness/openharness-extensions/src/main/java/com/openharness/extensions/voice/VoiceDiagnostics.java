package com.openharness.extensions.voice;

/**
 * Inspects voice recording capabilities.
 * Java equivalent of Python voice/voice_mode.py VoiceDiagnostics.
 */
public class VoiceDiagnostics {

    private final boolean recordingAvailable;
    private final boolean sttProviderAvailable;
    private final String reason;

    public VoiceDiagnostics(boolean recordingAvailable, boolean sttProviderAvailable, String reason) {
        this.recordingAvailable = recordingAvailable;
        this.sttProviderAvailable = sttProviderAvailable;
        this.reason = reason;
    }

    public static VoiceDiagnostics inspect() {
        boolean hasRecorder = commandExists("sox") || commandExists("ffmpeg") || commandExists("arecord");
        return new VoiceDiagnostics(hasRecorder, false,
                hasRecorder ? null : "No recording tool found (sox/ffmpeg/arecord)");
    }

    public boolean available() {
        return recordingAvailable;
    }

    public boolean recordingAvailable() { return recordingAvailable; }
    public boolean sttProviderAvailable() { return sttProviderAvailable; }
    public String reason() { return reason; }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}

package com.openharness.extensions.vim;

/**
 * Vim-style keyboard navigation mode.
 * Java equivalent of Python vim/__init__.py and vim/transitions.py.
 */
public class VimMode {

    private volatile boolean enabled = false;

    public boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public VimAction handleKey(String key) {
        if (!enabled) return VimAction.PASSTHROUGH;
        return switch (key) {
            case "i" -> VimAction.ENTER_INSERT;
            case "h" -> VimAction.CURSOR_LEFT;
            case "j" -> VimAction.CURSOR_DOWN;
            case "k" -> VimAction.CURSOR_UP;
            case "l" -> VimAction.CURSOR_RIGHT;
            case "", "ESC" -> VimAction.NORMAL_MODE;
            default -> VimAction.IGNORE;
        };
    }

    public enum VimAction {
        PASSTHROUGH, ENTER_INSERT, CURSOR_LEFT, CURSOR_DOWN,
        CURSOR_UP, CURSOR_RIGHT, NORMAL_MODE, IGNORE
    }
}

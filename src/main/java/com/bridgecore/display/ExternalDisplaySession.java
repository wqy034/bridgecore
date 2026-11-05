package com.bridgecore.display;

/** Optional content producer. No CC types: the display remains usable without CC. */
public interface ExternalDisplaySession {
    boolean isActive();
    void tick();
    void surfaceChanged();
    void touch(int x, int y, boolean sneak);
    void revoke(String reason);
    void close();
}

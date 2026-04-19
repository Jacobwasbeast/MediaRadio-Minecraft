package net.jacobwasbeast.mediaradio.compat;

public final class RadioAccessoryHooks {

    private static volatile RadioAccessoryProvider instance = RadioAccessoryProvider.NO_OP;

    private RadioAccessoryHooks() {
    }

    public static void set(RadioAccessoryProvider provider) {
        instance = provider == null ? RadioAccessoryProvider.NO_OP : provider;
    }

    public static RadioAccessoryProvider get() {
        return instance;
    }
}

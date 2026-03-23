package org.fentanylsolutions.minemoticon;

// Tracks whether the server has the mod installed.
// The GTNHLib config sync packet itself proves the server has the mod,
// so we set this flag when synced config values differ from defaults.
// Reset on every world join to handle unexpected disconnects.
public class ServerCapabilities {

    private static boolean serverHasMod = false;

    public static boolean hasServerMod() {
        return serverHasMod;
    }

    public static void setServerHasMod(boolean value) {
        serverHasMod = value;
    }

    public static void reset() {
        serverHasMod = false;
    }
}

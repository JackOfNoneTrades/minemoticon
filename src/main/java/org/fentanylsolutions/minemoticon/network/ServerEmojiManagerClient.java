package org.fentanylsolutions.minemoticon.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.fentanylsolutions.minemoticon.ServerCapabilities;
import org.fentanylsolutions.minemoticon.server.PersistentEmoteStore.OwnerEmojiEntry;

public final class ServerEmojiManagerClient {

    private static final List<OwnerEmojiEntry> ENTRIES = new ArrayList<>();
    private static long usedBytes;
    private static long quotaBytes;
    private static String statusMessage;
    private static boolean loading;
    private static long revision;

    private ServerEmojiManagerClient() {}

    public static synchronized void requestList() {
        if (!ServerCapabilities.hasServerMod()) {
            loading = false;
            statusMessage = "Server does not have Minemoticon installed";
            revision++;
            return;
        }

        loading = true;
        NetworkHandler.INSTANCE.sendToServer(new PacketServerEmojiListRequest());
    }

    public static void delete(String checksum) {
        if (!ServerCapabilities.hasServerMod()) {
            return;
        }
        loading = true;
        NetworkHandler.INSTANCE.sendToServer(new PacketServerEmojiDelete(checksum));
    }

    public static void clearMine() {
        if (!ServerCapabilities.hasServerMod()) {
            return;
        }
        loading = true;
        NetworkHandler.INSTANCE.sendToServer(new PacketServerEmojiClearMine());
    }

    public static synchronized void onListResponse(PacketServerEmojiListResponse message) {
        ENTRIES.clear();
        ENTRIES.addAll(message.entries);
        usedBytes = message.usedBytes;
        quotaBytes = message.quotaBytes;
        statusMessage = message.statusMessage;
        loading = false;
        revision++;
    }

    public static synchronized Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(ENTRIES), usedBytes, quotaBytes, statusMessage, loading, revision);
    }

    public static synchronized void reset() {
        ENTRIES.clear();
        usedBytes = 0L;
        quotaBytes = 0L;
        statusMessage = null;
        loading = false;
        revision++;
    }

    public static final class Snapshot {

        public final List<OwnerEmojiEntry> entries;
        public final long usedBytes;
        public final long quotaBytes;
        public final String statusMessage;
        public final boolean loading;
        public final long revision;

        private Snapshot(List<OwnerEmojiEntry> entries, long usedBytes, long quotaBytes, String statusMessage,
            boolean loading, long revision) {
            this.entries = Collections.unmodifiableList(entries);
            this.usedBytes = usedBytes;
            this.quotaBytes = quotaBytes;
            this.statusMessage = statusMessage;
            this.loading = loading;
            this.revision = revision;
        }
    }
}

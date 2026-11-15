package androidx.media3.session;

import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.session.legacy.MediaSessionCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Media3 mirrors the whole queue into the platform session that Bluetooth, the system UI and cars read.
 * With "Play all" that is the whole library, which ran the Bluetooth process out of memory. The app takes
 * the timeline away from the platform session, so Media3 leaves its queue empty, and puts only the tracks
 * around the current one there. This sits in Media3's package because the platform session isn't public;
 * if Media3 changes it, the build fails here.
 */
public final class PlatformQueue {
    private PlatformQueue() {}

    public static void set(MediaSession session, Player player, int around) {
        Timeline t = player.getCurrentTimeline();
        List<MediaSessionCompat.QueueItem> items = null;
        if (!t.isEmpty()) {
            int cur = player.getCurrentMediaItemIndex();
            int to = Math.min(t.getWindowCount(), cur + around + 1);
            items = new ArrayList<>();
            Timeline.Window w = new Timeline.Window();
            // Queue item ids are timeline indices, as Media3 uses them for the active item and skipping to an item.
            for (int i = Math.max(0, cur - around); i < to; i++) items.add(LegacyConversions.convertToQueueItem(t.getWindow(i, w).mediaItem, i, null));
        }
        session.getImpl().getMediaSessionLegacyStub().getSessionCompat().setQueue(items);
    }
}

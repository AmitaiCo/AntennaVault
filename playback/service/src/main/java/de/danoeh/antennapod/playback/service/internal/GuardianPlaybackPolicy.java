package de.danoeh.antennapod.playback.service.internal;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.preferences.GuardianGate;

public final class GuardianPlaybackPolicy {

    private GuardianPlaybackPolicy() {
    }

    public static boolean isBlocked(FeedMedia media) {
        Feed feed = media.getItem() != null ? media.getItem().getFeed() : null;
        return feed != null && feed.getState() == Feed.STATE_NOT_SUBSCRIBED && !GuardianGate.isOpen();
    }
}

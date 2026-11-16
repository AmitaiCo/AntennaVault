package de.danoeh.antennapod.storage.database;

public class GuardianGateClosedException extends IllegalStateException {
    public GuardianGateClosedException(String feedUrl) {
        super("Guardian gate is closed, refusing to subscribe to " + feedUrl);
    }
}

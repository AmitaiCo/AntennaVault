package de.danoeh.antennapod.storage.database;

public class GuardianGateClosedException extends IllegalStateException {
    public GuardianGateClosedException(String source) {
        super("Guardian gate is closed, refusing to add subscriptions from " + source);
    }
}

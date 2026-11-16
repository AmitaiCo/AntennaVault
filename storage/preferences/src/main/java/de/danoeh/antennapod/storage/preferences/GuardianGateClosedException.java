package de.danoeh.antennapod.storage.preferences;

public class GuardianGateClosedException extends RuntimeException {
    public GuardianGateClosedException(String action) {
        super("Guardian gate is closed, refusing to " + action);
    }
}

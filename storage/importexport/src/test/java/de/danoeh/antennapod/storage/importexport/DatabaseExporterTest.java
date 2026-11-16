package de.danoeh.antennapod.storage.importexport;

import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import de.danoeh.antennapod.storage.preferences.GuardianGateClosedException;
import de.danoeh.antennapod.storage.preferences.GuardianGateTestUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
public class DatabaseExporterTest {

    @Test
    public void closedGateRefusesDatabaseImportBeforeReadingIt() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        GuardianGateTestUtil.configureClosed(context);
        File backup = File.createTempFile("backup", ".db");

        assertThrows(GuardianGateClosedException.class,
                () -> DatabaseExporter.importBackup(Uri.fromFile(backup), context));
        assertFalse(context.getDatabasePath("Antennapod.db_tmp").exists());
    }
}

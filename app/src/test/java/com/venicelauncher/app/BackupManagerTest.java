package com.venicelauncher.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackupManagerTest {

    @Test
    public void testGenerateStandardBackupFilenameMatchesFormat() {
        String filename = BackupManager.generateStandardBackupFilename();
        assertNotNull(filename);
        assertTrue("Filename must start with prefix", filename.startsWith(BackupManager.BACKUP_PREFIX));
        assertTrue("Filename must end with .zip", filename.endsWith(".zip"));
        assertTrue("Filename format should match venice_backup_YYYY-MM-DD_HH-mm-ss.zip",
                filename.matches("venice_backup_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.zip"));
    }

    @Test
    public void testExtractTimestampFromBackupNameSupportsBothFormats() {
        long ts1 = BackupManager.extractTimestampFromBackupName("venice_backup_2026-11-03_01-15-30.zip");
        long ts2 = BackupManager.extractTimestampFromBackupName("venice_backup_2026-11-03_01-10-00.zip");
        long tsCompact = BackupManager.extractTimestampFromBackupName("venice_backup_20261103_011530.zip");

        assertTrue("Parsed timestamp must be positive", ts1 > 0);
        assertTrue("Parsed timestamp must be positive", ts2 > 0);
        assertTrue("ts1 must be after ts2", ts1 > ts2);
        assertEquals("Both date patterns should yield identical epoch time", ts1, tsCompact);

        long invalid = BackupManager.extractTimestampFromBackupName("random_archive.zip");
        assertEquals("Invalid filename should return 0", 0, invalid);
    }

    @Test
    public void testIsValidAccountsJsonValidation() {
        assertFalse(BackupManager.isValidAccountsJson(null));
        assertFalse(BackupManager.isValidAccountsJson(""));
        assertFalse(BackupManager.isValidAccountsJson("   "));
        assertFalse(BackupManager.isValidAccountsJson("[]"));
        assertFalse(BackupManager.isValidAccountsJson("{}"));
        assertFalse(BackupManager.isValidAccountsJson("invalid-json"));

        assertTrue(BackupManager.isValidAccountsJson("[{\"id\":\"acc_1\",\"name\":\"Account 1\"}]"));
    }
}

package com.venicelauncher.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class BackupManager {
    private static final String TAG = "VeniceBackupManager";
    public static final int CURRENT_BACKUP_SCHEMA_VERSION = 3;

    public static final String LATEST_BACKUP_ZIP = "venice_backup_latest.zip";
    public static final String LATEST_BACKUP_JSON = "venice_backup_latest.json";
    private static final int MAX_ROTATED_BACKUPS = 5;

    private static final String PREF_BACKUP = "venice_backup_prefs";
    private static final String KEY_AUTO_BACKUP_TREE_URI = "auto_backup_folder_uri";
    private static final String KEY_AUTO_BACKUP_DISPLAY_NAME = "auto_backup_folder_name";
    private static final String KEY_LAST_AUTO_BACKUP_TIME = "last_auto_backup_time";

    private final Context context;
    private final SharedPreferences backupPrefs;

    public BackupManager(Context context) {
        this.context = context.getApplicationContext();
        this.backupPrefs = this.context.getSharedPreferences(PREF_BACKUP, Context.MODE_PRIVATE);
    }

    public Uri getCustomBackupTreeUri() {
        String uriStr = backupPrefs.getString(KEY_AUTO_BACKUP_TREE_URI, null);
        if (uriStr != null) {
            try {
                return Uri.parse(uriStr);
            } catch (Exception e) {
                Log.w(TAG, "Invalid custom backup URI stored: " + uriStr);
            }
        }
        return null;
    }

    public void setCustomBackupTreeUri(Uri uri, String displayName) {
        if (uri != null) {
            backupPrefs.edit()
                    .putString(KEY_AUTO_BACKUP_TREE_URI, uri.toString())
                    .putString(KEY_AUTO_BACKUP_DISPLAY_NAME, displayName != null ? displayName : formatTreeUriToDisplayPath(uri))
                    .apply();
        } else {
            backupPrefs.edit()
                    .remove(KEY_AUTO_BACKUP_TREE_URI)
                    .remove(KEY_AUTO_BACKUP_DISPLAY_NAME)
                    .apply();
        }
    }

    public String getFormattedAutoBackupLocation() {
        String customName = backupPrefs.getString(KEY_AUTO_BACKUP_DISPLAY_NAME, null);
        if (customName != null && !customName.isEmpty()) {
            return customName;
        }
        Uri customUri = getCustomBackupTreeUri();
        if (customUri != null) {
            return formatTreeUriToDisplayPath(customUri);
        }
        return "Documents/VeniceAI_Backups (Default)";
    }

    public static String formatTreeUriToDisplayPath(Uri uri) {
        if (uri == null) return "Documents/VeniceAI_Backups (Default)";
        String path = uri.getPath();
        if (path == null) return uri.toString();
        if (path.contains(":")) {
            String[] parts = path.split(":");
            if (parts.length > 1) {
                return parts[1];
            }
        }
        return path;
    }

    public long getLastAutoBackupTimestamp() {
        return backupPrefs.getLong(KEY_LAST_AUTO_BACKUP_TIME, 0);
    }

    private void setLastAutoBackupTimestamp(long time) {
        backupPrefs.edit().putLong(KEY_LAST_AUTO_BACKUP_TIME, time).apply();
    }

    public File getPrimaryBackupDirectory() {
        File dir = context.getExternalFilesDir("backups");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "backups");
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public File getPublicDocumentsBackupDirectory() {
        File publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        File dir = new File(publicDocs, "VeniceAI_Backups");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Builds a full structured ZIP backup containing manifest, accounts, cookies and storage.
     */
    public boolean createZipBackup(String accountsJson, String activeAccountId, OutputStream out) {
        if (accountsJson == null || accountsJson.trim().isEmpty() || accountsJson.equals("[]")) {
            return false;
        }

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out))) {
            JSONArray accountsArr = new JSONArray(accountsJson);

            // 1. manifest.json
            JSONObject manifest = new JSONObject();
            manifest.put("app", "Venice AI Launcher");
            manifest.put("schema_version", CURRENT_BACKUP_SCHEMA_VERSION);
            manifest.put("timestamp", System.currentTimeMillis());
            manifest.put("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            manifest.put("active_account_id", activeAccountId);
            manifest.put("account_count", accountsArr.length());

            JSONArray summary = new JSONArray();
            for (int i = 0; i < accountsArr.length(); i++) {
                JSONObject acc = accountsArr.getJSONObject(i);
                JSONObject s = new JSONObject();
                s.put("id", acc.optString("id"));
                s.put("name", acc.optString("name"));
                s.put("has_cookies", !acc.optString("cookies", "").isEmpty());
                s.put("cookies_length", acc.optString("cookies", "").length());
                summary.put(s);
            }
            manifest.put("accounts_summary", summary);

            writeZipEntry(zos, "manifest.json", manifest.toString(2));

            // 2. accounts.json (Primary restore payload)
            writeZipEntry(zos, "accounts.json", accountsArr.toString(2));

            // 3. cookies & storage per account
            for (int i = 0; i < accountsArr.length(); i++) {
                JSONObject acc = accountsArr.getJSONObject(i);
                String accId = acc.optString("id", "profile_" + i);
                String safeName = acc.optString("name", "Account_" + i).replaceAll("[^a-zA-Z0-9_-]", "_");
                String cookies = acc.optString("cookies", "");
                String ls = acc.optString("localStorageJson", "{}");
                String ss = acc.optString("sessionStorageJson", "{}");

                writeZipEntry(zos, "cookies/" + safeName + "_" + accId + "_cookies.txt", cookies);
                writeZipEntry(zos, "storage/" + safeName + "_" + accId + "_localstorage.json", ls);
                writeZipEntry(zos, "storage/" + safeName + "_" + accId + "_sessionstorage.json", ss);
            }

            // 4. README.txt
            String readme = "Venice AI Launcher - Full Account & Session Backup Archive (.zip)\n" +
                    "===============================================================\n" +
                    "Created: " + manifest.getString("created_at") + "\n" +
                    "Total Profiles: " + accountsArr.length() + "\n" +
                    "Active Account ID: " + activeAccountId + "\n\n" +
                    "This archive contains complete authentication sessions, cookies, and local storage\n" +
                    "snapshots for your Venice AI accounts. Restoring this archive in Venice Launcher\n" +
                    "will restore all profiles and automatically re-authenticate your sessions.\n";
            writeZipEntry(zos, "README.txt", readme);

            zos.finish();
            zos.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed creating ZIP backup", e);
            return false;
        }
    }

    private void writeZipEntry(ZipOutputStream zos, String entryName, String content) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zos.write(bytes, 0, bytes.length);
        zos.closeEntry();
    }

    /**
     * Performs automatic backup as a full ZIP archive whenever profiles, cookies, or storage change.
     * Backs up to the user-selected custom folder (SAF) as well as internal/Documents storage.
     */
    public synchronized boolean performAutoBackup(String accountsJson, String activeAccountId) {
        if (accountsJson == null || accountsJson.trim().isEmpty() || accountsJson.equals("[]")) {
            return false;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        boolean success = false;

        // 1. User-configured SAF directory (takes highest priority)
        Uri customTreeUri = getCustomBackupTreeUri();
        if (customTreeUri != null) {
            try {
                DocumentFile treeDir = DocumentFile.fromTreeUri(context, customTreeUri);
                if (treeDir != null && treeDir.exists() && treeDir.canWrite()) {
                    // Overwrite venice_backup_latest.zip
                    DocumentFile latestDoc = treeDir.findFile(LATEST_BACKUP_ZIP);
                    if (latestDoc != null && latestDoc.exists()) {
                        latestDoc.delete();
                    }
                    latestDoc = treeDir.createFile("application/zip", LATEST_BACKUP_ZIP);
                    if (latestDoc != null) {
                        try (OutputStream os = context.getContentResolver().openOutputStream(latestDoc.getUri(), "wt")) {
                            if (os != null && createZipBackup(accountsJson, activeAccountId, os)) {
                                success = true;
                                Log.d(TAG, "Auto-backup latest written to custom folder: " + latestDoc.getUri());
                            }
                        }
                    }

                    // Timestamped archive: venice_backup_YYYYMMDD_HHMMSS.zip
                    String tsName = "venice_backup_" + timeStamp + ".zip";
                    DocumentFile tsDoc = treeDir.createFile("application/zip", tsName);
                    if (tsDoc != null) {
                        try (OutputStream os = context.getContentResolver().openOutputStream(tsDoc.getUri(), "wt")) {
                            if (os != null) {
                                createZipBackup(accountsJson, activeAccountId, os);
                            }
                        }
                    }

                    rotateSafBackups(treeDir, ".zip");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed auto-backup to custom SAF directory", e);
            }
        }

        // 2. Primary app internal backup folder
        File primaryDir = getPrimaryBackupDirectory();
        if (primaryDir != null && primaryDir.exists()) {
            File latestZip = new File(primaryDir, LATEST_BACKUP_ZIP);
            File timestampedZip = new File(primaryDir, "venice_backup_" + timeStamp + ".zip");

            if (writeZipToFileAtomically(latestZip, accountsJson, activeAccountId) &&
                writeZipToFileAtomically(timestampedZip, accountsJson, activeAccountId)) {
                success = true;
                rotateOldBackups(primaryDir, ".zip");
            }
        }

        // 3. Public Documents/VeniceAI_Backups folder fallback
        try {
            File publicDir = getPublicDocumentsBackupDirectory();
            if (publicDir != null && publicDir.exists()) {
                File pubLatestZip = new File(publicDir, LATEST_BACKUP_ZIP);
                File pubTimestampedZip = new File(publicDir, "venice_backup_" + timeStamp + ".zip");
                writeZipToFileAtomically(pubLatestZip, accountsJson, activeAccountId);
                writeZipToFileAtomically(pubTimestampedZip, accountsJson, activeAccountId);
                rotateOldBackups(publicDir, ".zip");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed auto-backup to public Documents: " + e.getMessage());
        }

        if (success) {
            setLastAutoBackupTimestamp(System.currentTimeMillis());
            Log.i(TAG, "Auto-backup successfully completed to: " + getFormattedAutoBackupLocation());
        }

        return success;
    }

    private void rotateSafBackups(DocumentFile dir, String extension) {
        try {
            DocumentFile[] files = dir.listFiles();
            List<DocumentFile> backups = new ArrayList<>();
            for (DocumentFile f : files) {
                String name = f.getName();
                if (name != null && name.startsWith("venice_backup_20") && name.endsWith(extension)) {
                    backups.add(f);
                }
            }
            if (backups.size() > MAX_ROTATED_BACKUPS) {
                Collections.sort(backups, Comparator.comparingLong(DocumentFile::lastModified));
                int toDelete = backups.size() - MAX_ROTATED_BACKUPS;
                for (int i = 0; i < toDelete; i++) {
                    backups.get(i).delete();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error rotating SAF backups", e);
        }
    }

    private boolean writeZipToFileAtomically(File targetFile, String accountsJson, String activeAccountId) {
        File tempFile = new File(targetFile.getParentFile(), targetFile.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            boolean ok = createZipBackup(accountsJson, activeAccountId, fos);
            if (!ok) {
                if (tempFile.exists()) tempFile.delete();
                return false;
            }
            if (targetFile.exists()) {
                targetFile.delete();
            }
            return tempFile.renameTo(targetFile);
        } catch (Exception e) {
            Log.e(TAG, "Atomic ZIP write failed: " + targetFile.getAbsolutePath(), e);
            if (tempFile.exists()) tempFile.delete();
            return false;
        }
    }

    private void rotateOldBackups(File directory, String extension) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("venice_backup_20") && name.endsWith(extension));
        if (files != null && files.length > MAX_ROTATED_BACKUPS) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            int toDelete = files.length - MAX_ROTATED_BACKUPS;
            for (int i = 0; i < toDelete; i++) {
                files[i].delete();
            }
        }
    }

    public static class BackupData {
        public int schemaVersion;
        public long timestamp;
        public String activeAccountId;
        public String accountsJson;
    }

    public BackupData readBackupFromZip(InputStream in) {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry entry;
            String manifestContent = null;
            String accountsContent = null;
            byte[] buffer = new byte[4096];

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("accounts.json") || name.endsWith("/accounts.json")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    accountsContent = baos.toString("UTF-8");
                } else if (name.equals("manifest.json") || name.endsWith("/manifest.json")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    manifestContent = baos.toString("UTF-8");
                }
                zis.closeEntry();
            }

            if (accountsContent != null && !accountsContent.trim().isEmpty()) {
                BackupData data = new BackupData();
                data.schemaVersion = CURRENT_BACKUP_SCHEMA_VERSION;
                data.accountsJson = accountsContent;
                data.timestamp = System.currentTimeMillis();
                data.activeAccountId = "";

                if (manifestContent != null) {
                    try {
                        JSONObject m = new JSONObject(manifestContent);
                        data.schemaVersion = m.optInt("schema_version", CURRENT_BACKUP_SCHEMA_VERSION);
                        data.timestamp = m.optLong("timestamp", System.currentTimeMillis());
                        data.activeAccountId = m.optString("active_account_id", "");
                    } catch (Exception ignored) {}
                }
                return data;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed reading backup ZIP", e);
        }
        return null;
    }

    public BackupData parseBackupJson(String jsonContent) {
        try {
            JSONObject root = new JSONObject(jsonContent);
            BackupData data = new BackupData();
            data.schemaVersion = root.optInt("schema_version", 1);
            data.timestamp = root.optLong("timestamp", 0);
            data.activeAccountId = root.optString("active_account_id", "");

            if (root.has("accounts")) {
                data.accountsJson = root.getJSONArray("accounts").toString();
            } else if (root.has("accounts_list")) {
                data.accountsJson = root.getJSONArray("accounts_list").toString();
            } else {
                return null;
            }
            return data;
        } catch (Exception e) {
            Log.e(TAG, "Failed parsing JSON backup", e);
            return null;
        }
    }

    /**
     * Reads and parses a backup from a URI, auto-detecting ZIP vs JSON format.
     */
    public BackupData readBackupFromUri(Uri uri) {
        try {
            // First try reading as ZIP archive
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    BackupData zipData = readBackupFromZip(is);
                    if (zipData != null && zipData.accountsJson != null) {
                        Log.d(TAG, "Successfully read backup from ZIP archive");
                        return zipData;
                    }
                }
            }

            // Fallback: try reading as plain JSON text
            try (InputStream is = context.getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return parseBackupJson(sb.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed reading backup from URI: " + uri, e);
            return null;
        }
    }

    public BackupData getLatestExistingBackup() {
        // 1. Try custom SAF folder if set
        Uri customTreeUri = getCustomBackupTreeUri();
        if (customTreeUri != null) {
            try {
                DocumentFile treeDir = DocumentFile.fromTreeUri(context, customTreeUri);
                if (treeDir != null && treeDir.exists()) {
                    DocumentFile latestDoc = treeDir.findFile(LATEST_BACKUP_ZIP);
                    if (latestDoc != null && latestDoc.exists() && latestDoc.length() > 50) {
                        try (InputStream is = context.getContentResolver().openInputStream(latestDoc.getUri())) {
                            if (is != null) {
                                BackupData d = readBackupFromZip(is);
                                if (d != null) return d;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Try primary app directory
        File primaryDir = getPrimaryBackupDirectory();
        File latestZip = new File(primaryDir, LATEST_BACKUP_ZIP);
        if (latestZip.exists() && latestZip.length() > 50) {
            try (FileInputStream fis = new FileInputStream(latestZip)) {
                BackupData d = readBackupFromZip(fis);
                if (d != null) return d;
            } catch (Exception ignored) {}
        }

        // 3. Try public Documents folder
        File publicDir = getPublicDocumentsBackupDirectory();
        File pubLatestZip = new File(publicDir, LATEST_BACKUP_ZIP);
        if (pubLatestZip.exists() && pubLatestZip.length() > 50) {
            try (FileInputStream fis = new FileInputStream(pubLatestZip)) {
                BackupData d = readBackupFromZip(fis);
                if (d != null) return d;
            } catch (Exception ignored) {}
        }

        // 4. Fallback: legacy JSON
        File latestJson = new File(primaryDir, LATEST_BACKUP_JSON);
        if (latestJson.exists() && latestJson.length() > 10) {
            return readBackupFromFile(latestJson);
        }

        return null;
    }

    public BackupData readBackupFromFile(File file) {
        if (file.getName().endsWith(".zip")) {
            try (FileInputStream fis = new FileInputStream(file)) {
                return readBackupFromZip(fis);
            } catch (Exception e) {
                Log.e(TAG, "Failed reading backup file as ZIP", e);
            }
        }
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return parseBackupJson(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed reading backup file: " + file, e);
            return null;
        }
    }
}

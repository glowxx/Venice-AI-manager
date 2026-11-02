package com.venicelauncher.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AccountManager {
    private static final String TAG = "VeniceAccountManager";
    private static final String PREF_NAME = "venice_accounts_pref";
    private static final String KEY_ACCOUNTS = "accounts_list";
    private static final String KEY_ACTIVE_ACCOUNT_ID = "active_account_id";
    private static final String KEY_SCHEMA_VERSION = "schema_version";

    public static final String VENICE_URL = "https://venice.ai";
    public static final String VENICE_DOMAIN = ".venice.ai";

    private final SharedPreferences prefs;
    private final Context context;
    private final BackupManager backupManager;
    private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor();

    public static class AccountProfile {
        public String id;
        public String name;
        public long createdAt;
        public String cookies;
        public String localStorageJson;
        public String sessionStorageJson;

        public AccountProfile(String id, String name, long createdAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.cookies = "";
            this.localStorageJson = "{}";
            this.sessionStorageJson = "{}";
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("name", name);
            obj.put("createdAt", createdAt);
            obj.put("cookies", cookies);
            obj.put("localStorageJson", localStorageJson);
            obj.put("sessionStorageJson", sessionStorageJson);
            return obj;
        }

        public static AccountProfile fromJson(JSONObject obj) {
            AccountProfile profile = new AccountProfile(
                    obj.optString("id", UUID.randomUUID().toString()),
                    obj.optString("name", "Account"),
                    obj.optLong("createdAt", System.currentTimeMillis())
            );
            profile.cookies = obj.optString("cookies", "");
            profile.localStorageJson = obj.optString("localStorageJson", "{}");
            profile.sessionStorageJson = obj.optString("sessionStorageJson", "{}");
            return profile;
        }
    }

    public interface SwitchCallback {
        void onSwitched(AccountProfile targetProfile);
    }

    public AccountManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.backupManager = new BackupManager(this.context);
        ensureDefaultAccount();
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }

    public synchronized String getAccountsJson() {
        return prefs.getString(KEY_ACCOUNTS, "[]");
    }

    private void ensureDefaultAccount() {
        List<AccountProfile> list = getAccounts();
        if (list.isEmpty()) {
            BackupManager.BackupData existing = backupManager.getLatestExistingBackup();
            if (existing != null && !TextUtils.isEmpty(existing.accountsJson) && !existing.accountsJson.equals("[]")) {
                Log.i(TAG, "Restoring accounts from discovered previous backup file");
                restoreFromBackup(existing);
                return;
            }

            AccountProfile defaultProfile = new AccountProfile(
                    "profile_default",
                    context.getString(R.string.default_account_name),
                    System.currentTimeMillis()
            );
            list.add(defaultProfile);
            saveAccountsList(list);
            prefs.edit()
                    .putString(KEY_ACTIVE_ACCOUNT_ID, defaultProfile.id)
                    .putInt(KEY_SCHEMA_VERSION, BackupManager.CURRENT_BACKUP_SCHEMA_VERSION)
                    .apply();
        }
    }

    public synchronized List<AccountProfile> getAccounts() {
        List<AccountProfile> result = new ArrayList<>();
        String raw = prefs.getString(KEY_ACCOUNTS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                result.add(AccountProfile.fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing accounts list", e);
        }
        return result;
    }

    public synchronized void saveAccountsList(List<AccountProfile> list) {
        JSONArray arr = new JSONArray();
        for (AccountProfile p : list) {
            try {
                arr.put(p.toJson());
            } catch (JSONException e) {
                Log.e(TAG, "Error serializing account", e);
            }
        }
        final String jsonString = arr.toString();
        prefs.edit()
                .putString(KEY_ACCOUNTS, jsonString)
                .putInt(KEY_SCHEMA_VERSION, BackupManager.CURRENT_BACKUP_SCHEMA_VERSION)
                .apply();

        // Perform asynchronous ZIP automatic backup to ensure data persistence
        final String activeId = prefs.getString(KEY_ACTIVE_ACCOUNT_ID, "profile_default");
        backupExecutor.execute(() -> {
            try {
                backupManager.performAutoBackup(jsonString, activeId);
            } catch (Exception e) {
                Log.w(TAG, "Auto-backup failed", e);
            }
        });
    }

    public synchronized void captureActiveCookies() {
        CookieManager cm = CookieManager.getInstance();
        String cookies = cm.getCookie(VENICE_URL);
        if (TextUtils.isEmpty(cookies)) {
            cookies = cm.getCookie("https://venice.ai");
        }
        if (!TextUtils.isEmpty(cookies)) {
            AccountProfile active = getActiveAccount();
            if (!cookies.equals(active.cookies)) {
                active.cookies = cookies;
                List<AccountProfile> list = getAccounts();
                for (AccountProfile p : list) {
                    if (p.id.equals(active.id)) {
                        p.cookies = cookies;
                        break;
                    }
                }
                saveAccountsList(list);
                Log.d(TAG, "Updated live cookies for " + active.name + " (" + cookies.length() + " chars)");
            }
        }
    }

    /**
     * Snapshots current active session (cookies + localStorage + sessionStorage) and saves before backup/switch.
     */
    public void snapshotAndSaveCurrentSession(WebView webView, Runnable onComplete) {
        captureActiveCookies();
        if (webView == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        String snapshotScript = "(function() { " +
                "  try { " +
                "    var ls = {}; for (var i=0; i<localStorage.length; i++) { var k = localStorage.key(i); ls[k] = localStorage.getItem(k); } " +
                "    var ss = {}; for (var j=0; j<sessionStorage.length; j++) { var sk = sessionStorage.key(j); ss[sk] = sessionStorage.getItem(sk); } " +
                "    return JSON.stringify({localStorage: ls, sessionStorage: ss}); " +
                "  } catch(e) { return JSON.stringify({localStorage:{}, sessionStorage:{}}); } " +
                "})();";

        webView.evaluateJavascript(snapshotScript, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String lsJson = "{}";
                String ssJson = "{}";
                try {
                    if (value != null && !value.equals("null")) {
                        if (value.startsWith("\"") && value.endsWith("\"")) {
                            value = new JSONObject("{ \"raw\": " + value + " }").getString("raw");
                        }
                        JSONObject storage = new JSONObject(value);
                        lsJson = storage.optJSONObject("localStorage") != null ? storage.getJSONObject("localStorage").toString() : "{}";
                        ssJson = storage.optJSONObject("sessionStorage") != null ? storage.getJSONObject("sessionStorage").toString() : "{}";
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed parsing storage snapshot", e);
                }

                CookieManager cm = CookieManager.getInstance();
                String currentCookies = cm.getCookie(VENICE_URL);
                if (TextUtils.isEmpty(currentCookies)) {
                    currentCookies = cm.getCookie("https://venice.ai");
                }

                saveActiveSession(currentCookies, lsJson, ssJson);

                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    public synchronized boolean restoreFromBackup(BackupManager.BackupData backupData) {
        if (backupData == null || TextUtils.isEmpty(backupData.accountsJson)) {
            return false;
        }

        try {
            JSONArray arr = new JSONArray(backupData.accountsJson);
            if (arr.length() == 0) return false;

            prefs.edit()
                    .putString(KEY_ACCOUNTS, backupData.accountsJson)
                    .putString(KEY_ACTIVE_ACCOUNT_ID, TextUtils.isEmpty(backupData.activeAccountId) ? arr.getJSONObject(0).optString("id", "profile_default") : backupData.activeAccountId)
                    .putInt(KEY_SCHEMA_VERSION, backupData.schemaVersion)
                    .commit();

            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed applying restored backup", e);
            return false;
        }
    }

    public synchronized AccountProfile getActiveAccount() {
        String activeId = prefs.getString(KEY_ACTIVE_ACCOUNT_ID, "profile_default");
        List<AccountProfile> list = getAccounts();
        for (AccountProfile p : list) {
            if (p.id.equals(activeId)) {
                return p;
            }
        }
        if (!list.isEmpty()) {
            return list.get(0);
        }
        ensureDefaultAccount();
        return getAccounts().get(0);
    }

    public synchronized AccountProfile createAccount(String name) {
        List<AccountProfile> list = getAccounts();
        String id = "profile_" + System.currentTimeMillis();
        AccountProfile newProfile = new AccountProfile(id, name, System.currentTimeMillis());
        list.add(newProfile);
        saveAccountsList(list);
        return newProfile;
    }

    public synchronized boolean deleteAccount(String id) {
        List<AccountProfile> list = getAccounts();
        if (list.size() <= 1) {
            return false;
        }

        AccountProfile toRemove = null;
        for (AccountProfile p : list) {
            if (p.id.equals(id)) {
                toRemove = p;
                break;
            }
        }

        if (toRemove != null) {
            list.remove(toRemove);
            saveAccountsList(list);

            String activeId = prefs.getString(KEY_ACTIVE_ACCOUNT_ID, "");
            if (activeId.equals(id)) {
                AccountProfile newActive = list.get(0);
                prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, newActive.id).apply();
            }
            return true;
        }
        return false;
    }

    public synchronized void renameAccount(String id, String newName) {
        List<AccountProfile> list = getAccounts();
        for (AccountProfile p : list) {
            if (p.id.equals(id)) {
                p.name = newName;
                break;
            }
        }
        saveAccountsList(list);
    }

    public synchronized void saveActiveSession(String cookies, String localStorageJson, String sessionStorageJson) {
        AccountProfile active = getActiveAccount();
        List<AccountProfile> list = getAccounts();
        for (AccountProfile p : list) {
            if (p.id.equals(active.id)) {
                if (cookies != null && !cookies.isEmpty()) p.cookies = cookies;
                if (localStorageJson != null && !localStorageJson.equals("{}")) p.localStorageJson = localStorageJson;
                if (sessionStorageJson != null && !sessionStorageJson.equals("{}")) p.sessionStorageJson = sessionStorageJson;
                break;
            }
        }
        saveAccountsList(list);
        Log.d(TAG, "Saved full session snapshot for: " + active.name);
    }

    /**
     * Applies target account's cookies & storage to WebView and completes switch.
     */
    public void applyActiveAccountToWebView(WebView webView, SwitchCallback callback) {
        final AccountProfile target = getActiveAccount();
        final CookieManager cookieManager = CookieManager.getInstance();

        cookieManager.removeAllCookies(new ValueCallback<Boolean>() {
            @Override
            public void onReceiveValue(Boolean success) {
                if (!TextUtils.isEmpty(target.cookies)) {
                    String[] cookiePairs = target.cookies.split(";");
                    for (String cookie : cookiePairs) {
                        String trimmed = cookie.trim();
                        if (!trimmed.isEmpty()) {
                            cookieManager.setCookie(VENICE_URL, trimmed + "; Domain=" + VENICE_DOMAIN + "; Path=/");
                            cookieManager.setCookie("https://venice.ai", trimmed + "; Domain=.venice.ai; Path=/");
                        }
                    }
                }
                cookieManager.flush();

                if (webView != null) {
                    String restoreScript = buildRestoreStorageScript(target);
                    webView.evaluateJavascript(restoreScript, null);
                }

                Log.d(TAG, "Applied session for profile: " + target.name);
                if (callback != null) {
                    callback.onSwitched(target);
                }
            }
        });
    }

    public void switchAccount(WebView webView, String targetId, SwitchCallback callback) {
        snapshotAndSaveCurrentSession(webView, new Runnable() {
            @Override
            public void run() {
                prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, targetId).apply();
                applyActiveAccountToWebView(webView, callback);
            }
        });
    }

    public static String buildRestoreStorageScript(AccountProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("(function() { try { ");
        sb.append("  localStorage.clear(); sessionStorage.clear(); ");

        if (!TextUtils.isEmpty(profile.localStorageJson) && !profile.localStorageJson.equals("{}")) {
            sb.append("  var ls = ").append(profile.localStorageJson).append("; ");
            sb.append("  for (var k in ls) { if (ls.hasOwnProperty(k)) { localStorage.setItem(k, ls[k]); } } ");
        }

        if (!TextUtils.isEmpty(profile.sessionStorageJson) && !profile.sessionStorageJson.equals("{}")) {
            sb.append("  var ss = ").append(profile.sessionStorageJson).append("; ");
            sb.append("  for (var sk in ss) { if (ss.hasOwnProperty(sk)) { sessionStorage.setItem(sk, ss[sk]); } } ");
        }

        sb.append("} catch(e) { console.error('Restore storage error', e); } })();");
        return sb.toString();
    }
}

package com.venicelauncher.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;

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
        public boolean isIncognito;

        public AccountProfile(String id, String name, long createdAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.cookies = "";
            this.localStorageJson = "{}";
            this.sessionStorageJson = "{}";
            this.isIncognito = false;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("name", name);
            obj.put("createdAt", createdAt);
            obj.put("cookies", cookies);
            obj.put("localStorageJson", localStorageJson);
            obj.put("sessionStorageJson", sessionStorageJson);
            obj.put("isIncognito", isIncognito);
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
            profile.isIncognito = obj.optBoolean("isIncognito", false);
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

    public static String getSafeProfileName(String accountId) {
        if (accountId == null || accountId.isEmpty() || "profile_default".equals(accountId)) {
            return Profile.DEFAULT_PROFILE_NAME;
        }
        return "acc_" + accountId.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    public synchronized void setActiveAccountId(String id) {
        prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, id).apply();
    }

    public CookieManager getCookieManagerForProfile(String accountId) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                String profileName = getSafeProfileName(accountId);
                ProfileStore store = ProfileStore.getInstance();
                Profile profile = store.getProfile(profileName);
                if (profile != null) {
                    return profile.getCookieManager();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed getting profile CookieManager for " + accountId, e);
            }
        }
        return CookieManager.getInstance();
    }

    public synchronized String getAccountsJson() {
        return prefs.getString(KEY_ACCOUNTS, "[]");
    }

    public synchronized String getPermanentAccountsJson() {
        List<AccountProfile> list = getAccounts();
        JSONArray arr = new JSONArray();
        for (AccountProfile p : list) {
            if (!p.isIncognito) {
                try {
                    arr.put(p.toJson());
                } catch (JSONException ignored) {}
            }
        }
        return arr.toString();
    }

    public synchronized String getFirstPermanentAccountId() {
        List<AccountProfile> list = getAccounts();
        for (AccountProfile p : list) {
            if (!p.isIncognito) {
                return p.id;
            }
        }
        return "profile_default";
    }

    private void ensureDefaultAccount() {
        try {
            purgeIncognitoAccounts(true);
        } catch (Throwable t) {
            Log.w(TAG, "Failed purging incognito accounts on init", t);
        }

        List<AccountProfile> list = getAccounts();
        if (list.isEmpty()) {
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

        // Perform asynchronous ZIP automatic backup for permanent accounts only
        final String permanentJson = getPermanentAccountsJson();
        AccountProfile active = getActiveAccount();
        final String backupActiveId = active.isIncognito ? getFirstPermanentAccountId() : active.id;
        backupExecutor.execute(() -> {
            try {
                backupManager.performAutoBackup(permanentJson, backupActiveId);
            } catch (Exception e) {
                Log.w(TAG, "Auto-backup failed", e);
            }
        });
    }

    public void triggerAutoBackup() {
        final String permanentJson = getPermanentAccountsJson();
        AccountProfile active = getActiveAccount();
        final String backupActiveId = active.isIncognito ? getFirstPermanentAccountId() : active.id;
        backupExecutor.execute(() -> {
            try {
                backupManager.performAutoBackup(permanentJson, backupActiveId);
            } catch (Exception e) {
                Log.w(TAG, "Trigger auto-backup failed", e);
            }
        });
    }

    public synchronized void captureActiveCookies() {
        AccountProfile active = getActiveAccount();
        if (active == null || active.isIncognito) return;

        CookieManager cm = getCookieManagerForProfile(active.id);
        String cookies = cm.getCookie(VENICE_URL);
        if (TextUtils.isEmpty(cookies)) {
            cookies = cm.getCookie("https://venice.ai");
        }
        if (!TextUtils.isEmpty(cookies)) {
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

            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    ProfileStore store = ProfileStore.getInstance();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        String id = obj.optString("id");
                        String cookies = obj.optString("cookies");
                        if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(cookies)) {
                            String profileName = getSafeProfileName(id);
                            Profile profile = store.getOrCreateProfile(profileName);
                            CookieManager cm = profile.getCookieManager();
                            String[] pairs = cookies.split(";");
                            for (String pair : pairs) {
                                String trimmed = pair.trim();
                                if (!trimmed.isEmpty()) {
                                    cm.setCookie(VENICE_URL, trimmed + "; Domain=" + VENICE_DOMAIN + "; Path=/");
                                    cm.setCookie("https://venice.ai", trimmed + "; Domain=.venice.ai; Path=/");
                                }
                            }
                            cm.flush();
                        }
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "Failed populating restored cookies to profiles", ex);
                }
            }

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
        AccountProfile defaultProfile = new AccountProfile(
                "profile_default",
                context.getString(R.string.default_account_name),
                System.currentTimeMillis()
        );
        list.add(defaultProfile);
        saveAccountsList(list);
        return defaultProfile;
    }

    public synchronized boolean isIncognitoActive() {
        AccountProfile active = getActiveAccount();
        return active != null && active.isIncognito;
    }

    public synchronized AccountProfile createAccount(String name) {
        List<AccountProfile> list = getAccounts();
        String id = "profile_" + System.currentTimeMillis();
        AccountProfile newProfile = new AccountProfile(id, name, System.currentTimeMillis());
        list.add(newProfile);
        saveAccountsList(list);
        return newProfile;
    }

    public synchronized AccountProfile createIncognitoAccount() {
        // Purge any preexisting incognito temporary accounts
        purgeIncognitoAccounts(false);

        List<AccountProfile> list = getAccounts();
        String id = "profile_incognito_" + System.currentTimeMillis();
        AccountProfile incognito = new AccountProfile(id, context.getString(R.string.incognito_account_name), System.currentTimeMillis());
        incognito.isIncognito = true;
        incognito.cookies = "";
        incognito.localStorageJson = "{}";
        incognito.sessionStorageJson = "{}";

        list.add(incognito);
        saveAccountsList(list);
        return incognito;
    }

    public synchronized void purgeIncognitoAccounts(boolean clearActiveCookiesIfActive) {
        try {
            boolean wasActiveIncognito = isIncognitoActive();
            List<AccountProfile> list = getAccounts();
            List<AccountProfile> toKeep = new ArrayList<>();
            List<AccountProfile> toPurge = new ArrayList<>();
            for (AccountProfile p : list) {
                if (!p.isIncognito) {
                    toKeep.add(p);
                } else {
                    toPurge.add(p);
                }
            }

            if (toKeep.isEmpty()) {
                toKeep.add(new AccountProfile("profile_default", context.getString(R.string.default_account_name), System.currentTimeMillis()));
            }

            if (list.size() != toKeep.size()) {
                saveAccountsList(toKeep);
                Log.i(TAG, "Purged incognito temporary accounts (" + (list.size() - toKeep.size()) + " removed)");
            }

            // Delete incognito isolated profiles from ProfileStore disk storage
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    ProfileStore store = ProfileStore.getInstance();
                    for (AccountProfile purged : toPurge) {
                        try {
                            String profileName = getSafeProfileName(purged.id);
                            if (!Profile.DEFAULT_PROFILE_NAME.equals(profileName)) {
                                store.deleteProfile(profileName);
                                Log.i(TAG, "Deleted incognito ProfileStore profile: " + profileName);
                            }
                        } catch (Throwable e) {
                            Log.w(TAG, "Could not delete incognito profile from store: " + purged.id, e);
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "ProfileStore unavailable during purge", e);
                }
            }

            if (wasActiveIncognito && !toKeep.isEmpty()) {
                prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, toKeep.get(0).id).apply();
                if (clearActiveCookiesIfActive) {
                    try {
                        CookieManager cm = getCookieManagerForProfile(toKeep.get(0).id);
                        cm.removeAllCookies(null);
                        cm.flush();
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed clearing cookies for active profile", t);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in purgeIncognitoAccounts", t);
        }
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

            // Remove profile from ProfileStore disk storage
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try {
                    String profileName = getSafeProfileName(id);
                    if (!Profile.DEFAULT_PROFILE_NAME.equals(profileName)) {
                        ProfileStore.getInstance().deleteProfile(profileName);
                        Log.i(TAG, "Deleted ProfileStore profile: " + profileName);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not delete profile from store: " + id, e);
                }
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
        AccountProfile current = getActiveAccount();
        if (current.isIncognito && !targetId.equals(current.id)) {
            // Switching away from Incognito: do NOT save incognito data!
            // Immediately purge the incognito temporary profile from storage
            purgeIncognitoAccounts(false);
            prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, targetId).apply();
            applyActiveAccountToWebView(webView, callback);
            return;
        }

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

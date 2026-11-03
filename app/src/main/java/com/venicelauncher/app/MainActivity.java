package com.venicelauncher.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "VeniceMainActivity";
    private static final String VENICE_CHAT_URL = "https://venice.ai/chat";

    private FrameLayout webViewContainer;
    private WebView webView;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ProgressBar progressBar;
    private FrameLayout fabLauncherMenu;

    private AccountManager accountManager;
    private VeniceJsBridge veniceJsBridge;
    private SharedPreferences settingsPrefs;

    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private ActivityResultLauncher<String> exportBackupLauncher;
    private ActivityResultLauncher<String[]> importBackupLauncher;
    private ActivityResultLauncher<Uri> chooseBackupFolderLauncher;
    private ActivityResultLauncher<Uri> chooseRestoreFolderLauncher;
    private BottomSheetDialog activeSettingsDialog;

    private boolean isGenerating = false;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (isGenerating && webView != null) {
                // Gentle tick to keep Chromium V8 event loop processing in background without suspension
                webView.evaluateJavascript("void 0;", null);
                heartbeatHandler.postDelayed(this, 3000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settingsPrefs = getSharedPreferences("venice_settings", MODE_PRIVATE);
        accountManager = new AccountManager(this);
        veniceJsBridge = new VeniceJsBridge(this);

        initViews();
        setupLaunchers();
        setupPermissions();
        setupFloatingMenu();

        // Initialize and attach isolated WebView for the active account
        switchAccountAndRecreateWebView(accountManager.getActiveAccount().id, null);
    }

    private void initViews() {
        webViewContainer = findViewById(R.id.webview_container);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh);
        progressBar = findViewById(R.id.progress_bar);
        fabLauncherMenu = findViewById(R.id.fab_launcher_menu);

        swipeRefreshLayout.setColorSchemeColors(getColor(R.color.venice_accent));
        swipeRefreshLayout.setProgressBackgroundColorSchemeColor(getColor(R.color.surface_dark_card));
        swipeRefreshLayout.setEnabled(settingsPrefs.getBoolean("pull_refresh", false));

        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                if (webView != null) {
                    webView.reload();
                }
            }
        });

        swipeRefreshLayout.setOnChildScrollUpCallback(new SwipeRefreshLayout.OnChildScrollUpCallback() {
            @Override
            public boolean canChildScrollUp(@NonNull SwipeRefreshLayout parent, @Nullable View child) {
                return webView != null && webView.canScrollVertically(-1);
            }
        });

        boolean showFab = settingsPrefs.getBoolean("show_floating_button", true);
        fabLauncherMenu.setVisibility(showFab ? View.VISIBLE : View.GONE);
    }

    private void setupLaunchers() {
        // Web file chooser launcher
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (filePathCallback == null) return;

                        Uri[] results = null;
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            if (result.getData().getClipData() != null) {
                                int count = result.getData().getClipData().getItemCount();
                                results = new Uri[count];
                                for (int i = 0; i < count; i++) {
                                    results[i] = result.getData().getClipData().getItemAt(i).getUri();
                                }
                            } else if (result.getData().getData() != null) {
                                results = new Uri[]{result.getData().getData()};
                            }
                        }
                        filePathCallback.onReceiveValue(results);
                        filePathCallback = null;
                    }
                }
        );

        // Export backup to custom location chosen by user (ZIP archive)
        exportBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (uri != null) {
                            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                                String accountsJson = accountManager.getAccountsJson();
                                String activeId = accountManager.getActiveAccount().id;
                                boolean success = accountManager.getBackupManager().createZipBackup(accountsJson, activeId, os);
                                if (success) {
                                    Toast.makeText(MainActivity.this, "Backup archive (.zip) exported successfully!", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(MainActivity.this, R.string.toast_backup_export_failed, Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Failed writing exported backup", e);
                                Toast.makeText(MainActivity.this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }
        );

        // Restore backup from chosen ZIP or JSON file
        importBackupLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (uri != null) {
                            BackupManager.BackupData data = accountManager.getBackupManager().readBackupFromUri(uri);
                            if (data != null && accountManager.restoreFromBackup(data)) {
                                int count = accountManager.getAccounts().size();
                                String msg = String.format(getString(R.string.toast_backup_restored), count);
                                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();

                                // Recreate isolated WebView for restored active account
                                switchAccountAndRecreateWebView(accountManager.getActiveAccount().id, null);
                            } else {
                                Toast.makeText(MainActivity.this, R.string.toast_backup_restore_failed, Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                }
        );

        // Choose custom auto-backup folder (SAF OpenDocumentTree)
        chooseBackupFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri treeUri) {
                        if (treeUri != null) {
                            try {
                                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                                getContentResolver().takePersistableUriPermission(treeUri, takeFlags);

                                String displayPath = BackupManager.formatTreeUriToDisplayPath(treeUri);
                                accountManager.getBackupManager().setCustomBackupTreeUri(treeUri, displayPath);

                                Toast.makeText(MainActivity.this, R.string.toast_folder_selected, Toast.LENGTH_SHORT).show();

                                // Trigger immediate auto-backup to verify destination
                                accountManager.triggerAutoBackup();

                                if (activeSettingsDialog != null && activeSettingsDialog.isShowing()) {
                                    TextView tvFolder = activeSettingsDialog.findViewById(R.id.tv_auto_backup_folder);
                                    if (tvFolder != null) {
                                        tvFolder.setText(displayPath);
                                    }
                                    TextView tvLast = activeSettingsDialog.findViewById(R.id.tv_last_backup_time);
                                    if (tvLast != null) {
                                        tvLast.setText(getString(R.string.backup_last_time_prefix) + "Just now");
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Failed taking persistable URI permission", e);
                                Toast.makeText(MainActivity.this, "Error setting folder: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }
        );

        // Choose folder to automatically find and restore the newest working backup
        chooseRestoreFolderLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri treeUri) {
                        if (treeUri != null) {
                            try {
                                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                                getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                            } catch (Exception ignored) {}
                            restoreFromFolderUri(treeUri);
                        }
                    }
                }
        );
    }

    private void setupFloatingMenu() {
        fabLauncherMenu.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            private float startX, startY;
            private static final int CLICK_ACTION_THRESHOLD = 15; // px

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = view.getX() - event.getRawX();
                        dY = view.getY() - event.getRawY();
                        startX = event.getRawX();
                        startY = event.getRawY();
                        view.setAlpha(1.0f);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float newX = event.getRawX() + dX;
                        float newY = event.getRawY() + dY;

                        View parent = (View) view.getParent();
                        if (parent != null) {
                            int parentWidth = parent.getWidth();
                            int parentHeight = parent.getHeight();
                            newX = Math.max(0, Math.min(newX, parentWidth - view.getWidth()));
                            newY = Math.max(0, Math.min(newY, parentHeight - view.getHeight()));
                            view.setX(newX);
                            view.setY(newY);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        view.setAlpha(0.65f);
                        float diffX = Math.abs(event.getRawX() - startX);
                        float diffY = Math.abs(event.getRawY() - startY);

                        if (diffX < CLICK_ACTION_THRESHOLD && diffY < CLICK_ACTION_THRESHOLD) {
                            showLauncherSettingsDialog();
                        } else {
                            // Snap to nearest edge (left or right)
                            View p = (View) view.getParent();
                            if (p != null) {
                                int pWidth = p.getWidth();
                                float margin = dpToPx(12);
                                float targetX = (view.getX() + view.getWidth() / 2f > pWidth / 2f)
                                        ? (pWidth - view.getWidth() - margin)
                                        : margin;
                                view.animate().x(targetX).setDuration(200).start();
                            }
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private float dpToPx(float dp) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return dp * (metrics.densityDpi / 160f);
    }

    private void setupPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher = registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    new ActivityResultCallback<Boolean>() {
                        @Override
                        public void onActivityResult(Boolean isGranted) {
                            Log.d(TAG, "Notification permission: " + isGranted);
                        }
                    }
            );

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(final WebView wv, final String accountId) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        AccountManager.AccountProfile profileObj = accountManager.getActiveAccount();
        final boolean isIncognito = profileObj != null && profileObj.isIncognito;

        // Apply full Anti-Detect protections (clean Chrome UA, suppress X-Requested-With, canvas/webgl/audio noise)
        AntiDetectManager.applyAntiDetect(wv, accountId, isIncognito);

        // Enable cookies for profile
        CookieManager cm = accountManager.getCookieManagerForProfile(accountId);
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);

        // Add WebMessage listener / JavaScript bridge for generation state detection
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.addWebMessageListener(
                        wv,
                        VeniceJsBridge.WEB_MESSAGE_PORT_NAME,
                        Collections.singleton("https://venice.ai"),
                        (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                            String data = message.getData();
                            if ("STARTED".equals(data)) {
                                veniceJsBridge.notifyGenerationStarted();
                            } else if ("COMPLETED".equals(data)) {
                                veniceJsBridge.notifyGenerationCompleted();
                            }
                        }
                );
            } catch (Exception e) {
                Log.w(TAG, "Failed adding WebMessageListener, fallback to interface", e);
                wv.addJavascriptInterface(veniceJsBridge, VeniceJsBridge.JS_INTERFACE_NAME);
            }
        } else {
            wv.addJavascriptInterface(veniceJsBridge, VeniceJsBridge.JS_INTERFACE_NAME);
        }

        veniceJsBridge.setStateListener(new VeniceJsBridge.GenerationStateListener() {
            @Override
            public void onGenerationStateChanged(final boolean generating) {
                isGenerating = generating;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (generating) {
                            heartbeatHandler.removeCallbacks(heartbeatRunnable);
                            heartbeatHandler.post(heartbeatRunnable);
                        } else {
                            heartbeatHandler.removeCallbacks(heartbeatRunnable);
                        }
                    }
                });
            }
        });

        // WebViewClient
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("https://venice.ai") || url.startsWith("https://chat.venice.ai")) {
                    return false; // Load inside WebView
                }
                // External link, open in browser
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Cannot open external URL: " + url, e);
                    return false;
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                int seed = AntiDetectManager.getProfileSeed(accountId, isIncognito);
                view.evaluateJavascript(AntiDetectManager.buildAntiDetectScript(seed), null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                swipeRefreshLayout.setRefreshing(false);

                // Inject anti-detect and background generation detector
                int seed = AntiDetectManager.getProfileSeed(accountId, isIncognito);
                view.evaluateJavascript(AntiDetectManager.buildAntiDetectScript(seed), null);
                view.evaluateJavascript(VeniceJsBridge.getInjectionScript(), null);

                // Flush active cookies and snapshot them to active profile
                CookieManager profileCm = accountManager.getCookieManagerForProfile(accountId);
                profileCm.flush();
                accountManager.captureActiveCookies();
            }
        });

        // WebChromeClient
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
                if (newProgress >= 70) {
                    int seed = AntiDetectManager.getProfileSeed(accountId, isIncognito);
                    view.evaluateJavascript(AntiDetectManager.buildAntiDetectScript(seed), null);
                    view.evaluateJavascript(VeniceJsBridge.getInjectionScript(), null);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    fileChooserLauncher.launch(intent);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, R.string.toast_file_chooser_error, Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        // Download Listener for generated images / attachments
        wv.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimeType);
                    String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    request.setDescription("Downloading image from Venice AI...");
                    request.setTitle(filename);
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        String msg = String.format(getString(R.string.toast_download_started), filename);
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Download error", e);
                    Toast.makeText(MainActivity.this, R.string.toast_download_error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    public void switchAccountAndRecreateWebView(final String targetAccountId, final Runnable onReady) {
        // 1. Save live cookies of current profile if not incognito
        AccountManager.AccountProfile current = accountManager.getActiveAccount();
        if (current != null) {
            if (!current.isIncognito) {
                accountManager.captureActiveCookies();
            } else if (!current.id.equals(targetAccountId)) {
                // If leaving an incognito session, delete its profile from ProfileStore
                accountManager.purgeIncognitoAccounts(false);
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    try {
                        String incognitoProfileName = AccountManager.getSafeProfileName(current.id);
                        if (!Profile.DEFAULT_PROFILE_NAME.equals(incognitoProfileName)) {
                            ProfileStore.getInstance().deleteProfile(incognitoProfileName);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed deleting incognito profile", e);
                    }
                }
            }
        }

        // 2. Set new active account ID in AccountManager
        accountManager.setActiveAccountId(targetAccountId);
        final AccountManager.AccountProfile targetProfile = accountManager.getActiveAccount();

        // 3. Cleanly remove and destroy the old WebView
        if (webView != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            isGenerating = false;

            webViewContainer.removeView(webView);
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.removeJavascriptInterface(VeniceJsBridge.JS_INTERFACE_NAME);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }

        // 4. If MULTI_PROFILE is NOT supported, clear global storage and cookies before recreating
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            android.webkit.WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        }

        // 5. Instantiate a fresh WebView instance
        WebView newWebView = new WebView(this);
        newWebView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        newWebView.setBackgroundColor(getColor(R.color.background_dark));

        // 6. Assign isolated profile BEFORE initializing / loading
        String profileName = AccountManager.getSafeProfileName(targetAccountId);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            try {
                ProfileStore store = ProfileStore.getInstance();
                Profile profile = store.getOrCreateProfile(profileName);
                WebViewCompat.setProfile(newWebView, profileName);
                Log.i(TAG, "Assigned isolated WebView profile: " + profileName);

                // If target account has saved cookies, inject them into this profile's CookieManager
                if (!TextUtils.isEmpty(targetProfile.cookies)) {
                    CookieManager profileCm = profile.getCookieManager();
                    String[] cookiePairs = targetProfile.cookies.split(";");
                    for (String c : cookiePairs) {
                        String trimmed = c.trim();
                        if (!trimmed.isEmpty()) {
                            profileCm.setCookie(VENICE_CHAT_URL, trimmed + "; Domain=" + AccountManager.VENICE_DOMAIN + "; Path=/");
                            profileCm.setCookie("https://venice.ai", trimmed + "; Domain=.venice.ai; Path=/");
                        }
                    }
                    profileCm.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed setting profile on WebView: " + profileName, e);
            }
        } else {
            // Fallback: inject saved cookies into global CookieManager
            if (!TextUtils.isEmpty(targetProfile.cookies)) {
                CookieManager cm = CookieManager.getInstance();
                String[] cookiePairs = targetProfile.cookies.split(";");
                for (String c : cookiePairs) {
                    String trimmed = c.trim();
                    if (!trimmed.isEmpty()) {
                        cm.setCookie(VENICE_CHAT_URL, trimmed + "; Domain=" + AccountManager.VENICE_DOMAIN + "; Path=/");
                        cm.setCookie("https://venice.ai", trimmed + "; Domain=.venice.ai; Path=/");
                    }
                }
                cm.flush();
            }
        }

        // 7. Configure WebSettings, WebViewClient, WebChromeClient, DownloadListener, JS Interface
        this.webView = newWebView;
        setupWebView(newWebView, targetAccountId);

        // 8. Attach new WebView to container
        webViewContainer.addView(newWebView);

        // 9. Load Venice AI Chat
        loadVenice();

        if (onReady != null) {
            onReady.run();
        }
    }

    private void loadVenice() {
        if (webView != null) {
            webView.loadUrl(VENICE_CHAT_URL);
        }
    }

    private boolean isKeepAliveEnabled() {
        return settingsPrefs.getBoolean("always_keep_alive", true);
    }

    private void startBackgroundService(String action) {
        Intent intent = new Intent(this, BackgroundKeepAliveService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void showLauncherSettingsDialog() {
        final BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.VeniceBottomSheetDialogTheme);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        dialog.setContentView(dialogView);
        activeSettingsDialog = dialog;

        // Active Account Display
        TextView tvActiveAccName = dialogView.findViewById(R.id.tv_active_account_name);
        AccountManager.AccountProfile active = accountManager.getActiveAccount();
        if (active != null && tvActiveAccName != null) {
            if (active.isIncognito) {
                tvActiveAccName.setText("🕶  " + active.name);
            } else {
                tvActiveAccName.setText(active.name);
            }
        }

        // Manage Accounts Button
        View btnManage = dialogView.findViewById(R.id.btn_manage_accounts);
        if (btnManage != null) {
            btnManage.setOnClickListener(v -> {
                dialog.dismiss();
                showAccountsDialog();
            });
        }

        // Background Keep-Alive Switch (ONLY active while AI is generating!)
        MaterialSwitch swKeepAlive = dialogView.findViewById(R.id.switch_always_keep_alive);
        if (swKeepAlive != null) {
            swKeepAlive.setChecked(settingsPrefs.getBoolean("always_keep_alive", true));
            swKeepAlive.setOnCheckedChangeListener((btn, isChecked) -> {
                settingsPrefs.edit().putBoolean("always_keep_alive", isChecked).apply();
                if (isChecked) {
                    Toast.makeText(MainActivity.this, R.string.toast_bg_enabled, Toast.LENGTH_SHORT).show();
                } else {
                    if (isGenerating) {
                        startBackgroundService(BackgroundKeepAliveService.ACTION_STOP_KEEPALIVE);
                    }
                    Toast.makeText(MainActivity.this, R.string.toast_bg_disabled, Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Completion Notification Switch
        MaterialSwitch swNotify = dialogView.findViewById(R.id.switch_notify_completion);
        if (swNotify != null) {
            swNotify.setChecked(settingsPrefs.getBoolean("notify_completion", true));
            swNotify.setOnCheckedChangeListener((btn, isChecked) ->
                    settingsPrefs.edit().putBoolean("notify_completion", isChecked).apply());
        }

        // Vibration Switch
        MaterialSwitch swVibrate = dialogView.findViewById(R.id.switch_vibration);
        if (swVibrate != null) {
            swVibrate.setChecked(settingsPrefs.getBoolean("vibrate_completion", true));
            swVibrate.setOnCheckedChangeListener((btn, isChecked) ->
                    settingsPrefs.edit().putBoolean("vibrate_completion", isChecked).apply());
        }

        // Auto-Backup Folder Display & Change Button
        TextView tvFolder = dialogView.findViewById(R.id.tv_auto_backup_folder);
        if (tvFolder != null) {
            tvFolder.setText(accountManager.getBackupManager().getFormattedAutoBackupLocation());
        }

        View btnChangeFolder = dialogView.findViewById(R.id.btn_change_backup_folder);
        if (btnChangeFolder != null) {
            btnChangeFolder.setOnClickListener(v -> {
                chooseBackupFolderLauncher.launch(null);
            });
        }

        TextView tvLastBackup = dialogView.findViewById(R.id.tv_last_backup_time);
        if (tvLastBackup != null) {
            long lastTime = accountManager.getBackupManager().getLastAutoBackupTimestamp();
            if (lastTime > 0) {
                String formatted = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(lastTime));
                tvLastBackup.setText(getString(R.string.backup_last_time_prefix) + formatted);
            } else {
                tvLastBackup.setText(R.string.backup_last_time_none);
            }
        }

        // Export Backup Button (ZIP archive with cookies and localStorage)
        View btnExport = dialogView.findViewById(R.id.btn_export_backup);
        if (btnExport != null) {
            btnExport.setOnClickListener(v -> {
                accountManager.snapshotAndSaveCurrentSession(webView, new Runnable() {
                    @Override
                    public void run() {
                        accountManager.getBackupManager().performAutoBackup(
                                accountManager.getAccountsJson(),
                                accountManager.getActiveAccount().id
                        );
                        exportBackupLauncher.launch(BackupManager.generateStandardBackupFilename());
                    }
                });
            });
        }

        // Restore Backup Button (supports folder auto-detection, .zip, and legacy .json)
        View btnRestore = dialogView.findViewById(R.id.btn_restore_backup);
        if (btnRestore != null) {
            btnRestore.setOnClickListener(v -> {
                showRestoreOptionsDialog(dialog);
            });
        }

        // Floating Menu Button Switch
        MaterialSwitch swFloating = dialogView.findViewById(R.id.switch_floating_button);
        if (swFloating != null) {
            swFloating.setChecked(settingsPrefs.getBoolean("show_floating_button", true));
            swFloating.setOnCheckedChangeListener((btn, isChecked) -> {
                settingsPrefs.edit().putBoolean("show_floating_button", isChecked).apply();
                fabLauncherMenu.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (!isChecked) {
                    Toast.makeText(MainActivity.this, R.string.toast_floating_hidden, Toast.LENGTH_LONG).show();
                }
            });
        }

        // Pull to refresh Switch
        MaterialSwitch swPullRefresh = dialogView.findViewById(R.id.switch_pull_refresh);
        if (swPullRefresh != null) {
            swPullRefresh.setChecked(settingsPrefs.getBoolean("pull_refresh", false));
            swPullRefresh.setOnCheckedChangeListener((btn, isChecked) -> {
                settingsPrefs.edit().putBoolean("pull_refresh", isChecked).apply();
                swipeRefreshLayout.setEnabled(isChecked);
            });
        }

        // Reload Page Button
        View btnReload = dialogView.findViewById(R.id.btn_reload_page);
        if (btnReload != null) {
            btnReload.setOnClickListener(v -> {
                webView.reload();
                dialog.dismiss();
            });
        }

        // Clear Cache Button
        View btnClear = dialogView.findViewById(R.id.btn_clear_cache);
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> {
                if (webView != null) {
                    webView.clearCache(true);
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    try {
                        String profileName = AccountManager.getSafeProfileName(accountManager.getActiveAccount().id);
                        Profile profile = ProfileStore.getInstance().getProfile(profileName);
                        if (profile != null) {
                            profile.getWebStorage().deleteAllData();
                        }
                    } catch (Exception ignored) {}
                } else {
                    android.webkit.WebStorage.getInstance().deleteAllData();
                }
                Toast.makeText(MainActivity.this, R.string.cache_cleared, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        }

        // Close Button
        View btnClose = dialogView.findViewById(R.id.btn_close_settings);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void showAccountsDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this, R.style.VeniceDialogTheme);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_accounts, null);
        builder.setView(dialogView);

        final AlertDialog dialog = builder.create();

        RecyclerView rvAccounts = dialogView.findViewById(R.id.rv_accounts);
        rvAccounts.setLayoutManager(new LinearLayoutManager(this));

        final List<AccountManager.AccountProfile> accounts = accountManager.getAccounts();
        final String activeId = accountManager.getActiveAccount().id;

        AccountsAdapter adapter = new AccountsAdapter(accounts, activeId, new AccountsAdapter.OnAccountClickListener() {
            @Override
            public void onAccountSelected(AccountManager.AccountProfile profile) {
                if (profile.id.equals(activeId)) {
                    dialog.dismiss();
                    return;
                }
                dialog.dismiss();
                String msg = String.format(getString(R.string.toast_switching_account), profile.name);
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();

                switchAccountAndRecreateWebView(profile.id, new Runnable() {
                    @Override
                    public void run() {
                        String activeMsg = String.format(getString(R.string.toast_account_active), profile.name);
                        Toast.makeText(MainActivity.this, activeMsg, Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onAccountDelete(final AccountManager.AccountProfile profile) {
                if (profile.isIncognito) {
                    accountManager.deleteAccount(profile.id);
                    Toast.makeText(MainActivity.this, R.string.toast_incognito_purged, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    switchAccountAndRecreateWebView(accountManager.getActiveAccount().id, null);
                    return;
                }

                new MaterialAlertDialogBuilder(MainActivity.this, R.style.VeniceDialogTheme)
                        .setTitle(R.string.delete_account)
                        .setMessage(getString(R.string.dialog_delete_confirm, profile.name))
                        .setPositiveButton(R.string.btn_delete, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                boolean deleted = accountManager.deleteAccount(profile.id);
                                if (deleted) {
                                    String delMsg = String.format(getString(R.string.toast_account_deleted), profile.name);
                                    Toast.makeText(MainActivity.this, delMsg, Toast.LENGTH_SHORT).show();
                                    dialog.dismiss();
                                    switchAccountAndRecreateWebView(accountManager.getActiveAccount().id, null);
                                }
                            }
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
            }
        });

        rvAccounts.setAdapter(adapter);

        dialogView.findViewById(R.id.btn_add_account).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showAddAccountDialog();
            }
        });

        View btnIncognito = dialogView.findViewById(R.id.btn_add_incognito);
        if (btnIncognito != null) {
            btnIncognito.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    final AccountManager.AccountProfile incognito = accountManager.createIncognitoAccount();
                    String msg = String.format(getString(R.string.toast_switching_account), incognito.name);
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();

                    switchAccountAndRecreateWebView(incognito.id, new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, R.string.toast_incognito_active, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        }

        dialog.show();
    }

    private void showAddAccountDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.dialog_add_account_hint);
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_muted));

        int padding = (int) (18 * getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding / 2, padding, padding / 2);
        container.addView(input);

        new MaterialAlertDialogBuilder(this, R.style.VeniceDialogTheme)
                .setTitle(R.string.dialog_add_account_title)
                .setView(container)
                .setPositiveButton(R.string.btn_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            name = "Account " + (accountManager.getAccounts().size() + 1);
                        }
                        final AccountManager.AccountProfile newAcc = accountManager.createAccount(name);
                        String createdMsg = String.format(getString(R.string.toast_account_created), newAcc.name);
                        Toast.makeText(MainActivity.this, createdMsg, Toast.LENGTH_SHORT).show();
                        switchAccountAndRecreateWebView(newAcc.id, null);
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            if (isGenerating) {
                heartbeatHandler.removeCallbacks(heartbeatRunnable);
                heartbeatHandler.post(heartbeatRunnable);
            }
            webView.evaluateJavascript(VeniceJsBridge.getInjectionScript(), null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
        if (accountManager != null && !accountManager.isIncognitoActive()) {
            accountManager.captureActiveCookies();
            accountManager.snapshotAndSaveCurrentSession(webView, new Runnable() {
                @Override
                public void run() {
                    accountManager.triggerAutoBackup();
                }
            });
        }

        // Background Keep-Alive ONLY runs if AI is currently generating and feature is enabled!
        if (isGenerating && isKeepAliveEnabled()) {
            startBackgroundService(BackgroundKeepAliveService.ACTION_GENERATION_STARTED);
            Log.d(TAG, "Activity onPause: Generating in background, KeepAlive active");
        } else {
            startBackgroundService(BackgroundKeepAliveService.ACTION_STOP_KEEPALIVE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isGenerating && isKeepAliveEnabled()) {
            startBackgroundService(BackgroundKeepAliveService.ACTION_GENERATION_STARTED);
        } else {
            startBackgroundService(BackgroundKeepAliveService.ACTION_STOP_KEEPALIVE);
        }
    }

    @Override
    protected void onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        if (accountManager != null && accountManager.isIncognitoActive()) {
            accountManager.purgeIncognitoAccounts(true);
        }
        if (webView != null) {
            if (webViewContainer != null) {
                webViewContainer.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void showRestoreOptionsDialog(final BottomSheetDialog parentDialog) {
        final Uri currentCustomUri = accountManager.getBackupManager().getCustomBackupTreeUri();
        final String currentLoc = accountManager.getBackupManager().getFormattedAutoBackupLocation();

        List<String> options = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        // Option 1: Select backup folder (auto-detects latest)
        options.add(getString(R.string.restore_option_folder));
        actions.add(() -> {
            if (parentDialog != null) parentDialog.dismiss();
            chooseRestoreFolderLauncher.launch(null);
        });

        // Option 2: Restore from configured location
        options.add(String.format(getString(R.string.restore_option_current_folder), currentLoc));
        actions.add(() -> {
            if (parentDialog != null) parentDialog.dismiss();
            restoreFromCurrentFolder();
        });

        // Option 3: Pick specific .zip file
        options.add(getString(R.string.restore_option_file));
        actions.add(() -> {
            if (parentDialog != null) parentDialog.dismiss();
            importBackupLauncher.launch(new String[]{
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                    "application/json",
                    "*/*"
            });
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.restore_dialog_title)
                .setMessage(R.string.restore_dialog_desc)
                .setItems(options.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (which >= 0 && which < actions.size()) {
                        actions.get(which).run();
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void restoreFromFolderUri(final Uri treeUri) {
        Toast.makeText(this, R.string.toast_restore_folder_scanning, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final BackupManager.BackupCandidate candidate = accountManager.getBackupManager().findLatestWorkingBackupInTree(treeUri);
            runOnUiThread(() -> {
                if (candidate != null && candidate.data != null && accountManager.restoreFromBackup(candidate.data)) {
                    int count = accountManager.getAccounts().size();
                    String msg = String.format(getString(R.string.toast_restore_success_details), candidate.name, count);
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    switchAccountAndRecreateWebView(accountManager.getActiveAccount().id, null);
                } else {
                    Toast.makeText(MainActivity.this, R.string.toast_restore_folder_none, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void restoreFromCurrentFolder() {
        Toast.makeText(this, R.string.toast_restore_folder_scanning, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final BackupManager.BackupCandidate candidate = accountManager.getBackupManager().findLatestWorkingBackupAcrossAllLocations();
            runOnUiThread(() -> {
                if (candidate != null && candidate.data != null && accountManager.restoreFromBackup(candidate.data)) {
                    int count = accountManager.getAccounts().size();
                    String msg = String.format(getString(R.string.toast_restore_success_details), candidate.name, count);
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    switchAccountAndRecreateWebView(accountManager.getActiveAccount().id, null);
                } else {
                    Toast.makeText(MainActivity.this, R.string.toast_restore_folder_none, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showLauncherSettingsDialog();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && (event.getFlags() & KeyEvent.FLAG_CANCELED) == 0) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            } else {
                showLauncherSettingsDialog();
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }
}

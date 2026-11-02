package com.venicelauncher.app;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;

public class VeniceJsBridge {
    private static final String TAG = "VeniceJsBridge";
    public static final String JS_INTERFACE_NAME = "VeniceBridge";

    public interface GenerationStateListener {
        void onGenerationStateChanged(boolean isGenerating);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private GenerationStateListener stateListener;
    private boolean currentGeneratingState = false;

    public VeniceJsBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setStateListener(GenerationStateListener listener) {
        this.stateListener = listener;
    }

    @JavascriptInterface
    public void notifyGenerationStarted() {
        Log.d(TAG, "JS notifyGenerationStarted received");
        if (!currentGeneratingState) {
            currentGeneratingState = true;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (stateListener != null) {
                        stateListener.onGenerationStateChanged(true);
                    }
                    Intent serviceIntent = new Intent(context, BackgroundKeepAliveService.class);
                    serviceIntent.setAction(BackgroundKeepAliveService.ACTION_GENERATION_STARTED);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                }
            });
        }
    }

    @JavascriptInterface
    public void notifyGenerationCompleted() {
        Log.d(TAG, "JS notifyGenerationCompleted received");
        if (currentGeneratingState) {
            currentGeneratingState = false;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (stateListener != null) {
                        stateListener.onGenerationStateChanged(false);
                    }
                    Intent serviceIntent = new Intent(context, BackgroundKeepAliveService.class);
                    serviceIntent.setAction(BackgroundKeepAliveService.ACTION_GENERATION_FINISHED);
                    context.startService(serviceIntent);
                }
            });
        }
    }

    @JavascriptInterface
    public void logFromJs(String message) {
        Log.d(TAG, "[WebView]: " + message);
    }

    /**
     * Non-destructive, 100% reliable script injected into Venice.ai.
     * Accurately tracks generation via Venice's own Stop Button lifecycle and inference POST requests.
     * NEVER wraps or modifies Response.body to avoid stream locking and state corruption.
     */
    public static String getInjectionScript() {
        return "(function() {\n" +
                "  if (window.__venice_bridge_injected__) return;\n" +
                "  window.__venice_bridge_injected__ = true;\n" +
                "  \n" +
                "  var isCurrentlyGenerating = false;\n" +
                "  var inferenceActive = false;\n" +
                "  var inferenceTimeout = null;\n" +
                "  \n" +
                "  function notifyState(generating) {\n" +
                "    if (isCurrentlyGenerating === generating) return;\n" +
                "    isCurrentlyGenerating = generating;\n" +
                "    try {\n" +
                "      if (window.VeniceBridge) {\n" +
                "        if (generating) {\n" +
                "          window.VeniceBridge.notifyGenerationStarted();\n" +
                "        } else {\n" +
                "          window.VeniceBridge.notifyGenerationCompleted();\n" +
                "        }\n" +
                "      }\n" +
                "    } catch(e) {\n" +
                "      console.error('VeniceBridge call error', e);\n" +
                "    }\n" +
                "  }\n" +
                "  \n" +
                "  function checkGenerationState() {\n" +
                "    // 1. Venice chat conditionally renders minds-chat-stop-button ONLY while generating\n" +
                "    var stopBtn = document.querySelector(\n" +
                "      'button[data-testid=\"minds-chat-stop-button\"], ' +\n" +
                "      'button[aria-label*=\"Stop generation\" i], ' +\n" +
                "      'button[title*=\"Stop generation\" i], ' +\n" +
                "      '[data-testid=\"agentic-stopped-indicator\"]'\n" +
                "    );\n" +
                "    \n" +
                "    var hasVisibleStopBtn = false;\n" +
                "    if (stopBtn) {\n" +
                "      var style = window.getComputedStyle(stopBtn);\n" +
                "      if (style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0') {\n" +
                "        hasVisibleStopBtn = true;\n" +
                "      }\n" +
                "    }\n" +
                "    \n" +
                "    // If Stop Button disappeared, inference is definitely no longer active\n" +
                "    if (!hasVisibleStopBtn && inferenceActive) {\n" +
                "      inferenceActive = false;\n" +
                "      if (inferenceTimeout) clearTimeout(inferenceTimeout);\n" +
                "    }\n" +
                "    \n" +
                "    var isGen = hasVisibleStopBtn || inferenceActive;\n" +
                "    notifyState(isGen);\n" +
                "  }\n" +
                "  \n" +
                "  // 2. Track chat and image inference requests WITHOUT modifying or locking res.body!\n" +
                "  if (window.fetch) {\n" +
                "    var rawFetch = window.fetch;\n" +
                "    window.fetch = function() {\n" +
                "      var url = '';\n" +
                "      var method = 'GET';\n" +
                "      \n" +
                "      if (typeof arguments[0] === 'string') {\n" +
                "        url = arguments[0];\n" +
                "      } else if (arguments[0] && arguments[0].url) {\n" +
                "        url = arguments[0].url;\n" +
                "      }\n" +
                "      \n" +
                "      if (arguments[1] && arguments[1].method) {\n" +
                "        method = arguments[1].method.toUpperCase();\n" +
                "      }\n" +
                "      \n" +
                "      // Match ONLY actual generation endpoints, never session_ticket or models list\n" +
                "      var isChatInference = method === 'POST' && (\n" +
                "        url.indexOf('/api/inference/chat') !== -1 ||\n" +
                "        url.indexOf('/api/inference/image') !== -1\n" +
                "      );\n" +
                "      \n" +
                "      var p = rawFetch.apply(this, arguments);\n" +
                "      \n" +
                "      if (isChatInference) {\n" +
                "        inferenceActive = true;\n" +
                "        notifyState(true);\n" +
                "        \n" +
                "        if (inferenceTimeout) clearTimeout(inferenceTimeout);\n" +
                "        // Safety timeout in case stream closes abruptly\n" +
                "        inferenceTimeout = setTimeout(function() {\n" +
                "          inferenceActive = false;\n" +
                "          checkGenerationState();\n" +
                "        }, 45000);\n" +
                "        \n" +
                "        p.then(function(res) {\n" +
                "          if (!res.ok) {\n" +
                "            // Request rejected by server (e.g. rate limit, auth)\n" +
                "            inferenceActive = false;\n" +
                "            if (inferenceTimeout) clearTimeout(inferenceTimeout);\n" +
                "            setTimeout(checkGenerationState, 400);\n" +
                "          }\n" +
                "        }).catch(function() {\n" +
                "          inferenceActive = false;\n" +
                "          if (inferenceTimeout) clearTimeout(inferenceTimeout);\n" +
                "          setTimeout(checkGenerationState, 400);\n" +
                "        });\n" +
                "      }\n" +
                "      \n" +
                "      // Return original promise directly, ensuring zero interference with React / Vercel AI SDK!\n" +
                "      return p;\n" +
                "    };\n" +
                "  }\n" +
                "  \n" +
                "  // 3. MutationObserver on document.body for instant stop-button detection\n" +
                "  var observer = new MutationObserver(function() {\n" +
                "    checkGenerationState();\n" +
                "  });\n" +
                "  \n" +
                "  if (document.body) {\n" +
                "    observer.observe(document.body, { childList: true, subtree: true });\n" +
                "  } else {\n" +
                "    document.addEventListener('DOMContentLoaded', function() {\n" +
                "      observer.observe(document.body, { childList: true, subtree: true });\n" +
                "    });\n" +
                "  }\n" +
                "  \n" +
                "  // Fast periodic check every 800ms\n" +
                "  setInterval(checkGenerationState, 800);\n" +
                "  checkGenerationState();\n" +
                "})();";
    }
}

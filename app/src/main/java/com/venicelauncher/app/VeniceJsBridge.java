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
    public static final String WEB_MESSAGE_PORT_NAME = "__vNativePort";

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
                    android.content.SharedPreferences prefs = context.getSharedPreferences("venice_settings", Context.MODE_PRIVATE);
                    boolean keepAliveEnabled = prefs.getBoolean("always_keep_alive", true);
                    if (keepAliveEnabled) {
                        Intent serviceIntent = new Intent(context, BackgroundKeepAliveService.class);
                        serviceIntent.setAction(BackgroundKeepAliveService.ACTION_GENERATION_STARTED);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
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
     * Non-destructive, anti-detect compatible generation tracker.
     * Accurately tracks generation via Venice's Stop Button lifecycle and inference POST requests.
     * Conceals bridge references and injection flags from global window enumeration.
     */
    public static String getInjectionScript() {
        return "(function() {\n" +
                "  var guard = typeof Symbol === 'function' ? Symbol.for('__v_bridge_guard__') : '__v_bg_guard';\n" +
                "  if (window[guard]) return;\n" +
                "  window[guard] = true;\n" +
                "  \n" +
                "  var nativePort = window." + WEB_MESSAGE_PORT_NAME + ";\n" +
                "  var nativeBridge = window." + JS_INTERFACE_NAME + ";\n" +
                "  \n" +
                "  // Conceal global bridge references from website scripts\n" +
                "  try {\n" +
                "    delete window." + JS_INTERFACE_NAME + ";\n" +
                "  } catch(e) {}\n" +
                "  try {\n" +
                "    Object.defineProperty(window, '" + JS_INTERFACE_NAME + "', {\n" +
                "      get: function() { return undefined; },\n" +
                "      set: function() {},\n" +
                "      configurable: false,\n" +
                "      enumerable: false\n" +
                "    });\n" +
                "  } catch(e) {}\n" +
                "  try {\n" +
                "    Object.defineProperty(window, '" + WEB_MESSAGE_PORT_NAME + "', {\n" +
                "      get: function() { return undefined; },\n" +
                "      set: function() {},\n" +
                "      configurable: false,\n" +
                "      enumerable: false\n" +
                "    });\n" +
                "  } catch(e) {}\n" +
                "  \n" +
                "  var isCurrentlyGenerating = false;\n" +
                "  var inferenceActive = false;\n" +
                "  var inferenceTimeout = null;\n" +
                "  \n" +
                "  function notifyState(generating) {\n" +
                "    if (isCurrentlyGenerating === generating) return;\n" +
                "    isCurrentlyGenerating = generating;\n" +
                "    try {\n" +
                "      if (nativePort && typeof nativePort.postMessage === 'function') {\n" +
                "        nativePort.postMessage(generating ? 'STARTED' : 'COMPLETED');\n" +
                "      } else if (nativeBridge) {\n" +
                "        if (generating) {\n" +
                "          nativeBridge.notifyGenerationStarted();\n" +
                "        } else {\n" +
                "          nativeBridge.notifyGenerationCompleted();\n" +
                "        }\n" +
                "      }\n" +
                "    } catch(e) {\n" +
                "      console.error('Bridge call error', e);\n" +
                "    }\n" +
                "  }\n" +
                "  \n" +
                "  function checkGenerationState() {\n" +
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
                "    if (!hasVisibleStopBtn && inferenceActive) {\n" +
                "      inferenceActive = false;\n" +
                "      if (inferenceTimeout) clearTimeout(inferenceTimeout);\n" +
                "    }\n" +
                "    \n" +
                "    var isGen = hasVisibleStopBtn || inferenceActive;\n" +
                "    notifyState(isGen);\n" +
                "  }\n" +
                "  \n" +
                "  // Track chat and image inference requests\n" +
                "  if (window.fetch) {\n" +
                "    var rawFetch = window.fetch;\n" +
                "    window.fetch = function() {\n" +
                "      var url = '';\n" +
                "      var method = 'GET';\n" +
                "      if (typeof arguments[0] === 'string') {\n" +
                "        url = arguments[0];\n" +
                "      } else if (arguments[0] && arguments[0].url) {\n" +
                "        url = arguments[0].url;\n" +
                "      }\n" +
                "      if (arguments[1] && arguments[1].method) {\n" +
                "        method = arguments[1].method.toUpperCase();\n" +
                "      }\n" +
                "      \n" +
                "      var isChatInference = method === 'POST' && (\n" +
                "        url.indexOf('/api/inference/chat') !== -1 ||\n" +
                "        url.indexOf('/api/inference/image') !== -1\n" +
                "      );\n" +
                "      \n" +
                "      var p = rawFetch.apply(this, arguments);\n" +
                "      if (isChatInference) {\n" +
                "        inferenceActive = true;\n" +
                "        notifyState(true);\n" +
                "        if (inferenceTimeout) clearTimeout(inferenceTimeout);\n" +
                "        inferenceTimeout = setTimeout(function() {\n" +
                "          inferenceActive = false;\n" +
                "          checkGenerationState();\n" +
                "        }, 45000);\n" +
                "        p.then(function(res) {\n" +
                "          if (!res.ok) {\n" +
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
                "      return p;\n" +
                "    };\n" +
                "  }\n" +
                "  \n" +
                "  var mutationThrottleTimer = null;\n" +
                "  var observer = new MutationObserver(function() {\n" +
                "    if (mutationThrottleTimer) return;\n" +
                "    mutationThrottleTimer = setTimeout(function() {\n" +
                "      mutationThrottleTimer = null;\n" +
                "      checkGenerationState();\n" +
                "    }, 200);\n" +
                "  });\n" +
                "  if (document.body) {\n" +
                "    observer.observe(document.body, { childList: true, subtree: true });\n" +
                "  } else {\n" +
                "    document.addEventListener('DOMContentLoaded', function() {\n" +
                "      observer.observe(document.body, { childList: true, subtree: true });\n" +
                "    });\n" +
                "  }\n" +
                "  \n" +
                "  setInterval(checkGenerationState, 800);\n" +
                "  checkGenerationState();\n" +
                "})();";
    }
}

package com.venicelauncher.app;

import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages privacy and anti-fingerprinting protections for Venice Launcher:
 * - Sanitizes User-Agent to match standard Google Chrome Mobile (removes '; wv' and 'Version/4.0')
 * - Suppresses 'X-Requested-With: com.venicelauncher.app' HTTP header
 * - Injects deterministic, profile-seeded noise into Canvas 2D, WebGL, and AudioContext
 * - Blocks WebRTC leaks and normalizes hardware concurrency & memory metrics
 */
public class AntiDetectManager {
    private static final String TAG = "AntiDetectManager";

    /**
     * Strips WebView signatures (e.g. '; wv', 'Version/4.0', custom launcher markers)
     * and returns a clean, authentic Google Chrome Mobile user-agent string.
     */
    public static String sanitizeUserAgent(String rawUa) {
        if (rawUa == null || rawUa.trim().isEmpty()) {
            return "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36";
        }
        return rawUa.replace("; wv", "")
                .replace("; wv;", ";")
                .replace("Version/4.0 ", "")
                .replaceAll("VeniceLauncher/\\S+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Generates a deterministic integer seed for the given profile ID.
     * Temporary (incognito) profiles receive a freshly randomized seed.
     */
    public static int getProfileSeed(String accountId, boolean isIncognito) {
        if (isIncognito || accountId == null) {
            return (int) (System.currentTimeMillis() ^ (long) (Math.random() * 1000000.0));
        }
        return Math.abs(accountId.hashCode());
    }

    /**
     * Generates self-contained anti-detect JavaScript:
     * 1. Blocks WebRTC to prevent STUN/local IP leakage.
     * 2. Spoofs navigator.hardwareConcurrency (8 cores) & deviceMemory (8 GB).
     * 3. Spoofs WebGL Vendor & Renderer per profile seed.
     * 4. Injects subtle Canvas 2D noise per profile seed (breaks cross-profile correlation).
     * 5. Injects subtle AudioBuffer noise per profile seed.
     */
    public static String buildAntiDetectScript(int seed) {
        return "(function() {\n" +
                "  var guard = typeof Symbol === 'function' ? Symbol.for('__v_anti_detect__') : '__v_anti_detect__';\n" +
                "  if (window[guard]) return;\n" +
                "  window[guard] = true;\n" +
                "  var seed = " + seed + ";\n" +
                "  \n" +
                "  // 1. Disable WebRTC to block STUN IP leaks\n" +
                "  try {\n" +
                "    window.RTCPeerConnection = undefined;\n" +
                "    window.webkitRTCPeerConnection = undefined;\n" +
                "    window.RTCSessionDescription = undefined;\n" +
                "    window.RTCIceCandidate = undefined;\n" +
                "  } catch(e) {}\n" +
                "  \n" +
                "  // 2. Hardware specs spoofing (flagship standard)\n" +
                "  try {\n" +
                "    Object.defineProperty(navigator, 'hardwareConcurrency', {\n" +
                "      get: function() { return 8; },\n" +
                "      configurable: false,\n" +
                "      enumerable: true\n" +
                "    });\n" +
                "    Object.defineProperty(navigator, 'deviceMemory', {\n" +
                "      get: function() { return 8; },\n" +
                "      configurable: false,\n" +
                "      enumerable: true\n" +
                "    });\n" +
                "  } catch(e) {}\n" +
                "  \n" +
                "  // 3. WebGL GPU spoofing (deterministic per seed)\n" +
                "  try {\n" +
                "    var gpuVendors = ['Google Inc. (Qualcomm)', 'Google Inc. (Qualcomm)', 'Google Inc. (ARM)', 'Google Inc. (ARM)'];\n" +
                "    var gpuRenderers = ['ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2)', 'ANGLE (Qualcomm, Adreno (TM) 750, OpenGL ES 3.2)', 'Mali-G715-Immortalis MC11', 'Mali-G720 MC12'];\n" +
                "    var vendor = gpuVendors[Math.abs(seed) % gpuVendors.length];\n" +
                "    var renderer = gpuRenderers[Math.abs(seed) % gpuRenderers.length];\n" +
                "    \n" +
                "    var patchGl = function(proto) {\n" +
                "      if (!proto) return;\n" +
                "      var orig = proto.getParameter;\n" +
                "      proto.getParameter = function(param) {\n" +
                "        if (param === 0x9245) return vendor;   // UNMASKED_VENDOR_WEBGL\n" +
                "        if (param === 0x9246) return renderer; // UNMASKED_RENDERER_WEBGL\n" +
                "        return orig.apply(this, arguments);\n" +
                "      };\n" +
                "    };\n" +
                "    if (window.WebGLRenderingContext) patchGl(window.WebGLRenderingContext.prototype);\n" +
                "    if (window.WebGL2RenderingContext) patchGl(window.WebGL2RenderingContext.prototype);\n" +
                "  } catch(e) {}\n" +
                "  \n" +
                "  // 4. Subtle Canvas 2D noise injection per seed (applied to off-screen export to protect live canvas)\n" +
                "  try {\n" +
                "    if (window.HTMLCanvasElement) {\n" +
                "      var origToDataURL = HTMLCanvasElement.prototype.toDataURL;\n" +
                "      HTMLCanvasElement.prototype.toDataURL = function() {\n" +
                "        try {\n" +
                "          if (this.width > 0 && this.height > 0 && this.width <= 512 && this.height <= 512) {\n" +
                "            var offscreen = document.createElement('canvas');\n" +
                "            offscreen.width = this.width;\n" +
                "            offscreen.height = this.height;\n" +
                "            var offCtx = offscreen.getContext('2d');\n" +
                "            if (offCtx) {\n" +
                "              offCtx.drawImage(this, 0, 0);\n" +
                "              var img = offCtx.getImageData(0, 0, 1, 1);\n" +
                "              var noise = (seed % 3) + 1;\n" +
                "              img.data[0] = (img.data[0] ^ noise);\n" +
                "              offCtx.putImageData(img, 0, 0);\n" +
                "              return origToDataURL.apply(offscreen, arguments);\n" +
                "            }\n" +
                "          }\n" +
                "        } catch(e) {}\n" +
                "        return origToDataURL.apply(this, arguments);\n" +
                "      };\n" +
                "      \n" +
                "      var origToBlob = HTMLCanvasElement.prototype.toBlob;\n" +
                "      HTMLCanvasElement.prototype.toBlob = function(callback, type, quality) {\n" +
                "        try {\n" +
                "          if (this.width > 0 && this.height > 0 && this.width <= 512 && this.height <= 512) {\n" +
                "            var offscreen = document.createElement('canvas');\n" +
                "            offscreen.width = this.width;\n" +
                "            offscreen.height = this.height;\n" +
                "            var offCtx = offscreen.getContext('2d');\n" +
                "            if (offCtx) {\n" +
                "              offCtx.drawImage(this, 0, 0);\n" +
                "              var img = offCtx.getImageData(0, 0, 1, 1);\n" +
                "              var noise = (seed % 3) + 1;\n" +
                "              img.data[0] = (img.data[0] ^ noise);\n" +
                "              offCtx.putImageData(img, 0, 0);\n" +
                "              return origToBlob.apply(offscreen, arguments);\n" +
                "            }\n" +
                "          }\n" +
                "        } catch(e) {}\n" +
                "        return origToBlob.apply(this, arguments);\n" +
                "      };\n" +
                "    }\n" +
                "  } catch(e) {}\n" +
                "  \n" +
                "  // 5. Subtle AudioContext noise injection per seed\n" +
                "  try {\n" +
                "    if (window.AudioBuffer) {\n" +
                "      var origGetChannel = AudioBuffer.prototype.getChannelData;\n" +
                "      AudioBuffer.prototype.getChannelData = function(c) {\n" +
                "        var buf = origGetChannel.apply(this, arguments);\n" +
                "        if (buf && buf.length > 0) {\n" +
                "          var delta = ((seed % 97) + 1) * 1e-7;\n" +
                "          buf[0] = buf[0] + delta;\n" +
                "        }\n" +
                "        return buf;\n" +
                "      };\n" +
                "    }\n" +
                "  } catch(e) {}\n" +
                "})();";
    }

    /**
     * Applies full Anti-Detect protections to the given WebView:
     * - Cleans User-Agent string (removes '; wv', 'Version/4.0', custom markers)
     * - Suppresses 'X-Requested-With' HTTP header via AndroidX allow-list
     * - Registers document-start script (Chromium WebKit API)
     */
    public static void applyAntiDetect(WebView wv, String accountId, boolean isIncognito) {
        if (wv == null) return;

        try {
            WebSettings ws = wv.getSettings();
            String cleanUa = sanitizeUserAgent(ws.getUserAgentString());
            ws.setUserAgentString(cleanUa);
        } catch (Throwable t) {
            Log.w(TAG, "Failed setting sanitized user agent", t);
        }

        try {
            // Suppress X-Requested-With header
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettings ws = wv.getSettings();
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(ws, Collections.<String>emptySet());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed suppressing X-Requested-With header", t);
        }

        try {
            int seed = getProfileSeed(accountId, isIncognito);
            String script = buildAntiDetectScript(seed);

            // Run at document-start before any page JavaScript executes
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                Set<String> origins = new HashSet<>(Arrays.asList(
                        "https://venice.ai",
                        "https://*.venice.ai",
                        "https://chat.venice.ai"
                ));
                WebViewCompat.addDocumentStartJavaScript(wv, script, origins);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed applying document-start script", t);
        }
    }
}

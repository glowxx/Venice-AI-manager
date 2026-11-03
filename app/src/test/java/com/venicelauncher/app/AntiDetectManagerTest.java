package com.venicelauncher.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AntiDetectManagerTest {

    @Test
    public void testSanitizeUserAgentRemovesWebViewAndLauncherMarkers() {
        String input = "Mozilla/5.0 (Linux; U; Android 14; SM-S928B Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36 VeniceLauncher/1.0.7 Mobile";
        String sanitized = AntiDetectManager.sanitizeUserAgent(input);

        assertFalse("Should not contain '; wv'", sanitized.contains("; wv"));
        assertFalse("Should not contain 'Version/4.0'", sanitized.contains("Version/4.0"));
        assertFalse("Should not contain 'VeniceLauncher'", sanitized.contains("VeniceLauncher"));
        assertTrue("Should contain Chrome version", sanitized.contains("Chrome/131.0.6778.135"));
        assertTrue("Should contain Mobile Safari", sanitized.contains("Mobile Safari/537.36"));
    }

    @Test
    public void testSanitizeUserAgentWithNullOrEmpty() {
        String fallback = AntiDetectManager.sanitizeUserAgent(null);
        assertNotNull(fallback);
        assertTrue(fallback.contains("Chrome/"));

        String emptyFallback = AntiDetectManager.sanitizeUserAgent("   ");
        assertNotNull(emptyFallback);
        assertTrue(emptyFallback.contains("Chrome/"));
    }

    @Test
    public void testGetProfileSeedDeterministicForPermanentAccount() {
        int seed1 = AntiDetectManager.getProfileSeed("account_work_123", false);
        int seed2 = AntiDetectManager.getProfileSeed("account_work_123", false);
        assertEquals("Seed should be strictly deterministic for the same permanent account", seed1, seed2);

        int seedOther = AntiDetectManager.getProfileSeed("account_personal_456", false);
        assertNotEquals("Seeds should differ across accounts", seed1, seedOther);
    }

    @Test
    public void testBuildAntiDetectScriptContainsEssentialProtections() {
        String script = AntiDetectManager.buildAntiDetectScript(42);
        assertNotNull(script);
        assertTrue("Script must disable WebRTC", script.contains("RTCPeerConnection = undefined"));
        assertTrue("Script must spoof hardwareConcurrency", script.contains("hardwareConcurrency"));
        assertTrue("Script must spoof deviceMemory", script.contains("deviceMemory"));
        assertTrue("Script must spoof WebGL parameters", script.contains("WebGLRenderingContext"));
        assertTrue("Script must inject Canvas noise", script.contains("HTMLCanvasElement.prototype.toDataURL"));
        assertTrue("Script must inject Audio noise", script.contains("AudioBuffer.prototype.getChannelData"));
        assertTrue("Script must embed profile seed", script.contains("var seed = 42;"));
    }
}

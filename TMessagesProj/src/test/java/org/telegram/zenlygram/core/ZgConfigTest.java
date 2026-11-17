package org.telegram.zenlygram.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** JVM tests of the website links the app builds. */
public class ZgConfigTest {

    @Test
    public void theDownloadPageFollowsTheWebsitesLanguagePrefixes() {
        assertEquals("https://zenlygram.org/download", ZgConfig.downloadUrl("uz"));
        assertEquals("https://zenlygram.org/ru/download", ZgConfig.downloadUrl("ru"));
        assertEquals("https://zenlygram.org/en/download", ZgConfig.downloadUrl("en"));
        // Any other language, and an unreadable one, open the Uzbek page, as the website's default is.
        assertEquals("https://zenlygram.org/download", ZgConfig.downloadUrl("de"));
        assertEquals("https://zenlygram.org/download", ZgConfig.downloadUrl(null));
        assertEquals(ZgConfig.DOWNLOAD_URL, ZgConfig.downloadUrl(""));
    }
}

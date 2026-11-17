/*
 * This is the source code of Zenlygram for Android, an unofficial Telegram client
 * based on Telegram for Android. It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 */

package org.telegram.zenlygram.cloud;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The credential-free bounded GET of the public policies (iOS SGEntitlementBoundedFetch): HTTPS only, redirects
 * refused, the body counted while it arrives and cut at 16 KiB (Content-Length is never trusted instead), 5 s idle
 * timeouts and a 10 s cap on the whole exchange. No Authorization, no device id, no cookies.
 */
public final class ZgBoundedFetch {

    public static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int IDLE_TIMEOUT_MS = 5000;
    private static final long TOTAL_TIMEOUT_MS = 10000;

    public static final class Response {
        /** HTTP status, or -1 when there was no usable answer (offline, timeout, redirect, oversized body). */
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private ZgBoundedFetch() {
    }

    public static Response get(String url, String accept, String userAgent) {
        HttpURLConnection connection = null;
        final long deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS;
        try {
            final URL target = new URL(url);
            if (!"https".equals(target.getProtocol())) {
                return new Response(-1, null);
            }
            connection = (HttpURLConnection) target.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(IDLE_TIMEOUT_MS);
            connection.setReadTimeout(IDLE_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", accept);
            if (userAgent != null) {
                connection.setRequestProperty("User-Agent", userAgent);
            }
            final int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                return new Response(-1, null);
            }
            final InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return new Response(status, null);
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[2048];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (out.size() + read > MAX_BODY_BYTES || System.currentTimeMillis() > deadline) {
                    return new Response(-1, null);
                }
                out.write(buffer, 0, read);
            }
            return new Response(status, out.toString("UTF-8"));
        } catch (Exception e) {
            return new Response(-1, null);
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
    }
}

package com.madhu.glasslauncher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WebdavCrawler {
    private static final String TAG = "WebdavCrawler";

    public interface CrawlCallback {
        void onCrawlComplete(List<String> photos);
    }

    public static void crawlAsync(final Context context,
                                  final String webdavUrl,
                                  final String webdavFolder,
                                  final String webdavUser,
                                  final String webdavPass,
                                  final boolean recursive,
                                  final CrawlCallback callback) {
        if (webdavUrl == null || webdavUrl.trim().isEmpty() || webdavFolder == null || webdavFolder.trim().isEmpty()) {
            if (callback != null) callback.onCrawlComplete(new ArrayList<String>());
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                // If media is currently active across the system, abort crawling immediately to save bandwidth & CPU
                if (PhotoDatabase.isMediaActive(context)) {
                    Log.i(TAG, "Media is currently active on TV. Aborting WebDAV crawl.");
                    if (callback != null) callback.onCrawlComplete(null);
                    return;
                }

                List<String> discovered = new ArrayList<String>();
                try {
                    String base = webdavUrl.trim();
                    if (!base.endsWith("/")) base += "/";
                    String startFolder = webdavFolder.trim();
                    if (startFolder.startsWith("/")) startFolder = startFolder.substring(1);
                    if (!startFolder.isEmpty() && !startFolder.endsWith("/")) startFolder += "/";

                    Queue<String> queue = new ArrayDeque<String>();
                    queue.add(startFolder);
                    HashSet<String> visited = new HashSet<String>();

                    String authHeader = null;
                    if (webdavUser != null && !webdavUser.isEmpty()) {
                        String userPass = webdavUser + ":" + (webdavPass != null ? webdavPass : "");
                        authHeader = "Basic " + android.util.Base64.encodeToString(userPass.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                    }

                    Pattern hrefPattern = Pattern.compile("<(?:D:)?href>([^<]+)</(?:D:)?href>", Pattern.CASE_INSENSITIVE);
                    Pattern colPattern = Pattern.compile("<(?:D:)?collection", Pattern.CASE_INSENSITIVE);

                    URL targetUrl = new URL(base);
                    String host = targetUrl.getHost();
                    int port = targetUrl.getPort() != -1 ? targetUrl.getPort() : (targetUrl.getProtocol().equalsIgnoreCase("https") ? 443 : 80);

                    int depthCount = 0;
                    while (!queue.isEmpty() && depthCount < 50) {
                        // Check media active during traversal as well
                        if (PhotoDatabase.isMediaActive(context)) {
                            Log.i(TAG, "Media playback started during crawl. Aborting.");
                            break;
                        }

                        String relPath = queue.poll();
                        if (visited.contains(relPath)) continue;
                        visited.add(relPath);
                        depthCount++;

                        String requestUri = "/" + relPath;
                        Socket s = new Socket(host, port);
                        s.setSoTimeout(10000);

                        StringBuilder req = new StringBuilder();
                        req.append("PROPFIND ").append(requestUri).append(" HTTP/1.1\r\n");
                        req.append("Host: ").append(host).append(":").append(port).append("\r\n");
                        if (authHeader != null) {
                            req.append("Authorization: ").append(authHeader).append("\r\n");
                        }
                        req.append("Depth: 1\r\n");
                        req.append("Connection: close\r\n");
                        req.append("\r\n");

                        java.io.OutputStream os = s.getOutputStream();
                        os.write(req.toString().getBytes("UTF-8"));
                        os.flush();

                        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                        String statusLine = br.readLine();
                        if (statusLine != null && (statusLine.contains("200") || statusLine.contains("207"))) {
                            StringBuilder sb = new StringBuilder();
                            String l;
                            while ((l = br.readLine()) != null) sb.append(l);
                            br.close();
                            s.close();

                            String xml = sb.toString();
                            String[] responses = xml.split("</(?:D:)?response>");
                            for (String respBlock : responses) {
                                Matcher m = hrefPattern.matcher(respBlock);
                                if (m.find()) {
                                    String href = m.group(1).trim();
                                    try { href = java.net.URLDecoder.decode(href, "UTF-8"); } catch (Exception e) {}

                                    boolean isCollection = colPattern.matcher(respBlock).find();
                                    if (isCollection) {
                                        if (recursive) {
                                            String subRel = href;
                                            if (subRel.startsWith("/")) subRel = subRel.substring(1);
                                            if (!subRel.endsWith("/")) subRel += "/";
                                            if (!subRel.equals(relPath) && subRel.length() > relPath.length()) {
                                                queue.add(subRel);
                                            }
                                        }
                                    } else {
                                        String lower = href.toLowerCase();
                                        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                                                || lower.endsWith(".png") || lower.endsWith(".webp")
                                                || lower.endsWith(".bmp") || lower.endsWith(".heic")
                                                || lower.endsWith(".gif")) {
                                            String fullPhotoUrl = href.startsWith("http") ? href : (base + (href.startsWith("/") ? href.substring(1) : href));
                                            discovered.add(fullPhotoUrl);
                                        }
                                    }
                                }
                            }
                        } else {
                            br.close();
                            s.close();
                        }
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Failed crawling WebDAV photos", t);
                }

                if (callback != null) {
                    callback.onCrawlComplete(discovered);
                }
            }
        }).start();
    }
}

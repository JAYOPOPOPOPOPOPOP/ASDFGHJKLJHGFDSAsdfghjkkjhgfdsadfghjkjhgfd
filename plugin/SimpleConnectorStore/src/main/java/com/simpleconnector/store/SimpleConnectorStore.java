package com.simpleconnector.store;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class SimpleConnectorStore extends JavaPlugin {

    private String apiUrl;
    private String code;
    private int interval;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocal();
        long ticks = Math.max(10, interval) * 20L;
        new BukkitRunnable() {
            @Override
            public void run() {
                sendHeartbeat();
            }
        }.runTaskTimerAsynchronously(this, 40L, ticks);
        getLogger().info("SimpleConnectorStore enabled. Heartbeat every " + interval + "s.");
        if (apiUrl == null || apiUrl.isEmpty() || "AUTO".equalsIgnoreCase(apiUrl)) {
            getLogger().warning("api-url is not set. Re-download the plugin from your website admin panel so URL is filled automatically.");
        }
    }

    private void reloadLocal() {
        reloadConfig();
        apiUrl = getConfig().getString("api-url", "").replaceAll("/$", "");
        if ("AUTO".equalsIgnoreCase(apiUrl)) apiUrl = "";
        code = getConfig().getString("connect-code", "").trim();
        interval = getConfig().getInt("heartbeat-seconds", 15);
        if (interval < 5) interval = 5;
    }

    private void sendHeartbeat() {
        if (apiUrl == null || apiUrl.isEmpty() || code == null || code.isEmpty() || code.contains("XXXX")) {
            return;
        }
        try {
            URL url = new URL(apiUrl + "/api/connector/heartbeat");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "SimpleConnectorStore/1.0");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            int players = Bukkit.getOnlinePlayers().size();
            String version = Bukkit.getVersion();
            String body = "{\"code\":\"" + escape(code) + "\",\"players\":" + players + ",\"version\":\"" + escape(version) + "\",\"results\":[]}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if (status != 200) {
                getLogger().warning("Website connector: HTTP " + status);
                conn.disconnect();
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            conn.disconnect();
            String resp = sb.toString();
            // Parse commands array roughly (no JSON lib)
            List<Cmd> cmds = parseCommands(resp);
            if (!cmds.isEmpty()) {
                List<String> resultJson = new ArrayList<>();
                for (Cmd cmd : cmds) {
                    String out = runConsole(cmd.command);
                    resultJson.add("{\"id\":\"" + escape(cmd.id) + "\",\"command\":\"" + escape(cmd.command) + "\",\"success\":true,\"output\":\"" + escape(out) + "\"}");
                }
                // send results on next short request
                postResults(resultJson);
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Website connector failed: " + e.getMessage());
        }
    }

    private void postResults(List<String> resultJson) {
        if (resultJson.isEmpty()) return;
        try {
            URL url = new URL(apiUrl + "/api/connector/heartbeat");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "SimpleConnectorStore/1.0");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            int players = Bukkit.getOnlinePlayers().size();
            String version = Bukkit.getVersion();
            String body = "{\"code\":\"" + escape(code) + "\",\"players\":" + players + ",\"version\":\"" + escape(version) + "\",\"results\":[" + String.join(",", resultJson) + "]}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to post command results: " + e.getMessage());
        }
    }

    private String runConsole(String command) {
        // Console must NOT use leading "/" — slash is only for player chat
        if (command == null) command = "";
        String cmd = command.trim();
        while (cmd.startsWith("/")) {
            cmd = cmd.substring(1).trim();
        }
        if (cmd.isEmpty()) {
            return "SKIP: empty command";
        }
        final String toRun = cmd;
        final StringBuilder out = new StringBuilder();
        try {
            // Run on main thread
            Bukkit.getScheduler().callSyncMethod(this, () -> {
                try {
                    boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toRun);
                    out.append(ok ? "OK" : "FAILED");
                } catch (Exception ex) {
                    out.append("ERR: ").append(ex.getMessage());
                }
                return null;
            }).get();
        } catch (Exception e) {
            out.append("ERR: ").append(e.getMessage());
        }
        getLogger().info("Remote command: " + toRun + " -> " + out);
        return out.toString();
    }

    private static class Cmd {
        String id;
        String command;
    }

    private List<Cmd> parseCommands(String json) {
        List<Cmd> list = new ArrayList<>();
        int idx = json.indexOf("\"commands\"");
        if (idx < 0) return list;
        int arrStart = json.indexOf('[', idx);
        int arrEnd = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return list;
        String arr = json.substring(arrStart + 1, arrEnd);
        // split objects
        int pos = 0;
        while (pos < arr.length()) {
            int o1 = arr.indexOf('{', pos);
            if (o1 < 0) break;
            int o2 = arr.indexOf('}', o1);
            if (o2 < 0) break;
            String obj = arr.substring(o1, o2 + 1);
            Cmd c = new Cmd();
            c.id = extract(obj, "id");
            c.command = extract(obj, "command");
            if (c.id != null && c.command != null && !c.command.isEmpty()) list.add(c);
            pos = o2 + 1;
        }
        return list;
    }

    private static String extract(String obj, String key) {
        String k = "\"" + key + "\"";
        int i = obj.indexOf(k);
        if (i < 0) return null;
        int colon = obj.indexOf(':', i + k.length());
        if (colon < 0) return null;
        int q1 = obj.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = obj.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return obj.substring(q1 + 1, q2).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}

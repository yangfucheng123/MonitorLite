package com.monitor.lite.service;

import com.monitor.lite.config.CacheStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;

/**
 * Docker容器监控 — 通过Docker Engine API探测主机上的容器状态
 * 支持信创环境: 麒麟V10/V11 + 统信UOS + 海光/鲲鹏架构
 */
@Service
public class DockerMonitorService {
    private static final Logger log = LoggerFactory.getLogger(DockerMonitorService.class);
    private final CacheStore cacheStore;
    private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public DockerMonitorService(CacheStore cacheStore, JdbcTemplate jdbc) {
        this.cacheStore = cacheStore;
        this.jdbc = jdbc;
    }

    public void checkAll() {
        cacheStore.clearDockerStatus();
        List<Map<String, Object>> targets = loadTargets();
        if (targets.isEmpty()) return;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map<String, Object> t : targets) {
            futures.add(CompletableFuture.runAsync(() -> {
                String name = (String) t.get("name");
                cacheStore.putDockerStatus(name, checkHost(t));
            }, pool));
        }
        try { CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS); }
        catch (Exception e) { log.warn("Docker主机检查超时", e); }
    }

    /** 探测单个Docker主机 — 先TCP探测端口, 再通过Docker API获取容器列表 */
    private Map<String, Object> checkHost(Map<String, Object> config) {
        String name = (String) config.get("name");
        String host = (String) config.get("host");
        int port = ((Number) config.getOrDefault("port", 2375)).intValue();
        boolean tls = Boolean.TRUE.equals(config.get("tls_enabled"));
        String apiBase = (tls ? "https://" : "http://") + host + ":" + port;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("host", host + ":" + port);

        long start = System.currentTimeMillis();
        try {
            // 第一步: TCP端口探测
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new InetSocketAddress(host, port), 3000);
            }

            // 第二步: 通过Docker API获取容器列表和系统信息
            Map<String, Object> containers = queryDockerContainers(apiBase, tls);
            r.putAll(containers);

            // 查询Docker系统信息
            try {
                JSONObject info = httpGetJson(apiBase + "/info", tls);
                r.put("dockerVersion", info.getString("ServerVersion"));
                r.put("storageDriver", info.getString("Driver"));
                r.put("images", info.getIntValue("Images", 0));
            } catch (Exception e) { log.debug("查询Docker info失败: {}", e.getMessage()); }

            r.put("status", "UP");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
            r.put("containers", Collections.emptyMap());
            alert(name, "Docker主机不可达: " + e.getMessage());
        }
        return r;
    }

    /** 通过Docker Engine API查询容器列表 */
    private Map<String, Object> queryDockerContainers(String apiBase, boolean tls) throws Exception {
        Map<String, Object> res = new LinkedHashMap<>();

        int[] stats = {0, 0, 0, 0}; // total, running, exited, unhealthy
        List<Map<String, Object>> containerList = new ArrayList<>();

        try {
            JSONObject resp = httpGetJson(apiBase + "/containers/json?all=true", tls);
            JSONArray items = resp.getJSONArray("items");
            if (items == null) items = JSON.parseArray(resp.toJSONString());

            if (items != null) {
                stats[0] = items.size();
                for (int i = 0; i < items.size(); i++) {
                    JSONObject c = items.getJSONObject(i);
                    String state = c.getString("State");
                    String cname = c.getString("Names");
                    if (cname != null && cname.startsWith("[\"")) {
                        JSONArray names = c.getJSONArray("Names");
                        if (names != null && !names.isEmpty()) cname = names.getString(0);
                    }
                    String image = c.getString("Image");
                    String status = c.getString("Status");

                    if ("running".equals(state)) {
                        stats[1]++;
                        if (status != null && status.contains("unhealthy")) stats[3]++;
                    } else if ("exited".equals(state) || "dead".equals(state)) {
                        stats[2]++;
                    }

                    Map<String, Object> container = new LinkedHashMap<>();
                    container.put("name", cname != null ? cname.replace("/", "") : "unknown");
                    container.put("image", image);
                    container.put("state", state);
                    container.put("status", status);
                    containerList.add(container);
                }
            }
        } catch (Exception e) { log.debug("查询容器列表失败: {}", e.getMessage()); }

        Map<String, Object> containers = new LinkedHashMap<>();
        containers.put("total", stats[0]);
        containers.put("running", stats[1]);
        containers.put("exited", stats[2]);
        containers.put("unhealthy", stats[3]);
        res.put("containers", containers);

        // 最多返回前20个容器详情
        if (containerList.size() > 20) containerList = containerList.subList(0, 20);
        res.put("containerList", containerList);

        return res;
    }

    /** HTTP GET请求Docker API, 返回JSON */
    private JSONObject httpGetJson(String urlStr, boolean tls) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (tls && conn instanceof HttpsURLConnection) {
                HttpsURLConnection hconn = (HttpsURLConnection) conn;
                hconn.setSSLSocketFactory(createTrustAllFactory());
                hconn.setHostnameVerifier((h, s) -> true);
            }

            int code = conn.getResponseCode();
            if (code != 200) throw new RuntimeException("HTTP " + code);

            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return JSON.parseObject(sb.toString());
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private SSLSocketFactory createTrustAllFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String t) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String t) {}
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) { return (SSLSocketFactory) SSLSocketFactory.getDefault(); }
    }

    private void alert(String target, String msg) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("alertLevel", "CRITICAL");
        a.put("targetType", "docker");
        a.put("targetName", target);
        a.put("message", msg);
        a.put("createTime", new Date());
        a.put("acked", false);
        cacheStore.addAlert(a);
    }

    private List<Map<String, Object>> loadTargets() {
        try { return jdbc.queryForList("SELECT * FROM t_docker_host WHERE enabled=TRUE ORDER BY sort_order"); }
        catch (Exception e) { return Collections.emptyList(); }
    }

    public List<Map<String, Object>> getTargetList() { return loadTargets(); }

    public Map<String, Object> addTarget(Map<String, Object> config) {
        jdbc.update("INSERT INTO t_docker_host (name,host,port,tls_enabled,enabled) VALUES (?,?,?,?,?)",
                config.get("name"),
                config.get("host"),
                config.getOrDefault("port", 2375),
                Boolean.TRUE.equals(config.get("tlsEnabled")),
                true);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }

    public Map<String, Object> deleteTarget(Long id) {
        jdbc.update("DELETE FROM t_docker_host WHERE id=?", id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }
}

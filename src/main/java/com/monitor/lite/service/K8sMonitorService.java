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
 * K8s集群监控 — 通过Kubernetes REST API探测集群状态
 * 支持国产化K8s发行版: 华为CCE / 阿里ACK / 腾讯TKE / KubeSphere / 灵雀云 / 道客DaoCloud等
 */
@Service
public class K8sMonitorService {
    private static final Logger log = LoggerFactory.getLogger(K8sMonitorService.class);
    private final CacheStore cacheStore;
    private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public K8sMonitorService(CacheStore cacheStore, JdbcTemplate jdbc) {
        this.cacheStore = cacheStore;
        this.jdbc = jdbc;
    }

    public void checkAll() {
        cacheStore.clearK8sStatus();
        List<Map<String, Object>> targets = loadTargets();
        if (targets.isEmpty()) return;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map<String, Object> t : targets) {
            futures.add(CompletableFuture.runAsync(() -> {
                String name = (String) t.get("name");
                cacheStore.putK8sStatus(name, checkCluster(t));
            }, pool));
        }
        try { CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS); }
        catch (Exception e) { log.warn("K8s集群检查超时", e); }
    }

    /** 探测单个K8s集群 — 先TCP探测API Server端口, 再通过Token查询资源状态 */
    private Map<String, Object> checkCluster(Map<String, Object> config) {
        String name = (String) config.get("name");
        String apiServer = (String) config.get("api_server");
        String token = (String) config.getOrDefault("token", "");
        String clusterType = (String) config.getOrDefault("cluster_type", "Kubernetes");

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("host", apiServer);
        r.put("clusterType", clusterType);

        long start = System.currentTimeMillis();
        try {
            URL url = new URL(apiServer);
            String host = url.getHost();
            int port = url.getPort() > 0 ? url.getPort() : (url.getProtocol().equals("https") ? 443 : 80);

            // 第一步: TCP端口探测
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 3000);
            }

            // 第二步: 如果有Token, 通过K8s API获取资源状态
            if (token != null && !token.trim().isEmpty()) {
                Map<String, Object> resources = queryK8sResources(apiServer, token.trim());
                r.putAll(resources);
            } else {
                // 无Token时仅返回连通性状态
                r.put("nodes", Collections.emptyMap());
                r.put("pods", Collections.emptyMap());
                r.put("deployments", Collections.emptyMap());
            }

            r.put("status", "UP");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
            r.put("nodes", Collections.emptyMap());
            r.put("pods", Collections.emptyMap());
            r.put("deployments", Collections.emptyMap());
            alert(name, "K8s集群不可达: " + e.getMessage());
        }
        return r;
    }

    /** 通过K8s REST API查询Node/Pod/Deployment状态 */
    private Map<String, Object> queryK8sResources(String apiServer, String token) throws Exception {
        Map<String, Object> res = new LinkedHashMap<>();
        String base = apiServer.endsWith("/") ? apiServer.substring(0, apiServer.length() - 1) : apiServer;

        // 查询Nodes: GET /api/v1/nodes
        int[] nodeStats = {0, 0}; // total, ready
        try {
            JSONObject nodesResp = httpGetJson(base + "/api/v1/nodes", token);
            JSONArray items = nodesResp.getJSONArray("items");
            if (items != null) {
                nodeStats[0] = items.size();
                for (int i = 0; i < items.size(); i++) {
                    JSONObject node = items.getJSONObject(i);
                    try {
                        JSONArray conditions = node.getJSONObject("status").getJSONArray("conditions");
                        for (int j = 0; j < conditions.size(); j++) {
                            JSONObject cond = conditions.getJSONObject(j);
                            if ("Ready".equals(cond.getString("type")) && "True".equals(cond.getString("status"))) {
                                nodeStats[1]++;
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) { log.debug("查询Nodes失败: {}", e.getMessage()); }

        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("total", nodeStats[0]);
        nodes.put("ready", nodeStats[1]);
        nodes.put("notReady", nodeStats[0] - nodeStats[1]);
        res.put("nodes", nodes);

        // 查询Pods: GET /api/v1/pods
        int[] podStats = {0, 0, 0, 0}; // total, running, pending, failed
        try {
            JSONObject podsResp = httpGetJson(base + "/api/v1/pods", token);
            JSONArray items = podsResp.getJSONArray("items");
            if (items != null) {
                podStats[0] = items.size();
                for (int i = 0; i < items.size(); i++) {
                    try {
                        String phase = items.getJSONObject(i).getJSONObject("status").getString("phase");
                        if ("Running".equals(phase)) podStats[1]++;
                        else if ("Pending".equals(phase)) podStats[2]++;
                        else if ("Failed".equals(phase)) podStats[3]++;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) { log.debug("查询Pods失败: {}", e.getMessage()); }

        Map<String, Object> pods = new LinkedHashMap<>();
        pods.put("total", podStats[0]);
        pods.put("running", podStats[1]);
        pods.put("pending", podStats[2]);
        pods.put("failed", podStats[3]);
        res.put("pods", pods);

        // 查询Deployments: GET /apis/apps/v1/deployments
        int[] depStats = {0, 0, 0}; // total, available, unavailable
        try {
            JSONObject depsResp = httpGetJson(base + "/apis/apps/v1/deployments", token);
            JSONArray items = depsResp.getJSONArray("items");
            if (items != null) {
                depStats[0] = items.size();
                for (int i = 0; i < items.size(); i++) {
                    try {
                        JSONObject status = items.getJSONObject(i).getJSONObject("status");
                        int available = status.getIntValue("availableReplicas", 0);
                        int replicas = status.getIntValue("replicas", 0);
                        if (available > 0 && available >= replicas) depStats[1]++;
                        else depStats[2]++;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) { log.debug("查询Deployments失败: {}", e.getMessage()); }

        Map<String, Object> deployments = new LinkedHashMap<>();
        deployments.put("total", depStats[0]);
        deployments.put("available", depStats[1]);
        deployments.put("unavailable", depStats[2]);
        res.put("deployments", deployments);

        return res;
    }

    /** HTTP GET请求K8s API, 返回JSON */
    private JSONObject httpGetJson(String urlStr, String token) throws Exception {
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setSSLSocketFactory(createTrustAllFactory());
            conn.setHostnameVerifier((h, s) -> true);

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

    /** 信任所有证书 — K8s API Server通常使用自签名证书 */
    private SSLSocketFactory createTrustAllFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String t) {}
                public void checkServerTrusted(X509Certificate[] certs, String t) {}
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) { return (SSLSocketFactory) SSLSocketFactory.getDefault(); }
    }

    private void alert(String target, String msg) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("alertLevel", "CRITICAL");
        a.put("targetType", "k8s");
        a.put("targetName", target);
        a.put("message", msg);
        a.put("createTime", new Date());
        a.put("acked", false);
        cacheStore.addAlert(a);
    }

    private List<Map<String, Object>> loadTargets() {
        try { return jdbc.queryForList("SELECT * FROM t_k8s_cluster WHERE enabled=TRUE ORDER BY sort_order"); }
        catch (Exception e) { return Collections.emptyList(); }
    }

    public List<Map<String, Object>> getTargetList() { return loadTargets(); }

    public Map<String, Object> addTarget(Map<String, Object> config) {
        jdbc.update("INSERT INTO t_k8s_cluster (name,cluster_type,api_server,token,namespace,enabled) VALUES (?,?,?,?,?,?)",
                config.get("name"),
                config.getOrDefault("clusterType", "Kubernetes"),
                config.get("apiServer"),
                config.getOrDefault("token", ""),
                config.getOrDefault("namespace", "default"),
                true);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }

    public Map<String, Object> deleteTarget(Long id) {
        jdbc.update("DELETE FROM t_k8s_cluster WHERE id=?", id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }
}

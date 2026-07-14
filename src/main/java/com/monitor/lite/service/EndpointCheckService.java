package com.monitor.lite.service;

import com.monitor.lite.config.CacheStore;
import com.monitor.lite.config.MonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 端点探测 — 全部目标从t_endpoint表读取, 前端可增删
 */
@Service
public class EndpointCheckService {
    private static final Logger log = LoggerFactory.getLogger(EndpointCheckService.class);
    private final CacheStore cacheStore;
    private final MonitorProperties props;
    private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newFixedThreadPool(10);
    private final ConcurrentHashMap<String, Integer> failCount = new ConcurrentHashMap<>();

    public EndpointCheckService(CacheStore cacheStore, MonitorProperties props, JdbcTemplate jdbc) {
        this.cacheStore = cacheStore;
        this.props = props;
        this.jdbc = jdbc;
    }

    public Map<String, Object> checkAll() {
        List<Map<String, Object>> endpoints = loadEndpoints();
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> results = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map<String, Object> ep : endpoints) {
            futures.add(CompletableFuture.runAsync(() -> {
                Map<String, Object> r = check((String) ep.get("name"), (String) ep.get("url"),
                        (String) ep.getOrDefault("method", "GET"));
                results.add(r);
            }, pool));
        }

        try { CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS); }
        catch (Exception e) { log.warn("端点检测超时", e); }

        long up = results.stream().filter(r -> "UP".equals(r.get("status"))).count();
        summary.put("total", endpoints.size());
        summary.put("up", up);
        summary.put("down", results.stream().filter(r -> "DOWN".equals(r.get("status"))).count());
        summary.put("timeout", results.stream().filter(r -> "TIMEOUT".equals(r.get("status"))).count());
        summary.put("results", results);

        cacheStore.setEndpointStatus(summary);
        return summary;
    }

    private Map<String, Object> check(String name, String url, String method) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("url", url);
        long start = System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            conn.disconnect();
            r.put("status", code >= 200 && code < 400 ? "UP" : "DOWN");
            r.put("httpCode", code);
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
            if (code < 400) resetFail(name); else incrFail(name, "HTTP " + code);
        } catch (SocketTimeoutException e) {
            r.put("status", "TIMEOUT");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
            incrFail(name, "TIMEOUT");
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
            incrFail(name, e.getMessage());
        }
        return r;
    }

    private void incrFail(String name, String msg) {
        int cnt = failCount.merge(name, 1, Integer::sum);
        if (cnt >= props.getAlert().getEndpointFailCount()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("alertLevel", cnt >= 5 ? "CRITICAL" : "WARNING");
            a.put("targetType", "endpoint");
            a.put("targetName", name);
            a.put("message", "连续" + cnt + "次失败: " + msg);
            a.put("createTime", new Date());
            a.put("acked", false);
            cacheStore.addAlert(a);
        }
    }

    private void resetFail(String name) {
        Integer prev = failCount.remove(name);
        if (prev != null && prev >= props.getAlert().getEndpointFailCount()) cacheStore.clearAlert(name);
    }

    public List<Map<String, Object>> loadEndpoints() {
        try {
            return jdbc.queryForList("SELECT * FROM t_endpoint WHERE enabled=TRUE ORDER BY sort_order");
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getEndpointList() { return loadEndpoints(); }

    public Map<String, Object> addEndpoint(Map<String, Object> ep) {
        jdbc.update("INSERT INTO t_endpoint (name,url,method,enabled) VALUES (?,?,?,?)",
                ep.get("name"), ep.get("url"), ep.getOrDefault("method", "GET"), true);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("message", "端点已添加");
        return r;
    }

    public Map<String, Object> deleteEndpoint(Long id) {
        jdbc.update("DELETE FROM t_endpoint WHERE id=?", id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }
}

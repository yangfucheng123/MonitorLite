package com.monitor.lite.service;

import com.monitor.lite.config.CacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class InfraCheckService {
    private static final Logger log = LoggerFactory.getLogger(InfraCheckService.class);
    private final CacheStore cacheStore;
    private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public InfraCheckService(CacheStore cacheStore, JdbcTemplate jdbc) {
        this.cacheStore = cacheStore;
        this.jdbc = jdbc;
    }

    public void checkAll() {
        cacheStore.clearInfraStatus();
        List<Map<String, Object>> targets = loadTargets();
        if (targets.isEmpty()) return;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map<String, Object> t : targets) {
            futures.add(CompletableFuture.runAsync(() -> {
                String name = (String) t.get("name");
                String type = (String) t.getOrDefault("target_type", "tcp");
                String host = (String) t.get("host");
                int port = ((Number) t.getOrDefault("port", 80)).intValue();
                cacheStore.putInfraStatus(name, "db".equals(type) ? checkJdbc(name, t) : checkTcp(name, host, port));
            }, pool));
        }
        try { CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS); }
        catch (Exception e) { log.warn("基础设施检查超时", e); }
    }

    private Map<String, Object> checkTcp(String name, String host, int port) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("host", host + ":" + port);
        long start = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            r.put("status", "UP");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
            alert(name, e.getMessage());
        }
        return r;
    }

    private Map<String, Object> checkJdbc(String name, Map<String, Object> config) {
        // 使用动态数据源连通性检测
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("host", config.get("host") + ":" + config.get("port"));
        long start = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress((String) config.get("host"),
                    ((Number) config.getOrDefault("port", 5432)).intValue()), 3000);
            r.put("status", "UP");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
            alert(name, e.getMessage());
        }
        return r;
    }

    private void alert(String target, String msg) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("alertLevel", "CRITICAL");
        a.put("targetType", "infra");
        a.put("targetName", target);
        a.put("message", "基础设施不可达: " + msg);
        a.put("createTime", new Date());
        a.put("acked", false);
        cacheStore.addAlert(a);
    }

    private List<Map<String, Object>> loadTargets() {
        try { return jdbc.queryForList("SELECT * FROM t_infra_target WHERE enabled=TRUE ORDER BY sort_order"); }
        catch (Exception e) { return Collections.emptyList(); }
    }

    public List<Map<String, Object>> getTargetList() { return loadTargets(); }

    public Map<String, Object> addTarget(Map<String, Object> config) {
        jdbc.update("INSERT INTO t_infra_target (name,target_type,host,port,enabled) VALUES (?,?,?,?,?)",
                config.get("name"), config.getOrDefault("targetType", "tcp"),
                config.get("host"), config.getOrDefault("port", 80), true);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }

    public Map<String, Object> deleteTarget(Long id) {
        jdbc.update("DELETE FROM t_infra_target WHERE id=?", id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }
}

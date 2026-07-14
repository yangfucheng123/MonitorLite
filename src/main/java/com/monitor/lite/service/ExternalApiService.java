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
public class ExternalApiService {
    private static final Logger log = LoggerFactory.getLogger(ExternalApiService.class);
    private final CacheStore cacheStore;
    private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public ExternalApiService(CacheStore cacheStore, JdbcTemplate jdbc) {
        this.cacheStore = cacheStore;
        this.jdbc = jdbc;
    }

    public void checkAll() {
        cacheStore.clearApiStatus();
        List<Map<String, Object>> apis = loadApis();
        if (apis.isEmpty()) return;

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map<String, Object> api : apis) {
            futures.add(CompletableFuture.runAsync(() -> {
                String name = (String) api.get("name");
                cacheStore.putApiStatus(name, checkTcp(name,
                    (String) api.get("host"), (Integer) api.get("port")));
            }, pool));
        }
        try { CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS); }
        catch (Exception e) { log.warn("外部API检查超时", e); }
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
        } catch (SocketTimeoutException e) {
            r.put("status", "TIMEOUT");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
            alert(name, "连接超时", "WARNING");
        } catch (Exception e) {
            r.put("status", "DOWN");
            r.put("responseTime", (int) (System.currentTimeMillis() - start));
            alert(name, e.getMessage(), "CRITICAL");
        }
        return r;
    }

    private void alert(String target, String msg, String level) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("alertLevel", level);
        a.put("targetType", "extApi");
        a.put("targetName", target);
        a.put("message", "外部API异常: " + msg);
        a.put("createTime", new Date());
        a.put("acked", false);
        cacheStore.addAlert(a);
    }

    private List<Map<String, Object>> loadApis() {
        try { return jdbc.queryForList("SELECT * FROM t_ext_api WHERE enabled=TRUE ORDER BY sort_order"); }
        catch (Exception e) { return Collections.emptyList(); }
    }

    public List<Map<String, Object>> getApiList() { return loadApis(); }

    public Map<String, Object> addApi(Map<String, Object> config) {
        jdbc.update("INSERT INTO t_ext_api (name,host,port,enabled) VALUES (?,?,?,?)",
                config.get("name"), config.get("host"), config.getOrDefault("port", 80), true);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }

    public Map<String, Object> deleteApi(Long id) {
        jdbc.update("DELETE FROM t_ext_api WHERE id=?", id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }
}

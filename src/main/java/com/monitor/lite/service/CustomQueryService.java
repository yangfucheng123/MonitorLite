package com.monitor.lite.service;

import com.monitor.lite.config.CacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 自定义SQL查询 — 用户前端配置SQL→动态执行→面板展示
 */
@Service
public class CustomQueryService {
    private static final Logger log = LoggerFactory.getLogger(CustomQueryService.class);

    private final CacheStore cacheStore;
    private final JdbcTemplate h2Jdbc;
    private final DynamicDataSourceManager dsManager;

    public CustomQueryService(CacheStore cacheStore, JdbcTemplate h2Jdbc, DynamicDataSourceManager dsManager) {
        this.cacheStore = cacheStore;
        this.h2Jdbc = h2Jdbc;
        this.dsManager = dsManager;
    }

    /** 执行所有启用的自定义查询 */
    public Map<String, Object> executeAll() {
        List<Map<String, Object>> queries = loadQueries();
        Map<String, Object> result = new LinkedHashMap<>();

        for (Map<String, Object> q : queries) {
            String name = (String) q.get("name");
            String displayType = (String) q.getOrDefault("display_type", "cards");
            try {
                String dsName = (String) q.getOrDefault("datasource_name", "H2");
                JdbcTemplate target = dsManager.getSource(dsName);
                String sql = (String) q.get("query_sql");
                List<Map<String, Object>> rows = target.queryForList(sql);
                Map<String, Object> queryResult = new LinkedHashMap<>();
                queryResult.put("type", displayType);
                queryResult.put("data", rows);
                queryResult.put("query", q);
                result.put(name, queryResult);
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("type", "error");
                err.put("message", e.getMessage());
                result.put(name, err);
                log.error("自定义查询失败: {}", name, e);
            }
        }

        cacheStore.setCustomQueries(result);
        return result;
    }

    public List<Map<String, Object>> getQueryList() { return loadQueries(); }

    public Map<String, Object> addQuery(Map<String, Object> config) {
        // 安全性: 只允许SELECT语句
        String sql = ((String) config.get("querySql")).trim().toUpperCase();
        if (!sql.startsWith("SELECT")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("error", "只允许SELECT查询");
            return r;
        }

        h2Jdbc.update("INSERT INTO t_custom_query (name,query_type,datasource_name,query_sql,display_type,enabled) VALUES (?,?,?,?,?,?)",
                config.get("name"), config.getOrDefault("queryType", "metric"),
                config.getOrDefault("datasourceName", "H2"), config.get("querySql"),
                config.getOrDefault("displayType", "cards"), true);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("message", "查询已添加");
        return r;
    }

    public Map<String, Object> deleteQuery(Long id) {
        h2Jdbc.update("DELETE FROM t_custom_query WHERE id=?", id);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }

    public Map<String, Object> testQuery(Map<String, Object> config) {
        try {
            String sql = ((String) config.get("querySql")).trim();
            if (!sql.toUpperCase().startsWith("SELECT")) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("success", false);
                r.put("error", "只允许SELECT查询");
                return r;
            }
            String dsName = (String) config.getOrDefault("datasourceName", "H2");
            JdbcTemplate target = dsManager.getSource(dsName);
            List<Map<String, Object>> rows = target.queryForList(sql);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("count", rows.size());
            r.put("preview", rows.size() > 5 ? rows.subList(0, 5) : rows);
            return r;
        } catch (Exception e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("error", e.getMessage());
            return r;
        }
    }

    private List<Map<String, Object>> loadQueries() {
        try {
            return h2Jdbc.queryForList("SELECT * FROM t_custom_query WHERE enabled=TRUE ORDER BY sort_order");
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}

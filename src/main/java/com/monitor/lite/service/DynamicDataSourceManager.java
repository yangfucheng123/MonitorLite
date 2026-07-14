package com.monitor.lite.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态数据源管理 — 用户从前端配置, 运行时创建, 无需重启
 */
@Service
public class DynamicDataSourceManager {
    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceManager.class);

    private final JdbcTemplate h2Jdbc;
    private final ConcurrentHashMap<String, JdbcTemplate> dynamicSources = new ConcurrentHashMap<>();

    public DynamicDataSourceManager(JdbcTemplate h2Jdbc) {
        this.h2Jdbc = h2Jdbc;
        h2Jdbc.setQueryTimeout(10);
        h2Jdbc.setMaxRows(5000);
        loadFromConfig();
    }

    /** 启动时加载所有已配置数据源 */
    private void loadFromConfig() {
        try {
            List<Map<String, Object>> rows = h2Jdbc.queryForList(
                "SELECT * FROM t_datasource WHERE enabled=TRUE");
            for (Map<String, Object> row : rows) {
                String name = (String) row.get("name");
                try {
                    JdbcTemplate tpl = createJdbcTemplate(row);
                    dynamicSources.put(name, tpl);
                    log.info("数据源加载成功: {}", name);
                } catch (Exception e) {
                    log.warn("数据源加载失败: {} — {}", name, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("t_datasource表暂未创建, 将使用H2作为默认数据源");
        }
    }

    public JdbcTemplate getSource(String name) {
        if (name == null || "H2".equals(name) || "default".equals(name)) {
            return h2Jdbc;
        }
        return dynamicSources.getOrDefault(name, h2Jdbc);
    }

    public List<Map<String, Object>> listSources() {
        try {
            List<Map<String, Object>> sources = h2Jdbc.queryForList(
                "SELECT id, name, driver_class, jdbc_url, username, max_pool_size, enabled FROM t_datasource ORDER BY id");
            for (Map<String, Object> s : sources) {
                s.put("connected", dynamicSources.containsKey((String) s.get("name")));
            }
            return sources;
        } catch (Exception e) {
            Map<String, Object> h2 = new LinkedHashMap<>();
            h2.put("name", "H2");
            h2.put("driver_class", "org.h2.Driver");
            h2.put("jdbc_url", "内嵌数据库");
            h2.put("connected", true);
            return Collections.singletonList(h2);
        }
    }

    public Map<String, Object> addSource(Map<String, Object> config) {
        String name = (String) config.get("name");
        try {
            // 先创建连接测试
            JdbcTemplate tpl = createJdbcTemplate(config);
            tpl.queryForObject("SELECT 1", Integer.class);

            // 测试通过, 持久化
            h2Jdbc.update(
                "MERGE INTO t_datasource (name, driver_class, jdbc_url, username, password, max_pool_size) KEY(name) VALUES (?,?,?,?,?,?)",
                name, config.get("driverClass"), config.get("jdbcUrl"),
                config.get("username"), config.get("password"),
                config.getOrDefault("maxPoolSize", 2));

            dynamicSources.put(name, tpl);
            log.info("数据源添加成功: {}", name);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "连接成功: " + name);
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", "连接失败: " + e.getMessage());
            return result;
        }
    }

    public Map<String, Object> removeSource(String name) {
        h2Jdbc.update("DELETE FROM t_datasource WHERE name=?", name);
        dynamicSources.remove(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    private JdbcTemplate createJdbcTemplate(Map<String, Object> config) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName((String) config.get("driverClass"));
        ds.setUrl((String) config.get("jdbcUrl"));
        ds.setUsername((String) config.get("username"));
        ds.setPassword((String) config.get("password"));

        JdbcTemplate tpl = new JdbcTemplate(ds);
        tpl.setQueryTimeout(10);
        tpl.setMaxRows(5000);
        return tpl;
    }

    public List<String> getSourceNames() {
        List<String> names = new ArrayList<>(dynamicSources.keySet());
        names.add(0, "H2");
        return names;
    }
}

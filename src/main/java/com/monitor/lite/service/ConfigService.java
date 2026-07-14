package com.monitor.lite.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConfigService {
    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public ConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        reload();
    }

    public void reload() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT config_key, config_value FROM t_config");
            for (Map<String, Object> row : rows) {
                String key = (String) row.get("config_key");
                String val = (String) row.get("config_value");
                if (key != null) cache.put(key, val != null ? val : "");
            }
        } catch (Exception e) {
            log.debug("t_config表暂未创建");
        }
    }

    public String get(String key, String def) {
        String v = cache.get(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    public Map<String, Object> save(String key, String value) {
        try {
            int u = jdbc.update("UPDATE t_config SET config_value=?, update_time=CURRENT_TIMESTAMP WHERE config_key=?", value, key);
            if (u == 0) jdbc.update("INSERT INTO t_config (config_key,config_value,config_group) VALUES (?,?,'general')", key, value);
            cache.put(key, value != null ? value : "");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            return r;
        } catch (Exception e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("error", e.getMessage());
            return r;
        }
    }

    public List<Map<String, Object>> listByGroup(String group) {
        try {
            String sql = "SELECT id,config_key,config_value,config_label,config_group FROM t_config";
            if (group != null && !group.isEmpty()) sql += " WHERE config_group=?";
            sql += " ORDER BY config_group,sort_order";
            if (group != null && !group.isEmpty()) return jdbc.queryForList(sql, group);
            return jdbc.queryForList(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}

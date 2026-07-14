package com.monitor.lite.config;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prometheus风格内存缓存 — 采集器写入, REST读取, 零阻塞
 */
@Component
public class CacheStore {

    private volatile Map<String, Object> systemInfo = new ConcurrentHashMap<>();
    private volatile Map<String, Object> endpointStatus = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> apiStatus = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> infraStatus = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> k8sStatus = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> dockerStatus = new ConcurrentHashMap<>();
    private volatile Map<String, Object> customQueries = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> alerts = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Long> lastUpdate = new ConcurrentHashMap<>();

    public CacheStore() {
        String[] categories = {"system","endpoint","api","infra","k8s","docker","custom"};
        for (String c : categories) lastUpdate.put(c, 0L);
    }

    public Map<String, Object> getSystemInfo() { return systemInfo; }
    public void setSystemInfo(Map<String, Object> info) { this.systemInfo = info; mark("system"); }

    public Map<String, Object> getEndpointStatus() { return endpointStatus; }
    public void setEndpointStatus(Map<String, Object> s) { this.endpointStatus = s; mark("endpoint"); }

    public Map<String, Map<String, Object>> getApiStatus() { return apiStatus; }
    public void putApiStatus(String name, Map<String, Object> s) { apiStatus.put(name, s); mark("api"); }
    public void clearApiStatus() { apiStatus.clear(); }

    public Map<String, Map<String, Object>> getInfraStatus() { return infraStatus; }
    public void putInfraStatus(String name, Map<String, Object> s) { infraStatus.put(name, s); mark("infra"); }
    public void clearInfraStatus() { infraStatus.clear(); }

    public Map<String, Map<String, Object>> getK8sStatus() { return k8sStatus; }
    public void putK8sStatus(String name, Map<String, Object> s) { k8sStatus.put(name, s); mark("k8s"); }
    public void clearK8sStatus() { k8sStatus.clear(); }

    public Map<String, Map<String, Object>> getDockerStatus() { return dockerStatus; }
    public void putDockerStatus(String name, Map<String, Object> s) { dockerStatus.put(name, s); mark("docker"); }
    public void clearDockerStatus() { dockerStatus.clear(); }

    public Map<String, Object> getCustomQueries() { return customQueries; }
    public void setCustomQueries(Map<String, Object> q) { this.customQueries = q; mark("custom"); }

    public List<Map<String, Object>> getAlerts() { synchronized (alerts) { return new ArrayList<>(alerts); } }
    public void addAlert(Map<String, Object> alert) {
        synchronized (alerts) {
            alerts.add(0, alert);
            if (alerts.size() > 100) alerts.remove(alerts.size() - 1);
        }
    }
    public void clearAlert(String targetName) {
        synchronized (alerts) { alerts.removeIf(a -> targetName.equals(a.get("targetName"))); }
    }

    public long getLastUpdate(String c) { return lastUpdate.getOrDefault(c, 0L); }
    private void mark(String c) { lastUpdate.put(c, System.currentTimeMillis()); }

    public Map<String, Object> snapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("system", systemInfo);
        snap.put("endpoints", endpointStatus);
        snap.put("apis", new HashMap<>(apiStatus));
        snap.put("infra", new HashMap<>(infraStatus));
        snap.put("k8s", new HashMap<>(k8sStatus));
        snap.put("docker", new HashMap<>(dockerStatus));
        snap.put("custom", customQueries);
        snap.put("alerts", new ArrayList<>(alerts));
        snap.put("lastUpdate", new HashMap<>(lastUpdate));
        snap.put("serverTime", System.currentTimeMillis());
        return snap;
    }
}

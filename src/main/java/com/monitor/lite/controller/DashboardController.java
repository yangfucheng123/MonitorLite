package com.monitor.lite.controller;

import com.monitor.lite.config.CacheStore;
import com.monitor.lite.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DashboardController {

    private final CacheStore cacheStore;
    private final SystemMonitorService sysSvc;
    private final EndpointCheckService epSvc;
    private final ExternalApiService extSvc;
    private final InfraCheckService infraSvc;
    private final K8sMonitorService k8sSvc;
    private final DockerMonitorService dockerSvc;
    private final CustomQueryService customSvc;
    private final ScriptEngineService scriptSvc;
    private final ConfigService configSvc;
    private final DynamicDataSourceManager dsManager;
    private final DemoService demoSvc;

    public DashboardController(CacheStore cacheStore, SystemMonitorService sysSvc,
            EndpointCheckService epSvc, ExternalApiService extSvc, InfraCheckService infraSvc,
            K8sMonitorService k8sSvc, DockerMonitorService dockerSvc,
            CustomQueryService customSvc, ScriptEngineService scriptSvc,
            ConfigService configSvc, DynamicDataSourceManager dsManager, DemoService demoSvc) {
        this.cacheStore = cacheStore;
        this.sysSvc = sysSvc;
        this.epSvc = epSvc;
        this.extSvc = extSvc;
        this.infraSvc = infraSvc;
        this.k8sSvc = k8sSvc;
        this.dockerSvc = dockerSvc;
        this.customSvc = customSvc;
        this.scriptSvc = scriptSvc;
        this.configSvc = configSvc;
        this.dsManager = dsManager;
        this.demoSvc = demoSvc;
    }

    // ============ 全量快照 ============
    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        return cacheStore.snapshot();
    }

    // ============ 立即检测 ============
    @PostMapping("/check/now")
    public Map<String, Object> checkNow() {
        Map<String, Object> r = new LinkedHashMap<>();
        try { r.put("system", sysSvc.collect()); } catch (Exception e) { r.put("system", e.getMessage()); }
        try { r.put("endpoint", epSvc.checkAll()); } catch (Exception e) { r.put("endpoint", e.getMessage()); }
        try { extSvc.checkAll(); r.put("api", "ok"); } catch (Exception e) { r.put("api", e.getMessage()); }
        try { infraSvc.checkAll(); r.put("infra", "ok"); } catch (Exception e) { r.put("infra", e.getMessage()); }
        try { k8sSvc.checkAll(); r.put("k8s", "ok"); } catch (Exception e) { r.put("k8s", e.getMessage()); }
        try { dockerSvc.checkAll(); r.put("docker", "ok"); } catch (Exception e) { r.put("docker", e.getMessage()); }
        try { r.put("custom", customSvc.executeAll()); } catch (Exception e) { r.put("custom", e.getMessage()); }
        r.put("time", System.currentTimeMillis());
        return r;
    }

    @GetMapping("/demo")
    public Map<String, Object> generateDemo() {
        demoSvc.generateDemoData();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("message", "演示数据已生成, 刷新查看");
        return r;
    }

    // ============ 端点管理 ============
    @GetMapping("/endpoints")
    public List<Map<String, Object>> getEndpoints() { return epSvc.getEndpointList(); }
    @PostMapping("/endpoints")
    public Map<String, Object> addEndpoint(@RequestBody Map<String, Object> ep) { return epSvc.addEndpoint(ep); }
    @DeleteMapping("/endpoints/{id}")
    public Map<String, Object> deleteEndpoint(@PathVariable Long id) { return epSvc.deleteEndpoint(id); }

    // ============ 外部API管理 ============
    @GetMapping("/ext-apis")
    public List<Map<String, Object>> getExtApis() { return extSvc.getApiList(); }
    @PostMapping("/ext-apis")
    public Map<String, Object> addExtApi(@RequestBody Map<String, Object> config) { return extSvc.addApi(config); }
    @DeleteMapping("/ext-apis/{id}")
    public Map<String, Object> deleteExtApi(@PathVariable Long id) { return extSvc.deleteApi(id); }

    // ============ 基础设施管理 ============
    @GetMapping("/infra-targets")
    public List<Map<String, Object>> getInfraTargets() { return infraSvc.getTargetList(); }
    @PostMapping("/infra-targets")
    public Map<String, Object> addInfraTarget(@RequestBody Map<String, Object> config) { return infraSvc.addTarget(config); }
    @DeleteMapping("/infra-targets/{id}")
    public Map<String, Object> deleteInfraTarget(@PathVariable Long id) { return infraSvc.deleteTarget(id); }

    // ============ K8s集群管理 ============
    @GetMapping("/k8s-targets")
    public List<Map<String, Object>> getK8sTargets() { return k8sSvc.getTargetList(); }
    @PostMapping("/k8s-targets")
    public Map<String, Object> addK8sTarget(@RequestBody Map<String, Object> config) { return k8sSvc.addTarget(config); }
    @DeleteMapping("/k8s-targets/{id}")
    public Map<String, Object> deleteK8sTarget(@PathVariable Long id) { return k8sSvc.deleteTarget(id); }

    // ============ Docker主机管理 ============
    @GetMapping("/docker-targets")
    public List<Map<String, Object>> getDockerTargets() { return dockerSvc.getTargetList(); }
    @PostMapping("/docker-targets")
    public Map<String, Object> addDockerTarget(@RequestBody Map<String, Object> config) { return dockerSvc.addTarget(config); }
    @DeleteMapping("/docker-targets/{id}")
    public Map<String, Object> deleteDockerTarget(@PathVariable Long id) { return dockerSvc.deleteTarget(id); }

    // ============ 自定义查询管理 ============
    @GetMapping("/custom-queries")
    public List<Map<String, Object>> getCustomQueries() { return customSvc.getQueryList(); }
    @PostMapping("/custom-queries")
    public Map<String, Object> addCustomQuery(@RequestBody Map<String, Object> config) { return customSvc.addQuery(config); }
    @DeleteMapping("/custom-queries/{id}")
    public Map<String, Object> deleteCustomQuery(@PathVariable Long id) { return customSvc.deleteQuery(id); }
    @PostMapping("/custom-queries/test")
    public Map<String, Object> testQuery(@RequestBody Map<String, Object> config) { return customSvc.testQuery(config); }

    // ============ 数据源管理 ============
    @GetMapping("/datasources")
    public List<Map<String, Object>> listDatasources() { return dsManager.listSources(); }
    @PostMapping("/datasources")
    public Map<String, Object> addDatasource(@RequestBody Map<String, Object> config) { return dsManager.addSource(config); }
    @DeleteMapping("/datasources/{name}")
    public Map<String, Object> deleteDatasource(@PathVariable String name) { return dsManager.removeSource(name); }

    // ============ 配置管理 ============
    @GetMapping("/config")
    public List<Map<String, Object>> getConfig(@RequestParam(required = false) String group) {
        return configSvc.listByGroup(group);
    }
    @PostMapping("/config")
    public Map<String, Object> saveConfig(@RequestParam String key, @RequestParam String value) {
        return configSvc.save(key, value);
    }

    // ============ 脚本引擎 ============
    @GetMapping("/scripts") public List<Map<String, Object>> listScripts() { return scriptSvc.listScripts(); }
    @PostMapping("/scripts/run") public Map<String, Object> runScript(@RequestParam String name,
            @RequestParam(required = false, defaultValue = "") String params) { return scriptSvc.execute(name, params); }
    @GetMapping("/scripts/output") public Map<String, Object> scriptOutput(@RequestParam String runId) { return scriptSvc.getOutput(runId); }
    @PostMapping("/scripts/kill") public Map<String, Object> killScript(@RequestParam String runId) { return scriptSvc.kill(runId); }
    @GetMapping("/scripts/history") public List<Map<String, Object>> scriptHistory() { return scriptSvc.getHistory(); }

    // ============ 告警 ============
    @GetMapping("/alerts") public List<Map<String, Object>> getAlerts() { return cacheStore.getAlerts(); }
    @PostMapping("/alerts/ack") public Map<String, Object> ackAlert(@RequestParam String targetName) {
        cacheStore.clearAlert(targetName);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }
}

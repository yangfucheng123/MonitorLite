package com.monitor.lite.scheduler;

import com.monitor.lite.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class MonitorScheduler {
    private static final Logger log = LoggerFactory.getLogger(MonitorScheduler.class);
    private final SystemMonitorService sysSvc;
    private final EndpointCheckService epSvc;
    private final ExternalApiService extSvc;
    private final InfraCheckService infraSvc;
    private final K8sMonitorService k8sSvc;
    private final DockerMonitorService dockerSvc;
    private final CustomQueryService customSvc;
    private final DemoService demoSvc;

    public MonitorScheduler(SystemMonitorService sysSvc, EndpointCheckService epSvc,
            ExternalApiService extSvc, InfraCheckService infraSvc,
            K8sMonitorService k8sSvc, DockerMonitorService dockerSvc,
            CustomQueryService customSvc, DemoService demoSvc) {
        this.sysSvc = sysSvc;
        this.epSvc = epSvc;
        this.extSvc = extSvc;
        this.infraSvc = infraSvc;
        this.k8sSvc = k8sSvc;
        this.dockerSvc = dockerSvc;
        this.customSvc = customSvc;
        this.demoSvc = demoSvc;
    }

    @Scheduled(fixedDelayString = "${monitor.interval.system}000")
    public void collectSystem() { CompletableFuture.runAsync(() -> { try { sysSvc.collect(); } catch (Exception e) {} }); }

    @Scheduled(fixedDelayString = "${monitor.interval.endpoint}000")
    public void checkEndpoints() { CompletableFuture.runAsync(() -> { try { epSvc.checkAll(); } catch (Exception e) {} }); }

    @Scheduled(fixedDelayString = "${monitor.interval.infra}000")
    public void checkInfra() { CompletableFuture.runAsync(() -> { try { infraSvc.checkAll(); } catch (Exception e) {} }); }

    @Scheduled(fixedDelayString = "${monitor.interval.k8s}000")
    public void checkK8s() { CompletableFuture.runAsync(() -> { try { k8sSvc.checkAll(); } catch (Exception e) {} }); }

    @Scheduled(fixedDelayString = "${monitor.interval.docker}000")
    public void checkDocker() { CompletableFuture.runAsync(() -> { try { dockerSvc.checkAll(); } catch (Exception e) {} }); }

    @Scheduled(fixedDelayString = "${monitor.interval.ext-api}000")
    public void checkExtApi() { CompletableFuture.runAsync(() -> { try { extSvc.checkAll(); } catch (Exception e) {} }); }

    @Scheduled(fixedDelayString = "${monitor.interval.custom}000")
    public void executeCustomQueries() { CompletableFuture.runAsync(() -> { try { customSvc.executeAll(); } catch (Exception e) {} }); }

    /** 启动3秒后首轮全量检测, 若无配置则生成演示数据 */
    @Scheduled(initialDelay = 3000, fixedDelay = Long.MAX_VALUE)
    public void initCheck() {
        log.info("MonitorLite 通用版启动, 开始首轮检测...");
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> { try { sysSvc.collect(); } catch (Exception e) {} });
        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> { try { epSvc.checkAll(); } catch (Exception e) {} });
        CompletableFuture<Void> f3 = CompletableFuture.runAsync(() -> { try { extSvc.checkAll(); } catch (Exception e) {} });
        CompletableFuture<Void> f4 = CompletableFuture.runAsync(() -> { try { infraSvc.checkAll(); } catch (Exception e) {} });
        CompletableFuture<Void> f5 = CompletableFuture.runAsync(() -> { try { k8sSvc.checkAll(); } catch (Exception e) {} });
        CompletableFuture<Void> f6 = CompletableFuture.runAsync(() -> { try { dockerSvc.checkAll(); } catch (Exception e) {} });
        CompletableFuture<Void> f7 = CompletableFuture.runAsync(() -> { try { customSvc.executeAll(); } catch (Exception e) {} });
        try {
            CompletableFuture.allOf(f1, f2, f3, f4, f5, f6, f7).get(30, TimeUnit.SECONDS);
            log.info("首轮检测完成");
        } catch (Exception e) {
            log.warn("首轮检测超时, 生成演示数据", e);
        }
        // 无实际目标时自动生成演示数据
        if (epSvc.loadEndpoints().isEmpty()) {
            log.info("未检测到任何配置目标, 启用演示模式");
            demoSvc.generateDemoData();
        }
    }
}

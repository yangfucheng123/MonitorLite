package com.monitor.lite.service;

import com.monitor.lite.config.CacheStore;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.util.*;

/**
 * 演示模式 — 当无任何目标配置时自动启用, 生成模拟数据展示全部面板效果
 * 比赛评委开箱即看, 无需搭建环境
 */
@Service
public class DemoService {
    private final CacheStore cacheStore;
    private final Random rnd = new Random();
    private final DecimalFormat df = new DecimalFormat("#.0");

    public DemoService(CacheStore cacheStore) { this.cacheStore = cacheStore; }

    public void generateDemoData() {
        // 系统资源
        Map<String, Object> sys = new LinkedHashMap<>();
        double cpu = 25 + rnd.nextDouble() * 30;
        double mem = 45 + rnd.nextDouble() * 20;
        double disk = 55 + rnd.nextDouble() * 15;
        sys.put("cpuUsage", Double.parseDouble(df.format(cpu)));
        sys.put("cpuLoad", Double.parseDouble(df.format(1.0 + rnd.nextDouble() * 2)));
        sys.put("cpuCores", 8);
        sys.put("memTotal", 8589934592L);
        sys.put("memUsed", (long) (8589934592L * mem / 100));
        sys.put("memPercent", Double.parseDouble(df.format(mem)));
        sys.put("jvmUsed", 80L * 1024 * 1024);
        sys.put("jvmTotal", 256L * 1024 * 1024);
        sys.put("jvmMax", 256L * 1024 * 1024);
        sys.put("diskTotal", 100L * 1024 * 1024 * 1024);
        sys.put("diskUsed", (long) (100L * 1024 * 1024 * 1024 * disk / 100));
        sys.put("diskPercent", Double.parseDouble(df.format(disk)));
        sys.put("uptime", System.currentTimeMillis() / 1000);
        sys.put("time", System.currentTimeMillis());
        cacheStore.setSystemInfo(sys);

        // 端点 (演示)
        String[][] demoEps = {{"首页","http://localhost:8080/","UP"},
                {"健康检查","http://localhost:8080/health","UP"},
                {"API文档","http://localhost:8080/docs","UP"},
                {"登录页","http://localhost:8080/login","UP"},
                {"样例服务","http://localhost:9090/demo","TIMEOUT"}};
        Map<String, Object> epSummary = new LinkedHashMap<>();
        List<Map<String, Object>> epResults = new ArrayList<>();
        for (String[] d : demoEps) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", d[0]); r.put("url", d[1]); r.put("status", d[2]);
            r.put("responseTime", 30 + rnd.nextInt(200));
            if ("UP".equals(d[2])) r.put("httpCode", 200);
            epResults.add(r);
        }
        epSummary.put("total", demoEps.length);
        epSummary.put("up", epResults.stream().filter(e -> "UP".equals(e.get("status"))).count());
        epSummary.put("down", 0);
        epSummary.put("timeout", 1);
        epSummary.put("results", epResults);
        cacheStore.setEndpointStatus(epSummary);

        // 外部API
        cacheStore.clearApiStatus();
        String[][] demoApis = {{"API网关","192.168.1.100:8080","UP"},
                {"认证服务","192.168.1.101:8081","UP"},
                {"消息队列","192.168.1.102:5672","DOWN"},
                {"缓存服务","192.168.1.103:6379","UP"}};
        for (String[] d : demoApis) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("name", d[0]); a.put("host", d[1]);
            a.put("status", d[2]); a.put("responseTime", 5 + rnd.nextInt(50));
            cacheStore.putApiStatus(d[0], a);
        }

        // 基础设施
        cacheStore.clearInfraStatus();
        String[][] demoInfra = {{"PostgreSQL","192.168.1.200:5432","UP"},
                {"Redis","192.168.1.201:6379","UP"},
                {"Nginx","192.168.1.202:80","UP"},
                {"SFTP","192.168.1.203:22","DOWN"}};
        for (String[] d : demoInfra) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", d[0]); f.put("host", d[1]);
            f.put("status", d[2]); f.put("responseTime", 2 + rnd.nextInt(30));
            cacheStore.putInfraStatus(d[0], f);
        }

        // K8s集群 (信创演示)
        cacheStore.clearK8sStatus();
        String[][] demoK8s = {{"华为CCE集群","https://192.168.1.220:6443","UP","华为CCE"},
                {"KubeSphere集群","https://192.168.1.221:6443","UP","KubeSphere"},
                {"阿里ACK集群","https://192.168.1.222:6443","DOWN","阿里ACK"}};
        for (String[] d : demoK8s) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("name", d[0]); k.put("host", d[1]); k.put("status", d[2]); k.put("clusterType", d[3]);
            k.put("responseTime", 10 + rnd.nextInt(80));
            Map<String, Object> nodes = new LinkedHashMap<>();
            nodes.put("total", 3 + rnd.nextInt(5)); nodes.put("ready", 2 + rnd.nextInt(4)); nodes.put("notReady", rnd.nextInt(2));
            k.put("nodes", nodes);
            Map<String, Object> pods = new LinkedHashMap<>();
            pods.put("total", 40 + rnd.nextInt(60)); pods.put("running", 35 + rnd.nextInt(50)); pods.put("pending", rnd.nextInt(5)); pods.put("failed", rnd.nextInt(3));
            k.put("pods", pods);
            Map<String, Object> deployments = new LinkedHashMap<>();
            deployments.put("total", 8 + rnd.nextInt(12)); deployments.put("available", 7 + rnd.nextInt(10)); deployments.put("unavailable", rnd.nextInt(2));
            k.put("deployments", deployments);
            cacheStore.putK8sStatus(d[0], k);
        }

        // Docker主机 (信创演示)
        cacheStore.clearDockerStatus();
        String[][] demoDocker = {{"麒麟Docker节点","192.168.1.230:2375","UP"},
                {"统信UOS节点","192.168.1.231:2375","UP"},
                {"海光容器节点","192.168.1.232:2376","UP"}};
        for (String[] d : demoDocker) {
            Map<String, Object> dk = new LinkedHashMap<>();
            dk.put("name", d[0]); dk.put("host", d[1]); dk.put("status", d[2]);
            dk.put("responseTime", 5 + rnd.nextInt(40));
            dk.put("dockerVersion", "24.0." + rnd.nextInt(9));
            dk.put("images", 10 + rnd.nextInt(30));
            Map<String, Object> containers = new LinkedHashMap<>();
            containers.put("total", 8 + rnd.nextInt(20)); containers.put("running", 6 + rnd.nextInt(15));
            containers.put("exited", rnd.nextInt(5)); containers.put("unhealthy", rnd.nextInt(2));
            dk.put("containers", containers);
            cacheStore.putDockerStatus(d[0], dk);
        }

        // 自定义查询 (演示)
        Map<String, Object> custom = new LinkedHashMap<>();
        Map<String, Object> q1 = new LinkedHashMap<>();
        q1.put("type", "cards");
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String label : Arrays.asList("今日请求量","平均响应","错误率","活跃连接")) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("label", label);
            card.put("value", String.valueOf(1000 + rnd.nextInt(9000)));
            cards.add(card);
        }
        q1.put("data", cards);
        custom.put("系统概览", q1);

        Map<String, Object> q2 = new LinkedHashMap<>();
        q2.put("type", "list");
        List<Map<String, Object>> items = new ArrayList<>();
        String[] labels = {"订单服务","用户服务","支付服务","通知服务"};
        for (String s : labels) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", s);
            item.put("value", 80 + rnd.nextInt(20) + "%");
            items.add(item);
        }
        q2.put("data", items);
        custom.put("服务健康度", q2);
        cacheStore.setCustomQueries(custom);
    }
}

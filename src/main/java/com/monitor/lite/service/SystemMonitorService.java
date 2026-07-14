package com.monitor.lite.service;

import com.monitor.lite.config.CacheStore;
import com.monitor.lite.config.MonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.lang.management.*;
import java.text.DecimalFormat;
import java.util.*;

@Service
public class SystemMonitorService {
    private static final Logger log = LoggerFactory.getLogger(SystemMonitorService.class);
    private final CacheStore cacheStore;
    private final MonitorProperties props;
    private final DecimalFormat df = new DecimalFormat("#.0");

    public SystemMonitorService(CacheStore cacheStore, MonitorProperties props) {
        this.cacheStore = cacheStore;
        this.props = props;
    }

    public Map<String, Object> collect() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            info.put("cpuUsage", Double.parseDouble(df.format(readCpu())));
            info.put("cpuLoad", Double.parseDouble(df.format(os.getSystemLoadAverage())));
            info.put("cpuCores", Runtime.getRuntime().availableProcessors());

            long memTotal = readKb("/proc/meminfo", "MemTotal") * 1024;
            long memAvail = readKb("/proc/meminfo", "MemAvailable") * 1024;
            info.put("memTotal", memTotal);
            info.put("memUsed", memTotal - memAvail);
            info.put("memPercent", memTotal > 0 ? Double.parseDouble(df.format((memTotal - memAvail) * 100.0 / memTotal)) : 0);

            Runtime rt = Runtime.getRuntime();
            info.put("jvmUsed", rt.totalMemory() - rt.freeMemory());
            info.put("jvmTotal", rt.totalMemory());
            info.put("jvmMax", rt.maxMemory());

            File root = new File(".");
            long diskTotal = root.getTotalSpace(), diskFree = root.getFreeSpace();
            info.put("diskPercent", diskTotal > 0 ? Double.parseDouble(df.format((diskTotal - diskFree) * 100.0 / diskTotal)) : 0);
            info.put("diskTotal", diskTotal);
            info.put("diskUsed", diskTotal - diskFree);

            info.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
            info.put("time", System.currentTimeMillis());
            cacheStore.setSystemInfo(info);
            checkAlerts(info);
        } catch (Exception e) {
            log.error("系统采集失败", e);
        }
        return info;
    }

    private double readCpu() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu ")) return 0;
            String[] p = line.trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
            long idle = Long.parseLong(p[4]);
            return total > 0 ? (total - idle) * 100.0 / total : 0;
        } catch (Exception e) { return 0; }
    }

    private long readKb(String file, String key) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(key)) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (Exception e) {}
        return 0;
    }

    private void checkAlerts(Map<String, Object> info) {
        double cpu = (double) info.getOrDefault("cpuUsage", 0.0);
        double mem = (double) info.getOrDefault("memPercent", 0.0);
        double disk = (double) info.getOrDefault("diskPercent", 0.0);

        if (cpu > props.getAlert().getCpuThreshold())
            alert("WARNING", "server", "CPU使用率过高: " + df.format(cpu) + "%");
        if (mem > props.getAlert().getMemThreshold())
            alert("WARNING", "server", "内存使用率过高: " + df.format(mem) + "%");
        if (disk > props.getAlert().getDiskThreshold())
            alert("WARNING", "server", "磁盘使用率过高: " + df.format(disk) + "%");
    }

    private void alert(String level, String type, String msg) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("alertLevel", level);
        a.put("targetType", type);
        a.put("targetName", "server");
        a.put("message", msg);
        a.put("createTime", new Date());
        a.put("acked", false);
        cacheStore.addAlert(a);
    }
}

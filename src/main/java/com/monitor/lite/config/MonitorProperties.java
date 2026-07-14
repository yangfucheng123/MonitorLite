package com.monitor.lite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "monitor")
public class MonitorProperties {

    private final Alert alert = new Alert();
    private final Clean clean = new Clean();
    private final Interval interval = new Interval();
    private String scriptDir = "./scripts";

    public String getScriptDir() { return scriptDir; }
    public void setScriptDir(String d) { this.scriptDir = d; }
    public Alert getAlert() { return alert; }
    public Clean getClean() { return clean; }
    public Interval getInterval() { return interval; }

    public static class Interval {
        private int system = 30, endpoint = 300, infra = 60, extApi = 120, custom = 300;
        public int getSystem() { return system; } public void setSystem(int v) { this.system = v; }
        public int getEndpoint() { return endpoint; } public void setEndpoint(int v) { this.endpoint = v; }
        public int getInfra() { return infra; } public void setInfra(int v) { this.infra = v; }
        public int getExtApi() { return extApi; } public void setExtApi(int v) { this.extApi = v; }
        public int getCustom() { return custom; } public void setCustom(int v) { this.custom = v; }
    }

    public static class Alert {
        private int cpuThreshold = 85, memThreshold = 90, diskThreshold = 85, endpointFailCount = 3;
        public int getCpuThreshold() { return cpuThreshold; } public void setCpuThreshold(int v) { this.cpuThreshold = v; }
        public int getMemThreshold() { return memThreshold; } public void setMemThreshold(int v) { this.memThreshold = v; }
        public int getDiskThreshold() { return diskThreshold; } public void setDiskThreshold(int v) { this.diskThreshold = v; }
        public int getEndpointFailCount() { return endpointFailCount; } public void setEndpointFailCount(int v) { this.endpointFailCount = v; }
    }

    public static class Clean {
        private int keepDays = 30;
        public int getKeepDays() { return keepDays; } public void setKeepDays(int v) { this.keepDays = v; }
    }
}

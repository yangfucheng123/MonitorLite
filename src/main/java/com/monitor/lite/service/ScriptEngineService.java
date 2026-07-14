package com.monitor.lite.service;

import com.monitor.lite.config.MonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class ScriptEngineService {
    private static final Logger log = LoggerFactory.getLogger(ScriptEngineService.class);
    private final MonitorProperties props;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ConcurrentHashMap<String, Process> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StringBuilder> outputs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> statuses = new ConcurrentHashMap<>();

    public ScriptEngineService(MonitorProperties props) { this.props = props; }

    public List<Map<String, Object>> listScripts() {
        List<Map<String, Object>> scripts = new ArrayList<>();
        File dir = new File(props.getScriptDir());
        if (!dir.exists()) return scripts;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".sh") || n.endsWith(".bat") || n.endsWith(".py"));
        if (files == null) return scripts;
        for (File f : files) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", f.getName());
            info.put("path", f.getAbsolutePath());
            info.put("size", f.length());
            info.put("modified", new Date(f.lastModified()));
            scripts.add(info);
        }
        scripts.sort((a, b) -> ((String) a.get("name")).compareTo((String) b.get("name")));
        return scripts;
    }

    public Map<String, Object> execute(String scriptName, String params) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        StringBuilder buf = new StringBuilder();
        outputs.put(runId, buf);

        Map<String, Object> st = new LinkedHashMap<>();
        st.put("status", "LAUNCHING");
        st.put("scriptName", scriptName);
        st.put("startTime", new Date());
        statuses.put(runId, st);

        pool.submit(() -> {
            try {
                List<String> cmd = new ArrayList<>();
                boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
                cmd.add(isWin ? "cmd.exe" : "/bin/bash");
                cmd.add(isWin ? "/c" : new File(props.getScriptDir(), scriptName).getAbsolutePath());
                if (!isWin) cmd.set(0, "/bin/bash");
                if (params != null && !params.isEmpty()) {
                    for (String p : params.split("\\s+")) if (!p.isEmpty()) cmd.add(p);
                }
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(new File(props.getScriptDir()));
                pb.redirectErrorStream(true);
                st.put("status", "RUNNING");
                Process proc = pb.start();
                tasks.put(runId, proc);
                try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        buf.append(line).append("\n");
                        if (buf.length() > 512 * 1024) { buf.append("...[截断]\n"); break; }
                    }
                }
                int exit = proc.waitFor();
                st.put("status", exit == 0 ? "SUCCESS" : "FAILED");
                st.put("exitCode", exit);
            } catch (Exception e) {
                buf.append("\n[ERROR] ").append(e.getMessage());
                st.put("status", "FAILED");
                st.put("exitCode", -1);
            } finally {
                st.put("endTime", new Date());
                tasks.remove(runId);
            }
        });

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("runId", runId);
        return r;
    }

    public Map<String, Object> getOutput(String runId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", runId);
        r.put("status", statuses.getOrDefault(runId, Collections.emptyMap()));
        r.put("output", outputs.containsKey(runId) ? outputs.get(runId).toString() : "");
        r.put("running", tasks.containsKey(runId));
        return r;
    }

    public Map<String, Object> kill(String runId) {
        Process proc = tasks.remove(runId);
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
            Map<String, Object> st = statuses.get(runId);
            if (st != null) { st.put("status", "KILLED"); st.put("endTime", new Date()); }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        return r;
    }

    public List<Map<String, Object>> getHistory() {
        List<Map<String, Object>> h = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : statuses.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>(e.getValue());
            item.put("runId", e.getKey());
            h.add(item);
        }
        h.sort((a, b) -> {
            Date da = (Date) a.getOrDefault("startTime", new Date(0));
            Date db = (Date) b.getOrDefault("startTime", new Date(0));
            return db.compareTo(da);
        });
        return h;
    }
}

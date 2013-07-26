package com.gmail.adamwoollen.CompassNavigation;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

public class Metrics {

    public static int REVISION = 7;

    public static String BASE_URL = "http://report.mcstats.org";

    public static String REPORT_URL = "/plugin/%s";

    public static int PING_INTERVAL = 15;

    public CompassNavigation plugin;

    public Set<Graph> graphs = Collections.synchronizedSet(new HashSet<Graph>());

    public YamlConfiguration configuration;

    public File configurationFile;

    public String guid;

    public boolean debug;

    public Object optOutLock = new Object();

    public volatile BukkitTask task = null;

    public Metrics(CompassNavigation plugin) throws Exception {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }

        this.plugin = plugin;

        configurationFile = getConfigFile();
        configuration = YamlConfiguration.loadConfiguration(configurationFile);

        configuration.addDefault("opt-out", false);
        configuration.addDefault("guid", UUID.randomUUID().toString());
        configuration.addDefault("debug", false);

        if (configuration.get("guid", null) == null) {
            configuration.options().header("http://mcstats.org").copyDefaults(true);
            configuration.save(configurationFile);
        }

        guid = configuration.getString("guid");
        debug = configuration.getBoolean("debug", false);
    }
    
    public Graph createGraph(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Graph name cannot be null");
        }

        Graph graph = new Graph(name);

        graphs.add(graph);

        return graph;
    }

    public void addGraph(Graph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("Graph cannot be null");
        }

        graphs.add(graph);
    }

    public boolean start() {
        synchronized (optOutLock) {
            if (isOptOut()) {
                return false;
            }

            if (task != null) {
                return true;
            }

            task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {

                public boolean firstPost = true;

                public void run() {
                    try {
                        synchronized (optOutLock) {
                            if (isOptOut() && task != null) {
                                task.cancel();
                                task = null;
                                for (Graph graph : graphs) {
                                    graph.onOptOut();
                                }
                            }
                        }

                        postPlugin(!firstPost);

                        firstPost = false;
                    } catch (Exception e) {
                        if (debug) {
                            Bukkit.getLogger().log(Level.INFO, "[Metrics] " + e.getMessage());
                        }
                    }
                }
            }, 0, PING_INTERVAL * 1200);

            return true;
        }
    }

    public boolean isOptOut() {
        synchronized (optOutLock) {
            try {
                configuration.load(getConfigFile());
            } catch (Exception ex) {
                if (debug) {
                    Bukkit.getLogger().log(Level.INFO, "[Metrics] " + ex.getMessage());
                }
                return true;
            }
            return configuration.getBoolean("opt-out", false);
        }
    }

    public void enable() throws Exception {
        synchronized (optOutLock) {
            if (isOptOut()) {
                configuration.set("opt-out", false);
                configuration.save(configurationFile);
            }

            if (task == null) {
                start();
            }
        }
    }

    public void disable() throws Exception {
        synchronized (optOutLock) {
            if (!isOptOut()) {
                configuration.set("opt-out", true);
                configuration.save(configurationFile);
            }

            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }

    public File getConfigFile() {
        File pluginsFolder = plugin.getDataFolder().getParentFile();

        return new File(new File(pluginsFolder, "PluginMetrics"), "config.yml");
    }

    public void postPlugin(boolean isPing) throws Exception {
        PluginDescriptionFile description = plugin.getDescription();
        String pluginName = description.getName();
        boolean onlineMode = Bukkit.getServer().getOnlineMode();
        String pluginVersion = description.getVersion();
        String serverVersion = Bukkit.getVersion();
        int playersOnline = Bukkit.getServer().getOnlinePlayers().length;

        StringBuilder json = new StringBuilder(1024);
        json.append('{');

        appendJSONPair(json, "guid", guid);
        appendJSONPair(json, "plugin_version", pluginVersion);
        appendJSONPair(json, "server_version", serverVersion);
        appendJSONPair(json, "players_online", Integer.toString(playersOnline));

        String osname = System.getProperty("os.name");
        String osarch = System.getProperty("os.arch");
        String osversion = System.getProperty("os.version");
        String java_version = System.getProperty("java.version");
        int coreCount = Runtime.getRuntime().availableProcessors();

        if (osarch.equals("amd64")) {
            osarch = "x86_64";
        }

        appendJSONPair(json, "osname", osname);
        appendJSONPair(json, "osarch", osarch);
        appendJSONPair(json, "osversion", osversion);
        appendJSONPair(json, "cores", Integer.toString(coreCount));
        appendJSONPair(json, "auth_mode", onlineMode ? "1" : "0");
        appendJSONPair(json, "java_version", java_version);

        if (isPing) {
            appendJSONPair(json, "ping", "1");
        }

        if (graphs.size() > 0) {
            synchronized (graphs) {
                json.append(',');
                json.append('"');
                json.append("graphs");
                json.append('"');
                json.append(':');
                json.append('{');

                boolean firstGraph = true;

                Iterator<Graph> iter = graphs.iterator();

                while (iter.hasNext()) {
                    Graph graph = iter.next();

                    StringBuilder graphJson = new StringBuilder();
                    graphJson.append('{');

                    for (Plotter plotter : graph.getPlotters()) {
                        appendJSONPair(graphJson, plotter.getColumnName(), Integer.toString(plotter.getValue()));
                    }

                    graphJson.append('}');

                    if (!firstGraph) {
                        json.append(',');
                    }

                    json.append(escapeJSON(graph.getName()));
                    json.append(':');
                    json.append(graphJson);

                    firstGraph = false;
                }

                json.append('}');
            }
        }

        json.append('}');

        URL url = new URL(BASE_URL + String.format(REPORT_URL, urlEncode(pluginName)));

        URLConnection connection;

        if (isMineshafterPresent()) {
            connection = url.openConnection(Proxy.NO_PROXY);
        } else {
            connection = url.openConnection();
        }


        byte[] uncompressed = json.toString().getBytes();
        byte[] compressed = gzip(json.toString());

        connection.addRequestProperty("User-Agent", "MCStats/" + REVISION);
        connection.addRequestProperty("Content-Type", "application/json");
        connection.addRequestProperty("Content-Encoding", "gzip");
        connection.addRequestProperty("Content-Length", Integer.toString(compressed.length));
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("Connection", "close");

        connection.setDoOutput(true);

        if (debug) {
            System.out.println("[Metrics] Prepared request for " + pluginName + " uncompressed=" + uncompressed.length + " compressed=" + compressed.length);
        }

        OutputStream os = connection.getOutputStream();
        os.write(compressed);
        os.flush();

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String response = reader.readLine();

        os.close();
        reader.close();

        if (response == null || response.startsWith("ERR") || response.startsWith("7")) {
            if (response == null) {
                response = "null";
            } else if (response.startsWith("7")) {
                response = response.substring(response.startsWith("7,") ? 2 : 1);
            }

            throw new Exception(response);
        } else {
            if (response.equals("1") || response.contains("This is your first update this hour")) {
                synchronized (graphs) {
                    Iterator<Graph> iter = graphs.iterator();

                    while (iter.hasNext()) {
                        Graph graph = iter.next();

                        for (Plotter plotter : graph.getPlotters()) {
                            plotter.reset();
                        }
                    }
                }
            }
        }
    }

    public static byte[] gzip(String input) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gzos = null;

        try {
            gzos = new GZIPOutputStream(baos);
            gzos.write(input.getBytes("UTF-8"));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (gzos != null) try {
                gzos.close();
            } catch (Exception ignore) {
            }
        }

        return baos.toByteArray();
    }

    public boolean isMineshafterPresent() {
        try {
            Class.forName("mineshafter.MineServer");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void appendJSONPair(StringBuilder json, String key, String value) throws Exception {
        boolean isValueNumeric = false;

        try {
            if (value.equals("0") || !value.endsWith("0")) {
                Double.parseDouble(value);
                isValueNumeric = true;
            }
        } catch (NumberFormatException e) {
            isValueNumeric = false;
        }

        if (json.charAt(json.length() - 1) != '{') {
            json.append(',');
        }

        json.append(escapeJSON(key));
        json.append(':');

        if (isValueNumeric) {
            json.append(value);
        } else {
            json.append(escapeJSON(value));
        }
    }
    
    public static String escapeJSON(String text) {
        StringBuilder builder = new StringBuilder();

        builder.append('"');
        for (int index = 0; index < text.length(); index++) {
            char chr = text.charAt(index);

            switch (chr) {
                case '"':
                case '\\':
                    builder.append('\\');
                    builder.append(chr);
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                default:
                    if (chr < ' ') {
                        String t = "000" + Integer.toHexString(chr);
                        builder.append("\\u" + t.substring(t.length() - 4));
                    } else {
                        builder.append(chr);
                    }
                    break;
            }
        }
        builder.append('"');

        return builder.toString();
    }

    public static String urlEncode(String text) throws Exception {
        return URLEncoder.encode(text, "UTF-8");
    }

    public static class Graph {

        public String name;

        public Set<Plotter> plotters = new LinkedHashSet<Plotter>();

        public Graph(String name) {
            this.name = name;
        }
        
        public String getName() {
            return name;
        }

        public void addPlotter(Plotter plotter) {
            plotters.add(plotter);
        }

        public void removePlotter(Plotter plotter) {
            plotters.remove(plotter);
        }

        public Set<Plotter> getPlotters() {
            return Collections.unmodifiableSet(plotters);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Graph)) {
                return false;
            }

            Graph graph = (Graph) object;
            return graph.name.equals(name);
        }

        protected void onOptOut() {
        }
    }

    public static abstract class Plotter {

        public String name;

        public Plotter() {
            this("Default");
        }

        public Plotter(String name) {
            this.name = name;
        }

        public abstract int getValue();

        public String getColumnName() {
            return name;
        }

        public void reset() {
        }

        @Override
        public int hashCode() {
            return getColumnName().hashCode();
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof Plotter)) {
                return false;
            }

            Plotter plotter = (Plotter) object;
            return plotter.name.equals(name) && plotter.getValue() == getValue();
        }
    }
}
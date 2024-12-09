import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.text.DecimalFormat;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

public class LanternaSystemMonitor {
    private static final int GRAPH_WIDTH = 30;
    private static final int BAR_HEIGHT = 5;
    private static final DecimalFormat df = new DecimalFormat("#.##");
    private static final Deque<Double> cpuHistory = new ArrayDeque<>(GRAPH_WIDTH);
    private static final Deque<Double> memoryHistory = new ArrayDeque<>(GRAPH_WIDTH);
    private static final Deque<Double> diskReadHistory = new ArrayDeque<>(GRAPH_WIDTH);
    private static final Deque<Double> diskWriteHistory = new ArrayDeque<>(GRAPH_WIDTH);
    private static final Deque<Double> netInHistory = new ArrayDeque<>(GRAPH_WIDTH);
    private static final Deque<Double> netOutHistory = new ArrayDeque<>(GRAPH_WIDTH);
    private static final long PAGE_SIZE = getPageSize();

    // Stats for calculating rates
    private static long lastDiskRead = 0;
    private static long lastDiskWrite = 0;
    private static long lastNetIn = 0;
    private static long lastNetOut = 0;
    private static long lastTimestamp = System.currentTimeMillis();

    // color scheme: subtle, muted
    private static final TextColor BG_COLOR = new TextColor.RGB(10, 10, 10);
    private static final TextColor TEXT_COLOR = new TextColor.RGB(180, 180, 180);
    private static final TextColor BORDER_COLOR = new TextColor.RGB(70, 120, 140);
    private static final TextColor TITLE_COLOR = new TextColor.RGB(120, 200, 220);

    private static final TextColor BAR_LOW = new TextColor.RGB(50, 120, 50);
    private static final TextColor BAR_MED = new TextColor.RGB(200, 180, 50);
    private static final TextColor BAR_HIGH = new TextColor.RGB(180, 50, 50);

    public static void main(String[] args) {
        initializeHistories();
        OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        try {
            DefaultTerminalFactory factory = new DefaultTerminalFactory();
            factory.setInitialTerminalSize(new TerminalSize(120, 35));  // Increased size for more stats
            Screen screen = factory.createScreen();
            screen.startScreen();

            boolean running = true;
            while (running) {
                // Get all stats
                double cpuLoad = osBean.getCpuLoad() * 100;
                if (Double.isNaN(cpuLoad)) cpuLoad = 0.0;

                Map<String, Long> memStats = getVMStats();
                double ioStats = getDiskStats();
                Map<String, Double> netStats = getNetworkStats();

                // Update histories
                updateHistory(cpuHistory, cpuLoad);
                updateHistory(memoryHistory, calculateMemoryUsage(memStats));
                updateHistory(diskReadHistory, ioStats);
                updateHistory(diskWriteHistory, 0);
                updateHistory(netInHistory, netStats.get("in_rate"));
                updateHistory(netOutHistory, netStats.get("out_rate"));

                // Draw everything
                screen.clear();
                TextGraphics tg = screen.newTextGraphics();
                tg.setBackgroundColor(BG_COLOR);
                tg.setForegroundColor(TEXT_COLOR);

                drawTitle(tg, "system monitor");
                drawSystemStats(tg, cpuLoad, memStats, ioStats, netStats);
                drawCharts(tg);

                screen.refresh();

                KeyStroke keyStroke = screen.pollInput();
                if (keyStroke != null && keyStroke.getKeyType() == KeyType.Character
                        && keyStroke.isCtrlDown() && (keyStroke.getCharacter() == 'c' || keyStroke.getCharacter() == 'C')) {
                    running = false;
                }

                Thread.sleep(1000);
            }

            screen.stopScreen();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static double normalizeRate(double bytesPerSec) {
        // Use different scales for different metrics
        double maxRate;

        // For network, use 100 MB/s as 100% (more reasonable for typical networks)
        maxRate = 100 * 1024 * 1024; // 100 MB/s

        return Math.min((bytesPerSec / maxRate) * 100, 100);
    }

    private static void drawSystemStats(TextGraphics tg, double cpuLoad, Map<String, Long> memStats,
                                        double ioStats, Map<String, Double> netStats) {
        // CPU & Memory bars
        drawLabeledBar(tg, 2, 3, "cpu:       ", cpuLoad, 20);
        double memoryUsage = calculateMemoryUsage(memStats);
        drawLabeledBar(tg, 2, 5, "mem:       ", memoryUsage, 20);

        // Disk I/O bars - use 1GB/s as max
        double diskMax = 1024.0 * 1024 * 1024;
        drawLabeledBar(tg, 2, 7, "disk read: ", (ioStats / diskMax) * 100, 20);

        // Network bars - use 1MB/s as max
        double netMax = 1024 * 1024; // 1 MB/s
        drawLabeledBar(tg, 2, 9, "net in:    ", (netStats.get("in_rate") / netMax) * 100, 20);
        drawLabeledBar(tg, 2, 11, "net out:   ", (netStats.get("out_rate") / netMax) * 100, 20);

        // Detailed Memory Statistics
        long totalMemory = getTotalPhysicalMemory();
        long freeMemory = memStats.getOrDefault("free", 0L) * PAGE_SIZE;
        long activeMemory = memStats.getOrDefault("active", 0L) * PAGE_SIZE;
        long inactiveMemory = memStats.getOrDefault("inactive", 0L) * PAGE_SIZE;
        long wiredMemory = memStats.getOrDefault("wired", 0L) * PAGE_SIZE;
        long compressedMemory = memStats.getOrDefault("compressed", 0L) * PAGE_SIZE;
        long fileBackedPages = memStats.getOrDefault("filebacked", 0L) * PAGE_SIZE;
        long anonymousPages = memStats.getOrDefault("anonymous", 0L) * PAGE_SIZE;
        long memoryUsed = activeMemory + anonymousPages;
        long totalMemoryUsed = activeMemory + inactiveMemory + wiredMemory + compressedMemory;

        // Stats text
        int statsX = 2;
        int statsY = 14;
        tg.setForegroundColor(TITLE_COLOR);
        tg.putString(statsX, statsY, "system statistics:");
        tg.setForegroundColor(TEXT_COLOR);

        // Basic stats
        tg.putString(statsX, statsY + 1, String.format("cpu usage:    %5.1f%%", cpuLoad));
        tg.putString(statsX, statsY + 2, String.format("memory usage: %5.1f%%", memoryUsage));
        tg.putString(statsX, statsY + 3, String.format("i/o:           %s/s", formatSize((long) ioStats)));
        tg.putString(statsX, statsY + 4, String.format("network in:    %s/s", formatSize((long) netStats.get("in_rate").doubleValue())));
        tg.putString(statsX, statsY + 5, String.format("network out:   %s/s", formatSize((long) netStats.get("out_rate").doubleValue())));

        // Detailed memory stats
        tg.setForegroundColor(TITLE_COLOR);
        tg.putString(statsX, statsY + 7, "physical memory:");
        tg.setForegroundColor(TEXT_COLOR);

        int memStatsY = statsY + 8;
        tg.putString(statsX, memStatsY, String.format("total:                  %s", formatSize(totalMemory)));
        tg.putString(statsX, memStatsY + 1, String.format("memory used + cached:   %s", formatSize(totalMemoryUsed)));
        tg.putString(statsX, memStatsY + 2, String.format("memory used:            %s", formatSize(memoryUsed)));
        tg.putString(statsX, memStatsY + 3, String.format("wired memory:           %s", formatSize(wiredMemory)));
        tg.putString(statsX, memStatsY + 4, String.format("cached files:           %s", formatSize(fileBackedPages)));
        tg.putString(statsX, memStatsY + 5, String.format("compressed:             %s", formatSize(compressedMemory)));
        tg.putString(statsX, memStatsY + 6, String.format("free:                   %s", formatSize(freeMemory)));
    }

    private static void drawCharts(TextGraphics tg) {
        // Left column
        drawHistoryChart(tg, 40, 4, "cpu history", cpuHistory, BAR_HEIGHT, GRAPH_WIDTH);
        drawHistoryChart(tg, 40, 12, "memory history", memoryHistory, BAR_HEIGHT, GRAPH_WIDTH);

        // Right column
        drawHistoryChart(tg, 80, 4, "disk i/o history", diskReadHistory, BAR_HEIGHT, GRAPH_WIDTH);
        drawHistoryChart(tg, 80, 12, "network history", netInHistory, BAR_HEIGHT, GRAPH_WIDTH);
    }

    private static BufferedReader iostatReader;
    private static boolean iostatInitialized = false;

    private static void initDiskStats() {
        if (iostatInitialized) return; // already initialized
        try {
            ProcessBuilder pb = new ProcessBuilder("iostat", "-d", "-w", "1"); // continuously output every second
            Process p = pb.start();
            iostatReader = new BufferedReader(new InputStreamReader(p.getInputStream()));

            // skip initial lines (headers)
            String line;
            while ((line = iostatReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.contains("KB/t") && line.contains("MB/s")) {
                    break; // stop when we reach the header row
                }
            }
            iostatInitialized = true;
        } catch (Exception e) {
            System.err.println("error initializing iostat: " + e.getMessage());
        }
    }

    // fetch the latest disk stats from the running iostat process
    private static double getDiskStats() {
        double ioRate = 0;

        try {
            initDiskStats(); // ensure iostat is initialized

            String line;
            while ((line = iostatReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // example line: "15.45   81  1.22   800.98    0  0.01"
                String[] parts = line.split("\\s+");
                if (parts.length >= 6) {
                    // parse disk0 read rate (MB/s to bytes/s)
                    double ioMbPerSecond = Double.parseDouble(parts[2]);
                    ioRate = ioMbPerSecond * 1024 * 1024;
                    break; // only process one line per call
                }
            }
        } catch (Exception e) {
            System.err.println("error reading disk stats: " + e.getMessage());
        }
        return ioRate;
    }

    private static Map<String, Double> getNetworkStats() {
        Map<String, Double> stats = new HashMap<>();
        double inRate = 0;
        double outRate = 0;

        try {
            ProcessBuilder pb = new ProcessBuilder("netstat", "-ib");
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            long totalIn = 0;
            long totalOut = 0;
            boolean headerPassed = false;

            while ((line = reader.readLine()) != null) {
                if (!headerPassed) {
                    headerPassed = true;
                    continue;
                }
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 7 && !parts[0].equals("lo0")) {  // Skip loopback interface
                    totalIn += Long.parseLong(parts[6]);
                    totalOut += Long.parseLong(parts[9]);
                }
            }
            reader.close();
            p.waitFor();

            long now = System.currentTimeMillis();
            double duration = (now - lastTimestamp) / 1000.0;

            inRate = (totalIn - lastNetIn) / duration;
            outRate = (totalOut - lastNetOut) / duration;

            lastNetIn = totalIn;
            lastNetOut = totalOut;

        } catch (Exception e) {
            System.err.println("Error getting network stats: " + e.getMessage());
        }

        stats.put("in_rate", inRate);
        stats.put("out_rate", outRate);
        return stats;
    }


    private static void initializeHistories() {
        for (int i = 0; i < GRAPH_WIDTH; i++) {
            cpuHistory.addFirst(0.0);
            memoryHistory.addFirst(0.0);
            diskReadHistory.addFirst(0.0);
            diskWriteHistory.addFirst(0.0);
            netInHistory.addFirst(0.0);
            netOutHistory.addFirst(0.0);
        }
    }

    private static double calculateMemoryUsage(Map<String, Long> memStats) {
        long totalMemory = getTotalPhysicalMemory();
        long activeMemory = memStats.getOrDefault("active", 0L) * PAGE_SIZE;
        long inactiveMemory = memStats.getOrDefault("inactive", 0L) * PAGE_SIZE;
        long wiredMemory = memStats.getOrDefault("wired", 0L) * PAGE_SIZE;
        long compressedMemory = memStats.getOrDefault("compressed", 0L) * PAGE_SIZE;

        long totalMemoryUsed = activeMemory + inactiveMemory + wiredMemory + compressedMemory;
        return ((double) totalMemoryUsed / totalMemory) * 100;
    }

    private static void drawTitle(TextGraphics tg, String title) {
        tg.setForegroundColor(TITLE_COLOR);
        String t = " " + title + " ";
        int x = (111 - t.length()) / 2;
        tg.putString(x, 0, t);

        tg.setForegroundColor(BORDER_COLOR);
        for (int i = 0; i < 111; i++) tg.putString(i, 1, "─");
    }

    private static void drawLabeledBar(TextGraphics tg, int x, int y, String label, double percentage, int length) {
        tg.setForegroundColor(TEXT_COLOR);
        tg.putString(x, y, label);
        drawBar(tg, x + label.length() + 1, y, percentage, length);
    }

    private static void drawBar(TextGraphics tg, int x, int y, double percentage, int length) {
        int filled = (int) (percentage * length / 100.0);
        for (int i = 0; i < length; i++) {
            if (i < filled) {
                tg.setForegroundColor(getBarColor((double) i / length));
                tg.putString(x + i, y, "█");
            } else {
                tg.setForegroundColor(TEXT_COLOR);
                tg.putString(x + i, y, "░");
            }
        }
    }

    private static TextColor getBarColor(double ratio) {
        if (ratio < 0.5) return BAR_LOW;
        else if (ratio < 0.8) return BAR_MED;
        else return BAR_HIGH;
    }

    private static void drawHistoryChart(TextGraphics tg, int x, int y, String title, Deque<Double> history, int height, int width) {
        tg.setForegroundColor(TITLE_COLOR);
        tg.putString(x, y - 1, title);

        // border line
        tg.setForegroundColor(BORDER_COLOR);
        for (int i = 0; i < width; i++) {
            tg.putString(x + i, y - 2, "─");
            tg.putString(x + i, y + height, "─");
        }
        tg.putString(x - 1, y - 2, "┌");
        tg.putString(x + width, y - 2, "┐");
        tg.putString(x - 1, y + height, "└");
        tg.putString(x + width, y + height, "┘");

        for (int h = 0; h < height; h++) {
            tg.putString(x - 1, y + h, "│");
        }

        List<Double> vals = new ArrayList<>(history);
        Collections.reverse(vals);
        for (int i = 0; i < vals.size() && i < width; i++) {
            double val = vals.get(i);
            // Normalize based on chart type
            double normalizedVal;
            if (title.toLowerCase().contains("network")) {
                // Use 1MB/s as maximum for network charts
                normalizedVal = (val / (1024 * 1024)) * 100;
            } else {
                normalizedVal = val;
            }

            int filled = (int) Math.round((normalizedVal / 100.0) * height);
            for (int line = 0; line < height; line++) {
                if (line < filled) {
                    tg.setForegroundColor(getBarColor((double) line / height));
                    tg.putString(x + i, y + (height - line - 1), "█");
                } else {
                    tg.setForegroundColor(TEXT_COLOR);
                    tg.putString(x + i, y + (height - line - 1), " ");
                }
            }
        }
    }

    private static void updateHistory(Deque<Double> hist, double val) {
        hist.removeLast();
        hist.addFirst(val);
    }

    private static long getTotalPhysicalMemory() {
        try {
            ProcessBuilder pb = new ProcessBuilder("sysctl", "-n", "hw.memsize");
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            reader.close();
            p.waitFor();
            if (line != null) {
                return Long.parseLong(line.trim());
            }
        } catch (Exception e) {
            System.err.println("Error getting total memory: " + e.getMessage());
        }
        return Runtime.getRuntime().maxMemory(); // fallback
    }

    private static Map<String, Long> getVMStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("free", 0L);
        stats.put("active", 0L);
        stats.put("inactive", 0L);
        stats.put("wired", 0L);
        stats.put("compressed", 0L);
        stats.put("filebacked", 0L);
        stats.put("anonymous", 0L);

        try {
            ProcessBuilder pb = new ProcessBuilder("vm_stat");
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.contains("Pages free:")) {
                    stats.put("free", parseVMStatValue(line));
                } else if (line.contains("Pages active:")) {
                    stats.put("active", parseVMStatValue(line));
                } else if (line.contains("Pages inactive:")) {
                    stats.put("inactive", parseVMStatValue(line));
                } else if (line.contains("Pages wired down:")) {
                    stats.put("wired", parseVMStatValue(line));
                } else if (line.contains("Pages occupied by compressor:")) {
                    stats.put("compressed", parseVMStatValue(line));
                } else if (line.contains("File-backed pages:")) {
                    stats.put("filebacked", parseVMStatValue(line));
                } else if (line.contains("Anonymous pages:")) {
                    stats.put("anonymous", parseVMStatValue(line));
                }
            }
            reader.close();
            p.waitFor();
        } catch (Exception e) {
            System.err.println("Error reading vm_stat: " + e.getMessage());
        }
        return stats;
    }

    private static long parseVMStatValue(String line) {
        try {
            return Long.parseLong(line.split(":")[1].trim().replace(".", ""));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String formatSize(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        double size = bytes;

        while (size > 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }

        return df.format(size) + " " + units[unitIndex];
    }

    private static long getPageSize() {
        try {
            ProcessBuilder pb = new ProcessBuilder("getconf", "PAGESIZE");
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            reader.close();
            p.waitFor();
            if (line != null) {
                return Long.parseLong(line.trim());
            }
        } catch (Exception e) {
            System.err.println("Error getting page size: " + e.getMessage());
            throw (new RuntimeException("Error getting page size: " + e.getMessage()));
        }
        return 4096; // fallback
    }
}

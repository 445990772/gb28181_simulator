package com.gb28181.simulator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局统计管理器（线程安全）
 */
class GlobalStats {
    private final Map<String, StreamInfo> activeStreams = new ConcurrentHashMap<>();
    private final AtomicBoolean globalStatsStop = new AtomicBoolean(false);
    private Thread globalStatsThread;
    private final AtomicLong startPlayTime = new AtomicLong(0);

    /**
     * 注册一个播放流
     */
    public void registerStream(String streamKey) {
        long currentTime = System.currentTimeMillis() / 1000;
        startPlayTime.compareAndSet(0, currentTime);
        
        activeStreams.put(streamKey, new StreamInfo(currentTime));
    }

    /**
     * 注销一个播放流
     */
    public void unregisterStream(String streamKey) {
        activeStreams.remove(streamKey);
    }

    /**
     * 更新流的字节统计
     */
    public void updateBytes(String streamKey, long bytesCount) {
        StreamInfo info = activeStreams.get(streamKey);
        if (info != null) {
            synchronized (info) {
                info.bytesTotal += bytesCount;
                long currentTime = System.currentTimeMillis() / 1000;
                long elapsed = currentTime - info.lastUpdateTime;
                
                if (elapsed >= 1) {
                    info.bytesLastSec = bytesCount;
                    info.lastUpdateTime = currentTime;
                } else {
                    info.bytesLastSec += bytesCount;
                }
            }
        }
    }

    /**
     * 获取当前统计信息
     */
    public StatsResult getStats() {
        int activeCount = activeStreams.size();
        long totalBytes = activeStreams.values().stream()
                .mapToLong(info -> {
                    synchronized (info) {
                        return info.bytesTotal;
                    }
                })
                .sum();
        double totalMb = totalBytes / (1024.0 * 1024.0);
        
        long playDuration = 0;
        long startTime = startPlayTime.get();
        if (startTime > 0) {
            playDuration = (System.currentTimeMillis() / 1000) - startTime;
        }
        
        return new StatsResult(activeCount, totalMb, playDuration);
    }

    /**
     * 启动全局统计线程
     */
    public void startGlobalStatsThread() {
        if (globalStatsThread != null && globalStatsThread.isAlive()) {
            return;
        }
        
        globalStatsStop.set(false);
        
        globalStatsThread = new Thread(() -> {
            long lastTime = System.currentTimeMillis() / 1000;
            while (!globalStatsStop.get()) {
                long currentTime = System.currentTimeMillis() / 1000;
                if (currentTime - lastTime >= 1) {
                    StatsResult stats = getStats();
                    // 灰绿色输出全局统计，包含开始播放时长
                    String grayGreen = "\033[38;5;245m";
                    String reset = "\033[0m";
                    System.out.println(grayGreen + "═══════════════════════════════════════════════════════════════════════════════" + reset);
                    System.out.printf("%s[全局统计] │ 播放路数: %d 路 │ 总流量: %.3f MB │ 开始播放时长: %ds%s%n",
                            grayGreen, stats.activeCount, stats.totalMb, stats.playDuration, reset);
                    System.out.println(grayGreen + "═══════════════════════════════════════════════════════════════════════════════" + reset);
                    lastTime = currentTime;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        globalStatsThread.setDaemon(true);
        globalStatsThread.start();
    }

    /**
     * 停止全局统计
     */
    public void stopGlobalStats() {
        globalStatsStop.set(true);
        if (globalStatsThread != null) {
            try {
                globalStatsThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 重置开始播放时间，以便下次统计时重新开始
        startPlayTime.set(0);
    }

    /**
     * 统计结果
     */
    static class StatsResult {
        final int activeCount;
        final double totalMb;
        final long playDuration;

        StatsResult(int activeCount, double totalMb, long playDuration) {
            this.activeCount = activeCount;
            this.totalMb = totalMb;
            this.playDuration = playDuration;
        }
    }
}


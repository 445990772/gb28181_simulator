package com.gb28181.simulator;

import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 查询指定平台的全部视频设备及其通道，并发发起点播（live.mp4）请求做压测
 * 
 * 用法示例：
 *   java -jar simulator-jar-with-dependencies.jar
 * 
 * 说明：
 * - 并发开启多个通道的视频流点播
 * - 每个通道播放300秒（5分钟）后自动停止
 * - 每个播放流独立开启接收数据统计，每秒输出全局统计信息
 */
public class QueryAndConcurrentLive {
    private static final GlobalStats globalStats = new GlobalStats();

    /**
     * 启动一个 live.mp4 流，持续播放指定时长，并实时统计接收数据
     * 
     * @param baseUrl 平台根地址
     * @param deviceId 设备ID
     * @param channelId 通道ID
     * @param token 访问令牌
     * @param duration 播放时长（秒），默认300秒
     * @param timeout HTTP超时时间（秒）
     * @param isRetry 是否为重试播放
     * @return 播放是否成功
     */
    private static boolean pullLiveStream(String baseUrl, String deviceId, String channelId,
                                         String token, int duration, double timeout, boolean isRetry) {
        String url = baseUrl + "/api/media/device/" + deviceId + "/" + channelId + "/live.mp4";
        String streamKey = deviceId + "/" + channelId;
        
        BlockingQueue<Long> byteQueue = new LinkedBlockingQueue<>();
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        long startTime = System.currentTimeMillis() / 1000;
        final long[] totalBytes = {0};

        // 注册到全局统计
        globalStats.registerStream(streamKey);

        // 统计线程：更新全局统计
        Thread statThread = new Thread(() -> {
            while (!stopFlag.get()) {
                // 收集队列中的所有字节
                long bytesThisCycle = 0;
                long count = byteQueue.size();
                for (int i = 0; i < count; i++) {
                    Long b = byteQueue.poll();
                    if (b != null) {
                        bytesThisCycle += b;
                    }
                }

                // 更新全局统计
                if (bytesThisCycle > 0) {
                    globalStats.updateBytes(streamKey, bytesThisCycle);
                }

                // 检查是否达到播放时长
                long elapsedFromStart = (System.currentTimeMillis() / 1000) - startTime;
                if (elapsedFromStart >= duration) {
                    stopFlag.set(true);
                    break;
                }

                try {
                    Thread.sleep(100); // 短暂休眠避免CPU占用过高
                } catch (InterruptedException e) {
                    break;
                }
            }

            // 最终统计 - 收集剩余字节
            long finalBytesThisCycle = 0;
            Long b;
            while ((b = byteQueue.poll()) != null) {
                finalBytesThisCycle += b;
            }

            // 更新全局统计最后一次
            if (finalBytesThisCycle > 0) {
                globalStats.updateBytes(streamKey, finalBytesThisCycle);
            }
        });
        statThread.setDaemon(false);
        statThread.start();

        boolean playSuccess = false;

        try {
            Response response = HttpUtils.getStream(url, token);
            
            if (!response.isSuccessful()) {
                response.close();
                stopFlag.set(true);
                return false;
            }

            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                stopFlag.set(true);
                return false;
            }

            try (InputStream inputStream = body.byteStream()) {
                byte[] buffer = new byte[64 * 1024];
                int bytesRead;

                while (!stopFlag.get() && (bytesRead = inputStream.read(buffer)) != -1) {
                    if (bytesRead > 0) {
                        totalBytes[0] += bytesRead;
                        byteQueue.offer((long) bytesRead);
                    }
                }
            }

            // 如果接收到数据，认为播放成功
            playSuccess = totalBytes[0] > 0;
            response.close();
            stopFlag.set(true);

        } catch (Exception e) {
            stopFlag.set(true);
            playSuccess = false;
        } finally {
            // 注销全局统计
            globalStats.unregisterStream(streamKey);
        }

        // 等待统计线程结束
        try {
            statThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return playSuccess;
    }

    /**
     * 读取用户输入
     */
    private static String readInput(String prompt) {
        System.out.print(prompt);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            return reader.readLine();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 读取整数输入
     */
    private static int readIntInput(String prompt, int defaultValue) {
        String input = readInput(prompt).trim();
        if (input.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 读取浮点数输入
     */
    private static double readDoubleInput(String prompt, double defaultValue) {
        String input = readInput(prompt).trim();
        if (input.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(input);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static void main(String[] args) {
        // 纯交互式输入
        String baseUrlInput = readInput("平台根地址（默认: http://192.168.32.84:9000: ").trim();
        final String baseUrl;
        if (baseUrlInput.isEmpty()) {
            baseUrl = "http://192.168.32.84:9000";
        } else {
            baseUrl = baseUrlInput.replaceAll("/+$", "");
        }

        final String token;
        while (true) {
            String tokenInput = readInput(":X_Access_Token（必填）: ").trim();
            if (!tokenInput.isEmpty()) {
                token = tokenInput;
                break;
            }
            System.out.println(":X_Access_Token不能为空，请重新输入");
        }

        int perDeviceLimit = readIntInput("每设备通道上限（0为不限制，默认: 0）: ", 0);
        int playDuration = readIntInput("每个通道播放时长（秒，默认: 300）: ", 300);
        int concurrency = readIntInput("并发线程数（默认: 20）: ", 20);
        double connectTimeout = readDoubleInput("HTTP超时秒（默认: 30）: ", 30.0);

        System.out.println("查询设备列表: " + baseUrl);
        
        List<Map.Entry<String, String>> targets = new ArrayList<>();
        try {
            List<com.google.gson.JsonObject> devices = HttpUtils.paginateDevices(baseUrl, token, 100);
            System.out.println("设备数量: " + devices.size());

            for (com.google.gson.JsonObject dev : devices) {
                String devId = null;
                if (dev.has("id")) {
                    devId = dev.get("id").getAsString();
                } else if (dev.has("deviceId")) {
                    devId = dev.get("deviceId").getAsString();
                } else if (dev.has("deviceID")) {
                    devId = dev.get("deviceID").getAsString();
                }

                if (devId == null || devId.isEmpty()) {
                    continue;
                }

                List<com.google.gson.JsonObject> chans = HttpUtils.paginateChannels(baseUrl, devId, token, 200);
                if (perDeviceLimit > 0 && chans.size() > perDeviceLimit) {
                    chans = chans.subList(0, perDeviceLimit);
                }

                for (com.google.gson.JsonObject ch : chans) {
                    String chId = null;
                    if (ch.has("channelId")) {
                        chId = ch.get("channelId").getAsString();
                    } else if (ch.has("id")) {
                        chId = ch.get("id").getAsString();
                    }

                    if (chId != null && !chId.isEmpty()) {
                        targets.add(new AbstractMap.SimpleEntry<>(devId, chId));
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("查询设备列表失败: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        System.out.println("通道总数: " + targets.size() + "，开始并发播放（每个通道播放 " + playDuration + " 秒，并发数: " + concurrency + "）…");
        System.out.println("=".repeat(60));

        // 启动全局统计线程
        globalStats.startGlobalStatsThread();

        // 失败重试集合（线程安全）
        List<Map.Entry<String, String>> failedStreams = Collections.synchronizedList(new ArrayList<>());
        long tStart = System.currentTimeMillis() / 1000;

        // 并发开启每个通道的播放
        System.out.println("\n" + "=".repeat(60));
        System.out.println("已启动 " + targets.size() + " 个通道并发播放，等待所有播放完成...");
        System.out.println("=".repeat(60));

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (Map.Entry<String, String> target : targets) {
            String devId = target.getKey();
            String chId = target.getValue();
            Future<Boolean> future = executor.submit(() -> {
                boolean success = pullLiveStream(baseUrl, devId, chId, token, playDuration, connectTimeout, false);
                if (!success) {
                    synchronized (failedStreams) {
                        failedStreams.add(new AbstractMap.SimpleEntry<>(devId, chId));
                    }
                }
                return success;
            });
            futures.add(future);
        }

        // 等待所有任务完成
        for (Future<Boolean> future : futures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                // 如果执行出错，添加到失败列表（已在pullLiveStream中处理）
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 停止全局统计线程
        globalStats.stopGlobalStats();

        // 如果有播放失败的通道，进行重试
        if (!failedStreams.isEmpty()) {
            System.out.println("\n" + "=".repeat(60));
            System.out.println("\033[33m发现 " + failedStreams.size() + " 个播放失败的通道，开始重试...\033[0m");
            System.out.println("=".repeat(60));

            // 重新启动全局统计线程用于重试统计
            globalStats.startGlobalStatsThread();

            long retryStartTime = System.currentTimeMillis() / 1000;

            System.out.println("\n" + "=".repeat(60));
            System.out.println("已启动 " + failedStreams.size() + " 个重试通道并发播放，等待所有重试完成...");
            System.out.println("=".repeat(60));

            ExecutorService retryExecutor = Executors.newFixedThreadPool(concurrency);
            List<Future<?>> retryFutures = new ArrayList<>();

            for (Map.Entry<String, String> target : failedStreams) {
                String devId = target.getKey();
                String chId = target.getValue();
                retryFutures.add(retryExecutor.submit(() -> {
                    pullLiveStream(baseUrl, devId, chId, token, playDuration, connectTimeout, true);
                }));
            }

            // 等待所有重试任务完成
            for (Future<?> retryFuture : retryFutures) {
                try {
                    retryFuture.get();
                } catch (InterruptedException | ExecutionException e) {
                    // 重试失败不处理
                }
            }

            retryExecutor.shutdown();
            try {
                retryExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 停止全局统计线程
            globalStats.stopGlobalStats();

            long retryDur = (System.currentTimeMillis() / 1000) - retryStartTime;
            System.out.println("\n" + "=".repeat(60));
            System.out.println("重试播放完成，耗时 " + retryDur + "s");
            System.out.println("=".repeat(60));
        }

        long dur = (System.currentTimeMillis() / 1000) - tStart;
        System.out.println("\n" + "=".repeat(60));
        System.out.println("所有通道播放完成，总耗时 " + dur + "s");
        System.out.println("已处理通道总数: " + targets.size());
        if (!failedStreams.isEmpty()) {
            System.out.println("失败通道数: " + failedStreams.size());
        }
    }
}


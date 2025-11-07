package com.gb28181.simulator.web;

import com.gb28181.simulator.device.GB28181DeviceSimulator;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GB28181设备模拟器 - Java后端API服务（使用Vert.x）
 * 提供RESTful API接口供Web前端调用
 */
public class ApiServer {
    
    private static GB28181DeviceSimulator simulator = null;
    private static Thread simulatorThread = null;
    private static volatile boolean running = false;
    private static final Gson gson = new Gson();
    private static Vertx vertx = null;
    private static HttpServer server = null;
    
    // 消息存储（用于Web展示）
    private static final List<Map<String, Object>> messageStore = new ArrayList<>();
    private static final int MAX_MESSAGES = 1000;
    
    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("GB28181 设备模拟器 - Java API服务 (Vert.x)");
        System.out.println("=".repeat(60));
        
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("无效的端口号，使用默认端口8080");
            }
        }
        
        final int finalPort = port;
        
        vertx = Vertx.vertx();
        
        // 创建Router
        Router router = Router.router(vertx);
        
        // 添加CORS支持
        router.route().handler(ctx -> {
            ctx.response()
                .putHeader("Access-Control-Allow-Origin", "*")
                .putHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                .putHeader("Access-Control-Allow-Headers", "Content-Type");
            
            if (ctx.request().method().name().equals("OPTIONS")) {
                ctx.response().end();
            } else {
                ctx.next();
            }
        });
        
        // 添加BodyHandler用于处理POST请求体
        router.route().handler(BodyHandler.create());
        
        // API路由
        router.get("/api/health").handler(ApiServer::handleHealth);
        router.post("/api/start").handler(ApiServer::handleStart);
        router.post("/api/stop").handler(ApiServer::handleStop);
        router.get("/api/status").handler(ApiServer::handleStatus);
        router.get("/api/stats").handler(ApiServer::handleStats);
        router.get("/api/messages").handler(ApiServer::handleMessages);
        router.get("/api/system/resources").handler(ApiServer::handleSystemResources);
        
        // 设备相关API
        router.get("/api/device/:deviceId/info").handler(ApiServer::handleDeviceInfo);
        router.get("/api/device/:deviceId/stats").handler(ApiServer::handleDeviceStats);
        router.get("/api/device/:deviceId/messages").handler(ApiServer::handleDeviceMessages);
        router.get("/api/device/:deviceId/stream").handler(ApiServer::handleDeviceStream);
        
        // 静态文件服务（放在最后，作为fallback）
        router.route("/*").handler(StaticHandler.create("web"));
        
        // 创建HTTP服务器
        vertx.createHttpServer()
            .requestHandler(router)
            .listen(finalPort, result -> {
                if (result.succeeded()) {
                    server = result.result();
                    System.out.println("API服务地址: http://localhost:" + finalPort);
                    System.out.println("Web界面地址: http://localhost:" + finalPort + "/index.html");
                    System.out.println("=".repeat(60));
                    System.out.println("按 Ctrl+C 停止服务");
                } else {
                    System.err.println("✗ 启动API服务失败: " + result.cause().getMessage());
                    result.cause().printStackTrace();
                    System.exit(1);
                }
            });
        
        // 等待中断信号
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在停止API服务...");
            if (server != null) {
                server.close();
            }
            if (vertx != null) {
                vertx.close();
            }
            if (simulator != null && running) {
                simulator = null;
                running = false;
            }
            System.out.println("✓ 已停止");
        }));
    }
    
    /**
     * 健康检查处理器
     */
    private static void handleHealth(RoutingContext ctx) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok");
        response.addProperty("message", "Java后端服务运行正常");
        response.addProperty("backend", "java");
        
        ctx.response()
            .putHeader("Content-Type", "application/json; charset=UTF-8")
            .end(response.toString());
    }
    
    /**
     * 启动模拟器处理器
     */
    private static void handleStart(RoutingContext ctx) {
        try {
            if (running) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "模拟器已在运行中");
                ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json; charset=UTF-8")
                    .end(error.toString());
                return;
            }
            
            String requestBody = ctx.body().asString();
            JsonObject json = JsonParser.parseString(requestBody).getAsJsonObject();
            
            // 验证必需参数
            String[] requiredFields = {"serverIp", "serverPort", "password", "deviceCount", "channelCount"};
            for (String field : requiredFields) {
                if (!json.has(field)) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "缺少必需参数: " + field);
                    ctx.response()
                        .setStatusCode(400)
                        .putHeader("Content-Type", "application/json; charset=UTF-8")
                        .end(error.toString());
                    return;
                }
            }
            
            // 创建新的模拟器实例
            simulator = new GB28181DeviceSimulator();
            
            // 配置参数
            String serverIp = json.get("serverIp").getAsString();
            int serverPort = json.get("serverPort").getAsInt();
            String password = json.get("password").getAsString();
            int deviceCount = json.get("deviceCount").getAsInt();
            int channelCount = json.get("channelCount").getAsInt();
            String baseDeviceId = json.has("baseDeviceId") ? 
                json.get("baseDeviceId").getAsString() : "3402000000132000";
            int basePort = json.has("basePort") ? 
                json.get("basePort").getAsInt() : 15060;
            
            // 确定本地IP
            String defaultLocalIp = "127.0.0.1";
            if (!"127.0.0.1".equals(serverIp) && !"localhost".equals(serverIp)) {
                defaultLocalIp = "0.0.0.0";
            }
            
            // 清空消息存储
            synchronized (messageStore) {
                messageStore.clear();
            }
            
            // 设置消息回调
            simulator.setMessageCallback((deviceId, direction, message, addr) -> {
                synchronized (messageStore) {
                    if (messageStore.size() >= MAX_MESSAGES) {
                        messageStore.remove(0);
                    }
                    
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("deviceId", deviceId);
                    msg.put("direction", direction);
                    msg.put("message", message);
                    msg.put("content", message);
                    msg.put("summary", message.split("\r\n")[0]);
                    msg.put("timestamp", System.currentTimeMillis() / 1000.0);
                    msg.put("addr", addr != null ? addr.toString() : "");
                    
                    // 尝试提取channelId
                    String channelId = extractChannelIdFromMessage(message, direction);
                    if (channelId != null) {
                        msg.put("channelId", channelId);
                    }
                    
                    messageStore.add(msg);
                }
            });
            
            // 创建设备
            for (int i = 0; i < deviceCount; i++) {
                String deviceId = baseDeviceId + String.format("%04d", i + 1);
                String deviceName = "模拟设备" + (i + 1);
                String localIp = defaultLocalIp;
                int localPort = basePort + i;
                
                simulator.createDevice(
                    deviceId,
                    deviceName,
                    localIp,
                    localPort,
                    serverIp,
                    serverPort,
                    password,
                    channelCount
                );
            }
            
            // 在后台线程中启动模拟器
            running = true;
            simulatorThread = new Thread(() -> {
                try {
                    simulator.startAllDevices();
                } catch (Exception e) {
                    System.err.println("模拟器运行错误: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    running = false;
                }
            });
            simulatorThread.setDaemon(true);
            simulatorThread.start();
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "模拟器启动成功");
            response.addProperty("deviceCount", deviceCount);
            response.addProperty("channelCount", channelCount);
            response.addProperty("totalChannels", deviceCount * channelCount);
            
            ctx.response()
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(response.toString());
            
        } catch (Exception e) {
            running = false;
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            ctx.response()
                .setStatusCode(500)
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(error.toString());
        }
    }
    
    /**
     * 停止模拟器处理器
     */
    private static void handleStop(RoutingContext ctx) {
        try {
            if (!running) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "模拟器未运行");
                ctx.response()
                    .setStatusCode(400)
                    .putHeader("Content-Type", "application/json; charset=UTF-8")
                    .end(error.toString());
                return;
            }
            
            if (simulator != null) {
                simulator.stopAllDevices();
                running = false;
            }
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "模拟器已停止");
            
            ctx.response()
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(response.toString());
            
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            ctx.response()
                .setStatusCode(500)
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(error.toString());
        }
    }
    
    /**
     * 状态查询处理器
     */
    private static void handleStatus(RoutingContext ctx) {
        try {
            List<Map<String, Object>> devicesInfo = new ArrayList<>();
            
            if (simulator != null && running) {
                for (com.gb28181.simulator.device.GB28181Device device : simulator.getDevices()) {
                    Map<String, Object> deviceInfo = new HashMap<>();
                    deviceInfo.put("deviceId", device.deviceId);
                    deviceInfo.put("deviceName", device.deviceName);
                    deviceInfo.put("localIp", device.localIp);
                    deviceInfo.put("localPort", device.localPort);
                    deviceInfo.put("serverIp", device.serverIp);
                    deviceInfo.put("serverPort", device.serverPort);
                    deviceInfo.put("isRegistered", device.isRegistered);
                    deviceInfo.put("channelCount", device.getChannels().size());
                    
                    List<Map<String, String>> channels = new ArrayList<>();
                    for (com.gb28181.simulator.device.Channel channel : device.getChannels()) {
                        Map<String, String> channelInfo = new HashMap<>();
                        channelInfo.put("id", channel.getId());
                        channelInfo.put("name", channel.getAttribute("name"));
                        channels.add(channelInfo);
                    }
                    deviceInfo.put("channels", channels);
                    
                    devicesInfo.add(deviceInfo);
                }
            }
            
            JsonObject response = new JsonObject();
            response.addProperty("running", running);
            response.addProperty("deviceCount", devicesInfo.size());
            response.add("devices", gson.toJsonTree(devicesInfo));
            
            ctx.response()
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(response.toString());
            
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            ctx.response()
                .setStatusCode(500)
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(error.toString());
        }
    }
    
    /**
     * 统计数据处理器
     */
    private static void handleStats(RoutingContext ctx) {
        try {
            List<Map<String, Object>> devicesStats = new ArrayList<>();
            
            if (simulator != null && running) {
                for (com.gb28181.simulator.device.GB28181Device device : simulator.getDevices()) {
                    Map<String, Object> deviceStat = new HashMap<>();
                    deviceStat.put("deviceId", device.deviceId);
                    deviceStat.put("deviceName", device.deviceName);
                    deviceStat.put("bytesPerSecond", 0); // 需要从设备获取实际统计
                    deviceStat.put("mbps", 0.0);
                    devicesStats.add(deviceStat);
                }
            }
            
            JsonObject response = new JsonObject();
            response.add("devices", gson.toJsonTree(devicesStats));
            
            ctx.response()
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(response.toString());
            
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            ctx.response()
                .setStatusCode(500)
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(error.toString());
        }
    }
    
    /**
     * 消息记录处理器
     */
    private static void handleMessages(RoutingContext ctx) {
        try {
            // 返回最近的消息（最多100条）
            int size;
            List<Map<String, Object>> recentMessages;
            synchronized (messageStore) {
                size = messageStore.size();
                int start = Math.max(0, size - 100);
                recentMessages = new ArrayList<>(
                    messageStore.subList(start, size)
                );
            }
            
            JsonObject response = new JsonObject();
            response.add("messages", gson.toJsonTree(recentMessages));
            
            ctx.response()
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(response.toString());
            
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            ctx.response()
                .setStatusCode(500)
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(error.toString());
        }
    }
    
    /**
     * 设备信息处理器
     */
    private static void handleDeviceInfo(RoutingContext ctx) {
        String deviceId = ctx.pathParam("deviceId");
        
        if (simulator == null || !running) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "模拟器未运行");
            ctx.response()
                .setStatusCode(400)
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(error.toString());
            return;
        }
        
        com.gb28181.simulator.device.GB28181Device device = null;
        for (com.gb28181.simulator.device.GB28181Device d : simulator.getDevices()) {
            if (d.deviceId.equals(deviceId)) {
                device = d;
                break;
            }
        }
        
        if (device == null) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "设备未找到");
            ctx.response()
                .setStatusCode(404)
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(error.toString());
            return;
        }
        
        List<Map<String, String>> channelsInfo = new ArrayList<>();
        for (com.gb28181.simulator.device.Channel channel : device.getChannels()) {
            Map<String, String> channelInfo = new HashMap<>();
            channelInfo.put("id", channel.getId());
            channelInfo.put("name", channel.getAttribute("name"));
            channelsInfo.add(channelInfo);
        }
        
        JsonObject response = new JsonObject();
        response.addProperty("deviceId", device.deviceId);
        response.addProperty("deviceName", device.deviceName);
        response.addProperty("localIp", device.localIp);
        response.addProperty("localPort", device.localPort);
        response.addProperty("serverIp", device.serverIp);
        response.addProperty("serverPort", device.serverPort);
        response.addProperty("isRegistered", device.isRegistered);
        response.add("channels", gson.toJsonTree(channelsInfo));
        
        ctx.response()
            .putHeader("Content-Type", "application/json; charset=UTF-8")
            .end(response.toString());
    }
    
    /**
     * 设备统计处理器
     */
    private static void handleDeviceStats(RoutingContext ctx) {
        String deviceId = ctx.pathParam("deviceId");
        
        List<Map<String, Object>> channelsStats = new ArrayList<>();
        
        if (simulator != null && running) {
            for (com.gb28181.simulator.device.GB28181Device device : simulator.getDevices()) {
                if (device.deviceId.equals(deviceId)) {
                    // 注意：Java版本需要添加获取通道统计的方法
                    for (com.gb28181.simulator.device.Channel channel : device.getChannels()) {
                        Map<String, Object> channelStat = new HashMap<>();
                        channelStat.put("channelId", channel.getId());
                        channelStat.put("bytesPerSecond", 0); // 需要从设备获取实际统计
                        channelStat.put("mbps", 0.0);
                        channelsStats.add(channelStat);
                    }
                    break;
                }
            }
        }
        
        JsonObject response = new JsonObject();
        response.addProperty("deviceId", deviceId);
        response.add("channels", gson.toJsonTree(channelsStats));
        
        ctx.response()
            .putHeader("Content-Type", "application/json; charset=UTF-8")
            .end(response.toString());
    }
    
    /**
     * 设备消息处理器
     */
    private static void handleDeviceMessages(RoutingContext ctx) {
        String deviceId = ctx.pathParam("deviceId");
        String channelId = ctx.queryParams().get("channelId");
        String sinceParam = ctx.queryParams().get("since");
        int since = 0;
        
        if (sinceParam != null) {
            try {
                since = Integer.parseInt(sinceParam);
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
        
        // 过滤消息
        List<Map<String, Object>> filteredMessages = new ArrayList<>();
        synchronized (messageStore) {
            for (Map<String, Object> msg : messageStore) {
                if (deviceId.equals(msg.get("deviceId"))) {
                    if (channelId != null && !channelId.equals("all")) {
                        if (!channelId.equals(msg.get("channelId"))) {
                            continue;
                        }
                    }
                    filteredMessages.add(msg);
                }
            }
        }
        
        // 如果指定了since参数，只返回新消息
        if (since > 0 && since < filteredMessages.size()) {
            filteredMessages = filteredMessages.subList(since, filteredMessages.size());
        }
        
        // 返回最近的消息（最多200条）
        int size = filteredMessages.size();
        int start = Math.max(0, size - 200);
        List<Map<String, Object>> recentMessages = filteredMessages.subList(start, size);
        
        JsonObject response = new JsonObject();
        response.add("messages", gson.toJsonTree(recentMessages));
        
        ctx.response()
            .putHeader("Content-Type", "application/json; charset=UTF-8")
            .end(response.toString());
    }
    
    /**
     * 设备SSE流处理器
     */
    private static void handleDeviceStream(RoutingContext ctx) {
        String deviceId = ctx.pathParam("deviceId");
        final String channelFilter = ctx.queryParams().get("channelId") != null ? 
            ctx.queryParams().get("channelId") : "all";
        final boolean newOnly = "true".equalsIgnoreCase(ctx.queryParams().get("newOnly"));
        
        HttpServerResponse response = ctx.response();
        
        // 设置SSE响应头
        response
            .setChunked(true)
            .putHeader("Content-Type", "text/event-stream")
            .putHeader("Cache-Control", "no-cache")
            .putHeader("Connection", "keep-alive")
            .putHeader("X-Accel-Buffering", "no");
        
        // 如果只发送新消息，记录当前消息数量作为起始点
        AtomicInteger lastMessageIndex = new AtomicInteger(0);
        if (newOnly) {
            synchronized (messageStore) {
                int initialCount = 0;
                for (Map<String, Object> msg : messageStore) {
                    if (deviceId.equals(msg.get("deviceId"))) {
                        if ("all".equals(channelFilter) || channelFilter.equals(msg.get("channelId"))) {
                            initialCount++;
                        }
                    }
                }
                lastMessageIndex.set(initialCount);
            }
        }
        
        // 使用Vert.x的定时器实现SSE推送
        long timerId = vertx.setPeriodic(1000, id -> {
            try {
                // 获取统计数据
                List<Map<String, Object>> channelsStats = new ArrayList<>();
                if (simulator != null && running) {
                    for (com.gb28181.simulator.device.GB28181Device device : simulator.getDevices()) {
                        if (device.deviceId.equals(deviceId)) {
                            // 注意：Java版本需要添加获取通道统计的方法
                            for (com.gb28181.simulator.device.Channel channel : device.getChannels()) {
                                Map<String, Object> channelStat = new HashMap<>();
                                channelStat.put("channelId", channel.getId());
                                channelStat.put("bytesPerSecond", 0); // 需要从设备获取实际统计
                                channelStat.put("mbps", 0.0);
                                channelStat.put("channelName", channel.getAttribute("name"));
                                channelsStats.add(channelStat);
                            }
                            break;
                        }
                    }
                }
                
                // 获取设备信息（用于添加通道信息）
                Map<String, com.gb28181.simulator.device.Channel> channelMap = new HashMap<>();
                if (simulator != null && running) {
                    for (com.gb28181.simulator.device.GB28181Device d : simulator.getDevices()) {
                        if (d.deviceId.equals(deviceId)) {
                            for (com.gb28181.simulator.device.Channel ch : d.getChannels()) {
                                channelMap.put(ch.getId(), ch);
                            }
                            break;
                        }
                    }
                }
                
                // 获取新消息
                List<Map<String, Object>> newMessages = new ArrayList<>();
                synchronized (messageStore) {
                    List<Map<String, Object>> filteredMessages = new ArrayList<>();
                    for (Map<String, Object> msg : messageStore) {
                        if (deviceId.equals(msg.get("deviceId"))) {
                            if (!"all".equals(channelFilter)) {
                                if (!channelFilter.equals(msg.get("channelId"))) {
                                    continue;
                                }
                            }
                            filteredMessages.add(msg);
                        }
                    }
                    
                    int currentIndex = lastMessageIndex.get();
                    if (currentIndex < filteredMessages.size()) {
                        newMessages = filteredMessages.subList(currentIndex, filteredMessages.size());
                        // 为每条消息添加通道信息
                        for (Map<String, Object> msg : newMessages) {
                            // 如果消息中没有channelId，尝试从消息内容中提取
                            if (!msg.containsKey("channelId") || msg.get("channelId") == null) {
                                String content = (String) msg.get("content");
                                if (content != null && content.contains("INVITE") && content.contains("sip:")) {
                                    try {
                                        String[] lines = content.split("\n");
                                        if (lines.length > 0 && lines[0].contains("sip:") && lines[0].contains("@")) {
                                            String part = lines[0].split("sip:")[1].split("@")[0];
                                            msg.put("channelId", part);
                                        }
                                    } catch (Exception e) {
                                        // 忽略提取错误
                                    }
                                }
                            }
                            
                            // 添加通道名称
                            String channelId = (String) msg.get("channelId");
                            if (channelId != null) {
                                com.gb28181.simulator.device.Channel channelInfo = channelMap.get(channelId);
                                if (channelInfo != null) {
                                    String channelName = channelInfo.getAttribute("name");
                                    msg.put("channelName", channelName != null && !channelName.isEmpty() ? channelName : channelId);
                                } else {
                                    msg.put("channelName", channelId);
                                }
                            }
                        }
                        lastMessageIndex.set(filteredMessages.size());
                    }
                }
                
                // 构建JSON数据
                Map<String, Object> data = new HashMap<>();
                data.put("type", "update");
                Map<String, Object> stats = new HashMap<>();
                stats.put("channels", channelsStats);
                data.put("stats", stats);
                data.put("messages", newMessages);
                
                String jsonData = gson.toJson(data);
                String sseData = "data: " + jsonData + "\n\n";
                
                response.write(sseData);
                
            } catch (Exception e) {
                Map<String, Object> errorData = new HashMap<>();
                errorData.put("type", "error");
                errorData.put("message", e.getMessage());
                String jsonError = gson.toJson(errorData);
                String sseError = "data: " + jsonError + "\n\n";
                response.write(sseError);
                vertx.cancelTimer(id);
                response.end();
            }
        });
        
        // 当连接关闭时，取消定时器
        response.closeHandler(v -> {
            vertx.cancelTimer(timerId);
        });
    }
    
    /**
     * 系统资源统计处理器
     */
    private static void handleSystemResources(RoutingContext ctx) {
        try {
            // CPU使用率
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            double cpuPercent = 0;
            int cpuCount = osBean.getAvailableProcessors();
            
            // 尝试使用com.sun.management获取CPU使用率（如果可用）
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = 
                    (com.sun.management.OperatingSystemMXBean) osBean;
                cpuPercent = sunOsBean.getProcessCpuLoad() * 100;
                if (cpuPercent < 0) {
                    cpuPercent = 0;
                }
            } else {
                // 回退到系统负载
                cpuPercent = osBean.getSystemLoadAverage() * 100 / cpuCount;
                if (cpuPercent < 0 || Double.isNaN(cpuPercent)) {
                    cpuPercent = 0;
                }
            }
            
            // 内存使用情况
            long memoryTotal = 0;
            long memoryUsed = 0;
            long memoryAvailable = 0;
            double memoryPercent = 0;
            
            // 尝试使用com.sun.management获取系统内存（如果可用）
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = 
                    (com.sun.management.OperatingSystemMXBean) osBean;
                memoryTotal = sunOsBean.getTotalPhysicalMemorySize();
                memoryUsed = memoryTotal - sunOsBean.getFreePhysicalMemorySize();
                memoryAvailable = sunOsBean.getFreePhysicalMemorySize();
                memoryPercent = (double) memoryUsed / memoryTotal * 100;
            } else {
                // 回退到JVM内存统计
                MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
                MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
                memoryTotal = heapUsage.getMax();
                memoryUsed = heapUsage.getUsed();
                memoryAvailable = memoryTotal - memoryUsed;
                memoryPercent = (double) memoryUsed / memoryTotal * 100;
            }
            
            // 网络收发包情况
            long bytesSent = 0;
            long bytesRecv = 0;
            long packetsSent = 0;
            long packetsRecv = 0;
            
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (ni.isUp() && !ni.isLoopback()) {
                        // 注意：Java标准库不直接提供网络统计，这里使用简化版本
                        // 实际项目中可以使用第三方库如oshi
                    }
                }
            } catch (Exception e) {
                // 忽略网络接口获取错误
            }
            
            JsonObject response = new JsonObject();
            
            JsonObject cpu = new JsonObject();
            cpu.addProperty("percent", Math.round(cpuPercent * 100.0) / 100.0);
            cpu.addProperty("count", cpuCount);
            response.add("cpu", cpu);
            
            JsonObject memory = new JsonObject();
            memory.addProperty("total", memoryTotal);
            memory.addProperty("used", memoryUsed);
            memory.addProperty("available", memoryAvailable);
            memory.addProperty("percent", Math.round(memoryPercent * 100.0) / 100.0);
            response.add("memory", memory);
            
            JsonObject network = new JsonObject();
            network.addProperty("bytesSent", bytesSent);
            network.addProperty("bytesRecv", bytesRecv);
            network.addProperty("packetsSent", packetsSent);
            network.addProperty("packetsRecv", packetsRecv);
            response.add("network", network);
            
            response.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
            
            ctx.response()
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(response.toString());
                
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            ctx.response()
                .setStatusCode(500)
                .putHeader("Content-Type", "application/json; charset=UTF-8")
                .end(error.toString());
        }
    }
    
    /**
     * 从消息中提取channelId
     */
    private static String extractChannelIdFromMessage(String message, String direction) {
        if (message == null) {
            return null;
        }
        
        try {
            String[] lines = message.split("\r\n");
            if (lines.length == 0) {
                return null;
            }
            
            String firstLine = lines[0];
            
            // 对于send方向，从INVITE或MESSAGE请求行提取
            if ("send".equals(direction)) {
                if (firstLine.contains("INVITE") || firstLine.contains("MESSAGE")) {
                    if (firstLine.contains("sip:") && firstLine.contains("@")) {
                        String part = firstLine.split("sip:")[1].split("@")[0];
                        return part;
                    }
                }
            }
            
            // 对于recv方向，从To头提取
            if ("recv".equals(direction)) {
                for (String line : lines) {
                    if (line.startsWith("To:") && line.contains("sip:")) {
                        String toValue = line.substring(3).trim();
                        if (toValue.contains("sip:") && toValue.contains("@")) {
                            String part = toValue.split("sip:")[1].split("@")[0];
                            return part;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略提取错误
        }
        
        return null;
    }
}

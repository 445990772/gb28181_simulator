package com.gb28181.simulator.device;

import com.gb28181.simulator.sip.SipMessageBuilder;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GB28181设备类
 */
public class GB28181Device {
    public final String deviceId;
    public final String deviceName;
    public final String localIp;
    public final int localPort;
    public final String serverIp;
    public final int serverPort;
    public final String password;
    
    private String callIdPrefix;
    private int callIdSeq = 0;
    private int cseq = 0;
    private final String tag;
    public int registerExpires = 3600;
    public volatile boolean isRegistered = false;
    public int heartbeatInterval = 30; // 心跳间隔（秒）
    public int retryInterval = 10; // 重试间隔（秒）
    
    private DatagramSocket socket;
    private final List<Channel> channels = new ArrayList<>();
    
    // 每通道独立的推流进程
    private final Map<String, Process> channelIdToProcess = new ConcurrentHashMap<>();
    
    public String contactIp; // Contact头中使用的IP地址（用于0.0.0.0绑定情况）
    private int heartbeatSn = 0; // 心跳消息序列号
    public volatile Long lastHeartbeat; // 上次心跳时间，注册成功后重置
    
    // 会话跟踪：Call-ID -> [session_keys]
    private final Map<String, List<String>> callIdToSessions = new ConcurrentHashMap<>();
    
    /**
     * 添加Call-ID到session_key的映射
     */
    public void addCallIdToSession(String callId, String sessionKey) {
        callIdToSessions.computeIfAbsent(callId, k -> new ArrayList<>()).add(sessionKey);
    }
    
    /**
     * 获取Call-ID对应的session keys
     */
    public List<String> getSessionKeysByCallId(String callId) {
        return callIdToSessions.getOrDefault(callId, new ArrayList<>());
    }
    
    /**
     * 移除Call-ID映射
     */
    public void removeCallIdMapping(String callId) {
        callIdToSessions.remove(callId);
    }
    
    /**
     * 按session key停止推流（供外部调用）
     */
    public void stopStreamPushBySessionKeyPublic(String sessionKey) {
        stopStreamPushBySessionKey(sessionKey);
    }
    
    public GB28181Device(String deviceId, String deviceName, String localIp, int localPort,
                        String serverIp, int serverPort, String password) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.localIp = localIp;
        this.localPort = localPort;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.password = password;
        this.tag = UUID.randomUUID().toString().substring(0, 32);
        this.callIdPrefix = UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * 创建注册请求
     */
    public String createRegisterRequest() {
        callIdSeq = 0;
        cseq = 0;
        
        String contactAddress = (contactIp != null && !contactIp.isEmpty()) ? contactIp : localIp;
        return SipMessageBuilder.createRegisterRequest(
            deviceId, localIp, localPort, serverIp, serverPort,
            password, tag, contactAddress
        );
    }
    
    /**
     * 发送Keepalive心跳消息
     */
    public void sendKeepalive(MessagePrinter messagePrinter) {
        heartbeatSn++;
        String keepaliveXml = XmlGenerator.createKeepaliveXml(deviceId, heartbeatSn);
        
        String contactAddress = (contactIp != null && !contactIp.isEmpty()) ? contactIp : localIp;
        String message = SipMessageBuilder.createKeepaliveMessage(
            deviceId, contactAddress, localPort, serverIp, serverPort,
            heartbeatSn, keepaliveXml, tag
        );
        
        try {
            if (socket != null && !socket.isClosed()) {
                byte[] data = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(data, data.length, 
                    InetAddress.getByName(serverIp), serverPort));
            }
        } catch (IOException e) {
            System.err.println("✗ 发送心跳消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 启动视频流推送
     */
    public boolean startStreamPush(String channelId, String avcaptureUrl, String targetIp,
                                   int targetPort, String ssrc) {
        // 使用会话键（同通道不同端口可并发）：channel@ip:port
        String sessionKey = channelId + "@" + targetIp + ":" + targetPort;
        
        // 停止该会话的旧推流
        stopStreamPushBySessionKey(sessionKey);
        
        String rtpUrl = "rtp://" + targetIp + ":" + targetPort;
        
        System.out.println("\n推流: 循环播放 -> " + rtpUrl + " (SSRC: " + ssrc + ", 通道: " + channelId + ")");
        
        try {
            // 构建FFmpeg命令（包含循环播放、音频编码等）
            List<String> cmd = new ArrayList<>();
            cmd.add("ffmpeg");
            // 循环播放参数：-stream_loop -1 表示无限循环，-re 表示实时速率读取
            cmd.add("-stream_loop");
            cmd.add("-1");
            cmd.add("-re");
            cmd.add("-i");
            cmd.add(avcaptureUrl);
            
            // 查找通道名称（用于水印）
            String channelName = channelId;
            for (Channel ch : channels) {
                if (ch.getId().equals(channelId)) {
                    String name = ch.getAttribute("name");
                    if (name != null && !name.isEmpty()) {
                        channelName = name;
                        break;
                    }
                }
            }
            if (channelName == null || channelName.isEmpty()) {
                channelName = channelId != null ? channelId : "CHANNEL";
            }
            
            // 构建drawtext水印滤镜（优先中文名，无则用ID）
            // 参考Python版本的实现，使用NotoSansCJK字体支持中文
            String watermark = channelName;
            // 转义单引号（Python版本在f-string中会自动处理，Java需要手动转义）
            String escapedWatermark = watermark.replace("'", "\\'");
            String filterStr = String.format(
                "drawtext=fontfile=/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc:text='%s':fontcolor=white:fontsize=28:box=1:boxcolor=black@0.4:boxborderw=6:x=10:y=10",
                escapedWatermark
            );
            cmd.add("-vf");
            cmd.add(filterStr);
            
            // 视频编码参数
            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-preset");
            cmd.add("veryfast");
            cmd.add("-tune");
            cmd.add("zerolatency");
            cmd.add("-b:v");
            cmd.add("2000k");
            cmd.add("-maxrate");
            cmd.add("2000k");
            cmd.add("-bufsize");
            cmd.add("4000k");
            cmd.add("-g");
            cmd.add("50");
            cmd.add("-pix_fmt");
            cmd.add("yuv420p");
            cmd.add("-flags");
            cmd.add("+global_header");
            
            // 音频编码参数（允许音频一并推送）
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("128k");
            
            // 输出格式
            cmd.add("-f");
            cmd.add("rtp_mpegts");  // GB28181通常使用MPEG-TS over RTP
            cmd.add(rtpUrl);
            
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 重定向错误输出到空，避免FFmpeg日志干扰
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            
            Process process = pb.start();
            channelIdToProcess.put(sessionKey, process);
            
            System.out.println("✓ 推流已启动到 " + rtpUrl);
            return true;
        } catch (IOException e) {
            System.err.println("✗ 启动推流失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 按session key停止推流
     */
    private void stopStreamPushBySessionKey(String sessionKey) {
        Process process = channelIdToProcess.remove(sessionKey);
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
    
    /**
     * 停止指定通道的推流
     */
    public void stopStreamPush(String channelId) {
        // 如果没有精确的session key，按通道前缀匹配全部会话
        List<String> keysToRemove = new ArrayList<>();
        for (String key : channelIdToProcess.keySet()) {
            if (key.startsWith(channelId + "@")) {
                keysToRemove.add(key);
            }
        }
        // 也尝试直接匹配（兼容旧代码）
        if (channelIdToProcess.containsKey(channelId)) {
            keysToRemove.add(channelId);
        }
        
        for (String key : keysToRemove) {
            stopStreamPushBySessionKey(key);
        }
    }
    
    /**
     * 停止所有推流
     */
    public void stopAllStreamPush() {
        for (String channelId : new ArrayList<>(channelIdToProcess.keySet())) {
            stopStreamPush(channelId);
        }
    }
    
    /**
     * 获取通道列表
     */
    public List<Channel> getChannels() {
        return new ArrayList<>(channels);
    }
    
    /**
     * 添加通道
     */
    public void addChannel(Channel channel) {
        channels.add(channel);
    }
    
    /**
     * 获取Socket
     */
    public DatagramSocket getSocket() {
        return socket;
    }
    
    /**
     * 设置Socket
     */
    public void setSocket(DatagramSocket socket) {
        this.socket = socket;
    }
    
    /**
     * 消息打印接口
     */
    @FunctionalInterface
    public interface MessagePrinter {
        void print(String deviceId, String direction, String message, SocketAddress addr);
    }
}


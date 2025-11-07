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
    
    // 存储每个通道的临时目录
    private final Map<String, String> tempDirs = new ConcurrentHashMap<>();
    
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
     * 获取系统字体路径，优先使用微软雅黑
     */
    private static String getFontPath() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        
        if (osName.contains("windows")) {
            // Windows系统：优先使用微软雅黑
            String[] fontPaths = {
                "C:\\Windows\\Fonts\\msyh.ttc",  // 微软雅黑
                "C:\\Windows\\Fonts\\simhei.ttf",  // 黑体
                "C:\\Windows\\Fonts\\simsun.ttc",  // 宋体
            };
            for (String path : fontPaths) {
                java.io.File fontFile = new java.io.File(path);
                if (fontFile.exists()) {
                    return path;
                }
            }
            // 如果找不到文件，使用字体名称
            return "Microsoft YaHei";
        } else if (osName.contains("mac")) {
            // macOS系统字体
            String[] fontPaths = {
                "/System/Library/Fonts/PingFang.ttc",
                "/System/Library/Fonts/STHeiti Light.ttc",
                "/Library/Fonts/Arial Unicode.ttf",
            };
            for (String path : fontPaths) {
                java.io.File fontFile = new java.io.File(path);
                if (fontFile.exists()) {
                    return path;
                }
            }
            return "PingFang SC";
        } else {
            // Linux系统字体
            String[] fontPaths = {
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            };
            for (String path : fontPaths) {
                java.io.File fontFile = new java.io.File(path);
                if (fontFile.exists()) {
                    return path;
                }
            }
            return "DejaVu Sans";
        }
    }
    
    /**
     * 获取视频信息（分辨率、帧率等）
     */
    private VideoInfo getVideoInfo(String videoPath) {
        try {
            List<String> cmd = Arrays.asList(
                "ffprobe",
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,r_frame_rate",
                "-of", "default=noprint_wrappers=1:nokey=1",
                videoPath
            );
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                String[] lines = output.toString().trim().split("\n");
                if (lines.length >= 3) {
                    int width = Integer.parseInt(lines[0].trim());
                    int height = Integer.parseInt(lines[1].trim());
                    String frameRate = lines[2].trim();
                    return new VideoInfo(width, height, frameRate);
                }
            }
        } catch (Exception e) {
            System.err.println("⚠ 无法获取视频信息 " + videoPath + ": " + e.getMessage());
        }
        return new VideoInfo(1920, 1080, "25/1");
    }
    
    /**
     * 视频信息类
     */
    private static class VideoInfo {
        final int width;
        final int height;
        final String frameRate;
        
        VideoInfo(int width, int height, String frameRate) {
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
        }
    }
    
    /**
     * 创建带水印的过渡视频
     */
    private boolean createWatermarkVideo(String outputPath, String text, int duration,
                                         int width, int height, String frameRate) {
        String escapedText = text.replace("'", "\\'");
        String font = getFontPath();
        
        String filterStr;
        java.io.File fontFile = new java.io.File(font);
        if (fontFile.exists() || font.startsWith("/") || font.contains(":\\")) {
            filterStr = String.format(
                "drawtext=fontfile=%s:text='%s':fontcolor=white:fontsize=48:box=1:boxcolor=black@0.5:boxborderw=8:x=(w-text_w)/2:y=(h-text_h)/2",
                font, escapedText
            );
        } else {
            filterStr = String.format(
                "drawtext=font=%s:text='%s':fontcolor=white:fontsize=48:box=1:boxcolor=black@0.5:boxborderw=8:x=(w-text_w)/2:y=(h-text_h)/2",
                font, escapedText
            );
        }
        
        List<String> cmd = Arrays.asList(
            "ffmpeg",
            "-f", "lavfi",
            "-i", String.format("color=c=black:s=%dx%d:d=%d:r=%s", width, height, duration, frameRate),
            "-vf", filterStr,
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-pix_fmt", "yuv420p",
            "-y",
            outputPath
        );
        
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 创建包含视频和水印过渡的播放列表
     */
    private String createPlaylistWithWatermarks(List<String> videoFiles, String tempDir, 
                                                String channelId, int duration) {
        String playlistPath = tempDir + java.io.File.separator + "playlist.txt";
        
        // 获取第一个视频的信息作为参考
        VideoInfo videoInfo;
        if (!videoFiles.isEmpty()) {
            videoInfo = getVideoInfo(videoFiles.get(0));
        } else {
            videoInfo = new VideoInfo(1920, 1080, "25/1");
        }
        
        try (java.io.PrintWriter writer = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(playlistPath), 
                    java.nio.charset.StandardCharsets.UTF_8))) {
            
            for (int i = 0; i < videoFiles.size(); i++) {
                String videoFile = videoFiles.get(i);
                // 添加视频文件
                java.io.File file = new java.io.File(videoFile);
                String absPath = file.getAbsolutePath().replace("'", "'\\''");
                writer.println("file '" + absPath + "'");
                
                // 创建下一个文件的水印过渡
                int nextIndex = (i + 1) % videoFiles.size();
                String nextFilename = new java.io.File(videoFiles.get(nextIndex)).getName();
                String watermarkText = "播放下一个 " + nextFilename;
                
                String watermarkPath = tempDir + java.io.File.separator + "watermark_" + i + ".mp4";
                
                if (createWatermarkVideo(watermarkPath, watermarkText, duration,
                        videoInfo.width, videoInfo.height, videoInfo.frameRate)) {
                    java.io.File watermarkFile = new java.io.File(watermarkPath);
                    String absWatermarkPath = watermarkFile.getAbsolutePath().replace("'", "'\\''");
                    writer.println("file '" + absWatermarkPath + "'");
                }
            }
        } catch (IOException e) {
            System.err.println("✗ 创建播放列表失败: " + e.getMessage());
            return null;
        }
        
        return playlistPath;
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
        
        // 收集resources目录下的所有mp4文件
        List<String> files = new ArrayList<>();
        java.io.File resourcesDir = null;
        
        // 基于类文件位置计算resources目录路径
        try {
            java.net.URL classUrl = this.getClass().getProtectionDomain()
                .getCodeSource().getLocation();
            if (classUrl != null && "file".equals(classUrl.getProtocol())) {
                java.io.File classFile = new java.io.File(classUrl.toURI());
                java.io.File baseDir;
                
                if (classFile.isFile() && classFile.getName().endsWith(".jar")) {
                    // JAR文件：在JAR所在目录的父目录查找java/src/main/resources
                    baseDir = classFile.getParentFile();
                    if (baseDir != null && baseDir.getParentFile() != null) {
                        java.io.File javaDir = baseDir.getParentFile();
                        resourcesDir = new java.io.File(javaDir, "src/main/resources");
                    }
                } else {
                    // 找到java目录，然后定位到src/main/resources
                    baseDir = classFile.getParentFile();
                    for (int i = 0; i < 3 && baseDir != null; i++) {
                        baseDir = baseDir.getParentFile();
                    }
                    if (baseDir != null && baseDir.exists()) {
                        resourcesDir = new java.io.File(baseDir, "src/main/resources");
                    }
                }
            }
        } catch (Exception e) {
            // 忽略异常，继续尝试其他方法
        }
        
        // 如果还是找不到，使用当前工作目录
        if (resourcesDir == null || !resourcesDir.exists()) {
            java.io.File currentDir = new java.io.File(System.getProperty("user.dir"));
            int maxLevels = 5;
            for (int level = 0; level < maxLevels && currentDir != null; level++) {
                java.io.File testResourcesDir = new java.io.File(currentDir, "java/src/main/resources");
                if (testResourcesDir.exists()) {
                    resourcesDir = testResourcesDir;
                    break;
                }
                java.io.File parentDir = currentDir.getParentFile();
                if (parentDir == null || parentDir.equals(currentDir)) {
                    break;
                }
                currentDir = parentDir;
            }
        }
        
        // 从resources目录查找mp4文件
        if (resourcesDir != null && resourcesDir.exists()) {
            java.io.File[] mp4Files = resourcesDir.listFiles((dir, name) -> 
                name.toLowerCase().endsWith(".mp4"));
            if (mp4Files != null) {
                for (java.io.File file : mp4Files) {
                    if (file.isFile()) {
                        files.add(file.getAbsolutePath());
                    }
                }
                // 排序以确保顺序一致
                files.sort(String::compareTo);
            }
        }
        
        // 环境变量指定的文件也纳入（若存在）
        if (avcaptureUrl != null && new java.io.File(avcaptureUrl).exists() && 
            !files.contains(avcaptureUrl)) {
            files.add(0, avcaptureUrl);
        }
        
        if (files.isEmpty()) {
            System.err.println("✗ 未找到可用的本地MP4文件，请放置到 " + 
                (resourcesDir != null ? resourcesDir.getAbsolutePath() : "resources") + " 目录");
            return false;
        }
        
        String rtpUrl = "rtp://" + targetIp + ":" + targetPort;
        
        // 创建临时目录用于存放水印视频和播放列表
        String tempDir;
        try {
            java.io.File tempFile = java.io.File.createTempFile("gb28181_playlist_" + deviceId + "_" + channelId + "_", "");
            tempFile.delete();
            tempFile.mkdirs();
            tempDir = tempFile.getAbsolutePath();
        } catch (IOException e) {
            System.err.println("✗ 创建临时目录失败: " + e.getMessage());
            return false;
        }
        
        // 保存临时目录，以便后续清理
        tempDirs.put(sessionKey, tempDir);
        
        // 创建包含水印过渡的播放列表
        String playlistPath = createPlaylistWithWatermarks(files, tempDir, channelId, 5);
        
        if (playlistPath == null) {
            System.err.println("✗ 创建播放列表失败");
            return false;
        }
        
        System.out.println("\n推流: 循环 " + files.size() + " 个文件（含水印过渡） -> " + 
            rtpUrl + " (SSRC: " + ssrc + ", 通道: " + channelId + ")");
        
        try {
            // 构建FFmpeg命令
            List<String> cmd = new ArrayList<>();
            cmd.add("ffmpeg");
            // 循环播放参数：-stream_loop -1 表示无限循环，-re 表示实时速率读取
            cmd.add("-stream_loop");
            cmd.add("-1");
            cmd.add("-re");
            cmd.add("-f");
            cmd.add("concat");
            cmd.add("-safe");
            cmd.add("0");
            cmd.add("-i");
            cmd.add(playlistPath);
            
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
            String watermark = channelName;
            // 转义单引号
            String escapedWatermark = watermark.replace("'", "\\'");
            
            // 获取字体路径
            String font = getFontPath();
            
            // 构建 drawtext 滤镜
            String filterStr;
            java.io.File fontFile = new java.io.File(font);
            if (fontFile.exists() || font.startsWith("/") || font.contains(":\\")) {
                // 使用字体文件路径
                filterStr = String.format(
                    "drawtext=fontfile=%s:text='%s':fontcolor=white:fontsize=28:box=1:boxcolor=black@0.4:boxborderw=6:x=10:y=10",
                    font, escapedWatermark
                );
            } else {
                // 使用字体名称
                filterStr = String.format(
                    "drawtext=font=%s:text='%s':fontcolor=white:fontsize=28:box=1:boxcolor=black@0.4:boxborderw=6:x=10:y=10",
                    font, escapedWatermark
                );
            }
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
        // 清理临时目录
        String tempDir = tempDirs.remove(sessionKey);
        if (tempDir != null) {
            try {
                java.nio.file.Files.walk(java.nio.file.Paths.get(tempDir))
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(java.nio.file.Path::toFile)
                    .forEach(java.io.File::delete);
            } catch (Exception e) {
                // 忽略清理错误
            }
        }
        
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


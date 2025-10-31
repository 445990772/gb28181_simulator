package com.gb28181.simulator.device.handler;

import com.gb28181.simulator.device.GB28181Device;
import com.gb28181.simulator.sip.SipMessageBuilder;
import com.gb28181.simulator.sip.SipMessageParser;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * INVITE处理类
 */
public class InviteHandler {
    
    /**
     * 消息打印接口
     */
    @FunctionalInterface
    public interface MessagePrinter {
        void print(String deviceId, String direction, String message, SocketAddress addr);
    }
    
    /**
     * 处理INVITE请求
     */
    public static void handleInvite(GB28181Device device, String[] lines, SocketAddress addr,
                                    MessagePrinter printSipMessage) {
        System.out.println("\n收到平台INVITE指令 (设备: " + device.deviceId + ")");
        
        Map<String, Object> sdp = SipMessageParser.parseInviteSdp(lines);
        if (sdp.containsKey("ip")) {
            String ip = (String) sdp.get("ip");
            Integer videoPort = (Integer) sdp.get("video_port");
            String ssrc = (String) sdp.get("ssrc");
            
            System.out.println("  推流地址: " + ip + ":" + (videoPort != null ? videoPort : "N/A"));
            System.out.println("  SSRC: " + (ssrc != null ? ssrc : "N/A"));
            
            // 发送200 OK响应（包含SDP）
            String contactIp = device.contactIp != null ? device.contactIp : device.localIp;
            String response = SipMessageBuilder.createInviteResponse(lines, contactIp, device.deviceId, device.localPort);
            try {
                byte[] responseData = response.getBytes(StandardCharsets.UTF_8);
                device.getSocket().send(new DatagramPacket(responseData, responseData.length,
                    ((InetSocketAddress) addr).getAddress(), ((InetSocketAddress) addr).getPort()));
                printSipMessage.print(device.deviceId, "send", response, addr);
            } catch (IOException e) {
                System.err.println("✗ 发送INVITE响应失败: " + e.getMessage());
                return;
            }
            
            // 启动推流
            String firstLine = lines[0];
            String channelId = SipMessageParser.extractChannelId(firstLine);
            if (channelId == null && !device.getChannels().isEmpty()) {
                channelId = device.getChannels().get(0).getId();
            }
            
            // 查找test.mp4文件：优先在java/src/main目录，然后项目根目录，最后向上查找
            String avcaptureUrl = null;
            java.util.List<String> searchPaths = new java.util.ArrayList<>();
            
            // 方法1: 基于类文件位置计算可能的查找路径
            try {
                java.net.URL classUrl = InviteHandler.class.getProtectionDomain()
                    .getCodeSource().getLocation();
                if (classUrl != null && "file".equals(classUrl.getProtocol())) {
                    java.io.File classFile = new java.io.File(classUrl.toURI());
                    java.io.File baseDir;
                    
                    if (classFile.isFile() && classFile.getName().endsWith(".jar")) {
                        // JAR文件：在JAR所在目录及其父目录查找
                        baseDir = classFile.getParentFile();
                        // 尝试JAR目录、java目录、项目根目录
                        java.io.File[] searchDirs = {
                            baseDir,  // target目录
                            baseDir.getParentFile(),  // java目录
                            baseDir.getParentFile() != null ? baseDir.getParentFile().getParentFile() : null  // 项目根目录
                        };
                        for (java.io.File dir : searchDirs) {
                            if (dir != null && dir.exists()) {
                                searchPaths.add(new java.io.File(dir, "test.mp4").getAbsolutePath());
                            }
                        }
                        // 添加java/src/main/test.mp4
                        if (baseDir.getParentFile() != null) {
                            java.io.File javaDir = baseDir.getParentFile();
                            searchPaths.add(new java.io.File(javaDir, "src/main/test.mp4").getAbsolutePath());
                        }
                    } else {
                        // 类文件：java/src/main/java/com/gb28181/simulator/device/
                        // 找到java目录和项目根目录
                        baseDir = classFile.getParentFile();  // device/
                        for (int i = 0; i < 3 && baseDir != null; i++) {
                            baseDir = baseDir.getParentFile();  // 到java目录
                        }
                        if (baseDir != null && baseDir.exists()) {
                            // java/src/main/test.mp4
                            searchPaths.add(new java.io.File(baseDir, "src/main/test.mp4").getAbsolutePath());
                            // 项目根目录
                            java.io.File projectRoot = baseDir.getParentFile();
                            if (projectRoot != null && projectRoot.exists()) {
                                searchPaths.add(new java.io.File(projectRoot, "test.mp4").getAbsolutePath());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略异常，继续尝试其他方法
            }
            
            // 方法2: 从当前工作目录向上查找
            java.io.File currentDir = new java.io.File(System.getProperty("user.dir"));
            int maxLevels = 5;
            for (int level = 0; level < maxLevels && currentDir != null; level++) {
                searchPaths.add(new java.io.File(currentDir, "test.mp4").getAbsolutePath());
                java.io.File parentDir = currentDir.getParentFile();
                if (parentDir == null || parentDir.equals(currentDir)) {
                    break;  // 已到达根目录
                }
                currentDir = parentDir;
            }
            
            // 按顺序查找
            for (String path : searchPaths) {
                java.io.File testFile = new java.io.File(path);
                if (testFile.exists() && testFile.isFile()) {
                    avcaptureUrl = path;
                    break;
                }
            }
            
            if (avcaptureUrl != null) {
                System.out.println("  使用test.mp4文件: " + avcaptureUrl);
            } else {
                System.out.println("  错误: 未找到test.mp4文件");
                System.out.println("  已查找的主要位置:");
                int count = 0;
                for (String path : searchPaths) {
                    if (count++ < 5) {  // 只显示前5个
                        System.out.println("    - " + path);
                    }
                }
            }
            
            int targetPort = videoPort != null ? videoPort : 5004;
            if (ssrc == null || ssrc.isEmpty()) {
                ssrc = String.valueOf(System.currentTimeMillis() % 100000000);
            }
            
            if (avcaptureUrl != null) {
                boolean success = device.startStreamPush(channelId, avcaptureUrl, ip, targetPort, ssrc);
                if (success) {
                    System.out.println("✓ 推流已启动到 " + ip + ":" + targetPort);
                    
                    // 记录Call-ID到session_key的映射
                    String callIdValue = null;
                    for (String line : lines) {
                        if (line.startsWith("Call-ID:")) {
                            callIdValue = line.substring(8).trim();
                            break;
                        }
                    }
                    if (callIdValue != null && !callIdValue.isEmpty()) {
                        String sessionKey = channelId + "@" + ip + ":" + targetPort;
                        device.addCallIdToSession(callIdValue, sessionKey);
                    }
                }
            } else {
                System.out.println("✗ 无法启动推流：未找到test.mp4文件");
            }
        }
    }
}


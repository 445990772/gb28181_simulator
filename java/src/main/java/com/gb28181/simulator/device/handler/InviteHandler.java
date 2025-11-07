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
            
            // 查找resources目录（用于查找mp4文件）
            // 注意：startStreamPush方法会自己查找所有mp4文件，这里只需要传递一个占位符
            String avcaptureUrl = null;  // 可以为null，startStreamPush会自己查找所有mp4文件
            
            int targetPort = videoPort != null ? videoPort : 5004;
            if (ssrc == null || ssrc.isEmpty()) {
                ssrc = String.valueOf(System.currentTimeMillis() % 100000000);
            }
            
            // 启动推流（startStreamPush会自己查找resources目录下的所有mp4文件）
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
            } else {
                System.out.println("✗ 无法启动推流：未找到mp4文件");
            }
        }
    }
}


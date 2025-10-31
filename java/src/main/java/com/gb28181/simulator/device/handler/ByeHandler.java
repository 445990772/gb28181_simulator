package com.gb28181.simulator.device.handler;

import com.gb28181.simulator.device.GB28181Device;
import com.gb28181.simulator.sip.SipMessageBuilder;
import com.gb28181.simulator.sip.SipMessageParser;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * BYE处理类（处理平台结束点播）
 */
public class ByeHandler {
    
    /**
     * 消息打印接口
     */
    @FunctionalInterface
    public interface MessagePrinter {
        void print(String deviceId, String direction, String message, SocketAddress addr);
    }
    
    /**
     * 处理BYE请求
     */
    public static void handleBye(GB28181Device device, String[] lines, SocketAddress addr,
                                 MessagePrinter printSipMessage) {
        System.out.println("\n收到平台BYE指令 (设备: " + device.deviceId + ")");
        
        // 发送200 OK响应
        String contactIp = device.contactIp != null ? device.contactIp : device.localIp;
        String byeResponse = SipMessageBuilder.createMessageResponse(lines, contactIp);
        
        try {
            byte[] responseData = byeResponse.getBytes(StandardCharsets.UTF_8);
            device.getSocket().send(new DatagramPacket(responseData, responseData.length,
                ((InetSocketAddress) addr).getAddress(), ((InetSocketAddress) addr).getPort()));
            printSipMessage.print(device.deviceId, "send", byeResponse, addr);
        } catch (IOException e) {
            System.err.println("✗ 发送BYE响应失败: " + e.getMessage());
            return;
        }
        
        // 优先按Call-ID精确停止
        String callIdValue = null;
        for (String line : lines) {
            if (line.startsWith("Call-ID:")) {
                callIdValue = line.substring(8).trim();
                break;
            }
        }
        
        boolean stopped = false;
        if (callIdValue != null && !callIdValue.isEmpty()) {
            List<String> sessionKeys = device.getSessionKeysByCallId(callIdValue);
            if (!sessionKeys.isEmpty()) {
                for (String sessKey : new java.util.ArrayList<>(sessionKeys)) {
                    System.out.println("  停止会话推流: " + sessKey);
                    device.stopStreamPushBySessionKeyPublic(sessKey);
                }
                device.removeCallIdMapping(callIdValue);
                stopped = true;
            }
        }
        
        if (!stopped) {
            // 按通道ID停止
            String firstLine = lines[0];
            String channelId = SipMessageParser.extractChannelId(firstLine);
            if (channelId != null && !channelId.isEmpty()) {
                System.out.println("  停止通道推流: " + channelId);
                device.stopStreamPush(channelId);
            } else {
                System.out.println("  停止所有推流");
                device.stopAllStreamPush();
            }
        }
    }
}


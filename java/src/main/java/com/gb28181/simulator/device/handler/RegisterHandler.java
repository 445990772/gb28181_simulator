package com.gb28181.simulator.device.handler;

import com.gb28181.simulator.device.GB28181Device;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * 注册处理类
 */
public class RegisterHandler {
    
    /**
     * 处理注册响应和心跳回复
     */
    public static void handleRegisterResponse(GB28181Device device, String[] lines) {
        String firstLine = lines[0];
        if (firstLine.contains("200 OK")) {
            // 检查是否是REGISTER响应
            String allLines = String.join("\r\n", lines);
            if (allLines.contains("REGISTER")) {
                device.isRegistered = true;
                device.lastHeartbeat = System.currentTimeMillis() / 1000;
            } 
            // 检查是否是MESSAGE响应（心跳回复）
            else if (allLines.contains("MESSAGE")) {
                // 收到心跳回复，确认设备已注册
                device.isRegistered = true;
                device.lastHeartbeat = System.currentTimeMillis() / 1000;
            }
        } else if (firstLine.contains("401 Unauthorized")) {
            device.isRegistered = false;
        }
    }
    
    /**
     * 发送注册请求
     */
    public static void sendRegisterRequest(GB28181Device device, DatagramSocket socket) throws IOException {
        String registerRequest = device.createRegisterRequest();
        byte[] data = registerRequest.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length,
            InetAddress.getByName(device.serverIp), device.serverPort));
    }
    
    /**
     * 检查并执行重注册（如果需要）
     * 
     * @param device 设备
     * @param socket Socket
     * @param lastRegister 上次注册时间（秒）
     * @param currentTime 当前时间（秒）
     * @return 新的lastRegister时间
     */
    public static long checkAndReRegister(GB28181Device device, DatagramSocket socket, 
                                         long lastRegister, long currentTime) {
        // 如果未注册或注册过期，重新注册
        if (!device.isRegistered || (currentTime - lastRegister) >= device.registerExpires) {
            if ((currentTime - lastRegister) >= device.retryInterval) {
                try {
                    sendRegisterRequest(device, socket);
                    return currentTime;
                } catch (IOException e) {
                    // 忽略，下次重试
                }
            }
        }
        return lastRegister;
    }
}


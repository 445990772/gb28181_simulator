package com.gb28181.simulator.device.handler;

import com.gb28181.simulator.device.GB28181Device;

/**
 * 心跳处理类
 */
public class HeartbeatHandler {
    
    /**
     * 检查并发送心跳（如果需要）
     * 
     * @param device 设备
     * @param currentTime 当前时间（秒）
     * @param messagePrinter 消息打印函数
     * @return 是否发送了心跳
     */
    public static boolean checkAndSendHeartbeat(GB28181Device device, long currentTime,
                                                GB28181Device.MessagePrinter messagePrinter) {
        // 注册成功后每30秒发送心跳消息
        if (device.isRegistered && device.lastHeartbeat != null) {
            if ((currentTime - device.lastHeartbeat) >= device.heartbeatInterval) {
                device.sendKeepalive(messagePrinter);
                device.lastHeartbeat = currentTime;
                return true;
            }
        }
        return false;
    }
}


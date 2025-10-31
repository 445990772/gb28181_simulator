package com.gb28181.simulator;

import com.gb28181.simulator.device.GB28181DeviceSimulator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * GB28181设备模拟器主程序
 */
public class GB28181DeviceSimulatorMain {
    
    // 共享的BufferedReader，不要关闭System.in
    private static BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
    
    /**
     * 读取用户输入
     */
    private static String readInput(String prompt) {
        System.out.print(prompt);
        try {
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
    
    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("GB28181 设备模拟器");
        System.out.println("=".repeat(60));
        
        GB28181DeviceSimulator simulator = new GB28181DeviceSimulator();
        
        // 配置参数
        String serverIp = readInput("\n请输入GB28181平台服务器IP（默认: 192.168.32.84）: ").trim();
        if (serverIp.isEmpty()) {
            serverIp = "192.168.32.84";
        }
        
        int serverPort = readIntInput("请输入GB28181平台服务器端口（默认: 8809）: ", 8809);
        
        String password = readInput("请输入设备密码（默认: 123456）: ").trim();
        if (password.isEmpty()) {
            password = "123456";
        }
        
        int deviceCount = readIntInput("请输入要模拟的设备数量（默认: 3）: ", 3);
        
        int channelCount = readIntInput("请输入每个设备的通道数（默认: 1）: ", 1);
        
        // 创建设备
        String baseDeviceId = "3402000000132000";
        int basePort = 15060;
        
        // 如果服务器IP是外部地址，使用0.0.0.0作为本地IP
        String defaultLocalIp = "127.0.0.1";
        if (!"127.0.0.1".equals(serverIp) && !"localhost".equals(serverIp)) {
            defaultLocalIp = "0.0.0.0";
        }
        
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
        
        // 显示配置信息
        System.out.println("\n" + "=".repeat(60));
        System.out.println("配置摘要:");
        System.out.println("  平台地址: " + serverIp + ":" + serverPort);
        System.out.println("  设备密码: " + password);
        System.out.println("  设备数量: " + deviceCount);
        System.out.println("  每设备通道数: " + channelCount);
        System.out.println("  总通道数: " + (deviceCount * channelCount));
        System.out.println("=".repeat(60));
        
        // 启动所有设备
        simulator.startAllDevices();
    }
}


package com.gb28181.simulator.device;

import com.gb28181.simulator.device.handler.ByeHandler;
import com.gb28181.simulator.device.handler.CatalogHandler;
import com.gb28181.simulator.device.handler.HeartbeatHandler;
import com.gb28181.simulator.device.handler.InviteHandler;
import com.gb28181.simulator.device.handler.RegisterHandler;
import com.gb28181.simulator.sip.SipMessageBuilder;
import com.gb28181.simulator.sip.SipMessageParser;

import java.io.IOException;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * GB28181设备模拟器
 */
public class GB28181DeviceSimulator {
    private final List<GB28181Device> devices = new ArrayList<>();
    private volatile boolean running = false;
    
    /**
     * 创建设备
     */
    public GB28181Device createDevice(String deviceId, String deviceName, String localIp,
                                      int localPort, String serverIp, int serverPort,
                                      String password, int channelCount) {
        GB28181Device device = new GB28181Device(deviceId, deviceName, localIp, localPort,
                                                 serverIp, serverPort, password);
        
        // 创建IPC通道（模拟摄像头通道）
        // 通道ID规则：通道ID = 设备ID + (100 + 序号-1)，保持20位
        // 例如: 设备ID=34020000001320000001，通道1 => +100 = 34020000001320000101（20位）
        for (int i = 0; i < channelCount; i++) {
            try {
                // 使用BigInteger处理20位大整数（Java的long类型无法处理20位数字）
                BigInteger baseNum = new BigInteger(deviceId);
                BigInteger channelNum = baseNum.add(BigInteger.valueOf(100 + i));
                String channelId = String.format("%020d", channelNum);
                
                Channel channel = new Channel(channelId, deviceName + "-通道" + (i + 1));
                // 严格按照GB28181标准IPC设备字段值设置
                channel.setAttribute("manufacturer", "IPC");  // 制造商：标准IPC标识
                channel.setAttribute("model", "IPC");  // 型号：标准IPC标识
                channel.setAttribute("owner", deviceId);  // 所有者ID：通常与设备ID相同
                channel.setAttribute("civil_code", deviceId.length() >= 6 ? deviceId.substring(0, 6) : "340200");  // 行政区域代码：取设备ID前6位
                channel.setAttribute("address", "Address");  // 地址信息
                channel.setAttribute("parental", "0");  // 是否为父设备：0-否（通道不是父设备），1-是
                channel.setAttribute("parent_id", deviceId);  // 父设备ID：通道的父设备就是主设备
                channel.setAttribute("safety_way", "0");  // 安全方式：0-不设防，1-周界设防
                channel.setAttribute("register_way", "1");  // 注册方式：1-RCF3831标准鉴权注册
                channel.setAttribute("secrecy", "0");  // 保密属性：0-不涉密，1-涉密
                channel.setAttribute("status", "ON");  // 设备状态：ON-正常，OFF-故障
                channel.setAttribute("online", "ON");  // 在线状态：ON-在线，OFF-离线
                channel.setAttribute("alarm_status", "READY");  // 报警状态：READY-正常，ALARM-报警
                
                device.addChannel(channel);
            } catch (NumberFormatException e) {
                // 回退：字符串拼接（与×100+序号等价）
                // 如果无法转数字，则替换末两位为 (10 + i)
                String channelId;
                if (deviceId.length() >= 2) {
                    channelId = deviceId.substring(0, deviceId.length() - 2) + String.format("%02d", 10 + i);
                } else {
                    channelId = deviceId + String.format("%02d", 10 + i);
                }
                
                Channel channel = new Channel(channelId, deviceName + "-通道" + (i + 1));
                // 严格按照GB28181标准IPC设备字段值设置
                channel.setAttribute("manufacturer", "IPC");
                channel.setAttribute("model", "IPC");
                channel.setAttribute("owner", deviceId);
                channel.setAttribute("civil_code", deviceId.length() >= 6 ? deviceId.substring(0, 6) : "340200");
                channel.setAttribute("address", "Address");
                channel.setAttribute("parental", "0");
                channel.setAttribute("parent_id", deviceId);
                channel.setAttribute("safety_way", "0");
                channel.setAttribute("register_way", "1");
                channel.setAttribute("secrecy", "0");
                channel.setAttribute("status", "ON");
                channel.setAttribute("online", "ON");
                channel.setAttribute("alarm_status", "READY");
                
                device.addChannel(channel);
            }
        }
        
        devices.add(device);
        System.out.println("✓ 创建设备: " + deviceId + " (" + deviceName + "), 通道数: " + channelCount);
        return device;
    }
    
    /**
     * 打印SIP消息
     */
    public void printSipMessage(String deviceId, String direction, String message, SocketAddress addr) {
        String green = "\033[92m";
        String blue = "\033[94m";
        String reset = "\033[0m";
        
        String directionMark = ">>> 发送".equals(direction) ? ">>> 发送" : "<<< 接收";
        String addrInfo = addr != null ? " -> " + addr : "";
        
        String colorStart = "recv".equals(direction) ? green : blue;
        String colorEnd = reset;
        
        System.out.println(colorStart + "\n" + "=".repeat(60) + colorEnd);
        System.out.println(colorStart + "[" + deviceId + "] " + directionMark + addrInfo + colorEnd);
        System.out.println(colorStart + "=".repeat(60) + colorEnd);
        
        String[] lines = message.split("\r\n");
        int printLines = Math.min(50, lines.length);
        for (int i = 0; i < printLines; i++) {
            if (!lines[i].trim().isEmpty()) {
                System.out.println(colorStart + "  " + lines[i] + colorEnd);
            }
        }
        if (lines.length > 50) {
            System.out.println(colorStart + "  ... (还有 " + (lines.length - 50) + " 行)" + colorEnd);
        }
        System.out.println(colorStart + "=".repeat(60) + "\n" + colorEnd);
    }
    
    /**
     * 处理SIP消息
     */
    public void processSipMessage(GB28181Device device, byte[] data, SocketAddress addr) {
        try {
            String messageText = new String(data, StandardCharsets.UTF_8);
            String[] lines = SipMessageParser.parseLines(messageText);
            
            if (lines.length == 0) {
                return;
            }
            
            // 仅在目录订阅相关时打印
            boolean shouldPrint = false;
            if (lines[0].startsWith("SUBSCRIBE")) {
                for (String line : lines) {
                    if (line.startsWith("Event:") && line.split(":", 2)[1].trim().equalsIgnoreCase("catalog")) {
                        shouldPrint = true;
                        break;
                    }
                }
            } else if (lines[0].startsWith("MESSAGE")) {
                if (messageText.contains("Catalog")) {
                    shouldPrint = true;
                }
            }
            
            if (shouldPrint) {
                printSipMessage(device.deviceId, "recv", messageText, addr);
            }
            
            String requestBody = SipMessageParser.extractBody(lines);
            String firstLine = lines[0];
            
            // 处理不同类型的消息
            if (firstLine.startsWith("SIP/2.0")) {
                // 响应消息 - 注册响应
                RegisterHandler.handleRegisterResponse(device, lines);
            } else if (firstLine.startsWith("SUBSCRIBE")) {
                // SUBSCRIBE请求 - Catalog订阅等
                handleSubscribe(device, lines, addr);
            } else if (firstLine.startsWith("MESSAGE")) {
                // MESSAGE请求
                handleMessage(device, requestBody, lines, addr);
            } else if (firstLine.startsWith("INVITE")) {
                // INVITE请求
                InviteHandler.handleInvite(device, lines, addr, this::printSipMessage);
            } else if (firstLine.startsWith("BYE")) {
                // BYE请求 - 平台结束点播
                ByeHandler.handleBye(device, lines, addr, this::printSipMessage);
            }
        } catch (Exception e) {
            System.err.println("✗ 处理SIP消息出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 处理SUBSCRIBE请求
     */
    private void handleSubscribe(GB28181Device device, String[] lines, SocketAddress addr) {
        String eventType = SipMessageParser.extractEventType(lines);
        
        if ("catalog".equals(eventType)) {
            CatalogHandler.handleSubscribeCatalog(device, lines, addr, this::printSipMessage);
        } else {
            // 其他类型的SUBSCRIBE，发送200 OK
            String contactIp = device.contactIp != null ? device.contactIp : device.localIp;
            String response = SipMessageBuilder.createSubscribeResponse(lines, contactIp, null, device.deviceId);
            try {
                byte[] responseData = response.getBytes(StandardCharsets.UTF_8);
                device.getSocket().send(new DatagramPacket(responseData, responseData.length,
                    ((InetSocketAddress) addr).getAddress(), ((InetSocketAddress) addr).getPort()));
            } catch (IOException e) {
                System.err.println("✗ 发送SUBSCRIBE响应失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 处理MESSAGE请求
     */
    private void handleMessage(GB28181Device device, String requestBody, String[] lines, SocketAddress addr) {
        System.out.println("\n收到MESSAGE请求 (设备: " + device.deviceId + ")");
        
        String cmdType = SipMessageParser.extractCmdType(requestBody);
        
        if (requestBody != null && requestBody.length() > 300) {
            System.out.println("  请求体预览: " + requestBody.substring(0, 300) + "...");
        }
        System.out.println("  提取的CmdType: " + cmdType);
        
        String cmdTypeLower = cmdType != null ? cmdType.toLowerCase() : null;
        
        if ("deviceinfo".equals(cmdTypeLower)) {
            handleDeviceInfo(device, requestBody, lines, addr);
        } else if ("configdownload".equals(cmdTypeLower)) {
            handleConfigDownload(device, requestBody, lines, addr);
        } else if ("catalog".equals(cmdTypeLower)) {
            CatalogHandler.handleCatalogMessage(device, requestBody, lines, addr, this::printSipMessage);
        } else {
            System.out.println("  请求类型: " + (cmdType != null ? cmdType : "未知") + " (未匹配到已知类型)");
            String contactIp = device.contactIp != null ? device.contactIp : device.localIp;
            String response = SipMessageBuilder.createMessageResponse(lines, contactIp);
            try {
                byte[] responseData = response.getBytes(StandardCharsets.UTF_8);
                device.getSocket().send(new DatagramPacket(responseData, responseData.length,
                    ((InetSocketAddress) addr).getAddress(), ((InetSocketAddress) addr).getPort()));
                printSipMessage(device.deviceId, "send", response, addr);
            } catch (IOException e) {
                System.err.println("✗ 发送响应失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 处理DeviceInfo请求
     */
    private void handleDeviceInfo(GB28181Device device, String requestBody, String[] lines, SocketAddress addr) {
        System.out.println("  请求类型: 获取设备信息（DeviceInfo）");
        
        String sn = SipMessageParser.extractSn(requestBody);
        String deviceInfoXml = XmlGenerator.createDeviceInfoXml(device.deviceId, device.deviceName, Integer.parseInt(sn));
        
        String contactIp = device.contactIp != null ? device.contactIp : device.localIp;
        String response = SipMessageBuilder.createMessageResponse(lines, contactIp);
        int bodyLength = deviceInfoXml.getBytes(StandardCharsets.UTF_8).length;
        response = response.replace("Content-Length: 0",
            "Content-Type: Application/MANSCDP+xml\r\nContent-Length: " + bodyLength);
        response += "\r\n" + deviceInfoXml;
        
        try {
            byte[] responseData = response.getBytes(StandardCharsets.UTF_8);
            device.getSocket().send(new DatagramPacket(responseData, responseData.length,
                ((InetSocketAddress) addr).getAddress(), ((InetSocketAddress) addr).getPort()));
            printSipMessage(device.deviceId, "send", response, addr);
            System.out.println("✓ 已发送设备信息");
        } catch (Exception e) {
            System.err.println("✗ 发送设备信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理ConfigDownload请求
     */
    private void handleConfigDownload(GB28181Device device, String requestBody, String[] lines, SocketAddress addr) {
        System.out.println("  请求类型: 配置下载（ConfigDownload）");
        
        String sn = SipMessageParser.extractSn(requestBody);
        String configXml = XmlGenerator.createConfigDownloadXml(
            device.deviceId, device.deviceName, device.localIp,
            device.localPort, device.password, Integer.parseInt(sn)
        );
        
        String contactIp = device.contactIp != null ? device.contactIp : device.localIp;
        String response = SipMessageBuilder.createMessageResponse(lines, contactIp);
        int bodyLength = configXml.getBytes(StandardCharsets.UTF_8).length;
        response = response.replace("Content-Length: 0",
            "Content-Type: Application/MANSCDP+xml\r\nContent-Length: " + bodyLength);
        response += "\r\n" + configXml;
        
        try {
            byte[] responseData = response.getBytes(StandardCharsets.UTF_8);
            device.getSocket().send(new DatagramPacket(responseData, responseData.length,
                ((InetSocketAddress) addr).getAddress(), ((InetSocketAddress) addr).getPort()));
            printSipMessage(device.deviceId, "send", response, addr);
            System.out.println("✓ 已发送配置信息");
        } catch (Exception e) {
            System.err.println("✗ 发送配置信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 设备线程函数
     */
    public void deviceThread(GB28181Device device) {
        try {
            // 创建UDP socket
            DatagramSocket socket = new DatagramSocket(null);
            
            // 如果服务器IP是外部地址，绑定到0.0.0.0而不是127.0.0.1
            String bindIp = device.localIp;
            if ("127.0.0.1".equals(bindIp) && !"127.0.0.1".equals(device.serverIp) && !"localhost".equals(device.serverIp)) {
                bindIp = "0.0.0.0";
            }
            
            try {
                socket.bind(new InetSocketAddress(bindIp, device.localPort));
                socket.setSoTimeout(1000);
            } catch (SocketException e) {
                System.err.println("✗ 绑定Socket失败: " + e.getMessage());
                return;
            }
            
            device.setSocket(socket);
            
            // 如果绑定到0.0.0.0，需要获取实际对外可见的IP地址
            if ("0.0.0.0".equals(bindIp)) {
                try {
                    DatagramSocket tempSocket = new DatagramSocket();
                    tempSocket.connect(InetAddress.getByName(device.serverIp), device.serverPort);
                    String actualIp = tempSocket.getLocalAddress().getHostAddress();
                    tempSocket.close();
                    device.contactIp = actualIp;
                    System.out.println("✓ 设备 " + device.deviceId + " 监听 " + bindIp + ":" + device.localPort +
                        "，Contact地址: " + device.contactIp + ":" + device.localPort);
                } catch (Exception e) {
                    System.out.println("⚠ 获取实际IP地址失败: " + e.getMessage() + "，使用localIp");
                    device.contactIp = device.localIp;
                }
            } else {
                device.contactIp = bindIp;
                System.out.println("✓ 设备 " + device.deviceId + " 监听 " + bindIp + ":" + device.localPort);
            }
            
            // 注册
            try {
                RegisterHandler.sendRegisterRequest(device, socket);
            } catch (IOException e) {
                System.err.println("✗ 发送注册请求失败: " + e.getMessage());
            }
            
            long lastRegister = System.currentTimeMillis() / 1000;
            device.lastHeartbeat = System.currentTimeMillis() / 1000;
            
            // 消息接收循环
            while (running) {
                try {
                    byte[] buffer = new byte[4096];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    
                    byte[] receivedData = Arrays.copyOf(packet.getData(), packet.getLength());
                    processSipMessage(device, receivedData, packet.getSocketAddress());
                    
                } catch (SocketTimeoutException e) {
                    // 超时，检查是否需要重注册或心跳
                    long currentTime = System.currentTimeMillis() / 1000;
                    
                    // 检查并发送心跳
                    HeartbeatHandler.checkAndSendHeartbeat(device, currentTime, this::printSipMessage);
                    
                    // 检查并重注册
                    lastRegister = RegisterHandler.checkAndReRegister(device, socket, lastRegister, currentTime);
                } catch (IOException e) {
                    if (running) {
                        System.err.println("✗ 设备 " + device.deviceId + " 接收消息出错: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("✗ 设备 " + device.deviceId + " 线程出错: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (device.getSocket() != null && !device.getSocket().isClosed()) {
                device.getSocket().close();
            }
            device.stopAllStreamPush();
        }
    }
    
    /**
     * 启动所有设备
     */
    public void startAllDevices() {
        running = true;
        
        System.out.println("\n启动 " + devices.size() + " 个设备...");
        
        List<Thread> threads = new ArrayList<>();
        for (GB28181Device device : devices) {
            Thread thread = new Thread(() -> deviceThread(device), "Device-" + device.deviceId);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
            try {
                Thread.sleep(500); // 避免端口冲突
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        System.out.println("\n✓ 所有设备已启动，按 Ctrl+C 停止");
        
        // 保持运行
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在停止所有设备...");
            running = false;
            for (GB28181Device device : devices) {
                device.stopAllStreamPush();
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("✓ 已停止");
        }));
        
        try {
            while (running) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}


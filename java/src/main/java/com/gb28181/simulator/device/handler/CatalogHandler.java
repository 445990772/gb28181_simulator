package com.gb28181.simulator.device.handler;

import com.gb28181.simulator.device.GB28181Device;
import com.gb28181.simulator.device.XmlGenerator;
import com.gb28181.simulator.sip.SipMessageBuilder;
import com.gb28181.simulator.sip.SipMessageParser;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catalog处理类（处理SUBSCRIBE catalog和MESSAGE catalog）
 */
public class CatalogHandler {
    
    /**
     * 消息打印接口
     */
    @FunctionalInterface
    public interface MessagePrinter {
        void print(String deviceId, String direction, String message, SocketAddress addr);
    }
    
    /**
     * 处理Catalog订阅（SUBSCRIBE catalog）
     */
    public static void handleSubscribeCatalog(GB28181Device device, String[] lines, SocketAddress addr,
                                             MessagePrinter printSipMessage) {
        long requestTime = System.currentTimeMillis();
        System.out.println("\n收到SUBSCRIBE请求 (事件: catalog, 设备: " + device.deviceId + ")");
        
        try {
            String requestBody = SipMessageParser.extractBody(lines);
            
            // 解析平台目录接收者编码、订阅者tag、Expires
            String platformId = SipMessageParser.extractPlatformIdFromSubscribe(lines);
            String subscriberTag = SipMessageParser.extractSubscriberTag(lines);
            Integer expires = SipMessageParser.extractExpires(lines);
            
            // 提取SN和InfoID
            String sn = SipMessageParser.extractSnFromSubscribe(requestBody);
            String infoId = SipMessageParser.extractInfoIdFromSubscribe(requestBody);
            
            // 从请求体提取DeviceID（如果存在）
            String deviceIdForOk = device.deviceId;
            if (requestBody != null && requestBody.contains("<DeviceID>")) {
                Pattern pattern = Pattern.compile("<DeviceID>(.*?)</DeviceID>", Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(requestBody);
                if (matcher.find()) {
                    deviceIdForOk = matcher.group(1).trim();
                }
            }
            
            // 立即发送200 OK响应（含Result=OK的MANSCDP体）
            String contactIp = device.contactIp != null ? device.contactIp : device.localIp;
            String subscribeResponse = SipMessageBuilder.createSubscribeResponse(lines, contactIp, sn, deviceIdForOk);
            
            long responseSendTime = System.currentTimeMillis();
            byte[] responseData = subscribeResponse.getBytes(StandardCharsets.UTF_8);
            device.getSocket().send(new DatagramPacket(responseData, responseData.length,
                ((InetSocketAddress) addr).getAddress(), ((InetSocketAddress) addr).getPort()));
            
            double delayMs = (responseSendTime - requestTime);
            System.out.println("✓ 已立即发送SUBSCRIBE 200 OK响应（延迟: " + String.format("%.2f", delayMs) + " 毫秒）");
            printSipMessage.print(device.deviceId, "send", subscribeResponse, addr);
            
            System.out.println("  使用的SN: " + sn);
            if (infoId != null) {
                System.out.println("  提取的InfoID: " + infoId);
            }
            
            // 立即发送NOTIFY消息（包含Catalog信息）
            String catalogXml = XmlGenerator.createCatalogXml(device.deviceId, device.getChannels(),
                Integer.parseInt(sn), infoId);
            
            String notifyRequest = SipMessageBuilder.createNotifyRequest(
                device.deviceId,
                device.localIp,
                device.localPort,
                device.serverIp,
                device.serverPort,
                platformId,
                catalogXml,
                contactIp,
                subscriberTag,
                expires
            );
            
            long notifySendTime = System.currentTimeMillis();
            byte[] notifyData = notifyRequest.getBytes(StandardCharsets.UTF_8);
            device.getSocket().send(new DatagramPacket(notifyData, notifyData.length,
                ((InetSocketAddress) addr).getAddress(), ((InetSocketAddress) addr).getPort()));
            
            double elapsedMs = (notifySendTime - requestTime);
            System.out.println("✓ 已发送NOTIFY消息（包含Catalog信息）");
            System.out.println("  发送到: " + ((InetSocketAddress) addr).getAddress().getHostAddress() + ":" + ((InetSocketAddress) addr).getPort());
            System.out.println("  总延迟: " + String.format("%.2f", elapsedMs) + " 毫秒");
            System.out.println("  通道数量: " + device.getChannels().size());
            printSipMessage.print(device.deviceId, "send", notifyRequest, addr);
            
        } catch (Exception e) {
            System.err.println("✗ 处理SUBSCRIBE请求时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 处理Catalog MESSAGE请求
     */
    public static void handleCatalogMessage(GB28181Device device, String requestBody, String[] lines, SocketAddress addr,
                                           MessagePrinter printSipMessage) {
        System.out.println("  请求类型: 查询通道目录（Catalog）");
        
        try {
            String sn = SipMessageParser.extractSn(requestBody);
            String infoId = SipMessageParser.extractInfoId(requestBody);
            System.out.println("  提取的SN: " + sn);
            if (infoId != null) {
                System.out.println("  提取的InfoID: " + infoId);
            }
            
            // 步骤1：先对平台的MESSAGE查询立即回复200 OK
            String contactIp = device.contactIp != null ? device.contactIp : device.localIp;
            String okResponse = SipMessageBuilder.createMessageResponse(lines, contactIp);
            try {
                byte[] responseData = okResponse.getBytes(StandardCharsets.UTF_8);
                device.getSocket().send(new DatagramPacket(responseData, responseData.length,
                    ((InetSocketAddress) addr).getAddress(), ((InetSocketAddress) addr).getPort()));
                printSipMessage.print(device.deviceId, "send", okResponse, addr);
                System.out.println("✓ 已发送第一步 200 OK");
            } catch (IOException e) {
                System.err.println("✗ 发送200 OK失败: " + e.getMessage());
                return;
            }
            
            // 步骤2：再主动发送一个MESSAGE携带Catalog响应XML
            String catalogXml = XmlGenerator.createCatalogXml(device.deviceId, device.getChannels(),
                Integer.parseInt(sn), infoId);
            
            String platformId = SipMessageParser.extractPlatformId(lines);
            String catalogMessage = SipMessageBuilder.createCatalogMessage(device.deviceId,
                contactIp, device.localPort, device.serverIp, device.serverPort,
                platformId, catalogXml, contactIp);
            
            try {
                byte[] messageData = catalogMessage.getBytes(StandardCharsets.UTF_8);
                device.getSocket().send(new DatagramPacket(messageData, messageData.length,
                    InetAddress.getByName(device.serverIp), device.serverPort));
                printSipMessage.print(device.deviceId, "send", catalogMessage,
                    new InetSocketAddress(device.serverIp, device.serverPort));
                System.out.println("✓ 已发送第二步 Catalog MESSAGE（通道数: " + device.getChannels().size() + "）");
            } catch (IOException e) {
                System.err.println("✗ 发送Catalog MESSAGE失败: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("✗ 处理Catalog请求时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
}


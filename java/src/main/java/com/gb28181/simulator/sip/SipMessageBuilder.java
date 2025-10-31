package com.gb28181.simulator.sip;

import java.util.UUID;

/**
 * SIP消息构建器
 */
public class SipMessageBuilder {
    
    /**
     * 生成Call-ID
     */
    public static String generateCallId(String localIp) {
        return UUID.randomUUID().toString().substring(0, 8) + "@" + localIp;
    }
    
    /**
     * 生成CSeq
     */
    public static int generateCSeq() {
        return 1;
    }
    
    /**
     * 生成Branch
     */
    public static String generateBranch() {
        return "z9hG4bK" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
    
    /**
     * 创建REGISTER请求
     */
    public static String createRegisterRequest(String deviceId, String localIp, int localPort,
                                              String serverIp, int serverPort, String password,
                                              String tag, String contactIp) {
        String callId = generateCallId(localIp);
        int cseq = generateCSeq();
        String branch = generateBranch();
        
        // 计算Authorization
        String realm = "3402000000";
        String nonce = String.valueOf(System.currentTimeMillis() / 1000);
        String uri = "sip:" + serverIp + ":" + serverPort;
        
        String response = DigestAuth.calculateResponse(deviceId, realm, password, "REGISTER", uri, nonce);
        String auth = DigestAuth.generateAuthorizationHeader(deviceId, realm, nonce, uri, response);
        
        // Contact头和Via头使用contactIp（如果提供），否则使用localIp
        String contactAddress = (contactIp != null && !contactIp.isEmpty()) ? contactIp : localIp;
        String viaAddress = contactAddress;
        
        return String.format(
            "REGISTER sip:%s:%d SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP %s:%d;branch=%s\r\n" +
            "From: <sip:%s@%s:%d>;tag=%s\r\n" +
            "To: <sip:%s@%s:%d>\r\n" +
            "Call-ID: %s\r\n" +
            "CSeq: %d REGISTER\r\n" +
            "Contact: <sip:%s@%s:%d>\r\n" +
            "Authorization: %s\r\n" +
            "Max-Forwards: 70\r\n" +
            "User-Agent: GB28181-Device/1.0\r\n" +
            "Expires: 3600\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n",
            serverIp, serverPort,
            viaAddress, localPort, branch,
            deviceId, serverIp, serverPort, tag,
            deviceId, serverIp, serverPort,
            callId,
            cseq,
            deviceId, contactAddress, localPort,
            auth
        );
    }
    
    /**
     * 创建Keepalive MESSAGE请求
     */
    public static String createKeepaliveMessage(String deviceId, String localIp, int localPort,
                                                String serverIp, int serverPort, int sn,
                                                String keepaliveXml, String tag) {
        String callId = UUID.randomUUID().toString().substring(0, 32);
        String branch = generateBranch();
        int cseq = 1;
        
        int contentLength = keepaliveXml.getBytes().length;
        
        return String.format(
            "MESSAGE sip:%s:%d SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP %s:%d;branch=%s\r\n" +
            "From: <sip:%s@%s:%d>;tag=%s\r\n" +
            "To: <sip:%s@%s:%d>\r\n" +
            "Call-ID: %s\r\n" +
            "CSeq: %d MESSAGE\r\n" +
            "Content-Type: Application/MANSCDP+xml\r\n" +
            "Content-Length: %d\r\n" +
            "User-Agent: GB28181-Device/1.0\r\n" +
            "Max-Forwards: 70\r\n" +
            "\r\n" +
            "%s",
            serverIp, serverPort,
            localIp, localPort, branch,
            deviceId, serverIp, serverPort, tag,
            deviceId, serverIp, serverPort,
            callId,
            cseq,
            contentLength,
            keepaliveXml
        );
    }
    
    /**
     * 创建MESSAGE响应（200 OK）
     */
    public static String createMessageResponse(String[] requestLines, String contactIp) {
        String via = null;
        String fromLine = null;
        String toLine = null;
        String callId = null;
        String cseq = null;
        
        for (String line : requestLines) {
            if (line.startsWith("Via:")) {
                via = line.substring(4).trim();
            } else if (line.startsWith("From:")) {
                fromLine = line.substring(5).trim();
            } else if (line.startsWith("To:")) {
                toLine = line.substring(3).trim();
            } else if (line.startsWith("Call-ID:")) {
                callId = line.substring(8).trim();
            } else if (line.startsWith("CSeq:")) {
                cseq = line.substring(5).trim();
            }
        }
        
        // 如果提供了contactIp且Via中包含不可路由地址，替换为contactIp
        if (contactIp != null && via != null) {
            if (via.contains("0.0.0.0")) {
                String[] viaParts = via.split("\\s+");
                if (viaParts.length >= 2) {
                    String oldAddr = viaParts[1];
                    if (oldAddr.contains(":")) {
                        String portPart = oldAddr.split(":")[1].split(";")[0];
                        String newVia = "SIP/2.0/UDP " + contactIp + ":" + portPart;
                        if (oldAddr.contains(";")) {
                            String branchPart = oldAddr.split(";", 2)[1];
                            newVia += ";" + branchPart;
                        }
                        via = newVia;
                    }
                }
            }
        }
        
        // 从To提取tag（如果没有则生成新的）
        String toTag = null;
        if (toLine != null && toLine.contains(";tag=")) {
            toTag = toLine.split(";tag=")[1].split("\\s+")[0];
        } else {
            toTag = UUID.randomUUID().toString().substring(0, 32);
        }
        
        return String.format(
            "SIP/2.0 200 OK\r\n" +
            "Via: %s\r\n" +
            "From: %s\r\n" +
            "To: %s;tag=%s\r\n" +
            "Call-ID: %s\r\n" +
            "CSeq: %s\r\n" +
            "User-Agent: GB28181-Device/1.0\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n",
            via != null ? via : "",
            fromLine != null ? fromLine : "",
            toLine != null ? toLine : "",
            toTag,
            callId != null ? callId : "",
            cseq != null ? cseq : ""
        );
    }
    
    /**
     * 创建INVITE响应（200 OK，包含SDP，符合GB28181点播流程）
     */
    public static String createInviteResponse(String[] requestLines, String contactIp, String deviceId, int localPort) {
        String via = null;
        String fromLine = null;
        String toLine = null;
        String callId = null;
        String cseq = null;
        
        // 解析对端SDP以继承 y(SSRC)、f 参数
        boolean inSdp = false;
        java.util.List<String> sdpLines = new java.util.ArrayList<>();
        
        for (String line : requestLines) {
            if (line.startsWith("Via:")) {
                via = line.substring(4).trim();
            } else if (line.startsWith("From:")) {
                fromLine = line.substring(5).trim();
            } else if (line.startsWith("To:")) {
                toLine = line.substring(3).trim();
            } else if (line.startsWith("Call-ID:")) {
                callId = line.substring(8).trim();
            } else if (line.startsWith("CSeq:")) {
                cseq = line.substring(5).trim();
            } else if (line.trim().isEmpty() && !inSdp) {
                inSdp = true;
                continue;
            } else if (inSdp) {
                sdpLines.add(line);
            }
        }
        
        // 如果提供了contactIp且Via中包含不可路由地址，替换为contactIp
        if (contactIp != null && via != null) {
            if (via.contains("0.0.0.0")) {
                String[] viaParts = via.split("\\s+");
                if (viaParts.length >= 2) {
                    String oldAddr = viaParts[1];
                    if (oldAddr.contains(":")) {
                        String portPart = oldAddr.split(":")[1].split(";")[0];
                        String newVia = "SIP/2.0/UDP " + contactIp + ":" + portPart;
                        if (oldAddr.contains(";")) {
                            String branchPart = oldAddr.split(";", 2)[1];
                            newVia += ";" + branchPart;
                        }
                        via = newVia;
                    }
                }
            } else if (via.contains("127.0.0.1") && !"127.0.0.1".equals(contactIp)) {
                String[] viaParts = via.split("\\s+");
                if (viaParts.length >= 2) {
                    String oldAddr = viaParts[1];
                    if (oldAddr.contains(":")) {
                        String portPart = oldAddr.split(":")[1].split(";")[0];
                        String newVia = "SIP/2.0/UDP " + contactIp + ":" + portPart;
                        if (oldAddr.contains(";")) {
                            String branchPart = oldAddr.split(";", 2)[1];
                            newVia += ";" + branchPart;
                        }
                        via = newVia;
                    }
                }
            }
        }
        
        // 从To提取tag
        String toTag = null;
        if (toLine != null && toLine.contains(";tag=")) {
            toTag = toLine.split(";tag=")[1].split("\\s+")[0];
        } else {
            toTag = UUID.randomUUID().toString().substring(0, 32);
        }
        
        // 解析平台SDP中的 y(SSRC) 与 f 参数
        String remoteSsrc = null;
        String fParam = null;
        for (String sdpLine : sdpLines) {
            if (sdpLine.startsWith("y=")) {
                String[] parts = sdpLine.split("=", 2);
                if (parts.length >= 2) {
                    remoteSsrc = parts[1].trim();
                }
            } else if (sdpLine.startsWith("f=")) {
                String[] parts = sdpLine.split("=", 2);
                if (parts.length >= 2) {
                    fParam = parts[1].trim();
                }
            }
        }
        
        // 若对端未提供SSRC则生成一个
        String localSsrc = remoteSsrc;
        if (localSsrc == null || localSsrc.isEmpty()) {
            localSsrc = String.valueOf(System.currentTimeMillis() % 100000000);
        }
        
        // 生成符合GB28181的SDP（发送端，PS/90000，sendonly）
        StringBuilder sdpBody = new StringBuilder();
        sdpBody.append("v=0\r\n");
        sdpBody.append(String.format("o=%s 0 0 IN IP4 %s\r\n", deviceId, contactIp));
        sdpBody.append("s=Play\r\n");
        sdpBody.append(String.format("c=IN IP4 %s\r\n", contactIp));
        sdpBody.append("t=0 0\r\n");
        sdpBody.append("m=video 0 RTP/AVP 96\r\n");
        sdpBody.append("a=rtpmap:96 PS/90000\r\n");
        sdpBody.append("a=sendonly\r\n");
        sdpBody.append(String.format("y=%s\r\n", localSsrc));
        if (fParam != null && !fParam.isEmpty()) {
            sdpBody.append(String.format("f=%s\r\n", fParam));
        }
        
        String sdpBodyStr = sdpBody.toString();
        int contentLength = sdpBodyStr.getBytes().length;
        
        // 构造带SDP的200 OK
        return String.format(
            "SIP/2.0 200 OK\r\n" +
            "Via: %s\r\n" +
            "From: %s\r\n" +
            "To: %s;tag=%s\r\n" +
            "Call-ID: %s\r\n" +
            "CSeq: %s\r\n" +
            "Contact: <sip:%s@%s:%d>\r\n" +
            "User-Agent: GB28181-Device/1.0\r\n" +
            "Content-Type: application/sdp\r\n" +
            "Content-Length: %d\r\n" +
            "\r\n" +
            "%s",
            via != null ? via : "",
            fromLine != null ? fromLine : "",
            toLine != null ? toLine : "",
            toTag,
            callId != null ? callId : "",
            cseq != null ? cseq : "",
            deviceId,
            contactIp,
            localPort,
            contentLength,
            sdpBodyStr
        );
    }
    
    /**
     * 创建Catalog MESSAGE请求（设备主动发送）
     */
    public static String createCatalogMessage(String deviceId, String localIp, int localPort,
                                              String serverIp, int serverPort, String platformId,
                                              String catalogXml, String contactIp) {
        String callId = UUID.randomUUID().toString().substring(0, 32);
        String branch = generateBranch();
        int cseq = 1;
        String fromTag = UUID.randomUUID().toString().substring(0, 32);
        
        String sendIp = (contactIp != null && !contactIp.isEmpty()) ? contactIp : localIp;
        byte[] catalogBytes = catalogXml.getBytes();
        int contentLength = catalogBytes.length;
        
        return String.format(
            "MESSAGE sip:%s@%s:%d SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP %s:%d;branch=%s\r\n" +
            "From: <sip:%s@%s:%d>;tag=%s\r\n" +
            "To: <sip:%s@%s:%d>\r\n" +
            "Call-ID: %s\r\n" +
            "CSeq: %d MESSAGE\r\n" +
            "Content-Type: Application/MANSCDP+xml\r\n" +
            "User-Agent: GB28181-Device/1.0\r\n" +
            "Max-Forwards: 70\r\n" +
            "Content-Length: %d\r\n" +
            "\r\n" +
            "%s",
            platformId, serverIp, serverPort,
            sendIp, localPort, branch,
            deviceId, serverIp, serverPort, fromTag,
            platformId, serverIp, serverPort,
            callId,
            cseq,
            contentLength,
            catalogXml
        );
    }
    
    /**
     * 创建SUBSCRIBE响应（200 OK，包含Result=OK的MANSCDP响应体）
     */
    public static String createSubscribeResponse(String[] requestLines, String contactIp, String sn, String deviceId) {
        String via = null;
        String fromLine = null;
        String toLine = null;
        String callId = null;
        String cseq = null;
        
        for (String line : requestLines) {
            if (line.startsWith("Via:")) {
                via = line.substring(4).trim();
            } else if (line.startsWith("From:")) {
                fromLine = line.substring(5).trim();
            } else if (line.startsWith("To:")) {
                toLine = line.substring(3).trim();
            } else if (line.startsWith("Call-ID:")) {
                callId = line.substring(8).trim();
            } else if (line.startsWith("CSeq:")) {
                cseq = line.substring(5).trim();
            }
        }
        
        // 如果提供了contactIp且Via中包含不可路由地址，替换为contactIp
        if (contactIp != null && via != null) {
            if (via.contains("0.0.0.0")) {
                String[] viaParts = via.split("\\s+");
                if (viaParts.length >= 2) {
                    String oldAddr = viaParts[1];
                    if (oldAddr.contains(":")) {
                        String portPart = oldAddr.split(":")[1].split(";")[0];
                        String newVia = "SIP/2.0/UDP " + contactIp + ":" + portPart;
                        if (oldAddr.contains(";")) {
                            String branchPart = oldAddr.split(";", 2)[1];
                            newVia += ";" + branchPart;
                        }
                        via = newVia;
                    }
                }
            } else if (via.contains("127.0.0.1") && !"127.0.0.1".equals(contactIp)) {
                String[] viaParts = via.split("\\s+");
                if (viaParts.length >= 2) {
                    String oldAddr = viaParts[1];
                    if (oldAddr.contains(":")) {
                        String portPart = oldAddr.split(":")[1].split(";")[0];
                        String newVia = "SIP/2.0/UDP " + contactIp + ":" + portPart;
                        if (oldAddr.contains(";")) {
                            String branchPart = oldAddr.split(";", 2)[1];
                            newVia += ";" + branchPart;
                        }
                        via = newVia;
                    }
                }
            }
        }
        
        // 从To提取tag（如果没有则生成新的）
        String toTag = null;
        if (toLine != null && toLine.contains(";tag=")) {
            toTag = toLine.split(";tag=")[1].split("\\s+")[0];
        } else {
            toTag = UUID.randomUUID().toString().substring(0, 32);
        }
        
        // 生成MANSCDP响应体（Result=OK）
        String snValue = (sn != null && !sn.isEmpty()) ? sn : String.valueOf(System.currentTimeMillis() / 1000);
        String deviceIdValue = (deviceId != null && !deviceId.isEmpty()) ? deviceId : "34020000001320000001";
        String okBody = String.format(
            "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
            "<Response>\r\n" +
            "<CmdType>Catalog</CmdType>\r\n" +
            "<SN>%s</SN>\r\n" +
            "<DeviceID>%s</DeviceID>\r\n" +
            "<Result>OK</Result>\r\n" +
            "</Response>",
            snValue, deviceIdValue
        );
        int bodyLength = okBody.getBytes().length;
        
        return String.format(
            "SIP/2.0 200 OK\r\n" +
            "Via: %s\r\n" +
            "From: %s\r\n" +
            "To: %s;tag=%s\r\n" +
            "Call-ID: %s\r\n" +
            "CSeq: %s\r\n" +
            "Content-Type: Application/MANSCDP+xml\r\n" +
            "User-Agent: GB28181-Device/1.0\r\n" +
            "Content-Length: %d\r\n" +
            "\r\n" +
            "%s",
            via != null ? via : "",
            fromLine != null ? fromLine : "",
            toLine != null ? toLine : "",
            toTag,
            callId != null ? callId : "",
            cseq != null ? cseq : "",
            bodyLength,
            okBody
        );
    }
    
    /**
     * 创建NOTIFY请求（用于Catalog订阅响应）
     */
    public static String createNotifyRequest(String deviceId, String localIp, int localPort,
                                            String serverIp, int serverPort, String platformId,
                                            String catalogXml, String contactIp, String subscriberTag,
                                            Integer expires) {
        String callId = UUID.randomUUID().toString().substring(0, 32);
        String branch = generateBranch();
        int cseq = 1;
        String fromTag = UUID.randomUUID().toString().substring(0, 32);
        
        String sendIp = (contactIp != null && !contactIp.isEmpty()) ? contactIp : localIp;
        byte[] catalogBytes = catalogXml.getBytes();
        int contentLength = catalogBytes.length;
        
        // 生成To tag
        String toTag = (subscriberTag != null && !subscriberTag.isEmpty()) ? subscriberTag : UUID.randomUUID().toString().substring(0, 32);
        
        // 生成Subscription-State
        String subscriptionState = "active";
        if (expires != null) {
            subscriptionState = String.format("active;expires=%d;retry-after=0", expires);
        }
        
        String platformIdValue = (platformId != null && !platformId.isEmpty()) ? platformId : "3402000000";
        
        return String.format(
            "NOTIFY sip:%s@%s:%d SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP %s:%d;branch=%s\r\n" +
            "From: <sip:%s@%s:%d>;tag=%s\r\n" +
            "To: <sip:%s@%s:%d>;tag=%s\r\n" +
            "Call-ID: %s\r\n" +
            "CSeq: %d NOTIFY\r\n" +
            "Content-Type: Application/MANSCDP+xml\r\n" +
            "Event: presence\r\n" +
            "Subscription-State: %s\r\n" +
            "User-Agent: GB28181-Device/1.0\r\n" +
            "Max-Forwards: 70\r\n" +
            "Content-Length: %d\r\n" +
            "\r\n" +
            "%s",
            platformIdValue, serverIp, serverPort,
            sendIp, localPort, branch,
            deviceId, serverIp, serverPort, fromTag,
            platformIdValue, serverIp, serverPort, toTag,
            callId,
            cseq,
            subscriptionState,
            contentLength,
            catalogXml
        );
    }
}


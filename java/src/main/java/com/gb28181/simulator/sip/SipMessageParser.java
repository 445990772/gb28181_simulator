package com.gb28181.simulator.sip;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SIP消息解析器
 */
public class SipMessageParser {
    
    /**
     * 解析SIP消息行
     */
    public static String[] parseLines(String message) {
        return message.split("\r\n");
    }
    
    /**
     * 提取SIP头字段值
     */
    public static String extractHeader(String[] lines, String headerName) {
        String prefix = headerName + ":";
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }
    
    /**
     * 提取请求体
     */
    public static String extractBody(String[] lines) {
        StringBuilder body = new StringBuilder();
        boolean inBody = false;
        
        for (String line : lines) {
            if (line.isEmpty() && !inBody) {
                inBody = true;
                continue;
            }
            if (inBody) {
                if (body.length() > 0) {
                    body.append("\r\n");
                }
                body.append(line);
            }
        }
        return body.toString();
    }
    
    /**
     * 解析INVITE请求的SDP信息
     */
    public static Map<String, Object> parseInviteSdp(String[] lines) {
        Map<String, Object> sdpInfo = new HashMap<>();
        String body = extractBody(lines);
        
        if (body == null || body.isEmpty()) {
            return sdpInfo;
        }
        
        String[] sdpLines = body.split("\r\n");
        for (String line : sdpLines) {
            if (line.startsWith("c=IN IP4 ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    sdpInfo.put("ip", parts[2]);
                }
            } else if (line.startsWith("m=video ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        sdpInfo.put("video_port", Integer.parseInt(parts[1]));
                    } catch (NumberFormatException e) {
                        // 忽略
                    }
                }
            } else if (line.startsWith("m=audio ")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    try {
                        sdpInfo.put("audio_port", Integer.parseInt(parts[1]));
                    } catch (NumberFormatException e) {
                        // 忽略
                    }
                }
            } else if (line.contains("y=")) {
                String[] parts = line.split("=");
                if (parts.length >= 2) {
                    sdpInfo.put("ssrc", parts[1].trim());
                }
            }
        }
        
        return sdpInfo;
    }
    
    /**
     * 从请求行提取通道ID
     */
    public static String extractChannelId(String requestLine) {
        // INVITE sip:34020000001320000001@192.168.1.100:5060 SIP/2.0
        Pattern pattern = Pattern.compile("sip:([^@]+)@");
        Matcher matcher = pattern.matcher(requestLine);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 从MESSAGE请求体中提取CmdType
     */
    public static String extractCmdType(String body) {
        Pattern pattern = Pattern.compile("<CmdType>(.*?)</CmdType>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    /**
     * 从MESSAGE请求体中提取SN
     */
    public static String extractSn(String body) {
        Pattern pattern = Pattern.compile("<SN>(.*?)</SN>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return String.valueOf(System.currentTimeMillis() / 1000);
    }
    
    /**
     * 从Catalog请求体中提取InfoID
     */
    public static String extractInfoId(String body) {
        Pattern pattern = Pattern.compile("<InfoID>(.*?)</InfoID>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
    
    /**
     * 从From头提取平台ID
     */
    public static String extractPlatformId(String[] lines) {
        String fromLine = extractHeader(lines, "From");
        if (fromLine != null && fromLine.contains("sip:")) {
            try {
                String part = fromLine.split("sip:")[1].split("@")[0];
                return part;
            } catch (Exception e) {
                // 忽略
            }
        }
        return "3402000000";
    }
    
    /**
     * 从SUBSCRIBE请求体中提取SN
     */
    public static String extractSnFromSubscribe(String body) {
        if (body == null || body.isEmpty()) {
            return String.valueOf(System.currentTimeMillis() / 1000);
        }
        Pattern pattern = Pattern.compile("<SN>(.*?)</SN>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return String.valueOf(System.currentTimeMillis() / 1000);
    }
    
    /**
     * 从SUBSCRIBE请求体中提取InfoID
     */
    public static String extractInfoIdFromSubscribe(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Pattern pattern = Pattern.compile("<InfoID>(.*?)</InfoID>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
    
    /**
     * 从SUBSCRIBE请求中提取平台ID（从From头）
     */
    public static String extractPlatformIdFromSubscribe(String[] lines) {
        String fromLine = extractHeader(lines, "From");
        if (fromLine != null && fromLine.contains("sip:")) {
            try {
                String part = fromLine.split("sip:")[1].split("@")[0];
                return part;
            } catch (Exception e) {
                // 忽略
            }
        }
        return "3402000000";
    }
    
    /**
     * 从SUBSCRIBE请求中提取订阅者tag（从From头）
     */
    public static String extractSubscriberTag(String[] lines) {
        String fromLine = extractHeader(lines, "From");
        if (fromLine != null && fromLine.contains(";tag=")) {
            try {
                return fromLine.split(";tag=")[1].split("\\s+")[0];
            } catch (Exception e) {
                // 忽略
            }
        }
        return null;
    }
    
    /**
     * 从SUBSCRIBE请求中提取Expires
     */
    public static Integer extractExpires(String[] lines) {
        String expiresLine = extractHeader(lines, "Expires");
        if (expiresLine != null) {
            try {
                return Integer.parseInt(expiresLine.trim());
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
        return null;
    }
    
    /**
     * 从SUBSCRIBE请求中提取Event类型
     */
    public static String extractEventType(String[] lines) {
        String eventLine = extractHeader(lines, "Event");
        if (eventLine != null) {
            return eventLine.trim().toLowerCase();
        }
        return null;
    }
}


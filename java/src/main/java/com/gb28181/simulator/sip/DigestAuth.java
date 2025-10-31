package com.gb28181.simulator.sip;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SIP Digest认证工具类
 */
public class DigestAuth {
    
    /**
     * 计算MD5哈希值
     */
    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5算法不可用", e);
        }
    }
    
    /**
     * 计算Digest认证响应
     * 
     * @param username 用户名（设备ID）
     * @param realm 域
     * @param password 密码
     * @param method 方法（如REGISTER）
     * @param uri 请求URI
     * @param nonce 随机数
     * @return 认证响应字符串
     */
    public static String calculateResponse(String username, String realm, String password,
                                           String method, String uri, String nonce) {
        // HA1 = MD5(username:realm:password)
        String ha1 = md5(username + ":" + realm + ":" + password);
        
        // HA2 = MD5(METHOD:uri)
        String ha2 = md5(method + ":" + uri);
        
        // response = MD5(HA1:nonce:HA2)
        String response = md5(ha1 + ":" + nonce + ":" + ha2);
        
        return response;
    }
    
    /**
     * 生成Authorization头
     */
    public static String generateAuthorizationHeader(String deviceId, String realm, String nonce,
                                                     String uri, String response) {
        return String.format("Digest username=\"%s\", realm=\"%s\", nonce=\"%s\", uri=\"%s\", response=\"%s\"",
                deviceId, realm, nonce, uri, response);
    }
}


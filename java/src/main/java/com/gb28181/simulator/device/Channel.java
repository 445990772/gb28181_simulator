package com.gb28181.simulator.device;

import java.util.HashMap;
import java.util.Map;

/**
 * GB28181设备通道信息
 */
public class Channel {
    private final Map<String, String> attributes = new HashMap<>();

    public Channel(String id, String name) {
        attributes.put("id", id);
        attributes.put("name", name);
        // 默认GB28181标准字段
        setAttribute("manufacturer", "IPC");
        setAttribute("model", "IPC");
        setAttribute("parental", "0");
        setAttribute("safety_way", "0");
        setAttribute("register_way", "1");
        setAttribute("secrecy", "0");
        setAttribute("status", "ON");
        setAttribute("online", "ON");
        setAttribute("alarm_status", "READY");
    }

    public String getId() {
        return attributes.get("id");
    }

    public String getName() {
        return attributes.get("name");
    }

    public void setAttribute(String key, String value) {
        attributes.put(key, value);
    }

    public String getAttribute(String key) {
        return attributes.get(key);
    }

    public String getAttribute(String key, String defaultValue) {
        return attributes.getOrDefault(key, defaultValue);
    }

    public Map<String, String> getAttributes() {
        return new HashMap<>(attributes);
    }
}


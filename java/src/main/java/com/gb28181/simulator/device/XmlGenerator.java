package com.gb28181.simulator.device;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.List;

/**
 * GB28181 XML消息生成器
 */
public class XmlGenerator {
    
    /**
     * 创建Keepalive心跳消息XML
     */
    public static String createKeepaliveXml(String deviceId, int sn) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element root = doc.createElement("Notify");
            doc.appendChild(root);

            appendElement(doc, root, "CmdType", "Keepalive");
            appendElement(doc, root, "SN", String.valueOf(sn));
            appendElement(doc, root, "DeviceID", deviceId);
            appendElement(doc, root, "Status", "OK");
            appendElement(doc, root, "Info", "");

            return xmlToString(doc);
        } catch (Exception e) {
            throw new RuntimeException("创建Keepalive XML失败", e);
        }
    }

    /**
     * 创建通道目录响应XML
     */
    public static String createCatalogXml(String deviceId, List<Channel> channels, int sn, String infoId) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element root = doc.createElement("Response");
            doc.appendChild(root);

            appendElement(doc, root, "CmdType", "Catalog");
            appendElement(doc, root, "SN", String.valueOf(sn));
            appendElement(doc, root, "DeviceID", deviceId);
            appendElement(doc, root, "SumNum", String.valueOf(channels.size()));

            if (infoId != null && !infoId.isEmpty()) {
                appendElement(doc, root, "InfoID", infoId);
            }

            Element deviceList = doc.createElement("DeviceList");
            deviceList.setAttribute("Num", String.valueOf(channels.size()));
            root.appendChild(deviceList);

            for (Channel channel : channels) {
                Element item = doc.createElement("Item");
                deviceList.appendChild(item);

                appendElement(doc, item, "DeviceID", channel.getId());
                appendElement(doc, item, "Name", channel.getName());
                appendElement(doc, item, "Manufacturer", channel.getAttribute("manufacturer", "IPC"));
                appendElement(doc, item, "Model", channel.getAttribute("model", "IPC"));
                appendElement(doc, item, "Owner", channel.getAttribute("owner", deviceId));
                
                String civilCode = channel.getAttribute("civil_code", "340200");
                if (civilCode.length() > 6) {
                    civilCode = civilCode.substring(0, 6);
                }
                appendElement(doc, item, "CivilCode", civilCode);
                
                appendElement(doc, item, "Address", channel.getAttribute("address", "Address"));
                appendElement(doc, item, "Parental", channel.getAttribute("parental", "0"));
                
                if ("1".equals(channel.getAttribute("parental"))) {
                    appendElement(doc, item, "ParentID", channel.getAttribute("parent_id", deviceId));
                }
                
                appendElement(doc, item, "SafetyWay", channel.getAttribute("safety_way", "0"));
                appendElement(doc, item, "RegisterWay", channel.getAttribute("register_way", "1"));
                appendElement(doc, item, "Secrecy", channel.getAttribute("secrecy", "0"));
                appendElement(doc, item, "Status", channel.getAttribute("status", "ON"));
                appendElement(doc, item, "Online", channel.getAttribute("online", "ON"));
                appendElement(doc, item, "AlarmStatus", channel.getAttribute("alarm_status", "READY"));
            }

            return xmlToString(doc);
        } catch (Exception e) {
            throw new RuntimeException("创建Catalog XML失败", e);
        }
    }

    /**
     * 创建设备信息响应XML
     */
    public static String createDeviceInfoXml(String deviceId, String deviceName, int sn) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element root = doc.createElement("Response");
            doc.appendChild(root);

            appendElement(doc, root, "CmdType", "DeviceInfo");
            appendElement(doc, root, "SN", String.valueOf(sn));
            appendElement(doc, root, "DeviceID", deviceId);
            appendElement(doc, root, "DeviceName", deviceName);
            appendElement(doc, root, "Manufacturer", "GB28181-Simulator");
            appendElement(doc, root, "Model", "IPC-Simulator-v1.0");
            appendElement(doc, root, "Firmware", "v1.0.0");
            appendElement(doc, root, "Result", "OK");

            return xmlToString(doc);
        } catch (Exception e) {
            throw new RuntimeException("创建设备信息XML失败", e);
        }
    }
    
    /**
     * 创建配置下载响应XML
     */
    public static String createConfigDownloadXml(String deviceId, String deviceName, String localIp, int localPort, String password, int sn) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element root = doc.createElement("Response");
            doc.appendChild(root);

            appendElement(doc, root, "CmdType", "ConfigDownload");
            appendElement(doc, root, "SN", String.valueOf(sn));
            appendElement(doc, root, "DeviceID", deviceId);
            appendElement(doc, root, "Result", "OK");

            Element basicParam = doc.createElement("BasicParam");
            root.appendChild(basicParam);
            
            appendElement(doc, basicParam, "Name", deviceName);
            appendElement(doc, basicParam, "DeviceID", deviceId);
            appendElement(doc, basicParam, "IPAddress", localIp);
            appendElement(doc, basicParam, "Port", String.valueOf(localPort));
            appendElement(doc, basicParam, "Password", password != null ? password : "");

            return xmlToString(doc);
        } catch (Exception e) {
            throw new RuntimeException("创建配置下载XML失败", e);
        }
    }

    private static void appendElement(Document doc, Element parent, String tagName, String textContent) {
        Element element = doc.createElement(tagName);
        element.setTextContent(textContent);
        parent.appendChild(element);
    }

    private static String xmlToString(Document doc) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "GB2312");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}


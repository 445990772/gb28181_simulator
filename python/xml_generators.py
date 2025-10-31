#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""XML响应生成器模块"""

import xml.etree.ElementTree as ET
import time
import re


def create_device_info_xml(device_id, device_name, sn):
    """创建设备信息响应XML（GB28181标准格式）"""
    root = ET.Element("Response")
    
    cmd_type = ET.SubElement(root, "CmdType")
    cmd_type.text = "DeviceInfo"
    
    sn_elem = ET.SubElement(root, "SN")
    sn_elem.text = str(sn)
    
    device_id_elem = ET.SubElement(root, "DeviceID")
    device_id_elem.text = device_id
    
    device_name_elem = ET.SubElement(root, "DeviceName")
    device_name_elem.text = device_name
    
    manufacturer = ET.SubElement(root, "Manufacturer")
    manufacturer.text = "GB28181-Simulator"
    
    model = ET.SubElement(root, "Model")
    model.text = "IPC-Simulator-v1.0"
    
    firmware = ET.SubElement(root, "Firmware")
    firmware.text = "v1.0.0"
    
    result = ET.SubElement(root, "Result")
    result.text = "OK"
    
    # GB28181使用紧凑XML格式（不带格式化）
    return ET.tostring(root, encoding='utf-8', method='xml').decode('utf-8')


def create_config_download_xml(device_id, device_name, local_ip, local_port, password, sn):
    """创建配置下载响应XML（GB28181标准格式）"""
    root = ET.Element("Response")
    
    cmd_type = ET.SubElement(root, "CmdType")
    cmd_type.text = "ConfigDownload"
    
    sn_elem = ET.SubElement(root, "SN")
    sn_elem.text = str(sn)
    
    device_id_elem = ET.SubElement(root, "DeviceID")
    device_id_elem.text = device_id
    
    result = ET.SubElement(root, "Result")
    result.text = "OK"
    
    # 基本配置参数
    basic_param = ET.SubElement(root, "BasicParam")
    
    name_elem = ET.SubElement(basic_param, "Name")
    name_elem.text = device_name
    
    device_id_param = ET.SubElement(basic_param, "DeviceID")
    device_id_param.text = device_id
    
    ip_address = ET.SubElement(basic_param, "IPAddress")
    ip_address.text = local_ip
    
    port = ET.SubElement(basic_param, "Port")
    port.text = str(local_port)
    
    username = ET.SubElement(basic_param, "Username")
    username.text = device_id
    
    password_elem = ET.SubElement(basic_param, "Password")
    password_elem.text = password
    
    # GB28181使用紧凑XML格式（不带格式化）
    return ET.tostring(root, encoding='utf-8', method='xml').decode('utf-8')


def create_catalog_xml(device_id, channels, sn, infoid=None):
    """创建通道目录响应XML（GB28181标准格式）"""
    root = ET.Element("Response")
    
    cmd_type = ET.SubElement(root, "CmdType")
    cmd_type.text = "Catalog"
    
    sn_elem = ET.SubElement(root, "SN")
    sn_elem.text = str(sn)
    
    device_id_elem = ET.SubElement(root, "DeviceID")
    device_id_elem.text = device_id
    
    # 总条数：部分平台用此字段控制消费数量（否则 .take(SumNum) 可能为0）
    sum_num_elem = ET.SubElement(root, "SumNum")
    sum_num_elem.text = str(len(channels))
    
    # 如果请求中包含了InfoID，则在响应中也包含InfoID
    if infoid:
        infoid_elem = ET.SubElement(root, "InfoID")
        infoid_elem.text = str(infoid)
    
    # 设备信息
    device_list = ET.SubElement(root, "DeviceList")
    device_list.set("Num", str(len(channels)))
    
    # 为每个通道创建设备项（严格按照GB28181标准字段顺序）
    for channel in channels:
        item = ET.SubElement(device_list, "Item")
        
        # 1. DeviceID - 通道设备ID（22位：20位设备ID + 2位通道编号）
        item_id = ET.SubElement(item, "DeviceID")
        item_id.text = channel.get('id', f"{device_id}01")
        
        # 2. Name - 通道名称
        name = ET.SubElement(item, "Name")
        name.text = channel.get('name', f"通道{channel.get('id', '01')}")
        
        # 3. Manufacturer - 制造商（GB28181标准要求）
        manufacturer = ET.SubElement(item, "Manufacturer")
        manufacturer.text = channel.get('manufacturer', 'IPC')
        
        # 4. Model - 型号（GB28181标准要求）
        model = ET.SubElement(item, "Model")
        model.text = channel.get('model', 'IPC')
        
        # 5. Owner - 所有者（GB28181标准要求）
        owner = ET.SubElement(item, "Owner")
        owner.text = channel.get('owner', '')
        
        # 6. CivilCode - 行政区域代码（GB28181标准要求，通常为6位）
        civil_code = ET.SubElement(item, "CivilCode")
        civil_code_value = channel.get('civil_code', '3402000000')
        # 如果是10位，取前6位作为CivilCode
        if len(civil_code_value) > 6:
            civil_code.text = civil_code_value[:6]
        else:
            civil_code.text = civil_code_value
        
        # 7. Address - 地址（GB28181标准要求）
        address = ET.SubElement(item, "Address")
        address.text = channel.get('address', 'Address')
        
        # 8. Parental - 是否父设备（GB28181标准要求：0-否，1-是）
        parental = ET.SubElement(item, "Parental")
        parental_value = channel.get('parental', '0')
        parental.text = parental_value
        
        # 9. ParentID - 父设备ID（当Parental=1时必须存在，当Parental=0时可选）
        if parental_value == '1' and channel.get('parent_id'):
            parent_id = ET.SubElement(item, "ParentID")
            parent_id.text = channel.get('parent_id', device_id)
        
        # 10. SafetyWay - 安全方式（GB28181标准要求：0-不设防，1-周界设防）
        safety_way = ET.SubElement(item, "SafetyWay")
        safety_way.text = channel.get('safety_way', '0')
        
        # 11. RegisterWay - 注册方式（GB28181标准要求：1-符合RCF3831标准的鉴权注册模式，2-符合RCF3261标准的注册模式）
        register_way = ET.SubElement(item, "RegisterWay")
        register_way.text = channel.get('register_way', '1')
        
        # 12. Secrecy - 保密属性（GB28181标准要求：0-不涉密，1-涉密）
        secrecy = ET.SubElement(item, "Secrecy")
        secrecy.text = channel.get('secrecy', '0')
        
        # 13. Status - 设备状态（GB28181标准要求：ON-正常，OFF-故障）
        status = ET.SubElement(item, "Status")
        status.text = channel.get('status', 'ON')
        
        # 14. Online - 在线状态（GB28181标准要求：ON-在线，OFF-离线）
        online = ET.SubElement(item, "Online")
        online.text = channel.get('online', 'ON')
        
        # 15. AlarmStatus - 报警状态（GB28181标准要求：READY-正常，ALARM-报警）
        alarm_status = ET.SubElement(item, "AlarmStatus")
        alarm_status.text = channel.get('alarm_status', 'READY')
    
    # GB28181标准要求：XML必须包含声明，编码为GB2312
    # 注意：虽然声明中写GB2312，但实际生成时仍使用UTF-8，然后在发送时按GB2312编码计算Content-Length
    xml_string = ET.tostring(root, encoding='utf-8', method='xml').decode('utf-8')
    # 确保XML声明存在（GB28181标准要求）
    if not xml_string.strip().startswith('<?xml'):
        xml_string = '<?xml version="1.0" encoding="GB2312"?>\r\n' + xml_string
    return xml_string


def create_keepalive_xml(device_id, sn):
    """创建心跳Keepalive消息XML（GB28181标准格式）"""
    root = ET.Element("Notify")
    
    cmd_type = ET.SubElement(root, "CmdType")
    cmd_type.text = "Keepalive"
    
    sn_elem = ET.SubElement(root, "SN")
    sn_elem.text = str(sn)
    
    device_id_elem = ET.SubElement(root, "DeviceID")
    device_id_elem.text = device_id
    
    status = ET.SubElement(root, "Status")
    status.text = "OK"
    
    info = ET.SubElement(root, "Info")
    # Info可以为空
    
    # GB28181使用紧凑XML格式（不带格式化）
    return ET.tostring(root, encoding='utf-8', method='xml').decode('utf-8')


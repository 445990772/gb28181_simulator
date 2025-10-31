#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""心跳消息处理模块"""

import time
import uuid
import xml.etree.ElementTree as ET


def create_keepalive_message(device_id, local_ip, local_port, server_ip, server_port, sn):
    """创建Keepalive心跳MESSAGE请求"""
    call_id = str(uuid.uuid4())[:32]
    branch = f"z9hG4bK{str(uuid.uuid4()).replace('-', '')[:20]}"
    cseq = 1
    
    # 创建Keepalive XML
    from xml_generators import create_keepalive_xml
    keepalive_xml = create_keepalive_xml(device_id, sn)
    
    # 创建MESSAGE请求
    message = f"""MESSAGE sip:{server_ip}:{server_port} SIP/2.0
Via: SIP/2.0/UDP {local_ip}:{local_port};branch={branch}
From: <sip:{device_id}@{server_ip}:{server_port}>;tag={uuid.uuid4()}
To: <sip:{device_id}@{server_ip}:{server_port}>
Call-ID: {call_id}
CSeq: {cseq} MESSAGE
Content-Type: Application/MANSCDP+xml
Content-Length: {len(keepalive_xml.encode('utf-8'))}
User-Agent: GB28181-Device/1.0
Max-Forwards: 70

{keepalive_xml}"""
    
    return message.replace('\n', '\r\n')


def send_keepalive(device, print_sip_message_func):
    """发送Keepalive心跳消息"""
    try:
        device.heartbeat_sn += 1
        keepalive_message = create_keepalive_message(
            device.device_id,
            device.local_ip,
            device.local_port,
            device.server_ip,
            device.server_port,
            device.heartbeat_sn
        )
        
        device.socket.sendto(
            keepalive_message.encode('utf-8'),
            (device.server_ip, device.server_port)
        )
        return True
    except Exception as e:
        return False


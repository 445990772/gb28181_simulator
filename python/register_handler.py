#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""REGISTER消息处理模块"""

import time
import uuid
import hashlib


def create_register_request(device_id, local_ip, local_port, server_ip, server_port, password, tag, contact_ip=None):
    """创建注册请求"""
    call_id_prefix = str(uuid.uuid4())[:8]
    call_id_seq = 0
    cseq = 0
    
    call_id = f"{call_id_prefix}@{local_ip}"
    cseq = 1
    branch = f"z9hG4bK{str(uuid.uuid4()).replace('-', '')[:20]}"
    
    # 计算Authorization
    realm = "3402000000"
    nonce = str(int(time.time()))
    
    # HA1 = MD5(username:realm:password)
    ha1_str = f"{device_id}:{realm}:{password}"
    ha1 = hashlib.md5(ha1_str.encode()).hexdigest()
    
    # HA2 = MD5(METHOD:uri)
    uri = f"sip:{server_ip}:{server_port}"
    ha2_str = f"REGISTER:{uri}"
    ha2 = hashlib.md5(ha2_str.encode()).hexdigest()
    
    # response = MD5(HA1:nonce:HA2)
    response_str = f"{ha1}:{nonce}:{ha2}"
    response = hashlib.md5(response_str.encode()).hexdigest()
    
    auth = f'Digest username="{device_id}", realm="{realm}", nonce="{nonce}", uri="{uri}", response="{response}"'
    
    # Contact头和Via头都使用contact_ip（如果提供），否则使用local_ip
    # contact_ip应该是一个可路由的IP地址
    contact_address = contact_ip if contact_ip else local_ip
    via_address = contact_address  # Via头也使用可路由地址
    
    request = f"""REGISTER sip:{server_ip}:{server_port} SIP/2.0
Via: SIP/2.0/UDP {via_address}:{local_port};branch={branch}
From: <sip:{device_id}@{server_ip}:{server_port}>;tag={tag}
To: <sip:{device_id}@{server_ip}:{server_port}>
Call-ID: {call_id}
CSeq: {cseq} REGISTER
Contact: <sip:{device_id}@{contact_address}:{local_port}>
Authorization: {auth}
Max-Forwards: 70
User-Agent: GB28181-Device/1.0
Expires: 3600
Content-Length: 0

"""
    return request.replace('\n', '\r\n')


def handle_register_response(device, lines):
    """处理REGISTER响应"""
    first_line = lines[0]
    
    if '200 OK' in first_line and 'REGISTER' in '\r\n'.join(lines):
        device.is_registered = True
        return True
    elif '401 Unauthorized' in first_line:
        device.is_registered = False
        return False
    
    return None


#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SIP响应生成模块"""

import uuid


def create_message_response(request_lines, contact_ip=None):
    """创建MESSAGE响应"""
    # 解析请求
    via = None
    from_line = None
    to_line = None
    call_id = None
    cseq = None
    
    for line in request_lines:
        if line.startswith('Via:'):
            via = line[4:].strip()
        elif line.startswith('From:'):
            from_line = line[5:].strip()
        elif line.startswith('To:'):
            to_line = line[3:].strip()
        elif line.startswith('Call-ID:'):
            call_id = line[8:].strip()
        elif line.startswith('CSeq:'):
            cseq = line[5:].strip()
    
    # 如果提供了contact_ip且Via中包含不可路由地址（如0.0.0.0），替换为contact_ip
    if contact_ip and via:
        # 检查Via中是否包含0.0.0.0或127.0.0.1（当服务器是外部IP时）
        if '0.0.0.0' in via:
            # 提取端口
            via_parts = via.split()
            if len(via_parts) >= 2:
                # 格式: SIP/2.0/UDP 0.0.0.0:5060;branch=...
                old_addr = via_parts[1]
                if ':' in old_addr:
                    port_part = old_addr.split(':')[1].split(';')[0]
                    new_via = f"SIP/2.0/UDP {contact_ip}:{port_part}"
                    if ';' in old_addr:
                        branch_part = old_addr.split(';', 1)[1]
                        new_via += ';' + branch_part
                    via = new_via
        elif '127.0.0.1' in via and contact_ip != '127.0.0.1':
            # 如果Via是127.0.0.1但contact_ip不是，也替换
            via_parts = via.split()
            if len(via_parts) >= 2:
                old_addr = via_parts[1]
                if ':' in old_addr:
                    port_part = old_addr.split(':')[1].split(';')[0]
                    new_via = f"SIP/2.0/UDP {contact_ip}:{port_part}"
                    if ';' in old_addr:
                        branch_part = old_addr.split(';', 1)[1]
                        new_via += ';' + branch_part
                    via = new_via
    
    # 从To提取tag（如果没有则生成新的）
    to_tag = None
    if to_line and ';tag=' in to_line:
        to_tag = to_line.split(';tag=')[1].split()[0]
    else:
        to_tag = str(uuid.uuid4())[:32]
    
    response = f"""SIP/2.0 200 OK
Via: {via}
From: {from_line}
To: {to_line};tag={to_tag}
Call-ID: {call_id}
CSeq: {cseq}
User-Agent: GB28181-Device/1.0
Content-Length: 0

"""
    return response.replace('\n', '\r\n')


def create_invite_response(request_lines):
    """创建INVITE 200 OK响应"""
    # 解析请求
    via = None
    from_line = None
    to_line = None
    call_id = None
    cseq = None
    
    for line in request_lines:
        if line.startswith('Via:'):
            via = line[4:].strip()
        elif line.startswith('From:'):
            from_line = line[5:].strip()
        elif line.startswith('To:'):
            to_line = line[3:].strip()
        elif line.startswith('Call-ID:'):
            call_id = line[8:].strip()
        elif line.startswith('CSeq:'):
            cseq = line[5:].strip()
    
    # 从To提取tag
    to_tag = None
    if to_line and ';tag=' in to_line:
        to_tag = to_line.split(';tag=')[1].split()[0]
    else:
        to_tag = str(uuid.uuid4())[:32]
    
    # SDP响应（通常不需要body，但某些平台可能需要）
    response = f"""SIP/2.0 200 OK
Via: {via}
From: {from_line}
To: {to_line};tag={to_tag}
Call-ID: {call_id}
CSeq: {cseq}
User-Agent: GB28181-Device/1.0
Content-Length: 0

"""
    return response.replace('\n', '\r\n')


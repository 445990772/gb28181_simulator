#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SUBSCRIBE消息处理模块"""

import time
import uuid
from sip_response import create_message_response
from xml_generators import create_catalog_xml


def create_subscribe_response(request_lines, contact_ip=None, sn=None, device_id=None):
    """创建SUBSCRIBE 200 OK响应（携带Result=OK的MANSCDP响应体，符合J.20.2示范）"""
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
        if '0.0.0.0' in via:
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
        elif '127.0.0.1' in via and contact_ip != '127.0.0.1':
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

    # 提取Expires（用于后续NOTIFY Subscription-State），此处仅透传，不强制使用
    expires = None
    for line in request_lines:
        if line.startswith('Expires:'):
            try:
                expires = int(line.split(':', 1)[1].strip())
            except Exception:
                pass
            break

    # 构建带Result=OK的响应体
    if sn is None:
        sn = extract_sn_from_subscribe('\r\n'.join(request_lines))
    if device_id is None:
        # 尝试从请求体中提取DeviceID
        try:
            import xml.etree.ElementTree as ET
            body = '\r\n'.join(request_lines).split('\r\n\r\n', 1)
            if len(body) == 2:
                root = ET.fromstring(body[1])
                did_elem = root.find('DeviceID')
                if did_elem is not None and did_elem.text:
                    device_id = did_elem.text
        except Exception:
            pass
    if device_id is None:
        device_id = '00000000000000000000'

    ok_body = f"<Response><CmdType>Catalog</CmdType><SN>{sn}</SN><DeviceID>{device_id}</DeviceID><Result>OK</Result></Response>"
    body_len = len(ok_body.encode('gb2312'))

    # 如果提供了contact_ip且Via中包含不可路由地址，替换为contact_ip
    if contact_ip and via:
        if '0.0.0.0' in via or ('127.0.0.1' in via and contact_ip != '127.0.0.1'):
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

    response = f"""SIP/2.0 200 OK
Via: {via}
From: {from_line}
To: {to_line};tag={to_tag}
Call-ID: {call_id}
CSeq: {cseq}
User-Agent: GB28181-Device/1.0
Event: presence
Content-Type: Application/MANSCDP+xml
Content-Length: {body_len}

{ok_body}"""
    return response.replace('\n', '\r\n')


def create_notify_request(device_id, device_name, local_ip, local_port, server_ip, server_port, channels, contact_ip=None, infoid=None, sn=None, platform_id=None, subscriber_tag=None, expires=None):
    """创建NOTIFY请求（用于Catalog订阅响应）
    sn: 必须与平台SUBSCRIBE中的SN一致，便于平台按SN匹配缓存
    """
    call_id = str(uuid.uuid4())[:32]
    branch = f"z9hG4bK{str(uuid.uuid4()).replace('-', '')[:20]}"
    cseq = 1
    
    # 使用contact_ip（如果提供）
    notify_ip = contact_ip if contact_ip else local_ip
    
    # 生成Catalog XML（包含InfoID如果存在），SN必须沿用请求中的SN
    if sn is None:
        sn = int(time.time() * 1000) % 100000000
    catalog_xml = create_catalog_xml(device_id, channels, sn, infoid=infoid)
    catalog_bytes = catalog_xml.encode('gb2312')  # GB28181使用GB2312编码
    catalog_length = len(catalog_bytes)
    
    # 创建NOTIFY请求
    # J.21：NOTIFY Request-URI 与 To 指向目录接收者（平台/上级），From 为目录拥有者（设备）
    if platform_id is None:
        platform_id = '3402000000'
    to_tag = subscriber_tag if subscriber_tag else str(uuid.uuid4())[:32]
    expires_kv = f"active" if expires is None else f"active;expires={expires};retry-after=0"
    notify = f"""NOTIFY sip:{platform_id}@{server_ip}:{server_port} SIP/2.0
Via: SIP/2.0/UDP {notify_ip}:{local_port};branch={branch}
From: <sip:{device_id}@{server_ip}:{server_port}>;tag={str(uuid.uuid4())[:32]}
To: <sip:{platform_id}@{server_ip}:{server_port}>;tag={to_tag}
Call-ID: {call_id}
CSeq: {cseq} NOTIFY
Content-Type: Application/MANSCDP+xml
Event: presence
Subscription-State: {expires_kv}
User-Agent: GB28181-Device/1.0
Max-Forwards: 70
Content-Length: {catalog_length}

{catalog_xml}"""
    
    return notify.replace('\n', '\r\n')


def extract_sn_from_subscribe(request_body):
    """从SUBSCRIBE请求体中提取SN"""
    if not request_body:
        return str(int(time.time() * 1000) % 100000000)
    
    try:
        # 解析XML
        import xml.etree.ElementTree as ET
        root = ET.fromstring(request_body)
        sn_elem = root.find('SN')
        if sn_elem is not None and sn_elem.text:
            return sn_elem.text
    except:
        pass
    
    return str(int(time.time() * 1000) % 100000000)


def extract_infoid_from_subscribe(request_body):
    """从SUBSCRIBE请求体中提取InfoID"""
    if not request_body:
        return None
    
    try:
        # 解析XML
        import xml.etree.ElementTree as ET
        root = ET.fromstring(request_body)
        infoid_elem = root.find('InfoID')
        if infoid_elem is not None and infoid_elem.text:
            return infoid_elem.text.strip()
    except:
        pass
    
    return None


def handle_subscribe_catalog(device, request_body, lines, addr, print_sip_message_func):
    """处理Catalog订阅（SUBSCRIBE）请求"""
    import time as time_module
    request_time = time_module.time()
    print(f"\n收到SUBSCRIBE请求 (事件: catalog, 设备: {device.device_id})")
    print(f"  接收时间: {time_module.strftime('%H:%M:%S', time_module.localtime(request_time))}")
    
    try:
        # 解析平台目录接收者编码（From 里的 sip:编码@）与对端tag、Expires
        platform_id = None
        subscriber_tag = None
        expires = None
        for h in lines:
            if h.startswith('From:') and 'sip:' in h:
                try:
                    platform_id = h.split('sip:')[1].split('@')[0]
                    if ';tag=' in h:
                        subscriber_tag = h.split(';tag=')[1].split()[0]
                except Exception:
                    pass
            elif h.startswith('Expires:'):
                try:
                    expires = int(h.split(':', 1)[1].strip())
                except Exception:
                    pass

        # 立即发送200 OK响应（含Result=OK的MANSCDP体）
        contact_ip = device.contact_ip if device.contact_ip else device.local_ip
        # 从请求体提取DeviceID供OK体使用
        device_id_for_ok = device.device_id
        try:
            import xml.etree.ElementTree as ET
            if request_body:
                root = ET.fromstring(request_body)
                did_elem = root.find('DeviceID')
                if did_elem is not None and did_elem.text:
                    device_id_for_ok = did_elem.text
        except Exception:
            pass
        subscribe_response = create_subscribe_response(lines, contact_ip=contact_ip, sn=extract_sn_from_subscribe(request_body), device_id=device_id_for_ok)
        
        response_send_time = time_module.time()
        device.socket.sendto(subscribe_response.encode('utf-8'), addr)
        print(f"✓ 已立即发送SUBSCRIBE 200 OK响应（延迟: {(response_send_time - request_time) * 1000:.2f} 毫秒）")
        print_sip_message_func(device.device_id, "send", subscribe_response, addr)
        
        # 提取SN和InfoID（如果请求中有）
        sn = extract_sn_from_subscribe(request_body)
        infoid = extract_infoid_from_subscribe(request_body)
        print(f"  使用的SN: {sn}")
        if infoid:
            print(f"  提取的InfoID: {infoid}")
        
        # 立即发送NOTIFY消息（包含Catalog信息），SN与请求一致；Event: presence
        notify_request = create_notify_request(
            device.device_id,
            device.device_name,
            device.local_ip,
            device.local_port,
            device.server_ip,
            device.server_port,
            device.channels,
            contact_ip=contact_ip,
            infoid=infoid,
            sn=sn,
            platform_id=platform_id,
            subscriber_tag=subscriber_tag,
            expires=expires
        )
        
        notify_send_time = time_module.time()
        device.socket.sendto(notify_request.encode('utf-8'), addr)
        elapsed_ms = (notify_send_time - request_time) * 1000
        
        print(f"✓ 已发送NOTIFY消息（包含Catalog信息）")
        print(f"  发送到: {addr[0]}:{addr[1]}")
        print(f"  总延迟: {elapsed_ms:.2f} 毫秒")
        print(f"  通道数量: {len(device.channels)}")
        print_sip_message_func(device.device_id, "send", notify_request, addr)
        
    except Exception as e:
        print(f"✗ 处理SUBSCRIBE请求时出错: {e}")
        import traceback
        traceback.print_exc()


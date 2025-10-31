#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MESSAGE消息处理模块"""

import re
import time
from sip_response import create_message_response
from xml_generators import create_device_info_xml, create_config_download_xml, create_catalog_xml


def extract_cmd_type(request_body):
    """从MESSAGE请求体中提取CmdType"""
    cmd_type = None
    if '<CmdType>' in request_body:
        match = re.search(r'<CmdType>(.*?)</CmdType>', request_body)
        if match:
            cmd_type = match.group(1)
    return cmd_type


def extract_sn(request_body):
    """从MESSAGE请求体中提取SN（序列号）"""
    sn = None
    if '<SN>' in request_body:
        match = re.search(r'<SN>(.*?)</SN>', request_body)
        if match:
            sn = match.group(1)
    return sn if sn else str(int(time.time()))


def extract_infoid(request_body):
    """从Catalog请求体中提取InfoID"""
    infoid = None
    if '<InfoID>' in request_body:
        match = re.search(r'<InfoID>(.*?)</InfoID>', request_body)
        if match:
            infoid = match.group(1).strip()
    return infoid


def handle_device_info(device, request_body, lines, addr, print_sip_message_func):
    """处理DeviceInfo请求"""
    print(f"  请求类型: 获取设备信息（DeviceInfo）")
    
    # 从请求中提取SN，保持一致
    sn = extract_sn(request_body)
    
    # 创建设备信息响应XML
    device_info_xml = create_device_info_xml(
        device.device_id,
        device.device_name,
        sn
    )
    
    # 创建MESSAGE响应（传入contact_ip以修复Via头）
    contact_ip = device.contact_ip if device.contact_ip else device.local_ip
    response = create_message_response(lines, contact_ip=contact_ip)
    body_length = len(device_info_xml.encode('utf-8'))
    response = response.replace('Content-Length: 0', f'Content-Type: Application/MANSCDP+xml\r\nContent-Length: {body_length}')
    response += '\r\n' + device_info_xml
    device.socket.sendto(response.encode('utf-8'), addr)
    
    # 打印发送的响应
    print_sip_message_func(device.device_id, "send", response, addr)
    
    print(f"✓ 已发送设备信息")


def handle_config_download(device, request_body, lines, addr, print_sip_message_func):
    """处理ConfigDownload请求"""
    print(f"  请求类型: 配置下载（ConfigDownload）")
    
    # 从请求中提取SN，保持一致
    sn = extract_sn(request_body)
    
    # 创建配置下载响应XML
    config_xml = create_config_download_xml(
        device.device_id,
        device.device_name,
        device.local_ip,
        device.local_port,
        device.password,
        sn
    )
    
    # 创建MESSAGE响应（传入contact_ip以修复Via头）
    contact_ip = device.contact_ip if device.contact_ip else device.local_ip
    response = create_message_response(lines, contact_ip=contact_ip)
    body_length = len(config_xml.encode('utf-8'))
    response = response.replace('Content-Length: 0', f'Content-Type: Application/MANSCDP+xml\r\nContent-Length: {body_length}')
    response += '\r\n' + config_xml
    device.socket.sendto(response.encode('utf-8'), addr)
    
    # 打印发送的响应
    print_sip_message_func(device.device_id, "send", response, addr)
    
    print(f"✓ 已发送配置信息")


def handle_catalog(device, request_body, lines, addr, print_sip_message_func):
    """处理Catalog请求"""
    import time as time_module
    import uuid
    request_time = time_module.time()
    print(f"  请求类型: 查询通道目录（Catalog）")
    print(f"  接收时间: {time_module.strftime('%H:%M:%S', time_module.localtime(request_time))}")
    
    try:
        # 从请求中提取SN和InfoID，保持一致
        sn = extract_sn(request_body)
        infoid = extract_infoid(request_body)
        print(f"  提取的SN: {sn}")
        if infoid:
            print(f"  提取的InfoID: {infoid}")
        
        # 步骤1：先对平台的MESSAGE查询立即回复200 OK（不带body）
        contact_ip = device.contact_ip if device.contact_ip else device.local_ip
        ok_response = create_message_response(lines, contact_ip=contact_ip)
        device.socket.sendto(ok_response.encode('utf-8'), addr)
        print_sip_message_func(device.device_id, "send", ok_response, addr)
        print("✓ 已发送第一步 200 OK")

        # 步骤2：再主动发送一个MESSAGE携带Catalog响应XML
        catalog_xml = create_catalog_xml(device.device_id, device.channels, sn, infoid=infoid)

        # 构造新的MESSAGE请求（设备 -> 平台）
        branch = device.generate_branch() if hasattr(device, 'generate_branch') else f"z9hG4bK{str(uuid.uuid4()).replace('-', '')[:20]}"
        call_id = device.generate_call_id() if hasattr(device, 'generate_call_id') else str(uuid.uuid4())[:32]
        cseq_num = device.generate_cseq() if hasattr(device, 'generate_cseq') else 1
        from_tag = str(uuid.uuid4())[:32]

        # 从原始请求的From头解析平台的源设备编码（用于Request-URI与To）
        platform_id = '3402000000'
        for h in lines:
            if h.startswith('From:') and 'sip:' in h:
                try:
                    platform_id = h.split('sip:')[1].split('@')[0]
                except Exception:
                    pass
                break

        send_ip = contact_ip
        catalog_bytes = catalog_xml.encode('gb2312')
        content_length = len(catalog_bytes)

        message_request = f"""MESSAGE sip:{platform_id}@{device.server_ip}:{device.server_port} SIP/2.0
Via: SIP/2.0/UDP {send_ip}:{device.local_port};branch={branch}
From: <sip:{device.device_id}@{device.server_ip}:{device.server_port}>;tag={from_tag}
To: <sip:{platform_id}@{device.server_ip}:{device.server_port}>
Call-ID: {call_id}
CSeq: {cseq_num} MESSAGE
Content-Type: Application/MANSCDP+xml
User-Agent: GB28181-Device/1.0
Max-Forwards: 70
Content-Length: {content_length}

{catalog_xml}"""
        message_request = message_request.replace('\n', '\r\n')

        send_time = time_module.time()
        device.socket.sendto(message_request.encode('utf-8'), (device.server_ip, device.server_port))
        elapsed_ms = (send_time - request_time) * 1000
        print_sip_message_func(device.device_id, "send", message_request, (device.server_ip, device.server_port))
        print(f"✓ 已发送第二步 Catalog MESSAGE（通道数: {len(device.channels)}），耗时 {elapsed_ms:.2f} 毫秒")
    except Exception as e:
        print(f"✗ 处理Catalog请求时出错: {e}")
        import traceback
        traceback.print_exc()


def handle_message_request(device, request_body, lines, addr, print_sip_message_func):
    """处理MESSAGE请求"""
    print(f"\n收到MESSAGE请求 (设备: {device.device_id})")
    
    # 解析MESSAGE请求类型
    cmd_type = extract_cmd_type(request_body)
    
    # 调试：打印请求体内容
    if request_body:
        print(f"  请求体预览: {request_body[:300]}...")
    print(f"  提取的CmdType: {cmd_type}")
    
    # 使用大小写不敏感的比较
    cmd_type_lower = cmd_type.lower() if cmd_type else None
    
    if cmd_type_lower == 'deviceinfo':
        handle_device_info(device, request_body, lines, addr, print_sip_message_func)
    elif cmd_type_lower == 'configdownload':
        handle_config_download(device, request_body, lines, addr, print_sip_message_func)
    elif cmd_type_lower == 'catalog':
        handle_catalog(device, request_body, lines, addr, print_sip_message_func)
    else:
        print(f"  请求类型: {cmd_type or '未知'} (未匹配到已知类型)")
        # 打印请求体内容以便调试
        if request_body:
            print(f"  完整请求体:\n{request_body}")
        # 其他类型的MESSAGE请求，返回200 OK（传入contact_ip以修复Via头）
        contact_ip = device.contact_ip if device.contact_ip else device.local_ip
        response = create_message_response(lines, contact_ip=contact_ip)
        device.socket.sendto(response.encode('utf-8'), addr)
        print_sip_message_func(device.device_id, "send", response, addr)
        print(f"✓ 已发送通用响应")


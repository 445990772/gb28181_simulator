#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""INVITE消息处理模块"""

import os
import time
from sip_response import create_invite_response
from stream_handler import start_stream_push


def parse_invite_request(lines):
    """解析INVITE请求，提取SDP信息"""
    sdp_info = {}
    in_sdp = False
    sdp_lines = []
    
    for line in lines:
        if line.strip() == '':
            in_sdp = True
            continue
        if in_sdp:
            sdp_lines.append(line)
    
    sdp_body = '\r\n'.join(sdp_lines)
    
    # 解析SDP
    if not sdp_body:
        return None
    
    result = {
        'video_port': None,
        'audio_port': None,
        'ip': None,
        'ssrc': None
    }
    
    # 解析SDP
    sdp_lines_parsed = sdp_body.split('\r\n')
    for line in sdp_lines_parsed:
        if line.startswith('c=IN IP4 '):
            result['ip'] = line.split()[2]
        elif line.startswith('m=video '):
            result['video_port'] = int(line.split()[1])
        elif line.startswith('m=audio '):
            result['audio_port'] = int(line.split()[1])
        elif 'y=' in line:
            result['ssrc'] = line.split('=')[1].strip()
    
    return result


def extract_channel_id(request_line):
    """从请求行提取通道ID"""
    # INVITE sip:34020000001320000001@192.168.1.100:5060 SIP/2.0
    try:
        if '@' in request_line:
            part = request_line.split('sip:')[1].split('@')[0]
            return part
    except:
        pass
    return None


def handle_invite_request(device, lines, addr, print_sip_message_func):
    """处理INVITE请求"""
    print(f"\n收到平台INVITE指令 (设备: {device.device_id})")
    
    # 解析SDP获取推流地址
    sdp = parse_invite_request(lines)
    if sdp:
        print(f"  推流地址: {sdp['ip']}:{sdp.get('video_port', 'N/A')}")
        print(f"  SSRC: {sdp.get('ssrc', 'N/A')}")
        
        # 发送200 OK响应
        response = create_invite_response(lines)
        device.socket.sendto(response.encode('utf-8'), addr)
        
        # 打印发送的响应
        print_sip_message_func(device.device_id, "send", response, addr)
        
        # 启动推流（使用笔记本摄像头）
        # 默认使用第一个可用的avcapture地址
        # 用户可以在启动时配置，这里使用默认值
        avcapture_url = os.environ.get('AVCAPTURE_URL', 'avcapture://6C707041-05AC-0010-0008-000000000001')
        
        # 提取通道ID（从INVITE的To或Request-URI中）
        first_line = lines[0]
        channel_id = extract_channel_id(first_line)
        
        target_port = sdp.get('video_port', 5004)
        ssrc = sdp.get('ssrc', str(int(time.time() * 1000) % 100000000))
        
        stream_process = start_stream_push(
            device.device_id,
            device.stream_process,
            avcapture_url,
            sdp['ip'],
            target_port,
            ssrc,
            channel_id or device.channels[0]['id']
        )
        
        if stream_process:
            device.stream_process = stream_process
            print(f"✓ 推流已启动到 {sdp['ip']}:{target_port}")
            return True
    
    return False


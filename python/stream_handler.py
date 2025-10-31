#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""推流处理模块"""

import subprocess
import os


def start_stream_push(device_id, stream_process, avcapture_url, target_ip, target_port, ssrc, channel_id):
    """启动FFmpeg推流到指定地址"""
    if stream_process and stream_process.poll() is None:
        print(f"⚠ 设备 {device_id} 已有推流在进行，先停止旧的推流")
        stop_stream_push(stream_process)
    
    # 构建RTP推流地址
    # GB28181通常使用RTP over UDP推流
    # 这里我们使用FFmpeg的rtp_mpegts输出
    
    rtp_url = f"rtp://{target_ip}:{target_port}"
    
    print(f"\n开始推流:")
    print(f"  输入: {avcapture_url}")
    print(f"  输出: {rtp_url}")
    print(f"  SSRC: {ssrc}")
    print(f"  通道: {channel_id}")
    
    # 构建FFmpeg命令
    # GB28181通常使用MPEG-TS over RTP推流
    # SSRC在RTP协议层设置，FFmpeg的rtp_mpegts输出会处理
    cmd = [
        'ffmpeg',
        '-i', avcapture_url,
        '-c:v', 'libx264',
        '-preset', 'veryfast',
        '-tune', 'zerolatency',
        '-b:v', '2000k',
        '-maxrate', '2000k',
        '-bufsize', '4000k',
        '-g', '50',
        '-pix_fmt', 'yuv420p',
        '-flags', '+global_header',
        '-f', 'rtp_mpegts',  # GB28181通常使用MPEG-TS over RTP
        rtp_url
    ]
    
    try:
        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        print(f"✓ 推流已启动，进程ID: {process.pid}")
        return process
    except Exception as e:
        print(f"✗ 启动推流失败: {e}")
        return None


def stop_stream_push(stream_process):
    """停止推流"""
    if stream_process:
        try:
            stream_process.terminate()
            stream_process.wait(timeout=5)
            print("✓ 推流已停止")
            return True
        except:
            if stream_process:
                stream_process.kill()
                return True
    return False


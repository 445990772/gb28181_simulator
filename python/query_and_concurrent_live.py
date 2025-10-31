#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
查询指定平台的全部视频设备及其通道，并发发起点播（live.mp4）请求做压测。

用法示例：
  python query_and_concurrent_live.py

说明：
- 并发开启多个通道的视频流点播
- 每个通道播放300秒（5分钟）后自动停止
- 每个播放流独立开启接收数据统计，每秒输出全局统计信息
"""

import sys
import time
from typing import Dict, List, Tuple
import requests
import threading
import queue
import concurrent.futures

# 全局统计管理器（线程安全）
class GlobalStats:
    def __init__(self):
        self.lock = threading.Lock()
        self.active_streams = {}  # {stream_key: {bytes_total, bytes_last_sec, last_update_time, start_time}}
        self.global_stats_stop = threading.Event()
        self.global_stats_thread = None
        self.start_play_time = None  # 开始播放的时间
    
    def register_stream(self, stream_key: str):
        """注册一个播放流"""
        with self.lock:
            current_time = time.time()
            if self.start_play_time is None:
                self.start_play_time = current_time
            self.active_streams[stream_key] = {
                'bytes_total': 0,
                'bytes_last_sec': 0,
                'last_update_time': current_time,
                'start_time': current_time
            }
    
    def unregister_stream(self, stream_key: str):
        """注销一个播放流"""
        with self.lock:
            self.active_streams.pop(stream_key, None)
    
    def update_bytes(self, stream_key: str, bytes_count: int):
        """更新流的字节统计"""
        with self.lock:
            if stream_key in self.active_streams:
                self.active_streams[stream_key]['bytes_total'] += bytes_count
                current_time = time.time()
                # 计算每秒新增的字节
                elapsed = current_time - self.active_streams[stream_key]['last_update_time']
                if elapsed >= 1.0:
                    self.active_streams[stream_key]['bytes_last_sec'] = bytes_count
                    self.active_streams[stream_key]['last_update_time'] = current_time
                else:
                    # 累加当前秒的字节
                    self.active_streams[stream_key]['bytes_last_sec'] += bytes_count
    
    def get_stats(self):
        """获取当前统计信息"""
        with self.lock:
            active_count = len(self.active_streams)
            total_bytes = sum(stream['bytes_total'] for stream in self.active_streams.values())
            total_mb = total_bytes / (1024 * 1024)
            # 计算开始播放时长
            play_duration = 0
            if self.start_play_time is not None:
                play_duration = time.time() - self.start_play_time
            return active_count, total_mb, play_duration
    
    def start_global_stats_thread(self):
        """启动全局统计线程"""
        def stats_loop():
            last_time = time.time()
            while not self.global_stats_stop.is_set():
                current_time = time.time()
                if current_time - last_time >= 1.0:
                    active_count, total_mb, play_duration = self.get_stats()
                    # 灰绿色输出全局统计，包含开始播放时长
                    print(f"\033[38;5;245m═══════════════════════════════════════════════════════════════════════════════\033[0m")
                    print(f"\033[38;5;245m[全局统计] │ 播放路数: {active_count} 路 │ 总流量: {total_mb:.3f} MB │ 开始播放时长: {play_duration:.0f}s\033[0m")
                    print(f"\033[38;5;245m═══════════════════════════════════════════════════════════════════════════════\033[0m")
                    last_time = current_time
                time.sleep(0.1)
        
        self.global_stats_thread = threading.Thread(target=stats_loop, daemon=True)
        self.global_stats_thread.start()
    
    def stop_global_stats(self):
        """停止全局统计"""
        self.global_stats_stop.set()
        if self.global_stats_thread:
            self.global_stats_thread.join(timeout=2)
        # 重置开始播放时间，以便下次统计时重新开始
        with self.lock:
            self.start_play_time = None

# 全局统计实例
_global_stats = GlobalStats()


def post_json(url: str, json_body: dict, token: str, timeout: float = 10.0) -> dict:
    headers = {
        # JetLinks 常见两种写法都带上，平台会择一识别
        'X-Access-Token': token,
        'X_Access_Token': token,
        'Content-Type': 'application/json',
    }
    resp = requests.post(url, json=json_body, headers=headers, timeout=timeout)
    resp.raise_for_status()
    return resp.json()

def paginate_devices(base_url: str, token: str, page_size: int = 100) -> List[Dict]:
    devices: List[Dict] = []
    page_index = 0
    while True:
        body = {
            "pageIndex": page_index,
            "pageSize": page_size,
            "sorts": [{"name": "createTime", "order": "desc"}],
            "terms": [],
        }
        data = post_json(f"{base_url}/api/media/device/_query/", body, token)
        arr = (data or {}).get("result", {}).get("data", [])
        if not arr:
            break
        devices.extend(arr)
        if len(arr) < page_size:
            break
        page_index += 1
    return devices

def paginate_channels(base_url: str, device_id: str, token: str, page_size: int = 200) -> List[Dict]:
    channels: List[Dict] = []
    page_index = 0
    while True:
        body = {
            "pageIndex": page_index,
            "pageSize": page_size,
            "sorts": [{"name": "modifyTime", "order": "desc"}],
            "terms": [],
        }
        data = post_json(
            f"{base_url}/api/media/device/{device_id}/channel/_query", body, token
        )
        arr = (data or {}).get("result", {}).get("data", [])
        if not arr:
            break
        channels.extend(arr)
        if len(arr) < page_size:
            break
        page_index += 1
    return channels

def pull_live_stream(base_url: str, device_id: str, channel_id: str, token: str, 
                     duration: int = 300, timeout: float = 30.0, is_retry: bool = False):
    """启动一个 live.mp4 流，持续播放指定时长，并实时统计接收数据。
    
    Args:
        base_url: 平台根地址
        device_id: 设备ID
        channel_id: 通道ID
        token: 访问令牌
        duration: 播放时长（秒），默认600秒
        timeout: HTTP超时时间（秒）
        is_retry: 是否为重试播放
    
    Returns:
        bool: 播放是否成功
    """
    url = f"{base_url}/api/media/device/{device_id}/{channel_id}/live.mp4"
    params = {":X_Access_Token": token}
    byte_q = queue.Queue()
    stop_flag = threading.Event()
    start_time = time.time()
    total_bytes = 0
    stream_key = f"{device_id}/{channel_id}"
    
    # 注册到全局统计
    _global_stats.register_stream(stream_key)
    
    def stat_thread():
        """统计线程：更新全局统计，不输出单个通道的统计"""
        while not stop_flag.is_set():
            # 收集队列中的所有字节
            bytes_this_cycle = 0
            try:
                while True:
                    b = byte_q.get_nowait()
                    bytes_this_cycle += b
            except queue.Empty:
                pass
            
            # 更新全局统计
            if bytes_this_cycle > 0:
                _global_stats.update_bytes(stream_key, bytes_this_cycle)
            
            # 检查是否达到播放时长
            elapsed_from_start = time.time() - start_time
            if elapsed_from_start >= duration:
                stop_flag.set()
                break
            
            time.sleep(0.1)  # 短暂休眠避免CPU占用过高
        
        # 最终统计 - 收集剩余字节
        final_bytes_this_cycle = 0
        try:
            while True:
                b = byte_q.get_nowait()
                final_bytes_this_cycle += b
        except queue.Empty:
            pass
        
        # 更新全局统计最后一次
        if final_bytes_this_cycle > 0:
            _global_stats.update_bytes(stream_key, final_bytes_this_cycle)
    
    # 启动统计线程
    stat_t = threading.Thread(target=stat_thread, daemon=False)
    stat_t.start()
    
    play_success = False
    
    try:
        headers = {
            'X-Access-Token': token,
            'X_Access_Token': token,
        }
        
        with requests.get(url, params=params, headers=headers, stream=True, timeout=timeout) as r:
            r.raise_for_status()
            
            # 持续接收数据直到达到播放时长或流结束
            for chunk in r.iter_content(chunk_size=64 * 1024):
                if stop_flag.is_set():
                    break
                if chunk:
                    chunk_len = len(chunk)
                    total_bytes += chunk_len
                    byte_q.put(chunk_len)
            
            # 如果接收到数据，认为播放成功
            play_success = total_bytes > 0
        
        stop_flag.set()
        
    except Exception as e:
        stop_flag.set()
        play_success = False
    finally:
        # 注销全局统计
        _global_stats.unregister_stream(stream_key)
    
    # 等待统计线程结束
    stat_t.join(timeout=5)
    
    return play_success

def main():
    # 纯交互式输入
    base_url = input("平台根地址（默认: http://192.168.32.84:9000: ").strip() or "http://192.168.32.84:9000"
    base_url = base_url.rstrip("/")
    token = input(":X_Access_Token（必填）: ").strip()
    while not token:
        token = input(":X_Access_Token不能为空，请重新输入: ").strip()
    try:
        per_device_limit = int(input("每设备通道上限（0为不限制，默认: 0）: ").strip() or 0)
    except Exception:
        per_device_limit = 0
    try:
        play_duration = int(input("每个通道播放时长（秒，默认: 300）: ").strip() or 300)
    except Exception:
        play_duration = 300
    try:
        concurrency = int(input("并发线程数（默认: 20）: ").strip() or 20)
    except Exception:
        concurrency = 20
    try:
        connect_timeout = float(input("HTTP超时秒（默认: 30）: ").strip() or 30)
    except Exception:
        connect_timeout = 30.0

    print(f"查询设备列表: {base_url}")
    devices = paginate_devices(base_url, token)
    print(f"设备数量: {len(devices)}")

    targets: List[Tuple[str, str]] = []
    for dev in devices:
        dev_id = dev.get("id") or dev.get("deviceId") or dev.get("deviceID")
        if not dev_id:
            continue
        chans = paginate_channels(base_url, dev_id, token)
        if per_device_limit > 0:
            chans = chans[: per_device_limit]
        for ch in chans:
            ch_id = ch.get("channelId") or ch.get("id")
            if ch_id:
                targets.append((dev_id, ch_id))

    print(f"通道总数: {len(targets)}，开始并发播放（每个通道播放 {play_duration} 秒，并发数: {concurrency}）…")
    print("=" * 60)

    # 启动全局统计线程
    _global_stats.start_global_stats_thread()

    # 失败重试集合（线程安全）
    failed_streams_lock = threading.Lock()
    failed_streams = []  # 存储播放失败的通道 [(dev_id, ch_id), ...]
    t_start = time.time()

    # 并发开启每个通道的播放
    def start_stream(d_id, c_id, result_list, lock):
        success = pull_live_stream(base_url, d_id, c_id, token, duration=play_duration, timeout=connect_timeout)
        if not success:
            with lock:
                result_list.append((d_id, c_id))
        return success

    print("\n" + "=" * 60)
    print(f"已启动 {len(targets)} 个通道并发播放，等待所有播放完成...")
    print("=" * 60)

    # 使用线程池并发执行
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {
            executor.submit(start_stream, dev_id, ch_id, failed_streams, failed_streams_lock): (dev_id, ch_id)
            for dev_id, ch_id in targets
        }
        
        # 等待所有任务完成
        for future in concurrent.futures.as_completed(futures):
            dev_id, ch_id = futures[future]
            try:
                future.result()
            except Exception as e:
                # 如果执行出错，添加到失败列表
                with failed_streams_lock:
                    failed_streams.append((dev_id, ch_id))

    # 停止全局统计线程
    _global_stats.stop_global_stats()

    # 如果有播放失败的通道，进行重试
    if failed_streams:
        print("\n" + "=" * 60)
        print(f"\033[33m发现 {len(failed_streams)} 个播放失败的通道，开始重试...\033[0m")
        print("=" * 60)
        
        # 重新启动全局统计线程用于重试统计
        _global_stats.start_global_stats_thread()
        
        retry_start_time = time.time()
        
        def retry_stream(d_id, c_id):
            pull_live_stream(base_url, d_id, c_id, token, duration=play_duration, timeout=connect_timeout, is_retry=True)
        
        print("\n" + "=" * 60)
        print(f"已启动 {len(failed_streams)} 个重试通道并发播放，等待所有重试完成...")
        print("=" * 60)
        
        # 使用线程池并发执行重试
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = {
                executor.submit(retry_stream, dev_id, ch_id): (dev_id, ch_id)
                for dev_id, ch_id in failed_streams
            }
            
            # 等待所有重试任务完成
            for future in concurrent.futures.as_completed(futures):
                dev_id, ch_id = futures[future]
                try:
                    future.result()
                except Exception:
                    pass  # 重试失败不处理
        
        # 停止全局统计线程
        _global_stats.stop_global_stats()
        
        retry_dur = time.time() - retry_start_time
        print("\n" + "=" * 60)
        print(f"重试播放完成，耗时 {retry_dur:.2f}s")
        print("=" * 60)

    dur = time.time() - t_start
    print("\n" + "=" * 60)
    print(f"所有通道播放完成，总耗时 {dur:.2f}s")
    print(f"已处理通道总数: {len(targets)}")
    if failed_streams:
        print(f"失败通道数: {len(failed_streams)}")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GB28181设备模拟器 - Python后端API服务
提供RESTful API接口供Web前端调用
"""

import sys
import os
import threading
import time
import json
import psutil

# 添加python目录到路径
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'python'))

from flask import Flask, request, jsonify, send_from_directory, Response, stream_with_context
from flask_cors import CORS
from gb28181_device_simulator import GB28181DeviceSimulator

# 获取web目录路径
WEB_DIR = os.path.join(os.path.dirname(__file__))

app = Flask(__name__, static_folder=os.path.join(WEB_DIR, 'static'), 
            template_folder=WEB_DIR)
CORS(app)  # 允许跨域请求

# 添加错误处理器，确保404返回JSON而不是HTML
@app.errorhandler(404)
def not_found(error):
    """处理404错误，API路径返回JSON，其他返回HTML"""
    if request.path.startswith('/api/'):
        return jsonify({'error': 'API endpoint not found', 'path': request.path}), 404
    # 对于非API路径，返回默认的404页面
    return send_from_directory(WEB_DIR, 'index.html'), 404

# 全局模拟器实例
simulator = None
simulator_thread = None
running = False
# 消息存储（用于Web展示）
message_store = []
MAX_MESSAGES = 1000  # 最多保存1000条消息

@app.route('/api/health', methods=['GET'])
def health():
    """健康检查接口"""
    return jsonify({
        'status': 'ok',
        'message': 'Python后端服务运行正常',
        'backend': 'python'
    })

@app.route('/api/start', methods=['POST'])
def start():
    """启动模拟器"""
    global simulator, simulator_thread, running
    
    if running:
        return jsonify({'error': '模拟器已在运行中'}), 400
    
    try:
        data = request.get_json()
        
        # 验证必需参数
        required_fields = ['serverIp', 'serverPort', 'password', 'deviceCount', 'channelCount']
        for field in required_fields:
            if field not in data:
                return jsonify({'error': f'缺少必需参数: {field}'}), 400
        
        # 创建新的模拟器实例
        simulator = GB28181DeviceSimulator()
        
        # 配置参数
        server_ip = data.get('serverIp', '192.168.32.84')
        server_port = int(data.get('serverPort', 8809))
        password = data.get('password', '123456')
        device_count = int(data.get('deviceCount', 3))
        channel_count = int(data.get('channelCount', 1))
        base_device_id = data.get('baseDeviceId', '3402000000132000')
        base_port = int(data.get('basePort', 15060))
        
        # 确定本地IP
        default_local_ip = "127.0.0.1"
        if server_ip != "127.0.0.1" and server_ip != "localhost":
            default_local_ip = "0.0.0.0"
        
        # 创建设备
        for i in range(device_count):
            device_id = f"{base_device_id}{i+1:04d}"
            device_name = f"模拟设备{i+1}"
            local_ip = default_local_ip
            local_port = base_port + i
            
            simulator.create_device(
                device_id=device_id,
                device_name=device_name,
                local_ip=local_ip,
                local_port=local_port,
                server_ip=server_ip,
                server_port=server_port,
                password=password,
                channel_count=channel_count
            )
        
        # 在后台线程中启动模拟器
        running = True
        simulator_thread = threading.Thread(target=run_simulator, daemon=True)
        simulator_thread.start()
        
        return jsonify({
            'success': True,
            'message': '模拟器启动成功',
            'deviceCount': device_count,
            'channelCount': channel_count,
            'totalChannels': device_count * channel_count
        })
        
    except Exception as e:
        running = False
        return jsonify({'error': str(e)}), 500

def message_callback(device_id, direction, message, addr):
    """消息回调函数，用于记录SIP消息"""
    global message_store
    
    # 提取消息摘要（第一行）
    lines = message.split('\n')
    summary = lines[0] if lines else message[:100]
    
    # 尝试从消息内容中提取通道ID
    channel_id = None
    if lines:
        first_line = lines[0]
        # 从INVITE请求中提取：INVITE sip:通道ID@...
        if 'INVITE' in first_line and 'sip:' in first_line and '@' in first_line:
            try:
                part = first_line.split('sip:')[1].split('@')[0]
                channel_id = part
            except:
                pass
        # 从MESSAGE请求中提取：MESSAGE sip:通道ID@...
        elif 'MESSAGE' in first_line and 'sip:' in first_line and '@' in first_line:
            try:
                part = first_line.split('sip:')[1].split('@')[0]
                channel_id = part
            except:
                pass
        # 从To头中提取通道ID（对于响应消息）
        elif direction == 'send':
            for line in lines:
                if line.startswith('To:') and 'sip:' in line and '@' in line:
                    try:
                        # To: <sip:通道ID@...> 或 To: sip:通道ID@...
                        if '<' in line:
                            part = line.split('sip:')[1].split('@')[0]
                        else:
                            part = line.split('sip:')[1].split('@')[0]
                        channel_id = part
                        break
                    except:
                        pass
    
    # 存储消息（不清理，由前端处理）
    message_entry = {
        'deviceId': device_id,
        'direction': 'send' if direction == 'send' else 'recv',
        'summary': summary,
        'content': message,
        'timestamp': time.time()
    }
    
    # 如果提取到了通道ID，添加到消息条目中
    if channel_id:
        message_entry['channelId'] = channel_id
    
    message_store.append(message_entry)
    
    # 限制消息数量
    if len(message_store) > MAX_MESSAGES:
        message_store = message_store[-MAX_MESSAGES:]

def run_simulator():
    """在后台线程中运行模拟器"""
    global simulator, running
    try:
        # 设置消息回调
        original_print = simulator.print_sip_message
        def wrapped_print(device_id, direction, message, addr):
            original_print(device_id, direction, message, addr)
            message_callback(device_id, direction, message, addr)
        simulator.print_sip_message = wrapped_print
        
        simulator.start_all_devices()
    except Exception as e:
        print(f"模拟器运行错误: {e}")
    finally:
        running = False

@app.route('/api/stop', methods=['POST'])
def stop():
    """停止模拟器"""
    global simulator, running
    
    if not running:
        return jsonify({'error': '模拟器未运行'}), 400
    
    try:
        if simulator:
            simulator.running = False
            for device in simulator.devices:
                device.stop_stream_push()
            running = False
        
        return jsonify({
            'success': True,
            'message': '模拟器已停止'
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/status', methods=['GET'])
def status():
    """获取模拟器状态"""
    global simulator, running
    
    try:
        devices_info = []
        
        if simulator and running:
            for device in simulator.devices:
                devices_info.append({
                    'deviceId': device.device_id,
                    'deviceName': device.device_name,
                    'localIp': device.local_ip,
                    'localPort': device.local_port,
                    'serverIp': device.server_ip,
                    'serverPort': device.server_port,
                    'isRegistered': device.is_registered,
                    'channelCount': len(device.channels),
                    'channels': [
                        {
                            'id': ch.get('id'),
                            'name': ch.get('name')
                        }
                        for ch in device.channels
                    ]
                })
        
        return jsonify({
            'running': running,
            'deviceCount': len(devices_info),
            'devices': devices_info
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/stats', methods=['GET'])
def stats():
    """获取统计数据（每秒发送数据包大小）"""
    global simulator, running
    
    try:
        devices_stats = []
        
        if simulator and running:
            for device in simulator.devices:
                # 获取设备的带宽统计
                total_bytes_per_second = 0
                
                # 从relay_state获取每个通道的带宽
                for channel_id, state in device.relay_state.items():
                    if state.get('running', False):
                        # 计算每秒字节数（从bytes_in_second获取）
                        bytes_in_second = state.get('bytes_in_second', 0)
                        total_bytes_per_second += bytes_in_second
                
                devices_stats.append({
                    'deviceId': device.device_id,
                    'deviceName': device.device_name,
                    'bytesPerSecond': total_bytes_per_second,
                    'mbps': total_bytes_per_second / (1024 * 1024)
                })
        
        return jsonify({
            'devices': devices_stats
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/device/<device_id>/stats', methods=['GET'])
def device_stats(device_id):
    """获取指定设备的统计数据（按通道）"""
    global simulator, running
    
    try:
        if not simulator or not running:
            return jsonify({'error': '模拟器未运行'}), 400
        
        device = None
        for d in simulator.devices:
            if d.device_id == device_id:
                device = d
                break
        
        if not device:
            return jsonify({'error': '设备未找到'}), 404
        
        channels_stats = []
        
        # 从relay_state获取每个通道的带宽
        for channel_id, state in device.relay_state.items():
            if state.get('running', False):
                bytes_in_second = state.get('bytes_in_second', 0)
                channels_stats.append({
                    'channelId': channel_id,
                    'bytesPerSecond': bytes_in_second,
                    'mbps': bytes_in_second / (1024 * 1024)
                })
        
        # 如果没有运行中的通道，返回所有通道的零数据
        if not channels_stats:
            for channel in device.channels:
                channel_id = channel.get('id')
                channels_stats.append({
                    'channelId': channel_id,
                    'bytesPerSecond': 0,
                    'mbps': 0.0
                })
        
        return jsonify({
            'deviceId': device_id,
            'channels': channels_stats
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/messages', methods=['GET'])
def messages():
    """获取SIP消息记录"""
    global message_store
    
    try:
        # 返回最近的消息（最多100条）
        recent_messages = message_store[-100:] if len(message_store) > 100 else message_store
        
        return jsonify({
            'messages': recent_messages
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/device/<device_id>/messages', methods=['GET'])
def device_messages(device_id):
    """获取指定设备的消息记录"""
    global message_store
    
    try:
        # 获取查询参数
        channel_id = request.args.get('channelId')
        since = request.args.get('since', type=int, default=0)
        
        # 过滤消息
        filtered_messages = []
        for msg in message_store:
            if msg.get('deviceId') == device_id:
                # 如果指定了通道ID，进行过滤
                if channel_id and channel_id != 'all':
                    if msg.get('channelId') != channel_id:
                        continue
                filtered_messages.append(msg)
        
        # 如果指定了since参数，只返回新消息
        if since > 0:
            filtered_messages = filtered_messages[since:]
        
        # 返回最近的消息（最多200条）
        recent_messages = filtered_messages[-200:] if len(filtered_messages) > 200 else filtered_messages
        
        return jsonify({
            'messages': recent_messages
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/system/resources', methods=['GET'])
def system_resources():
    """获取系统资源统计信息（CPU、内存、网络）"""
    try:
        # CPU使用率
        cpu_percent = psutil.cpu_percent(interval=0.1)
        cpu_count = psutil.cpu_count()
        
        # 内存使用情况
        memory = psutil.virtual_memory()
        memory_total = memory.total
        memory_used = memory.used
        memory_percent = memory.percent
        memory_available = memory.available
        
        # 网络收发包情况
        net_io = psutil.net_io_counters()
        bytes_sent = net_io.bytes_sent
        bytes_recv = net_io.bytes_recv
        packets_sent = net_io.packets_sent
        packets_recv = net_io.packets_recv
        
        return jsonify({
            'cpu': {
                'percent': cpu_percent,
                'count': cpu_count
            },
            'memory': {
                'total': memory_total,
                'used': memory_used,
                'available': memory_available,
                'percent': memory_percent
            },
            'network': {
                'bytesSent': bytes_sent,
                'bytesRecv': bytes_recv,
                'packetsSent': packets_sent,
                'packetsRecv': packets_recv
            },
            'timestamp': time.time()
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/system/resources/stream', methods=['GET'])
def system_resources_stream():
    """SSE流：推送系统资源统计数据"""
    def generate():
        last_network_stats = {'bytesSent': 0, 'bytesRecv': 0, 'timestamp': time.time()}
        
        while True:
            try:
                # CPU使用率
                cpu_percent = psutil.cpu_percent(interval=0.1)
                cpu_count = psutil.cpu_count()
                
                # 内存使用情况
                memory = psutil.virtual_memory()
                memory_total = memory.total
                memory_used = memory.used
                memory_percent = memory.percent
                memory_available = memory.available
                
                # 网络收发包情况
                net_io = psutil.net_io_counters()
                bytes_sent = net_io.bytes_sent
                bytes_recv = net_io.bytes_recv
                packets_sent = net_io.packets_sent
                packets_recv = net_io.packets_recv
                
                current_timestamp = time.time()
                
                # 计算网络速率
                time_diff = current_timestamp - last_network_stats['timestamp']
                sent_rate = 0
                recv_rate = 0
                if time_diff > 0:
                    sent_rate = (bytes_sent - last_network_stats['bytesSent']) / time_diff / 1024  # KB/s
                    recv_rate = (bytes_recv - last_network_stats['bytesRecv']) / time_diff / 1024  # KB/s
                
                last_network_stats = {
                    'bytesSent': bytes_sent,
                    'bytesRecv': bytes_recv,
                    'timestamp': current_timestamp
                }
                
                data = {
                    'type': 'update',
                    'cpu': {
                        'percent': cpu_percent,
                        'count': cpu_count
                    },
                    'memory': {
                        'total': memory_total,
                        'used': memory_used,
                        'available': memory_available,
                        'percent': memory_percent
                    },
                    'network': {
                        'bytesSent': bytes_sent,
                        'bytesRecv': bytes_recv,
                        'packetsSent': packets_sent,
                        'packetsRecv': packets_recv,
                        'sentRate': sent_rate,
                        'recvRate': recv_rate
                    },
                    'timestamp': current_timestamp
                }
                
                yield f"data: {json.dumps(data, ensure_ascii=False)}\n\n"
                
                time.sleep(2)  # 每2秒推送一次
                
            except Exception as e:
                error_data = {'type': 'error', 'message': str(e)}
                yield f"data: {json.dumps(error_data, ensure_ascii=False)}\n\n"
                break
    
    return Response(
        stream_with_context(generate()),
        mimetype='text/event-stream',
        headers={
            'Cache-Control': 'no-cache',
            'X-Accel-Buffering': 'no',
            'Connection': 'keep-alive'
        }
    )

@app.route('/api/device/<device_id>/info', methods=['GET'])
def device_info(device_id):
    """获取设备详细信息"""
    global simulator, running
    
    # 调试日志
    print(f"[API] 请求设备信息: device_id={device_id}, path={request.path}, method={request.method}")
    print(f"[API] 模拟器状态: running={running}, simulator={simulator is not None}")
    
    try:
        if not simulator or not running:
            print(f"[API] 错误: 模拟器未运行")
            return jsonify({'error': '模拟器未运行'}), 400
        
        # 调试日志
        print(f"[API] 设备数量={len(simulator.devices) if simulator else 0}")
        
        device = None
        for d in simulator.devices:
            if d.device_id == device_id:
                device = d
                break
        
        if not device:
            return jsonify({'error': '设备未找到'}), 404
        
        channels_info = []
        for channel in device.channels:
            channels_info.append({
                'id': channel.get('id'),
                'name': channel.get('name')
            })
        
        return jsonify({
            'deviceId': device.device_id,
            'deviceName': device.device_name,
            'localIp': device.local_ip,
            'localPort': device.local_port,
            'serverIp': device.server_ip,
            'serverPort': device.server_port,
            'isRegistered': device.is_registered,
            'channels': channels_info
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/device/<device_id>/stream', methods=['GET'])
def device_stream(device_id):
    """SSE流：推送统计数据和消息"""
    global simulator, running, message_store
    
    def generate():
        import time
        last_message_index = 0
        channel_filter = request.args.get('channelId', 'all')
        new_only = request.args.get('newOnly', 'false').lower() == 'true'
        
        # 如果只发送新消息，记录当前消息数量作为起始点
        if new_only:
            if channel_filter == 'all':
                initial_message_count = len([msg for msg in message_store if msg.get('deviceId') == device_id])
            else:
                initial_message_count = len([msg for msg in message_store 
                                           if msg.get('deviceId') == device_id and msg.get('channelId') == channel_filter])
            last_message_index = initial_message_count
        
        while True:
            try:
                # 获取统计数据
                channels_stats = []
                if simulator and running:
                    device = None
                    for d in simulator.devices:
                        if d.device_id == device_id:
                            device = d
                            break
                    
                    if device:
                        # 收集运行中的通道统计数据
                        running_stats_map = {}
                        for session_key, state in device.relay_state.items():
                            if state.get('running', False):
                                bytes_in_second = state.get('bytes_in_second', 0)
                                # session_key格式可能是 "channel_id@ip:port"，提取纯通道ID
                                pure_channel_id = session_key.split('@')[0] if '@' in session_key else session_key
                                # 如果该通道已有数据，累加；否则新建
                                if pure_channel_id in running_stats_map:
                                    running_stats_map[pure_channel_id]['bytesPerSecond'] += bytes_in_second
                                    running_stats_map[pure_channel_id]['mbps'] += bytes_in_second / (1024 * 1024)
                                else:
                                    running_stats_map[pure_channel_id] = {
                                        'channelId': pure_channel_id,
                                        'bytesPerSecond': bytes_in_second,
                                        'mbps': bytes_in_second / (1024 * 1024)
                                    }
                        
                        # 将运行中的统计数据添加到列表
                        channels_stats = list(running_stats_map.values())
                        
                        # 创建通道ID到通道信息的映射，用于添加通道名称
                        channel_map = {ch.get('id'): ch for ch in device.channels}
                        
                        # 为统计数据添加通道名称
                        for stat in channels_stats:
                            channel_id = stat.get('channelId')
                            channel_info = channel_map.get(channel_id)
                            if channel_info:
                                stat['channelName'] = channel_info.get('name') or channel_id
                            else:
                                stat['channelName'] = channel_id
                        
                        # 确保所有通道都有统计数据（包括未运行的通道）
                        # 先收集所有通道ID
                        all_channel_ids = {channel.get('id') for channel in device.channels}
                        running_channel_ids = {stat['channelId'] for stat in channels_stats}
                        
                        # 为未运行的通道添加零数据
                        for channel in device.channels:
                            channel_id = channel.get('id')
                            if channel_id not in running_channel_ids:
                                channel_info = channel_map.get(channel_id)
                                channel_name = channel_info.get('name') if channel_info else channel_id
                                channels_stats.append({
                                    'channelId': channel_id,
                                    'channelName': channel_name,
                                    'bytesPerSecond': 0,
                                    'mbps': 0.0
                                })
                
                # 获取新消息
                new_messages = []
                if channel_filter == 'all':
                    filtered_messages = [msg for msg in message_store if msg.get('deviceId') == device_id]
                else:
                    filtered_messages = [msg for msg in message_store 
                                       if msg.get('deviceId') == device_id and msg.get('channelId') == channel_filter]
                
                if last_message_index < len(filtered_messages):
                    new_messages = filtered_messages[last_message_index:]
                    # 为每条消息添加通道信息（channelId和channelName）
                    if device:
                        # 创建通道ID到通道信息的映射
                        channel_map = {ch.get('id'): ch for ch in device.channels}
                        
                        for msg in new_messages:
                            # 如果消息中没有channelId，尝试从消息内容中提取
                            if 'channelId' not in msg or not msg.get('channelId'):
                                # 尝试从INVITE请求中提取通道ID
                                content = msg.get('content', '')
                                if content:
                                    lines = content.split('\n')
                                    if lines and 'INVITE' in lines[0] and 'sip:' in lines[0]:
                                        try:
                                            # 提取 sip:通道ID@ 格式
                                            if '@' in lines[0]:
                                                part = lines[0].split('sip:')[1].split('@')[0]
                                                msg['channelId'] = part
                                        except:
                                            pass
                            
                            # 添加通道名称
                            channel_id = msg.get('channelId')
                            if channel_id:
                                channel_info = channel_map.get(channel_id)
                                if channel_info:
                                    msg['channelName'] = channel_info.get('name') or channel_id
                                else:
                                    msg['channelName'] = channel_id
                    
                    # 不清理消息内容，由前端处理
                    last_message_index = len(filtered_messages)
                
                # 发送数据
                data = {
                    'type': 'update',
                    'stats': {
                        'channels': channels_stats
                    },
                    'messages': new_messages
                }
                
                yield f"data: {json.dumps(data, ensure_ascii=False)}\n\n"
                
                time.sleep(1)  # 每秒推送一次
                
            except Exception as e:
                error_data = {'type': 'error', 'message': str(e)}
                yield f"data: {json.dumps(error_data, ensure_ascii=False)}\n\n"
                break
    
    return Response(
        stream_with_context(generate()),
        mimetype='text/event-stream',
        headers={
            'Cache-Control': 'no-cache',
            'X-Accel-Buffering': 'no',
            'Connection': 'keep-alive'
        }
    )

@app.route('/')
def index():
    """提供Web前端页面"""
    return send_from_directory(WEB_DIR, 'index.html')

@app.route('/<path:path>')
def serve_static(path):
    """提供静态文件"""
    # 排除 API 路径，避免覆盖 API 路由
    # Flask应该已经匹配了API路由，但如果到这里说明路由不存在
    if path.startswith('api/'):
        # 返回JSON格式的404错误
        return jsonify({'error': 'API endpoint not found', 'path': path}), 404
    
    if path.startswith('static/'):
        return send_from_directory(os.path.join(WEB_DIR, 'static'), path[7:])
    elif path == 'index.html':
        return send_from_directory(WEB_DIR, 'index.html')
    elif path.endswith('.html'):
        # 支持其他 HTML 文件，如 device-detail.html
        return send_from_directory(WEB_DIR, path)
    else:
        # 默认返回 index.html（用于前端路由）
        return send_from_directory(WEB_DIR, 'index.html')

if __name__ == '__main__':
    print("=" * 60)
    print("GB28181 设备模拟器 - Python API服务")
    print("=" * 60)
    print("API服务地址: http://localhost:5000")
    print("Web界面地址: http://localhost:5000/")
    print("=" * 60)
    
    # 启动Flask服务
    app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)


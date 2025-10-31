#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GB28181 设备模拟器
1. 模拟多个GB28181设备的连接、IPC通道信息
2. 接收平台下发的INVITE指令，将笔记本摄像头的H.264流推给平台
"""

import socket
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime
import subprocess
import hashlib
import signal
import os
import atexit
import tempfile

class GB28181Device:
    """GB28181设备类"""
    
    def __init__(self, device_id, device_name, ip, port, server_ip, server_port, password='admin123'):
        self.device_id = device_id  # 设备ID（20位）
        self.device_name = device_name
        self.local_ip = ip
        self.local_port = port
        self.server_ip = server_ip
        self.server_port = server_port
        self.password = password
        self.call_id_prefix = str(uuid.uuid4())[:8]
        self.call_id_seq = 0
        self.cseq = 0
        self.tag = str(uuid.uuid4())[:32]
        self.register_expires = 3600
        self.is_registered = False
        self.heartbeat_interval = 30  # 心跳间隔（秒）- 改为30秒
        self.retry_interval = 10  # 重试间隔（秒）
        self.socket = None
        self.channels = []  # IPC通道列表
        # 每通道独立的FFmpeg推流进程
        self.channel_id_to_process = {}
        self.contact_ip = None  # Contact头中使用的IP地址（用于0.0.0.0绑定情况）
        self.heartbeat_sn = 0  # 心跳消息序列号
        self.last_heartbeat = None  # 上次心跳时间，注册成功后重置
        # 每通道独立的UDP中继与带宽统计
        self.relay_state = {}
        # 会话跟踪：Call-ID -> [session_keys]
        self.callid_to_sessions = {}
        self.playlist_path = None

    def _start_udp_relay(self, channel_id: str, target_ip: str, target_port: int) -> int:
        """启动本地UDP中继（按通道），返回本地监听端口"""
        import threading
        self._stop_udp_relay(channel_id)
        sock_in = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock_in.bind(('127.0.0.1', 0))
        local_port = sock_in.getsockname()[1]
        sock_in.settimeout(1.0)
        sock_out = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        target = (target_ip, target_port)
        state = {
            'running': True,
            'sock_in': sock_in,
            'sock_out': sock_out,
            'thread': None,
            'bytes_in_second': 0,
            'last_ts': time.time(),
            'target': target,
            'last_recv': time.time()
        }
        self.relay_state[channel_id] = state

        def _relay_loop():
            while state['running']:
                try:
                    data, _ = sock_in.recvfrom(65535)
                    if not data:
                        continue
                    state['bytes_in_second'] += len(data)
                    sock_out.sendto(data, target)
                    state['last_recv'] = time.time()
                except socket.timeout:
                    pass
                except Exception:
                    if state['running']:
                        pass
                # 每秒打印一次带宽
                now = time.time()
                # 空闲超时自动停流（平台可能未发BYE），默认15秒
                if now - state['last_recv'] > 15:
                    print(f"  通道空闲超时，自动停止: {channel_id}")
                    try:
                        self.stop_stream_push(channel_id)
                    except Exception:
                        pass
                    break
                if now - state['last_ts'] >= 1.0:
                    mbps = state['bytes_in_second'] / (1024 * 1024)
                    COLOR_DARK_GREEN = '\033[2;32m'  # 深灰绿色
                    COLOR_RESET = '\033[0m'
                    print(f"{COLOR_DARK_GREEN}  带宽[{channel_id}]: {mbps:.3f} MB/s -> {target[0]}:{target[1]}{COLOR_RESET}")
                    state['bytes_in_second'] = 0
                    state['last_ts'] = now

        t = threading.Thread(target=_relay_loop, daemon=True)
        state['thread'] = t
        t.start()
        return local_port

    def _stop_udp_relay(self, channel_id: str = None):
        """停止UDP中继（单通道或全部）"""
        if channel_id is None:
            keys = list(self.relay_state.keys())
            for k in keys:
                self._stop_udp_relay(k)
            return
        state = self.relay_state.get(channel_id)
        if not state:
            return
        state['running'] = False
        # 等待线程退出，避免继续打印统计
        try:
            t = state.get('thread')
            if t and t.is_alive():
                t.join(timeout=0.5)
        except Exception:
            pass
        try:
            if state.get('sock_in'):
                state['sock_in'].close()
        except Exception:
            pass
        try:
            if state.get('sock_out'):
                state['sock_out'].close()
        except Exception:
            pass
        self.relay_state.pop(channel_id, None)
        
    def generate_call_id(self):
        """生成Call-ID"""
        self.call_id_seq += 1
        return f"{self.call_id_prefix}@{self.local_ip}"
    
    def generate_cseq(self):
        """生成CSeq"""
        self.cseq += 1
        return self.cseq
    
    def generate_branch(self):
        """生成Branch"""
        return f"z9hG4bK{str(uuid.uuid4()).replace('-', '')[:20]}"
    
    def create_register_request(self):
        """创建注册请求"""
        self.call_id_seq = 0
        self.cseq = 0
        
        call_id = self.generate_call_id()
        cseq = self.generate_cseq()
        branch = self.generate_branch()
        
        # 计算Authorization
        realm = "3402000000"
        nonce = str(int(time.time()))
        
        # HA1 = MD5(username:realm:password)
        ha1_str = f"{self.device_id}:{realm}:{self.password}"
        ha1 = hashlib.md5(ha1_str.encode()).hexdigest()
        
        # HA2 = MD5(METHOD:uri)
        uri = f"sip:{self.server_ip}:{self.server_port}"
        ha2_str = f"REGISTER:{uri}"
        ha2 = hashlib.md5(ha2_str.encode()).hexdigest()
        
        # response = MD5(HA1:nonce:HA2)
        response_str = f"{ha1}:{nonce}:{ha2}"
        response = hashlib.md5(response_str.encode()).hexdigest()
        
        auth = f'Digest username="{self.device_id}", realm="{realm}", nonce="{nonce}", uri="{uri}", response="{response}"'
        
        # 使用contact_ip（如果已设置），否则使用local_ip
        # contact_ip用于0.0.0.0绑定情况，需要是实际可路由的IP
        contact_ip = self.contact_ip if self.contact_ip else self.local_ip
        
        # Via头和Contact头都使用可路由的地址（contact_ip）
        # 这样平台才能正确地向设备发送消息
        via_ip = contact_ip
        
        request = f"""REGISTER sip:{self.server_ip}:{self.server_port} SIP/2.0
Via: SIP/2.0/UDP {via_ip}:{self.local_port};branch={branch}
From: <sip:{self.device_id}@{self.server_ip}:{self.server_port}>;tag={self.tag}
To: <sip:{self.device_id}@{self.server_ip}:{self.server_port}>
Call-ID: {call_id}
CSeq: {cseq} REGISTER
Contact: <sip:{self.device_id}@{contact_ip}:{self.local_port}>
Authorization: {auth}
Max-Forwards: 70
User-Agent: GB28181-Device/1.0
Expires: {self.register_expires}
Content-Length: 0

"""
        return request.replace('\n', '\r\n')
    
    def parse_invite_sdp(self, sdp_body):
        """解析INVITE中的SDP，提取推流地址"""
        if not sdp_body:
            return None
        
        result = {
            'video_port': None,
            'audio_port': None,
            'ip': None,
            'ssrc': None
        }
        
        # 解析SDP
        lines = sdp_body.split('\r\n')
        for line in lines:
            if line.startswith('c=IN IP4 '):
                result['ip'] = line.split()[2]
            elif line.startswith('m=video '):
                result['video_port'] = int(line.split()[1])
            elif line.startswith('m=audio '):
                result['audio_port'] = int(line.split()[1])
            elif 'y=' in line:
                result['ssrc'] = line.split('=')[1].strip()
        
        return result
    
    def start_stream_push(self, avcapture_url, target_ip, target_port, ssrc, channel_id):
        """启动FFmpeg推流到指定地址（仅允许本地文件作为输入）"""
        # 使用会话键（同通道不同端口可并发）：channel@ip:port
        session_key = f"{channel_id}@{target_ip}:{target_port}"
        
        # 收集目录下的所有mp4文件，循环播放
        search_dirs = [
            os.path.dirname(__file__),
            os.path.dirname(os.path.dirname(__file__)),
        ]
        files = []
        for d in search_dirs:
            try:
                for name in os.listdir(d):
                    if name.lower().endswith('.mp4'):
                        p = os.path.join(d, name)
                        if os.path.isfile(p):
                            files.append(p)
            except Exception:
                pass
        # 环境变量指定的文件也纳入（若存在）
        if avcapture_url and os.path.isfile(avcapture_url) and avcapture_url not in files:
            files.insert(0, avcapture_url)
        
        if not files:
            print(f"✗ 未找到可用的本地MP4文件，请放置到项目或 gb28181_simulator 目录")
            return False

        # 使用本地UDP中继统计带宽：FFmpeg 推到本地端口，由中继转发到目标
        relay_port = self._start_udp_relay(session_key, target_ip, target_port)
        rtp_url = f"rtp://127.0.0.1:{relay_port}"
        
        print(f"\n推流: 循环 {len(files)} 个文件 -> {rtp_url} (SSRC: {ssrc}, 通道: {channel_id})")
        
        # 构建FFmpeg命令
        # GB28181通常使用MPEG-TS over RTP推流
        # SSRC在RTP协议层设置，FFmpeg的rtp_mpegts输出会处理
        # 本地文件无限循环推送，并使用 -re 以实时速率读取
        if len(files) == 1:
            input_args = ['-stream_loop', '-1', '-re', '-i', files[0]]
        else:
            # 生成临时播放列表
            try:
                if self.playlist_path and os.path.exists(self.playlist_path):
                    os.remove(self.playlist_path)
            except Exception:
                pass
            self.playlist_path = os.path.join(tempfile.gettempdir(), f"gb28181_playlist_{self.device_id}.txt")
            with open(self.playlist_path, 'w') as f:
                for p in files:
                    f.write(f"file '{p}'\n")
            input_args = ['-stream_loop', '-1', '-re', '-f', 'concat', '-safe', '0', '-i', self.playlist_path]

        # 构建drawtext水印滤镜（通道ID名）
       # 构建drawtext水印滤镜（优先中文名，无则用ID）
        watermark = None
        for c in self.channels:
            if c.get('id') == channel_id and c.get('name'):
                watermark = c.get('name')
                break
        if not watermark:
            watermark = channel_id if channel_id else "CHANNEL"
        filter_str = (
            "drawtext=fontfile=/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc:"
            f"text='{watermark}':fontcolor=white:fontsize=28:box=1:boxcolor=black@0.4:boxborderw=6:x=10:y=10"
        )
        cmd = [
            'ffmpeg',
            *input_args,
            '-vf', filter_str,
            '-c:v', 'libx264',
            '-preset', 'veryfast',
            '-tune', 'zerolatency',
            '-b:v', '2000k',
            '-maxrate', '2000k',
            '-bufsize', '4000k',
            '-g', '50',
            '-pix_fmt', 'yuv420p',
            '-flags', '+global_header',
            # 允许音频一并推送（若源文件有音轨）
            '-c:a', 'aac',
            '-b:a', '128k',
            '-f', 'rtp_mpegts',  # GB28181通常使用MPEG-TS over RTP
            rtp_url
        ]
        
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL
            )
            self.channel_id_to_process[session_key] = proc
            print(f"✓ 推流已启动到 {rtp_url}")
            return True
        except Exception as e:
            print(f"✗ 启动推流失败: {e}")
            return False
    
    def stop_stream_push(self, channel_id: str = None):
        """停止推流（单通道或全部）"""
        if channel_id is None:
            keys = list(self.channel_id_to_process.keys())
            for k in keys:
                self.stop_stream_push(k)
            return
        proc = self.channel_id_to_process.get(channel_id)
        # 如果没有精确的session key，按通道前缀匹配全部会话
        if not proc:
            keys = [k for k in list(self.channel_id_to_process.keys()) if k.startswith(f"{channel_id}@")]
            for k in keys:
                self.stop_stream_push(k)
            return
        if proc:
            try:
                proc.terminate()
                proc.wait(timeout=5)
                print(f"✓ 推流已停止: {channel_id}")
            except Exception:
                try:
                    proc.kill()
                except Exception:
                    pass
            finally:
                self.channel_id_to_process.pop(channel_id, None)
        # 停止该通道的中继
        self._stop_udp_relay(channel_id)
    
    def create_message_response(self, request_lines):
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


class GB28181DeviceSimulator:
    """GB28181设备模拟器"""
    
    def __init__(self):
        self.devices = []
        self.running = False
        # 退出清理：确保推流进程被关闭
        atexit.register(self._cleanup_on_exit)
        try:
            signal.signal(signal.SIGTERM, self._handle_terminate)
            signal.signal(signal.SIGINT, self._handle_terminate)
        except Exception:
            pass

    def _cleanup_on_exit(self):
        try:
            for device in getattr(self, 'devices', []):
                device.stop_stream_push()
        except Exception:
            pass

    def _handle_terminate(self, signum, frame):
        self.running = False
        self._cleanup_on_exit()
        
    def create_device(self, device_id, device_name, local_ip, local_port, server_ip, server_port, password='admin123', channel_count=1):
        """创建设备"""
        device = GB28181Device(device_id, device_name, local_ip, local_port, server_ip, server_port, password)
        
        # 创建IPC通道（模拟摄像头通道）
        # 通道ID规则：通道ID = 设备ID + (100 + 序号-1)，保持20位
        # 例如: 设备ID=34020000001320000001，通道1 => +100 = 34020000001320000101（20位）
        for i in range(channel_count):
            try:
                base_num = int(device_id)
                channel_num = base_num + 100 + i
                channel_id = f"{channel_num:020d}"
            except Exception:
                # 回退：字符串拼接（与×100+序号等价）
                # 如果无法转数字，则替换末两位为 (10 + i)
                if len(device_id) >= 2:
                    channel_id = f"{device_id[:-2]}{10 + i:02d}"
                else:
                    channel_id = f"{device_id}{10 + i:02d}"
            device.channels.append({
                'id': channel_id,  # 通道ID：22位（20位设备ID + 2位通道编号）
                'name': f"{device_name}-通道{i+1}",  # 通道名称
                # 严格按照GB28181标准IPC设备字段值设置
                'manufacturer': 'IPC',  # 制造商：标准IPC标识
                'model': 'IPC',  # 型号：标准IPC标识
                'owner': device_id,  # 所有者ID：通常与设备ID相同
                'civil_code': device_id[:6] if len(device_id) >= 6 else '340200',  # 行政区域代码：取设备ID前6位
                'address': 'Address',  # 地址信息
                'parental': '0',  # 是否为父设备：0-否（通道不是父设备），1-是
                'parent_id': device_id,  # 父设备ID：通道的父设备就是主设备
                'safety_way': '0',  # 安全方式：0-不设防，1-周界设防
                'register_way': '1',  # 注册方式：1-RCF3831标准鉴权注册
                'secrecy': '0',  # 保密属性：0-不涉密，1-涉密
                'status': 'ON',  # 设备状态：ON-正常，OFF-故障
                'online': 'ON',  # 在线状态：ON-在线，OFF-离线
                'alarm_status': 'READY'  # 报警状态：READY-正常，ALARM-报警
            })
        
        self.devices.append(device)
        print(f"✓ 创建设备: {device_id} ({device_name}), 通道数: {channel_count}")
        return device
    
    def print_sip_message(self, device_id, direction, message, addr=None):
        """打印SIP消息（格式化输出）"""
        # ANSI颜色代码
        COLOR_GREEN = '\033[92m'  # 绿色（亮绿色）
        COLOR_BLUE = '\033[94m'   # 蓝色
        COLOR_RESET = '\033[0m'   # 重置颜色
        
        direction_mark = ">>> 发送" if direction == "send" else "<<< 接收"
        addr_info = f" -> {addr}" if addr else ""
        
        # 接收的消息（平台响应）使用绿色，发送的消息使用蓝色
        if direction == "recv":
            color_start = COLOR_GREEN
            color_end = COLOR_RESET
        else:
            color_start = COLOR_BLUE
            color_end = COLOR_RESET
        
        print(f"{color_start}\n{'='*60}{color_end}")
        print(f"{color_start}[{device_id}] {direction_mark}{addr_info}{color_end}")
        print(f"{color_start}{'='*60}{color_end}")
        # 只打印前50行，避免太长
        message_lines = message.split('\r\n')
        lines = message_lines[:50]
        for line in lines:
            if line.strip():
                print(f"{color_start}  {line}{color_end}")
        total_lines = len(message_lines)
        if total_lines > 50:
            remaining = total_lines - 50
            print(f"{color_start}  ... (还有 {remaining} 行){color_end}")
        print(f"{color_start}{'='*60}\n{color_end}")
    
    def process_sip_message(self, device, data, addr):
        """处理SIP消息"""
        try:
            message_text = data.decode('utf-8')
            lines = message_text.split('\r\n')
            if not lines:
                return
            
            # 仅在目录订阅相关时打印（SUBSCRIBE catalog 或 MESSAGE 含 Catalog）
            should_print = False
            if lines and lines[0].startswith('SUBSCRIBE'):
                for line in lines:
                    if line.startswith('Event:') and line.split(':', 1)[1].strip().lower() == 'catalog':
                        should_print = True
                        break
            elif lines and lines[0].startswith('MESSAGE'):
                if 'Catalog' in message_text:
                    should_print = True
            if should_print:
                self.print_sip_message(device.device_id, "recv", message_text, addr)
            
            first_line = lines[0]
            request_body = ''
            body_start = False
            body_lines = []
            
            for i, line in enumerate(lines):
                if line == '':
                    body_start = True
                    continue
                if body_start:
                    body_lines.append(line)
            
            request_body = '\r\n'.join(body_lines)
            
            # 处理不同类型的消息
            if first_line.startswith('SIP/2.0'):
                # 响应消息
                if '200 OK' in first_line and 'REGISTER' in '\r\n'.join(lines):
                    device.is_registered = True
                    device.last_heartbeat = time.time()
                elif '401 Unauthorized' in first_line:
                    device.is_registered = False
                    
            elif first_line.startswith('SUBSCRIBE'):
                # SUBSCRIBE请求 - Catalog订阅等
                print(f"\n收到SUBSCRIBE请求 (设备: {device.device_id})")
                # 检查是否是Catalog订阅
                event_type = None
                for line in lines:
                    if line.startswith('Event:'):
                        event_type = line.split(':', 1)[1].strip().lower()
                        break
                
                if event_type == 'catalog':
                    from subscribe_handler import handle_subscribe_catalog
                    handle_subscribe_catalog(device, request_body, lines, addr, self.print_sip_message)
                else:
                    # 其他类型的SUBSCRIBE，发送200 OK
                    from subscribe_handler import create_subscribe_response
                    contact_ip = device.contact_ip if device.contact_ip else device.local_ip
                    response = create_subscribe_response(lines, contact_ip=contact_ip)
                    device.socket.sendto(response.encode('utf-8'), addr)
                    # 非Catalog订阅不打印
                    
            elif first_line.startswith('MESSAGE'):
                # MESSAGE请求 - DeviceInfo、ConfigDownload、Catalog等
                # 使用统一的消息处理模块
                from message_handler import handle_message_request
                handle_message_request(device, request_body, lines, addr, self.print_sip_message)
                    
            elif first_line.startswith('INVITE'):
                # INVITE请求 - 平台要求推流
                print(f"\n收到平台INVITE指令 (设备: {device.device_id})")
                # 打印平台的INVITE原始报文（含SDP）以便排查端口范围
                try:
                    self.print_sip_message(device.device_id, "recv", message_text, addr)
                except Exception:
                    pass
                
                # 解析SDP获取推流地址
                sdp = self.parse_invite_request(lines)
                if sdp:
                    print(f"  推流地址: {sdp['ip']}:{sdp.get('video_port', 'N/A')}")
                    print(f"  SSRC: {sdp.get('ssrc', 'N/A')}")
                    
                    # 发送200 OK响应
                    response = self.create_invite_response(device, lines)
                    device.socket.sendto(response.encode('utf-8'), addr)
                    
                    # 打印发送的响应
                    self.print_sip_message(device.device_id, "send", response, addr)
                    
                    # 查找test.mp4文件：优先在python目录，然后项目根目录，最后向上查找
                    script_dir = os.path.dirname(os.path.abspath(__file__))  # python目录
                    project_root = os.path.dirname(script_dir)  # 项目根目录
                    
                    # 多个可能的查找位置（按优先级）
                    search_paths = [
                        os.path.join(script_dir, 'test.mp4'),        # python/test.mp4 (优先)
                        os.path.join(project_root, 'test.mp4'),      # 项目根目录/test.mp4
                    ]
                    
                    # 从当前工作目录开始，向上查找test.mp4文件
                    current_dir = os.getcwd()
                    max_levels = 5  # 最多向上查找5层
                    for level in range(max_levels):
                        search_paths.append(os.path.join(current_dir, 'test.mp4'))
                        parent_dir = os.path.dirname(current_dir)
                        if parent_dir == current_dir:  # 已经到达根目录
                            break
                        current_dir = parent_dir
                    
                    avcapture_url = None
                    for test_path in search_paths:
                        if os.path.isfile(test_path):
                            avcapture_url = test_path
                            print(f"  使用test.mp4文件: {avcapture_url}")
                            break
                    
                    if avcapture_url is None:
                        print(f"  错误: 未找到test.mp4文件")
                        print(f"  已查找的位置:")
                        for path in search_paths[:5]:  # 显示前5个主要位置
                            status = "✓" if os.path.isfile(path) else "✗"
                            print(f"    {status} {path}")
                    
                    # 提取通道ID（从INVITE的To或Request-URI中）
                    channel_id = self.extract_channel_id(first_line)
                    
                    target_port = sdp.get('video_port', 5004)
                    ssrc = sdp.get('ssrc', str(int(time.time() * 1000) % 100000000))
                    
                    if avcapture_url and device.start_stream_push(avcapture_url, sdp['ip'], target_port, ssrc, channel_id or device.channels[0]['id']):
                        print(f"✓ 推流已启动到 {sdp['ip']}:{target_port}")
                        # 记录 Call-ID -> session_key 映射
                        call_id_value = None
                        for h in lines:
                            if h.startswith('Call-ID:'):
                                call_id_value = h.split(':', 1)[1].strip()
                                break
                        if call_id_value:
                            sess_key = f"{channel_id or device.channels[0]['id']}@{sdp['ip']}:{target_port}"
                            device.callid_to_sessions.setdefault(call_id_value, []).append(sess_key)
            
            elif first_line.startswith('BYE'):
                # 平台结束点播：回复200 OK并关闭对应通道的推流（符合GB28181）
                from sip_response import create_message_response
                contact_ip = device.contact_ip if device.contact_ip else device.local_ip
                bye_ok = create_message_response(lines, contact_ip=contact_ip)
                device.socket.sendto(bye_ok.encode('utf-8'), addr)
                self.print_sip_message(device.device_id, "send", bye_ok, addr)
                # 优先按Call-ID精确停止
                call_id_value = None
                for h in lines:
                    if h.startswith('Call-ID:'):
                        call_id_value = h.split(':', 1)[1].strip()
                        break
                stopped = False
                if call_id_value and call_id_value in device.callid_to_sessions:
                    for sess_key in list(device.callid_to_sessions.get(call_id_value, [])):
                        device.stop_stream_push(sess_key)
                    device.callid_to_sessions.pop(call_id_value, None)
                    stopped = True
                if not stopped:
                    channel_id = self.extract_channel_id(first_line)
                    if channel_id:
                        print(f"\n收到平台BYE (设备: {device.device_id}, 通道: {channel_id})，停止该通道推流")
                        device.stop_stream_push(channel_id)
                    else:
                        print(f"\n收到平台BYE (设备: {device.device_id})，停止所有推流")
                        device.stop_stream_push()
                        
        except Exception as e:
            print(f"✗ 处理SIP消息失败: {e}")
            import traceback
            traceback.print_exc()
    
    def parse_invite_request(self, lines):
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
    
    def extract_channel_id(self, request_line):
        """从请求行提取通道ID"""
        # INVITE sip:34020000001320000001@192.168.1.100:5060 SIP/2.0
        try:
            if '@' in request_line:
                part = request_line.split('sip:')[1].split('@')[0]
                return part
        except:
            pass
        return None
    
    def create_invite_response(self, device, request_lines):
        """创建INVITE 200 OK响应（包含SDP，符合GB28181点播流程）"""
        # 解析请求
        via = None
        from_line = None
        to_line = None
        call_id = None
        cseq = None
        # 解析对端SDP以继承 y(SSRC)、f 参数
        in_sdp = False
        sdp_lines = []
        
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
            elif line.strip() == '':
                in_sdp = True
                continue
            elif in_sdp:
                sdp_lines.append(line)
        
        # 从To提取tag
        to_tag = None
        if to_line and ';tag=' in to_line:
            to_tag = to_line.split(';tag=')[1].split()[0]
        else:
            to_tag = str(uuid.uuid4())[:32]
        
        # 解析平台SDP中的 y(SSRC) 与 f 参数
        remote_ssrc = None
        f_param = None
        for l in sdp_lines:
            if l.startswith('y='):
                remote_ssrc = l.split('=', 1)[1].strip()
            elif l.startswith('f='):
                f_param = l.split('=', 1)[1].strip()

        # 设备用于SDP的IP
        contact_ip = device.contact_ip if device.contact_ip else device.local_ip
        # 若对端未提供SSRC则生成一个
        local_ssrc = remote_ssrc if remote_ssrc else str(int(time.time()*1000) % 100000000)

        # 生成符合GB28181的SDP（发送端，PS/90000，sendonly）
        sdp_body = (
            f"v=0\r\n"
            f"o={device.device_id} 0 0 IN IP4 {contact_ip}\r\n"
            f"s=Play\r\n"
            f"c=IN IP4 {contact_ip}\r\n"
            f"t=0 0\r\n"
            f"m=video 0 RTP/AVP 96\r\n"
            f"a=rtpmap:96 PS/90000\r\n"
            f"a=sendonly\r\n"
            f"y={local_ssrc}\r\n"
        )
        if f_param:
            sdp_body += f"f={f_param}\r\n"

        content_length = len(sdp_body.encode('utf-8'))

        # 构造带SDP的200 OK
        response = f"""SIP/2.0 200 OK
Via: {via}
From: {from_line}
To: {to_line};tag={to_tag}
Call-ID: {call_id}
CSeq: {cseq}
Contact: <sip:{device.device_id}@{contact_ip}:{device.local_port}>
User-Agent: GB28181-Device/1.0
Content-Type: application/sdp
Content-Length: {content_length}

{sdp_body}"""
        return response.replace('\n', '\r\n')
    
    def device_thread(self, device):
        """设备线程函数"""
        try:
            # 创建UDP socket
            device.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            
            # 如果服务器IP是外部地址，绑定到0.0.0.0而不是127.0.0.1
            # 这样可以正常发送到外部服务器
            bind_ip = device.local_ip
            if bind_ip == "127.0.0.1" and device.server_ip != "127.0.0.1" and device.server_ip != "localhost":
                bind_ip = "0.0.0.0"  # 绑定到所有接口
            
            device.socket.bind((bind_ip, device.local_port))
            device.socket.settimeout(1.0)
            
            # 如果绑定到0.0.0.0，需要获取实际对外可见的IP地址用于Contact头
            if bind_ip == "0.0.0.0":
                try:
                    # 创建一个临时socket连接到服务器，获取实际使用的本地IP地址
                    temp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                    temp_socket.connect((device.server_ip, device.server_port))
                    actual_ip, _ = temp_socket.getsockname()
                    temp_socket.close()
                    device.contact_ip = actual_ip
                    print(f"✓ 设备 {device.device_id} 监听 {bind_ip}:{device.local_port}，Contact地址: {device.contact_ip}:{device.local_port}")
                except Exception as e:
                    print(f"⚠ 获取实际IP地址失败: {e}，尝试使用网络接口IP")
                    try:
                        # 备选方案：获取默认网关接口的IP
                        try:
                            import netifaces
                            gateways = netifaces.gateways()
                            default_interface = gateways['default'][netifaces.AF_INET][1]
                            addrs = netifaces.ifaddresses(default_interface)
                            device.contact_ip = addrs[netifaces.AF_INET][0]['addr']
                            print(f"✓ 使用网络接口IP: {device.contact_ip}:{device.local_port}")
                        except ImportError:
                            # 如果没有netifaces，最后的备选方案：使用服务器IP（不理想，但至少能让平台发送消息到某处）
                            print(f"⚠ netifaces库未安装，Contact头将使用服务器IP（可能导致问题）")
                            device.contact_ip = device.server_ip
                    except Exception as e2:
                        # 最后的备选方案：使用服务器IP（不理想，但至少能让平台发送消息到某处）
                        print(f"⚠ 无法获取本地IP: {e2}，Contact头将使用服务器IP（可能导致问题）")
                        device.contact_ip = device.server_ip
            
            # 如果绑定到非0.0.0.0的地址，contact_ip就是bind_ip
            if bind_ip != "0.0.0.0":
                device.contact_ip = bind_ip
                print(f"✓ 设备 {device.device_id} 监听 {bind_ip}:{device.local_port}")
            
            # 确保contact_ip已设置
            if not device.contact_ip:
                device.contact_ip = device.server_ip
                print(f"⚠ Contact IP未设置，使用服务器IP作为后备: {device.contact_ip}")
            
            # 注册
            register_request = device.create_register_request()
            
            # 打印注册信息（显示使用的IP地址）
            contact_ip_used = device.contact_ip if device.contact_ip else device.local_ip
            # 精简注册日志：不打印注册细节
            
            device.socket.sendto(register_request.encode('utf-8'), (device.server_ip, device.server_port))
            
            # 不打印注册请求
            
            last_register = time.time()
            device.last_heartbeat = time.time()  # 初始化心跳计时器
            
            while self.running:
                try:
                    # 接收消息
                    data, addr = device.socket.recvfrom(4096)
                    self.process_sip_message(device, data, addr)
                    
                except socket.timeout:
                    # 超时，检查是否需要重注册或心跳
                    current_time = time.time()
                    
                    # 注册成功后每30秒发送心跳消息
                    if device.is_registered and device.last_heartbeat is not None:
                        if (current_time - device.last_heartbeat) >= device.heartbeat_interval:
                            from heartbeat_handler import send_keepalive
                            send_keepalive(device, self.print_sip_message)
                            device.last_heartbeat = current_time
                    
                    # 如果未注册或注册过期，重新注册
                    # 检查是否达到重试间隔
                    if not device.is_registered or (current_time - last_register) >= device.register_expires:
                        # 确保满足重试间隔（10秒）
                        if (current_time - last_register) >= device.retry_interval:
                            register_request = device.create_register_request()
                            device.socket.sendto(register_request.encode('utf-8'), (device.server_ip, device.server_port))
                            # 不打印重注册
                            last_register = current_time
                
                except Exception as e:
                    if self.running:
                        print(f"✗ 设备 {device.device_id} 接收消息出错: {e}")
        
        except Exception as e:
            print(f"✗ 设备 {device.device_id} 线程出错: {e}")
            import traceback
            traceback.print_exc()
        finally:
            if device.socket:
                device.socket.close()
            device.stop_stream_push()
    
    def start_all_devices(self):
        """启动所有设备"""
        self.running = True
        
        print(f"\n启动 {len(self.devices)} 个设备...")
        
        threads = []
        for device in self.devices:
            thread = threading.Thread(target=self.device_thread, args=(device,), daemon=True)
            thread.start()
            threads.append(thread)
            time.sleep(0.5)  # 避免端口冲突
        
        print(f"\n✓ 所有设备已启动，按 Ctrl+C 停止")
        
        # 保持运行
        try:
            while self.running:
                time.sleep(1)
        except KeyboardInterrupt:
            print("\n\n正在停止所有设备...")
            self.running = False
            for device in self.devices:
                device.stop_stream_push()
            time.sleep(2)
            print("✓ 已停止")


def main():
    """主函数"""
    print("=" * 60)
    print("GB28181 设备模拟器")
    print("=" * 60)
    
    simulator = GB28181DeviceSimulator()
    
    # 配置参数
    server_ip = input("\n请输入GB28181平台服务器IP（默认: 192.168.32.84）: ").strip()
    if not server_ip:
        server_ip = "192.168.32.84"
    
    server_port = input("请输入GB28181平台服务器端口（默认: 8809）: ").strip()
    server_port = int(server_port) if server_port else 8809
    
    password = input("请输入设备密码（默认: 123456）: ").strip()
    if not password:
        password = "123456"
    
    device_count = input("请输入要模拟的设备数量（默认: 3）: ").strip()
    device_count = int(device_count) if device_count else 3
    
    channel_count = input("请输入每个设备的通道数（默认: 1）: ").strip()
    channel_count = int(channel_count) if channel_count else 1
    
    # 创建设备
    base_device_id = "3402000000132000"
    base_port = 15060
    
    # 如果服务器IP是外部地址，使用0.0.0.0作为本地IP
    default_local_ip = "127.0.0.1"
    if server_ip != "127.0.0.1" and server_ip != "localhost":
        default_local_ip = "0.0.0.0"
    
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
    
    # 显示配置信息
    print("\n" + "=" * 60)
    print("配置摘要:")
    print(f"  平台地址: {server_ip}:{server_port}")
    print(f"  设备密码: {password}")
    print(f"  设备数量: {device_count}")
    print(f"  每设备通道数: {channel_count}")
    print(f"  总通道数: {device_count * channel_count}")
    print("=" * 60)
    
    # 启动所有设备
    simulator.start_all_devices()


if __name__ == "__main__":
    main()


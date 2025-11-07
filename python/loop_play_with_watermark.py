#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
循环播放 resources 目录下的所有 mp4 文件，每个文件之间添加 5 秒水印过渡
"""

import os
import subprocess
import tempfile
import sys
import glob
import platform

def get_font_path():
    """获取系统字体路径，优先使用微软雅黑"""
    system = platform.system()
    
    if system == "Windows":
        # Windows系统：优先使用微软雅黑
        font_paths = [
            r"C:\Windows\Fonts\msyh.ttc",  # 微软雅黑
            r"C:\Windows\Fonts\simhei.ttf",  # 黑体
            r"C:\Windows\Fonts\simsun.ttc",  # 宋体
        ]
        for path in font_paths:
            if os.path.exists(path):
                return path
        # 如果找不到文件，使用字体名称
        return "Microsoft YaHei"
    elif system == "Darwin":  # macOS
        # macOS系统字体
        font_paths = [
            "/System/Library/Fonts/PingFang.ttc",
            "/System/Library/Fonts/STHeiti Light.ttc",
            "/Library/Fonts/Arial Unicode.ttf",
        ]
        for path in font_paths:
            if os.path.exists(path):
                return path
        return "PingFang SC"
    else:  # Linux
        # Linux系统字体
        font_paths = [
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        ]
        for path in font_paths:
            if os.path.exists(path):
                return path
        return "DejaVu Sans"

def get_video_info(video_path):
    """获取视频信息（分辨率、帧率等）"""
    try:
        cmd = [
            'ffprobe',
            '-v', 'error',
            '-select_streams', 'v:0',
            '-show_entries', 'stream=width,height,r_frame_rate',
            '-of', 'default=noprint_wrappers=1:nokey=1',
            video_path
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, check=True)
        lines = result.stdout.strip().split('\n')
        width = int(lines[0])
        height = int(lines[1])
        frame_rate = lines[2]
        return width, height, frame_rate
    except Exception as e:
        print(f"⚠ 无法获取视频信息 {video_path}: {e}，使用默认值")
        return 1920, 1080, "25/1"

def create_watermark_video(output_path, text, duration=5, width=1920, height=1080, frame_rate="25/1"):
    """创建带水印的过渡视频"""
    # 转义单引号
    escaped_text = text.replace("'", "\\'")
    
    # 获取字体路径
    font = get_font_path()
    
    # 构建 drawtext 滤镜
    if os.path.exists(font) or font.startswith("/") or (platform.system() == "Windows" and ":" in font):
        # 使用字体文件路径
        filter_str = (
            f"drawtext=fontfile={font}:"
            f"text='{escaped_text}':fontcolor=white:fontsize=48:box=1:boxcolor=black@0.5:"
            f"boxborderw=8:x=(w-text_w)/2:y=(h-text_h)/2"
        )
    else:
        # 使用字体名称
        filter_str = (
            f"drawtext=font={font}:"
            f"text='{escaped_text}':fontcolor=white:fontsize=48:box=1:boxcolor=black@0.5:"
            f"boxborderw=8:x=(w-text_w)/2:y=(h-text_h)/2"
        )
    
    cmd = [
        'ffmpeg',
        '-f', 'lavfi',
        '-i', f'color=c=black:s={width}x{height}:d={duration}:r={frame_rate}',
        '-vf', filter_str,
        '-c:v', 'libx264',
        '-preset', 'veryfast',
        '-pix_fmt', 'yuv420p',
        '-y',  # 覆盖输出文件
        output_path
    ]
    
    try:
        subprocess.run(cmd, check=True, capture_output=True)
        print(f"✓ 创建水印视频: {output_path}")
        return True
    except subprocess.CalledProcessError as e:
        print(f"✗ 创建水印视频失败: {e.stderr.decode('utf-8', errors='ignore')}")
        return False

def find_mp4_files(resources_dir):
    """查找 resources 目录下的所有 mp4 文件"""
    pattern = os.path.join(resources_dir, '*.mp4')
    files = sorted(glob.glob(pattern))
    return files

def create_playlist_with_watermarks(video_files, temp_dir, duration=5):
    """创建包含视频和水印过渡的播放列表"""
    playlist_path = os.path.join(temp_dir, 'playlist.txt')
    watermark_files = []
    
    # 获取第一个视频的信息作为参考
    if video_files:
        width, height, frame_rate = get_video_info(video_files[0])
    else:
        width, height, frame_rate = 1920, 1080, "25/1"
    
    with open(playlist_path, 'w', encoding='utf-8') as f:
        for i, video_file in enumerate(video_files):
            # 添加视频文件（转义路径中的特殊字符）
            abs_path = os.path.abspath(video_file).replace("'", "'\\''")
            f.write(f"file '{abs_path}'\n")
            
            # 创建下一个文件的水印过渡（最后一个文件后显示第一个文件名）
            next_index = (i + 1) % len(video_files)
            next_filename = os.path.basename(video_files[next_index])
            watermark_text = f"播放下一个 {next_filename}"
            
            watermark_path = os.path.join(temp_dir, f'watermark_{i}.mp4')
            watermark_files.append(watermark_path)
            
            if create_watermark_video(watermark_path, watermark_text, duration=duration, 
                                     width=width, height=height, frame_rate=frame_rate):
                abs_watermark_path = os.path.abspath(watermark_path).replace("'", "'\\''")
                f.write(f"file '{abs_watermark_path}'\n")
    
    return playlist_path, watermark_files

def main():
    """主函数"""
    import argparse
    
    parser = argparse.ArgumentParser(
        description='循环播放 resources 目录下的所有 mp4 文件，每个文件之间添加 5 秒水印过渡'
    )
    parser.add_argument(
        '--output', '-o',
        type=str,
        help='输出地址。可以是文件路径（如 output.ts）或 RTP 地址（如 rtp://192.168.1.100:5004）。默认输出到文件 output.ts'
    )
    parser.add_argument(
        '--resources-dir', '-r',
        type=str,
        help='resources 目录路径（默认: 脚本所在目录/resources）'
    )
    parser.add_argument(
        '--duration', '-d',
        type=int,
        default=5,
        help='水印过渡时长（秒），默认 5 秒'
    )
    parser.add_argument(
        '--playlist-only',
        action='store_true',
        help='仅生成播放列表文件，不执行播放'
    )
    parser.add_argument(
        '--speed', '-s',
        type=float,
        default=1.0,
        help='推流倍速（例如 8.0 表示8倍速，默认 1.0）'
    )
    
    args = parser.parse_args()
    
    # 获取 resources 目录路径
    if args.resources_dir:
        resources_dir = args.resources_dir
    else:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        resources_dir = os.path.join(script_dir, 'resources')
    
    if not os.path.exists(resources_dir):
        print(f"✗ 错误: resources 目录不存在: {resources_dir}")
        sys.exit(1)
    
    # 查找所有 mp4 文件
    video_files = find_mp4_files(resources_dir)
    
    if not video_files:
        print(f"✗ 错误: 在 {resources_dir} 目录下未找到 mp4 文件")
        sys.exit(1)
    
    print(f"找到 {len(video_files)} 个视频文件:")
    for f in video_files:
        print(f"  - {os.path.basename(f)}")
    
    # 创建临时目录存放水印视频和播放列表
    temp_dir = tempfile.mkdtemp(prefix='gb28181_loop_play_')
    print(f"\n临时目录: {temp_dir}")
    
    try:
        # 创建播放列表
        print(f"\n正在创建播放列表和水印过渡视频（时长 {args.duration} 秒）...")
        playlist_path, watermark_files = create_playlist_with_watermarks(
            video_files, temp_dir, duration=args.duration
        )
        
        print(f"\n✓ 播放列表已创建: {playlist_path}")
        
        if args.playlist_only:
            print(f"\n播放列表文件: {playlist_path}")
            print("可以使用以下命令播放:")
            print(f"  ffmpeg -stream_loop -1 -re -f concat -safe 0 -i {playlist_path} -c copy output.ts")
            return
        
        # 确定输出地址
        if args.output:
            output = args.output
        else:
            output = 'output.ts'
        
        # 判断输出类型
        is_rtp = output.startswith('rtp://')
        
        speed = args.speed if hasattr(args, 'speed') else 1.0
        speed_str = f"{speed}倍速" if speed != 1.0 else "正常速度"
        print(f"\n开始循环播放 -> {output}（{speed_str}，按 Ctrl+C 停止）...")
        
        # 直接使用 concat demuxer 循环播放（都是mp4，不需要统一格式）
        # 添加参数确保连续播放，即使编码参数略有不同
        loop_cmd = [
            'ffmpeg',
            '-stream_loop', '-1',  # 无限循环
        ]
        
        # 如果使用倍速，不使用 -re（实时速率），而是使用滤镜加速
        if speed == 1.0:
            loop_cmd.append('-re')  # 实时速率读取
        
        loop_cmd.extend([
            '-f', 'concat',
            '-safe', '0',
            '-i', playlist_path,
            # 关键参数：确保连续播放
            '-fflags', '+genpts',  # 生成新的时间戳
            '-vsync', 'cfr',  # 恒定帧率
            '-avoid_negative_ts', 'make_zero',  # 处理负时间戳
            '-err_detect', 'ignore_err',  # 忽略错误，继续播放
        ])
        
        # 如果使用倍速，添加速度滤镜
        if speed != 1.0:
            # 视频加速：setpts=PTS/speed
            # 音频加速：atempo最大支持2.0，所以需要链式使用（8倍速 = 2.0 * 2.0 * 2.0）
            video_filter = f'setpts=PTS/{speed}'
            
            # 计算需要多少个atempo（每个最大2.0）
            audio_filters = []
            remaining_speed = speed
            while remaining_speed > 2.0:
                audio_filters.append('atempo=2.0')
                remaining_speed /= 2.0
            if remaining_speed > 1.0:
                audio_filters.append(f'atempo={remaining_speed:.2f}')
            
            if audio_filters:
                audio_filter = ','.join(audio_filters)
                filter_complex = f'[0:v]{video_filter}[v];[0:a]{audio_filter}[a]'
            else:
                filter_complex = f'[0:v]{video_filter}[v];[0:a]anull[a]'
            
            loop_cmd.extend([
                '-filter_complex', filter_complex,
                '-map', '[v]',
                '-map', '[a]',
            ])
        
        # 编码参数
        loop_cmd.extend([
            '-c:v', 'libx264',
            '-preset', 'veryfast',
            '-tune', 'zerolatency',
            '-b:v', '2000k',
            '-maxrate', '2000k',
            '-bufsize', '4000k',
            '-g', '50',
            '-pix_fmt', 'yuv420p',
            '-flags', '+global_header',
            '-c:a', 'aac',
            '-b:a', '128k',
            '-ar', '44100',
            '-ac', '2',
        ])
        
        # 打印播放列表内容（用于调试）
        print(f"\n播放列表内容:")
        with open(playlist_path, 'r', encoding='utf-8') as f:
            for i, line in enumerate(f, 1):
                print(f"  {i}. {line.strip()}")
        
        # 验证所有文件是否存在
        print(f"\n验证文件存在性:")
        with open(playlist_path, 'r', encoding='utf-8') as f:
            for line in f:
                if line.strip().startswith("file '"):
                    file_path = line.strip()[6:-1].replace("'\\''", "'")
                    exists = os.path.exists(file_path)
                    status = "✓" if exists else "✗"
                    print(f"  {status} {os.path.basename(file_path)}: {file_path[:60]}...")
                    if not exists:
                        print(f"    ⚠ 警告: 文件不存在！")
        
        # 打印 FFmpeg 命令（用于调试）
        print(f"\n执行循环播放命令: {' '.join(loop_cmd[:10])}... (共 {len(loop_cmd)} 个参数)")
        
        if is_rtp:
            # RTP 推流
            loop_cmd.extend(['-f', 'rtp_mpegts', output])
            # 保存命令到文件以便调试
            cmd_file = os.path.join(temp_dir, 'ffmpeg_loop_cmd.txt')
            with open(cmd_file, 'w', encoding='utf-8') as f:
                f.write(' '.join(loop_cmd))
            print(f"FFmpeg 循环播放命令已保存到: {cmd_file}")
            # 不重定向输出，让 FFmpeg 直接推流
            process = subprocess.Popen(
                loop_cmd,
                stderr=subprocess.PIPE
            )
            try:
                # 等待进程（会被 Ctrl+C 中断）
                process.wait()
            except KeyboardInterrupt:
                print("\n\n停止播放...")
                process.terminate()
                process.wait()
        else:
            # 输出到文件
            loop_cmd.extend(['-f', 'mpegts', '-y', output])
            # 保存命令到文件以便调试
            cmd_file = os.path.join(temp_dir, 'ffmpeg_loop_cmd.txt')
            with open(cmd_file, 'w', encoding='utf-8') as f:
                f.write(' '.join(loop_cmd))
            print(f"FFmpeg 循环播放命令已保存到: {cmd_file}")
            process = subprocess.Popen(
                loop_cmd,
                stderr=subprocess.PIPE
            )
            try:
                process.wait()
            except KeyboardInterrupt:
                print("\n\n停止播放...")
                process.terminate()
                process.wait()
        
        print(f"\n✓ 完成")
        
    except KeyboardInterrupt:
        print("\n\n用户中断")
    except Exception as e:
        print(f"\n✗ 错误: {e}")
        import traceback
        traceback.print_exc()
    finally:
        print(f"\n临时文件保留在: {temp_dir}")
        print("提示: 可以手动删除临时目录以清理文件")

if __name__ == '__main__':
    main()


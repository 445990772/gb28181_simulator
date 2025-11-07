package com.gb28181.simulator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 循环播放 resources 目录下的所有 mp4 文件，每个文件之间添加水印过渡
 * 
 * 用法示例：
 *   java -cp target/simulator-1.0.0-jar-with-dependencies.jar com.gb28181.simulator.LoopPlayWithWatermark
 *   java -cp target/simulator-1.0.0-jar-with-dependencies.jar com.gb28181.simulator.LoopPlayWithWatermark --output output.ts
 *   java -cp target/simulator-1.0.0-jar-with-dependencies.jar com.gb28181.simulator.LoopPlayWithWatermark --output rtp://192.168.1.100:5004
 */
public class LoopPlayWithWatermark {
    
    private static final int DEFAULT_DURATION = 5;
    
    /**
     * 获取系统字体路径，优先使用微软雅黑
     */
    private static String getFontPath() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        
        if (osName.contains("windows")) {
            // Windows系统：优先使用微软雅黑
            String[] fontPaths = {
                "C:\\Windows\\Fonts\\msyh.ttc",  // 微软雅黑
                "C:\\Windows\\Fonts\\simhei.ttf",  // 黑体
                "C:\\Windows\\Fonts\\simsun.ttc",  // 宋体
            };
            for (String path : fontPaths) {
                java.io.File fontFile = new java.io.File(path);
                if (fontFile.exists()) {
                    return path;
                }
            }
            // 如果找不到文件，使用字体名称
            return "Microsoft YaHei";
        } else if (osName.contains("mac")) {
            // macOS系统字体
            String[] fontPaths = {
                "/System/Library/Fonts/PingFang.ttc",
                "/System/Library/Fonts/STHeiti Light.ttc",
                "/Library/Fonts/Arial Unicode.ttf",
            };
            for (String path : fontPaths) {
                java.io.File fontFile = new java.io.File(path);
                if (fontFile.exists()) {
                    return path;
                }
            }
            return "PingFang SC";
        } else {
            // Linux系统字体
            String[] fontPaths = {
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            };
            for (String path : fontPaths) {
                java.io.File fontFile = new java.io.File(path);
                if (fontFile.exists()) {
                    return path;
                }
            }
            return "DejaVu Sans";
        }
    }
    
    /**
     * 获取视频信息（分辨率、帧率等）
     */
    private static VideoInfo getVideoInfo(String videoPath) {
        try {
            List<String> cmd = Arrays.asList(
                "ffprobe",
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,r_frame_rate",
                "-of", "default=noprint_wrappers=1:nokey=1",
                videoPath
            );
            
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String widthStr = reader.readLine();
                String heightStr = reader.readLine();
                String frameRateStr = reader.readLine();
                
                process.waitFor();
                
                if (widthStr != null && heightStr != null && frameRateStr != null) {
                    int width = Integer.parseInt(widthStr.trim());
                    int height = Integer.parseInt(heightStr.trim());
                    return new VideoInfo(width, height, frameRateStr.trim());
                }
            }
        } catch (Exception e) {
            System.err.println("⚠ 无法获取视频信息 " + videoPath + ": " + e.getMessage());
        }
        
        // 返回默认值
        return new VideoInfo(1920, 1080, "25/1");
    }
    
    /**
     * 创建带水印的过渡视频
     */
    private static boolean createWatermarkVideo(String outputPath, String text, int duration,
                                                int width, int height, String frameRate) {
        // 转义单引号
        String escapedText = text.replace("'", "\\'");
        
        // 获取字体路径
        String font = getFontPath();
        
        // 构建 drawtext 滤镜
        String filterStr;
        java.io.File fontFile = new java.io.File(font);
        if (fontFile.exists() || font.startsWith("/") || font.contains(":\\")) {
            // 使用字体文件路径
            filterStr = String.format(
                "drawtext=fontfile=%s:text='%s':fontcolor=white:fontsize=48:box=1:boxcolor=black@0.5:boxborderw=8:x=(w-text_w)/2:y=(h-text_h)/2",
                font, escapedText
            );
        } else {
            // 使用字体名称
            filterStr = String.format(
                "drawtext=font=%s:text='%s':fontcolor=white:fontsize=48:box=1:boxcolor=black@0.5:boxborderw=8:x=(w-text_w)/2:y=(h-text_h)/2",
                font, escapedText
            );
        }
        
        List<String> cmd = Arrays.asList(
            "ffmpeg",
            "-f", "lavfi",
            "-i", String.format("color=c=black:s=%dx%d:d=%d:r=%s", width, height, duration, frameRate),
            "-vf", filterStr,
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-pix_fmt", "yuv420p",
            "-y",  // 覆盖输出文件
            outputPath
        );
        
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                System.out.println("✓ 创建水印视频: " + outputPath);
                return true;
            } else {
                System.err.println("✗ 创建水印视频失败，退出码: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            System.err.println("✗ 创建水印视频失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 查找 resources 目录下的所有 mp4 文件
     */
    private static List<String> findMp4Files(String resourcesDir) {
        try {
            Path dir = Paths.get(resourcesDir);
            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return new ArrayList<>();
            }
            
            return Files.list(dir)
                .filter(path -> {
                    String fileName = path.getFileName().toString().toLowerCase();
                    return fileName.endsWith(".mp4") && Files.isRegularFile(path);
                })
                .map(path -> path.toAbsolutePath().toString())
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("✗ 查找 mp4 文件失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 创建包含视频和水印过渡的播放列表
     */
    private static PlaylistResult createPlaylistWithWatermarks(List<String> videoFiles, String tempDir, int duration) {
        String playlistPath = Paths.get(tempDir, "playlist.txt").toString();
        List<String> watermarkFiles = new ArrayList<>();
        
        // 获取第一个视频的信息作为参考
        VideoInfo videoInfo;
        if (!videoFiles.isEmpty()) {
            videoInfo = getVideoInfo(videoFiles.get(0));
        } else {
            videoInfo = new VideoInfo(1920, 1080, "25/1");
        }
        
        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(playlistPath), StandardCharsets.UTF_8))) {
            
            for (int i = 0; i < videoFiles.size(); i++) {
                String videoFile = videoFiles.get(i);
                // 添加视频文件（使用绝对路径）
                Path videoPath = Paths.get(videoFile);
                writer.println("file '" + videoPath.toAbsolutePath().toString().replace("'", "\\'") + "'");
                
                // 创建下一个文件的水印过渡（最后一个文件后显示第一个文件名）
                int nextIndex = (i + 1) % videoFiles.size();
                String nextFilename = Paths.get(videoFiles.get(nextIndex)).getFileName().toString();
                String watermarkText = "播放下一个 " + nextFilename;
                
                String watermarkPath = Paths.get(tempDir, "watermark_" + i + ".mp4").toString();
                watermarkFiles.add(watermarkPath);
                
                if (createWatermarkVideo(watermarkPath, watermarkText, duration,
                        videoInfo.width, videoInfo.height, videoInfo.frameRate)) {
                    Path watermarkPathObj = Paths.get(watermarkPath);
                    writer.println("file '" + watermarkPathObj.toAbsolutePath().toString().replace("'", "\\'") + "'");
                }
            }
        } catch (IOException e) {
            System.err.println("✗ 创建播放列表失败: " + e.getMessage());
            return null;
        }
        
        return new PlaylistResult(playlistPath, watermarkFiles);
    }
    
    /**
     * 启动循环播放
     */
    private static void startLoopPlay(String playlistPath, String output, int duration, double speed) {
        boolean isRtp = output.startsWith("rtp://");
        
        String speedStr = speed != 1.0 ? speed + "倍速" : "正常速度";
        System.out.println("\n开始循环播放 -> " + output + "（" + speedStr + "，按 Ctrl+C 停止）...");
        
        // 直接使用 concat demuxer 循环播放（都是mp4，不需要统一格式）
        // 添加参数确保连续播放，即使编码参数略有不同
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-stream_loop");
        cmd.add("-1");  // 无限循环
        
        // 如果使用倍速，不使用 -re（实时速率），而是使用滤镜加速
        if (speed == 1.0) {
            cmd.add("-re");  // 实时速率读取
        }
        
        cmd.add("-f");
        cmd.add("concat");
        cmd.add("-safe");
        cmd.add("0");
        cmd.add("-i");
        cmd.add(playlistPath);
        
        // 关键参数：确保连续播放
        cmd.add("-fflags");
        cmd.add("+genpts");  // 生成新的时间戳
        cmd.add("-vsync");
        cmd.add("cfr");  // 恒定帧率
        cmd.add("-avoid_negative_ts");
        cmd.add("make_zero");  // 处理负时间戳
        cmd.add("-err_detect");
        cmd.add("ignore_err");  // 忽略错误，继续播放
        
        // 如果使用倍速，添加速度滤镜
        if (speed != 1.0) {
            // 视频加速：setpts=PTS/speed
            String videoFilter = "setpts=PTS/" + speed;
            
            // 计算需要多少个atempo（每个最大2.0）
            List<String> audioFilters = new ArrayList<>();
            double remainingSpeed = speed;
            while (remainingSpeed > 2.0) {
                audioFilters.add("atempo=2.0");
                remainingSpeed /= 2.0;
            }
            if (remainingSpeed > 1.0) {
                audioFilters.add(String.format("atempo=%.2f", remainingSpeed));
            }
            
            String filterComplex;
            if (!audioFilters.isEmpty()) {
                String audioFilter = String.join(",", audioFilters);
                filterComplex = "[0:v]" + videoFilter + "[v];[0:a]" + audioFilter + "[a]";
            } else {
                filterComplex = "[0:v]" + videoFilter + "[v];[0:a]anull[a]";
            }
            
            cmd.add("-filter_complex");
            cmd.add(filterComplex);
            cmd.add("-map");
            cmd.add("[v]");
            cmd.add("-map");
            cmd.add("[a]");
        }
        
        // 编码参数
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("veryfast");
        cmd.add("-tune");
        cmd.add("zerolatency");
        cmd.add("-b:v");
        cmd.add("2000k");
        cmd.add("-maxrate");
        cmd.add("2000k");
        cmd.add("-bufsize");
        cmd.add("4000k");
        cmd.add("-g");
        cmd.add("50");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-flags");
        cmd.add("+global_header");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-ar");
        cmd.add("44100");
        cmd.add("-ac");
        cmd.add("2");
        
        if (isRtp) {
            // RTP 推流
            cmd.add("-f");
            cmd.add("rtp_mpegts");
            cmd.add(output);
        } else {
            // 输出到文件
            cmd.add("-f");
            cmd.add("mpegts");
            cmd.add("-y");
            cmd.add(output);
        }
        
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // 启动一个线程读取输出（避免缓冲区满）
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) {
                        // 读取并丢弃输出（避免缓冲区满）
                    }
                } catch (IOException e) {
                    // 忽略
                }
            });
            outputThread.setDaemon(true);
            outputThread.start();
            
            // 等待进程（会被 Ctrl+C 中断）
            process.waitFor();
            System.out.println("\n✓ 完成");
        } catch (InterruptedException e) {
            System.out.println("\n\n用户中断");
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("\n✗ 启动播放失败: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        // 解析命令行参数
        String output = null;
        String resourcesDir = null;
        int duration = DEFAULT_DURATION;
        boolean playlistOnly = false;
        double speed = 1.0;
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output":
                case "-o":
                    if (i + 1 < args.length) {
                        output = args[++i];
                    }
                    break;
                case "--resources-dir":
                case "-r":
                    if (i + 1 < args.length) {
                        resourcesDir = args[++i];
                    }
                    break;
                case "--duration":
                case "-d":
                    if (i + 1 < args.length) {
                        try {
                            duration = Integer.parseInt(args[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("✗ 无效的时长参数: " + args[i]);
                            return;
                        }
                    }
                    break;
                case "--playlist-only":
                    playlistOnly = true;
                    break;
                case "--speed":
                case "-s":
                    if (i + 1 < args.length) {
                        try {
                            speed = Double.parseDouble(args[++i]);
                            if (speed <= 0) {
                                System.err.println("✗ 倍速必须大于0");
                                return;
                            }
                        } catch (NumberFormatException e) {
                            System.err.println("✗ 无效的倍速参数: " + args[i]);
                            return;
                        }
                    }
                    break;
                case "--help":
                case "-h":
                    System.out.println("用法: LoopPlayWithWatermark [选项]");
                    System.out.println("选项:");
                    System.out.println("  --output, -o <地址>     输出地址（文件路径或 rtp:// 地址），默认: output.ts");
                    System.out.println("  --resources-dir, -r <目录>  resources 目录路径（默认: 脚本所在目录/resources）");
                    System.out.println("  --duration, -d <秒数>    水印过渡时长（默认: 5）");
                    System.out.println("  --speed, -s <倍速>       推流倍速（例如 8.0 表示8倍速，默认: 1.0）");
                    System.out.println("  --playlist-only         仅生成播放列表文件，不执行播放");
                    System.out.println("  --help, -h              显示帮助信息");
                    return;
            }
        }
        
        // 获取 resources 目录路径
        if (resourcesDir == null) {
            // 尝试从类文件位置推断
            try {
                java.net.URL classUrl = LoopPlayWithWatermark.class.getProtectionDomain()
                    .getCodeSource().getLocation();
                if (classUrl != null && "file".equals(classUrl.getProtocol())) {
                    java.io.File classFile = new java.io.File(classUrl.toURI());
                    java.io.File baseDir;
                    
                    if (classFile.isFile() && classFile.getName().endsWith(".jar")) {
                        // JAR文件：在JAR所在目录的父目录查找java/src/main/resources
                        baseDir = classFile.getParentFile();  // target目录
                        if (baseDir != null && baseDir.getParentFile() != null) {
                            java.io.File javaDir = baseDir.getParentFile();  // java目录
                            resourcesDir = new java.io.File(javaDir, "src/main/resources").getAbsolutePath();
                        }
                    } else {
                        // 类文件：java/src/main/java/com/gb28181/simulator/
                        // 找到java目录，然后定位到src/main/resources
                        baseDir = classFile.getParentFile();  // simulator/
                        for (int i = 0; i < 4 && baseDir != null; i++) {
                            baseDir = baseDir.getParentFile();  // 到java目录
                        }
                        if (baseDir != null && baseDir.exists()) {
                            resourcesDir = new java.io.File(baseDir, "src/main/resources").getAbsolutePath();
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略异常
            }
            
            // 如果还是找不到，使用当前工作目录
            if (resourcesDir == null || !Files.exists(Paths.get(resourcesDir))) {
                java.io.File currentDir = new java.io.File(System.getProperty("user.dir"));
                // 尝试从当前目录向上查找java/src/main/resources
                int maxLevels = 5;
                for (int level = 0; level < maxLevels && currentDir != null; level++) {
                    java.io.File testResourcesDir = new java.io.File(currentDir, "java/src/main/resources");
                    if (testResourcesDir.exists()) {
                        resourcesDir = testResourcesDir.getAbsolutePath();
                        break;
                    }
                    java.io.File parentDir = currentDir.getParentFile();
                    if (parentDir == null || parentDir.equals(currentDir)) {
                        break;  // 已到达根目录
                    }
                    currentDir = parentDir;
                }
            }
        }
        
        if (!Files.exists(Paths.get(resourcesDir))) {
            System.err.println("✗ 错误: resources 目录不存在: " + resourcesDir);
            System.exit(1);
        }
        
        // 查找所有 mp4 文件
        List<String> videoFiles = findMp4Files(resourcesDir);
        
        if (videoFiles.isEmpty()) {
            System.err.println("✗ 错误: 在 " + resourcesDir + " 目录下未找到 mp4 文件");
            System.exit(1);
        }
        
        System.out.println("找到 " + videoFiles.size() + " 个视频文件:");
        for (String f : videoFiles) {
            System.out.println("  - " + Paths.get(f).getFileName().toString());
        }
        
        // 创建临时目录存放水印视频和播放列表
        String tempDir;
        try {
            Path tempPath = Files.createTempDirectory("gb28181_loop_play_");
            tempDir = tempPath.toString();
        } catch (IOException e) {
            System.err.println("✗ 创建临时目录失败: " + e.getMessage());
            System.exit(1);
            return;
        }
        
        System.out.println("\n临时目录: " + tempDir);
        
        try {
            // 创建播放列表
            System.out.println("\n正在创建播放列表和水印过渡视频（时长 " + duration + " 秒）...");
            PlaylistResult result = createPlaylistWithWatermarks(videoFiles, tempDir, duration);
            
            if (result == null) {
                System.err.println("✗ 创建播放列表失败");
                System.exit(1);
                return;
            }
            
            System.out.println("\n✓ 播放列表已创建: " + result.playlistPath);
            
            if (playlistOnly) {
                System.out.println("\n播放列表文件: " + result.playlistPath);
                System.out.println("可以使用以下命令播放:");
                System.out.println("  ffmpeg -stream_loop -1 -re -f concat -safe 0 -i " + result.playlistPath + " -c copy output.ts");
                return;
            }
            
            // 确定输出地址
            if (output == null) {
                output = "output.ts";
            }
            
            // 启动播放
            startLoopPlay(result.playlistPath, output, duration, speed);
            
        } catch (Exception e) {
            System.err.println("\n✗ 错误: " + e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println("\n临时文件保留在: " + tempDir);
            System.out.println("提示: 可以手动删除临时目录以清理文件");
        }
    }
    
    /**
     * 视频信息类
     */
    private static class VideoInfo {
        final int width;
        final int height;
        final String frameRate;
        
        VideoInfo(int width, int height, String frameRate) {
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
        }
    }
    
    /**
     * 播放列表结果类
     */
    @SuppressWarnings("unused")
    private static class PlaylistResult {
        final String playlistPath;
        @SuppressWarnings("unused")
        final List<String> watermarkFiles;
        
        PlaylistResult(String playlistPath, List<String> watermarkFiles) {
            this.playlistPath = playlistPath;
            this.watermarkFiles = watermarkFiles;
        }
    }
}


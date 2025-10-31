# GB28181 设备模拟器与压力测试工具

本项目包含GB28181设备模拟器和视频点播压力测试工具，支持Python和Java两种实现方式。

## 📋 项目结构

```
gb28181_simulator/
├── python/                           # Python版本
│   ├── gb28181_device_simulator.py  # 设备模拟器主程序
│   ├── query_and_concurrent_live.py # 视频点播压力测试工具
│   └── requirements.txt              # Python依赖
├── java/                             # Java版本
│   ├── pom.xml                       # Maven配置
│   └── src/main/java/                # Java源代码
└── README.md                         # 本文件
```

## 🎯 功能特性

### GB28181设备模拟器

- SIP协议通信（REGISTER、心跳、INVITE、BYE、MESSAGE、SUBSCRIBE）
- 多设备同时运行，支持多通道
- FFmpeg视频推流，支持循环播放和水印
- 符合GB28181标准的XML消息

### 视频点播压力测试工具

- 查询平台设备和通道列表
- 并发发起点播请求
- 实时流量和播放统计
- 失败自动重试

---

## 🐍 Python版本

### 环境要求

- Python 3.6+
- FFmpeg（需要系统安装）

### 安装依赖

```bash
cd python
pip install -r requirements.txt
```

### 运行设备模拟器

```bash
cd python
python3 gb28181_device_simulator.py
```

### 运行视频点播压力测试

```bash
cd python
python3 query_and_concurrent_live.py
```

### 使用说明

#### 设备模拟器参数

运行后按提示输入：

1. **GB28181平台服务器IP**：默认 `192.168.32.84`
2. **服务器端口**：默认 `8809`
3. **设备密码**：默认 `123456`
4. **设备数量**：默认 `3`
5. **每设备通道数**：默认 `1`

#### 视频点播压力测试参数

运行后按提示输入：

1. **平台根地址**：默认 `http://192.168.32.84:9000`
2. **:X_Access_Token**：访问令牌（必填）
3. **每设备通道上限**：0为不限制，默认 `0`
4. **每个通道播放时长**：秒数，默认 `300秒`（5分钟）
5. **并发线程数**：默认 `20`
6. **HTTP超时秒**：默认 `30秒`

---

## ☕ Java版本

### 环境要求

- JDK 11 或更高版本
- Maven 3.6 或更高版本
- FFmpeg（需要系统安装）

### 编译项目

```bash
cd java
mvn clean package
```

编译完成后，在 `target` 目录下生成 `simulator-1.0.0-jar-with-dependencies.jar`

### 运行设备模拟器

```bash
cd java
java -jar target/simulator-1.0.0-jar-with-dependencies.jar
```

### 运行视频点播压力测试

```bash
cd java
java -cp target/simulator-1.0.0-jar-with-dependencies.jar \
     com.gb28181.simulator.QueryAndConcurrentLive
```

### 使用说明

#### 设备模拟器参数

运行后按提示输入：

1. **GB28181平台服务器IP**：默认 `192.168.32.84`
2. **服务器端口**：默认 `8809`
3. **设备密码**：默认 `123456`
4. **设备数量**：默认 `3`
5. **每设备通道数**：默认 `1`

#### 视频点播压力测试参数

运行后按提示输入：

1. **平台根地址**：默认 `http://192.168.32.84:8809`
2. **:X_Access_Token**：访问令牌（必填）
3. **每设备通道上限**：0为不限制，默认 `0`
4. **每个通道播放时长**：秒数，默认 `300秒`（5分钟）
5. **并发线程数**：默认 `20`
6. **HTTP超时秒**：默认 `30秒`

---

## ⚠️ 注意事项

1. **FFmpeg要求**：设备模拟器需要系统安装FFmpeg，确保FFmpeg在系统PATH中

2. **test.mp4文件**：
   - Python版本：优先查找 `python/test.mp4`，然后项目根目录 `test.mp4`
   - Java版本：优先查找 `java/src/main/test.mp4`，然后项目根目录 `test.mp4`
   - 如果没有找到，推流将无法启动

3. **网络配置**：确保网络连接正常，能够访问GB28181平台

4. **端口占用**：每个设备使用不同的本地端口（默认从15060开始递增），确保端口未被占用

5. **字体文件**（水印功能）：
   - 默认字体路径：`/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc`
   - 字体文件名称：**Noto Sans CJK Regular**（思源黑体）
   - 如果系统没有该字体，需要下载并安装：
     - 下载地址：https://github.com/googlefonts/noto-cjk/releases
     - 下载 `NotoSansCJK-Regular.ttc` 文件
     - Linux/macOS：将字体文件复制到 `/usr/share/fonts/opentype/noto/` 目录（需要root权限）
     - 或者修改代码中的字体路径指向您下载的字体文件位置

6. **并发限制**：压力测试时，并发线程数不要设置过大，避免对服务器造成过大压力

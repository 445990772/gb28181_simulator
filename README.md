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
├── web/                              # Web界面
│   ├── index.html                    # Web前端页面
│   ├── static/                       # 静态资源
│   │   ├── style.css                 # 样式文件
│   │   └── app.js                    # JavaScript文件
│   ├── api_python.py                 # Python后端API服务
│   └── ...                           # Java后端API服务在java/src/main/java/com/gb28181/simulator/web/
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

---

## 🌐 Web配置界面

为了方便不熟悉命令行的用户使用，项目提供了Web配置界面，支持Python和Java两种后端服务。

### 功能特性

- 📱 可视化配置界面，无需命令行操作
- 🔄 支持Python和Java两种后端服务切换
- 📊 实时查看设备状态和运行日志
- ⚙️ 完整的参数配置选项
- 🎨 现代化的用户界面设计

### Python版本Web服务

#### 安装依赖

```bash
cd python
pip install -r requirements.txt
```

#### 启动Web服务

```bash
cd web
python3 api_python.py
```

或者从项目根目录启动：

```bash
python3 web/api_python.py
```

服务启动后，在浏览器中访问：`http://localhost:5000`

**启动成功后会看到：**
```
============================================================
GB28181 设备模拟器 - Python API服务
============================================================
API服务地址: http://localhost:5000
Web界面地址: http://localhost:5000/
============================================================
```

### Java版本Web服务

#### 编译项目

```bash
cd java
mvn clean package
```

#### 启动Web服务

```bash
cd java
java -cp target/simulator-1.0.0-jar-with-dependencies.jar \
     com.gb28181.simulator.web.ApiServer
```

或者指定端口：

```bash
java -cp target/simulator-1.0.0-jar-with-dependencies.jar \
     com.gb28181.simulator.web.ApiServer 8080
```

服务启动后，在浏览器中访问：`http://localhost:8080`

**启动成功后会看到：**
```
============================================================
GB28181 设备模拟器 - Java API服务
============================================================
API服务地址: http://localhost:8080
Web界面地址: http://localhost:8080/index.html
============================================================
按 Ctrl+C 停止服务
```

### Web界面使用说明

1. **选择后端服务**：在页面顶部选择Python或Java后端服务
2. **检查连接**：点击"检查后端连接"按钮，确认后端服务正常运行
3. **配置参数**：
   - GB28181平台服务器IP和端口
   - 设备密码
   - 设备数量和每设备通道数
   - 设备ID前缀和起始端口
4. **启动模拟器**：点击"启动模拟器"按钮
5. **查看状态**：点击"查看状态"按钮查看设备运行状态
6. **停止模拟器**：点击"停止模拟器"按钮停止所有设备

### API接口说明

Web界面通过RESTful API与后端服务通信：

- `GET /api/health` - 健康检查
- `POST /api/start` - 启动模拟器
- `POST /api/stop` - 停止模拟器
- `GET /api/status` - 获取运行状态

### 快速启动

#### Python版本一键启动

**Linux/macOS:**
```bash
cd python && pip3 install -r requirements.txt && cd ../web && python3 api_python.py
```

**Windows:**
```cmd
cd python && pip3 install -r requirements.txt && cd ..\web && python api_python.py
```

#### Java版本一键启动

**Linux/macOS:**
```bash
cd java && mvn clean package && java -cp target/simulator-1.0.0-jar-with-dependencies.jar com.gb28181.simulator.web.ApiServer
```

**Windows:**
```cmd
cd java && mvn clean package && java -cp target/simulator-1.0.0-jar-with-dependencies.jar com.gb28181.simulator.web.ApiServer
```

### 注意事项

1. **端口占用**：确保5000端口（Python）或8080端口（Java）未被占用
2. **后端服务**：Web界面需要对应的后端服务（Python或Java）正在运行
3. **跨域访问**：如果从其他机器访问，需要修改后端服务的host配置
4. **防火墙**：确保防火墙允许访问Web服务端口
5. **MP4文件**：确保在 `python/resources/` 或 `java/src/main/resources/` 目录下有mp4文件用于推流
6. **FFmpeg**：确保系统已安装FFmpeg并配置到PATH环境变量

### 详细启动说明

更多详细的启动说明和故障排除，请参考：`web/启动说明.md`

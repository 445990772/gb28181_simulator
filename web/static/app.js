// API基础URL配置
const API_BASE_URLS = {
    python: 'http://localhost:5000/api',
    java: 'http://localhost:8080/api'
};

let currentBackend = 'python';
let autoScroll = true;
let statusCheckInterval = null;
let monitorInterval = null;
let messageInterval = null;
let isMonitorPaused = false;
let deviceCharts = {}; // 存储设备图表的对象
let systemResourcesInterval = null; // 系统资源轮询
let systemResourceCharts = {}; // 系统资源图表
let lastNetworkStats = { bytesSent: 0, bytesRecv: 0 }; // 上次网络统计，用于计算速率

// 初始化
document.addEventListener('DOMContentLoaded', function() {
    initializeEventListeners();
    initializeSystemResourceCharts();
    checkBackendConnection().then(() => {
        // 检查后端连接成功后，同步后端状态
        syncBackendStatus();
        // 开始系统资源监控
        startSystemResourcesPolling();
    });
});

function initializeEventListeners() {
    // 后端选择
    document.getElementById('backendType').addEventListener('change', function(e) {
        currentBackend = e.target.value;
        checkBackendConnection();
    });

    // 检查后端连接
    document.getElementById('checkBackend').addEventListener('click', checkBackendConnection);

    // 表单提交
    document.getElementById('configForm').addEventListener('submit', function(e) {
        e.preventDefault();
        startSimulator();
    });

    // 停止按钮
    document.getElementById('stopBtn').addEventListener('click', stopSimulator);

    // 状态按钮
    document.getElementById('statusBtn').addEventListener('click', getStatus);

    // 清空日志
    document.getElementById('clearLogBtn').addEventListener('click', function() {
        document.getElementById('logContainer').innerHTML = '';
        addLog('日志已清空', 'info');
    });

    // 自动滚动
    document.getElementById('autoScrollBtn').addEventListener('click', function() {
        autoScroll = !autoScroll;
        this.textContent = autoScroll ? '自动滚动 ✓' : '自动滚动';
        this.classList.toggle('active', autoScroll);
    });
}

async function checkBackendConnection() {
    const statusBar = document.getElementById('statusBar');
    const statusText = document.getElementById('statusText');
    
    try {
        const response = await fetch(`${API_BASE_URLS[currentBackend]}/health`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        if (response.ok) {
            const data = await response.json();
            statusBar.className = 'status-bar connected';
            statusText.textContent = `✓ 已连接到${currentBackend === 'python' ? 'Python' : 'Java'}后端服务`;
            addLog(`后端连接成功: ${data.message || 'OK'}`, 'success');
        } else {
            throw new Error('连接失败');
        }
    } catch (error) {
        statusBar.className = 'status-bar error';
        statusText.textContent = `✗ 无法连接到${currentBackend === 'python' ? 'Python' : 'Java'}后端服务`;
        addLog(`后端连接失败: ${error.message}`, 'error');
        addLog(`请确保${currentBackend === 'python' ? 'Python' : 'Java'}后端服务正在运行`, 'warning');
    }
}

async function startSimulator() {
    const formData = {
        serverIp: document.getElementById('serverIp').value,
        serverPort: parseInt(document.getElementById('serverPort').value),
        password: document.getElementById('password').value,
        deviceCount: parseInt(document.getElementById('deviceCount').value),
        channelCount: parseInt(document.getElementById('channelCount').value),
        baseDeviceId: document.getElementById('baseDeviceId').value,
        basePort: parseInt(document.getElementById('basePort').value)
    };

    addLog('正在启动模拟器...', 'info');
    addLog(`配置: ${JSON.stringify(formData, null, 2)}`, 'info');

    try {
        const response = await fetch(`${API_BASE_URLS[currentBackend]}/start`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(formData)
        });

        const data = await response.json();

        if (response.ok) {
            addLog('✓ 模拟器启动成功', 'success');
            addLog(`设备数量: ${data.deviceCount || formData.deviceCount}`, 'info');
            addLog(`总通道数: ${(data.deviceCount || formData.deviceCount) * (data.channelCount || formData.channelCount)}`, 'info');
            
            document.getElementById('startBtn').disabled = true;
            document.getElementById('stopBtn').disabled = false;
            
            // 显示监控和消息区域（如果存在）
            const monitorSection = document.getElementById('monitorSection');
            const messageSection = document.getElementById('messageSection');
            if (monitorSection) {
                monitorSection.style.display = 'block';
            }
            if (messageSection) {
                messageSection.style.display = 'block';
            }
            
            // 启动后立即获取并刷新设备列表状态
            setTimeout(async () => {
                try {
                    const statusResponse = await fetch(`${API_BASE_URLS[currentBackend]}/status`, {
                        method: 'GET',
                        headers: {
                            'Content-Type': 'application/json'
                        }
                    });
                    if (statusResponse.ok) {
                        const statusData = await statusResponse.json();
                        if (statusData.devices && statusData.devices.length > 0) {
                            displayDeviceList(statusData.devices);
                            addLog(`✓ 已更新设备列表，共 ${statusData.devices.length} 个设备`, 'success');
                        }
                    }
                } catch (error) {
                    console.error('启动后刷新设备列表失败:', error);
                }
            }, 1000); // 延迟1秒，等待设备初始化完成
            
            // 开始定期检查状态和监控数据
            startStatusPolling();
            startMonitorPolling();
            startMessagePolling();
            startSystemResourcesPolling();
        } else {
            addLog(`✗ 启动失败: ${data.error || '未知错误'}`, 'error');
        }
    } catch (error) {
        addLog(`✗ 启动失败: ${error.message}`, 'error');
    }
}

async function stopSimulator() {
    addLog('正在停止模拟器...', 'info');

    try {
        const response = await fetch(`${API_BASE_URLS[currentBackend]}/stop`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const data = await response.json();

        if (response.ok) {
            addLog('✓ 模拟器已停止', 'success');
            document.getElementById('startBtn').disabled = false;
            document.getElementById('stopBtn').disabled = true;
            
            // 停止状态轮询
            stopStatusPolling();
            stopMonitorPolling();
            stopMessagePolling();
            stopSystemResourcesPolling();
        } else {
            addLog(`✗ 停止失败: ${data.error || '未知错误'}`, 'error');
        }
    } catch (error) {
        addLog(`✗ 停止失败: ${error.message}`, 'error');
    }
}

async function syncBackendStatus() {
    // 同步后端状态到前端UI
    try {
        const response = await fetch(`${API_BASE_URLS[currentBackend]}/status`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        if (response.ok) {
            const data = await response.json();
            
            // 根据后端状态更新UI
            if (data.running) {
                // 后端正在运行，同步前端UI
                document.getElementById('startBtn').disabled = true;
                document.getElementById('stopBtn').disabled = false;
                
                // 显示监控和消息区域（如果存在）
                const monitorSection = document.getElementById('monitorSection');
                const messageSection = document.getElementById('messageSection');
                if (monitorSection) {
                    monitorSection.style.display = 'block';
                }
                if (messageSection) {
                    messageSection.style.display = 'block';
                }
                
                // 开始状态轮询
                startStatusPolling();
                startMonitorPolling();
                startMessagePolling();
                startSystemResourcesPolling();
                
                // 显示设备列表
                if (data.devices && data.devices.length > 0) {
                    displayDeviceList(data.devices);
                }
            } else {
                // 后端已停止，同步前端UI
                document.getElementById('startBtn').disabled = false;
                document.getElementById('stopBtn').disabled = true;
                
                // 停止状态轮询
                stopStatusPolling();
                stopSystemResourcesPolling();
            }
        }
    } catch (error) {
        // 静默处理，不影响页面加载
        console.log('同步后端状态失败:', error);
    }
}

async function getStatus() {
    try {
        const response = await fetch(`${API_BASE_URLS[currentBackend]}/status`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        const data = await response.json();

        if (response.ok) {
            addLog('=== 状态信息 ===', 'info');
            addLog(`运行状态: ${data.running ? '运行中' : '已停止'}`, data.running ? 'success' : 'warning');
            addLog(`设备数量: ${data.deviceCount || 0}`, 'info');
            
            // 强制刷新设备列表，无论设备列表是否已存在
            if (data.devices && data.devices.length > 0) {
                displayDeviceList(data.devices);
                addLog(`✓ 已刷新设备列表，共 ${data.devices.length} 个设备`, 'success');
            } else {
                // 如果没有设备，隐藏设备列表区域
                const deviceListSection = document.getElementById('deviceListSection');
                if (deviceListSection) {
                    deviceListSection.style.display = 'none';
                }
                addLog('当前没有运行中的设备', 'warning');
            }
        } else {
            addLog(`✗ 获取状态失败: ${data.error || '未知错误'}`, 'error');
        }
    } catch (error) {
        addLog(`✗ 获取状态失败: ${error.message}`, 'error');
    }
}

function startStatusPolling() {
    if (statusCheckInterval) {
        clearInterval(statusCheckInterval);
    }
    
    // 每5秒检查一次状态
    statusCheckInterval = setInterval(async () => {
        try {
            const response = await fetch(`${API_BASE_URLS[currentBackend]}/status`, {
                method: 'GET',
                headers: {
                    'Content-Type': 'application/json'
                }
            });

            if (response.ok) {
                const data = await response.json();
                if (!data.running) {
                    // 如果已停止，更新UI
                    document.getElementById('startBtn').disabled = false;
                    document.getElementById('stopBtn').disabled = true;
                    stopStatusPolling();
                    
                    // 清空设备列表
                    const deviceListSection = document.getElementById('deviceListSection');
                    if (deviceListSection) {
                        deviceListSection.style.display = 'none';
                    }
                } else {
                    // 如果正在运行，更新设备列表（无论设备列表是否为空）
                    if (data.devices && data.devices.length > 0) {
                        displayDeviceList(data.devices);
                    } else {
                        // 如果没有设备，隐藏设备列表区域
                        const deviceListSection = document.getElementById('deviceListSection');
                        const deviceList = document.getElementById('deviceList');
                        if (deviceListSection && deviceList) {
                            deviceListSection.style.display = 'block';
                            deviceList.innerHTML = '<div class="no-devices">当前没有运行中的设备</div>';
                        }
                    }
                }
            }
        } catch (error) {
            // 静默处理轮询错误
            console.error('状态轮询错误:', error);
        }
    }, 5000);
}

function stopStatusPolling() {
    if (statusCheckInterval) {
        clearInterval(statusCheckInterval);
        statusCheckInterval = null;
    }
}

function startMonitorPolling() {
    if (monitorInterval) {
        clearInterval(monitorInterval);
    }
    
    // 每秒获取一次监控数据
    monitorInterval = setInterval(async () => {
        if (isMonitorPaused) return;
        
        try {
            const response = await fetch(`${API_BASE_URLS[currentBackend]}/stats`, {
                method: 'GET',
                headers: {
                    'Content-Type': 'application/json'
                }
            });

            if (response.ok) {
                const data = await response.json();
                updateCharts(data);
            }
        } catch (error) {
            // 静默处理监控错误
        }
    }, 1000);
}

function stopMonitorPolling() {
    if (monitorInterval) {
        clearInterval(monitorInterval);
        monitorInterval = null;
    }
    clearAllCharts();
}

function updateCharts(stats) {
    if (!stats || !stats.devices) return;
    
    const monitorCharts = document.getElementById('monitorCharts');
    if (!monitorCharts) return; // 如果监控区域不存在，直接返回
    
    stats.devices.forEach(deviceStat => {
        const deviceId = deviceStat.deviceId;
        
        // 如果图表不存在，创建新图表
        if (!deviceCharts[deviceId]) {
            createChart(deviceId, deviceStat.deviceName || deviceId);
        }
        
        // 更新图表数据
        const chart = deviceCharts[deviceId];
        const now = new Date().toLocaleTimeString();
        const bytesPerSecond = deviceStat.bytesPerSecond || 0;
        const mbps = bytesPerSecond / (1024 * 1024);
        
        // 添加新数据点
        chart.data.labels.push(now);
        chart.data.datasets[0].data.push(mbps);
        
        // 保持最多60个数据点（1分钟的数据）
        if (chart.data.labels.length > 60) {
            chart.data.labels.shift();
            chart.data.datasets[0].data.shift();
        }
        
        chart.update('none'); // 'none'模式不显示动画，提高性能
    });
}

function createChart(deviceId, deviceName) {
    const monitorCharts = document.getElementById('monitorCharts');
    if (!monitorCharts) return; // 如果监控区域不存在，直接返回
    
    // 创建图表容器
    const chartContainer = document.createElement('div');
    chartContainer.className = 'chart-container';
    chartContainer.id = `chart-${deviceId}`;
    
    const chartTitle = document.createElement('h3');
    chartTitle.textContent = `${deviceName} (${deviceId})`;
    
    const chartWrapper = document.createElement('div');
    chartWrapper.className = 'chart-wrapper';
    const canvas = document.createElement('canvas');
    chartWrapper.appendChild(canvas);
    
    chartContainer.appendChild(chartTitle);
    chartContainer.appendChild(chartWrapper);
    monitorCharts.appendChild(chartContainer);
    
    // 创建Chart.js图表
    const ctx = canvas.getContext('2d');
    const chart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: [],
                datasets: [{
                    label: '数据包大小 (MB/s)',
                    data: [],
                    borderColor: '#0969da',
                    backgroundColor: 'rgba(9, 105, 218, 0.1)',
                    borderWidth: 2,
                    fill: true,
                    tension: 0.4
                }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: true,
                    position: 'top'
                },
                tooltip: {
                    mode: 'index',
                    intersect: false
                }
            },
            scales: {
                y: {
                    beginAtZero: true,
                    title: {
                        display: true,
                        text: 'MB/s'
                    }
                },
                x: {
                    title: {
                        display: true,
                        text: '时间'
                    },
                    ticks: {
                        maxTicksLimit: 10
                    }
                }
            },
            animation: false
        }
    });
    
    deviceCharts[deviceId] = chart;
}

function clearAllCharts() {
    Object.keys(deviceCharts).forEach(deviceId => {
        const chart = deviceCharts[deviceId];
        if (chart) {
            chart.destroy();
        }
        const chartContainer = document.getElementById(`chart-${deviceId}`);
        if (chartContainer) {
            chartContainer.remove();
        }
    });
    deviceCharts = {};
}

function startMessagePolling() {
    // 主页面不需要消息轮询，消息在设备详情页显示
    // 此函数保留以保持兼容性
    if (messageInterval) {
        clearInterval(messageInterval);
    }
}

function stopMessagePolling() {
    if (messageInterval) {
        clearInterval(messageInterval);
        messageInterval = null;
    }
}

function displayDeviceList(devices) {
    const deviceListSection = document.getElementById('deviceListSection');
    const deviceList = document.getElementById('deviceList');
    
    if (!deviceListSection || !deviceList) {
        console.error('设备列表元素不存在');
        return;
    }
    
    // 显示设备列表区域
    deviceListSection.style.display = 'block';
    
    // 清空现有列表，强制刷新所有设备状态
    deviceList.innerHTML = '';

    // 如果没有设备，显示提示信息
    if (!devices || devices.length === 0) {
        deviceList.innerHTML = '<div class="no-devices">当前没有运行中的设备</div>';
        return;
    }

    // 重新创建所有设备卡片，确保状态信息是最新的
    devices.forEach(device => {
        const deviceCard = document.createElement('div');
        deviceCard.className = 'device-card';
        deviceCard.style.cursor = 'pointer';
        deviceCard.addEventListener('click', () => {
            // 跳转到设备详情页
            window.location.href = `device-detail.html?deviceId=${device.deviceId}&backend=${currentBackend}`;
        });
        
        // 确保状态信息正确显示
        const isRegistered = device.isRegistered === true;
        const statusText = isRegistered ? '已注册' : '未注册';
        const statusClass = isRegistered ? 'registered' : 'stopped';
        
        deviceCard.innerHTML = `
            <h3>${device.deviceName || device.deviceId} <span style="font-size: 0.8em; color: #6c757d;">→</span></h3>
            <div class="device-info">
                <div class="device-info-item">
                    <label>设备ID</label>
                    <span>${device.deviceId}</span>
                </div>
                <div class="device-info-item">
                    <label>本地地址</label>
                    <span>${device.localIp}:${device.localPort}</span>
                </div>
                <div class="device-info-item">
                    <label>注册状态</label>
                    <span class="status-badge ${statusClass}">
                        ${statusText}
                    </span>
                </div>
                <div class="device-info-item">
                    <label>通道数</label>
                    <span>${device.channelCount || 0}</span>
                </div>
            </div>
        `;
        
        deviceList.appendChild(deviceCard);
    });
    
    console.log(`设备列表已更新，共 ${devices.length} 个设备`);
}

function addLog(message, type = 'info') {
    const logContainer = document.getElementById('logContainer');
    const timestamp = new Date().toLocaleTimeString();
    const logEntry = document.createElement('div');
    logEntry.className = `log-entry ${type}`;
    logEntry.textContent = `[${timestamp}] ${message}`;
    
    logContainer.appendChild(logEntry);
    
    if (autoScroll) {
        logContainer.scrollTop = logContainer.scrollHeight;
    }
}

// 消息功能已移至设备详情页

// 初始化系统资源图表
function initializeSystemResourceCharts() {
    // CPU图表
    const cpuCtx = document.getElementById('cpuChart');
    if (cpuCtx) {
        systemResourceCharts.cpu = new Chart(cpuCtx.getContext('2d'), {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: 'CPU使用率 (%)',
                    data: [],
                    borderColor: '#0969da',
                    backgroundColor: 'rgba(9, 105, 218, 0.1)',
                    borderWidth: 2,
                    fill: true,
                    tension: 0.4
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        mode: 'index',
                        intersect: false
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        max: 100,
                        title: {
                            display: true,
                            text: '%'
                        }
                    },
                    x: {
                        display: false
                    }
                },
                animation: false
            }
        });
    }
    
    // 内存图表
    const memoryCtx = document.getElementById('memoryChart');
    if (memoryCtx) {
        systemResourceCharts.memory = new Chart(memoryCtx.getContext('2d'), {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: '内存使用率 (%)',
                    data: [],
                    borderColor: '#2da44e',
                    backgroundColor: 'rgba(45, 164, 78, 0.1)',
                    borderWidth: 2,
                    fill: true,
                    tension: 0.4
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        mode: 'index',
                        intersect: false
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        max: 100,
                        title: {
                            display: true,
                            text: '%'
                        }
                    },
                    x: {
                        display: false
                    }
                },
                animation: false
            }
        });
    }
    
    // 网络图表
    const networkCtx = document.getElementById('networkChart');
    if (networkCtx) {
        systemResourceCharts.network = new Chart(networkCtx.getContext('2d'), {
            type: 'line',
            data: {
                labels: [],
                datasets: [{
                    label: '发送速率 (KB/s)',
                    data: [],
                    borderColor: '#da3633',
                    backgroundColor: 'rgba(218, 54, 51, 0.1)',
                    borderWidth: 2,
                    fill: true,
                    tension: 0.4
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: false
                    },
                    tooltip: {
                        mode: 'index',
                        intersect: false
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        title: {
                            display: true,
                            text: 'KB/s'
                        }
                    },
                    x: {
                        display: false
                    }
                },
                animation: false
            }
        });
    }
}

// 开始系统资源轮询
function startSystemResourcesPolling() {
    if (systemResourcesInterval) {
        clearInterval(systemResourcesInterval);
    }
    
    // 每2秒获取一次系统资源数据
    systemResourcesInterval = setInterval(async () => {
        try {
            const response = await fetch(`${API_BASE_URLS[currentBackend]}/system/resources`, {
                method: 'GET',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
            
            if (response.ok) {
                const data = await response.json();
                updateSystemResources(data);
            }
        } catch (error) {
            // 静默处理错误
            console.error('获取系统资源失败:', error);
        }
    }, 2000);
}

// 停止系统资源轮询
function stopSystemResourcesPolling() {
    if (systemResourcesInterval) {
        clearInterval(systemResourcesInterval);
        systemResourcesInterval = null;
    }
}

// 更新系统资源显示
function updateSystemResources(data) {
    if (!data) return;
    
    // 更新CPU信息
    if (data.cpu) {
        const cpuPercent = data.cpu.percent || 0;
        const cpuCount = data.cpu.count || 0;
        
        document.getElementById('cpuPercent').textContent = cpuPercent.toFixed(1);
        document.getElementById('cpuCount').textContent = cpuCount;
        
        // 更新CPU图表
        if (systemResourceCharts.cpu) {
            const now = new Date().toLocaleTimeString();
            systemResourceCharts.cpu.data.labels.push(now);
            systemResourceCharts.cpu.data.datasets[0].data.push(cpuPercent);
            
            // 保持最多60个数据点（2分钟的数据）
            if (systemResourceCharts.cpu.data.labels.length > 60) {
                systemResourceCharts.cpu.data.labels.shift();
                systemResourceCharts.cpu.data.datasets[0].data.shift();
            }
            
            systemResourceCharts.cpu.update('none');
        }
    }
    
    // 更新内存信息
    if (data.memory) {
        const memoryPercent = data.memory.percent || 0;
        const memoryUsed = (data.memory.used || 0) / (1024 * 1024); // 转换为MB
        const memoryAvailable = (data.memory.available || 0) / (1024 * 1024); // 转换为MB
        
        document.getElementById('memoryPercent').textContent = memoryPercent.toFixed(1);
        document.getElementById('memoryUsed').textContent = memoryUsed.toFixed(1);
        document.getElementById('memoryAvailable').textContent = memoryAvailable.toFixed(1);
        
        // 更新内存图表
        if (systemResourceCharts.memory) {
            const now = new Date().toLocaleTimeString();
            systemResourceCharts.memory.data.labels.push(now);
            systemResourceCharts.memory.data.datasets[0].data.push(memoryPercent);
            
            // 保持最多60个数据点
            if (systemResourceCharts.memory.data.labels.length > 60) {
                systemResourceCharts.memory.data.labels.shift();
                systemResourceCharts.memory.data.datasets[0].data.shift();
            }
            
            systemResourceCharts.memory.update('none');
        }
    }
    
    // 更新网络信息
    if (data.network) {
        const bytesSent = data.network.bytesSent || 0;
        const bytesRecv = data.network.bytesRecv || 0;
        
        // 计算速率（KB/s）
        const currentTime = data.timestamp || Date.now() / 1000;
        let sentRate = 0;
        let recvRate = 0;
        
        if (lastNetworkStats.timestamp) {
            const timeDiff = currentTime - lastNetworkStats.timestamp;
            if (timeDiff > 0) {
                sentRate = (bytesSent - lastNetworkStats.bytesSent) / timeDiff / 1024; // KB/s
                recvRate = (bytesRecv - lastNetworkStats.bytesRecv) / timeDiff / 1024; // KB/s
            }
        }
        
        // 更新显示
        document.getElementById('networkSent').textContent = sentRate.toFixed(1);
        document.getElementById('networkBytesSent').textContent = (bytesSent / (1024 * 1024)).toFixed(1);
        document.getElementById('networkBytesRecv').textContent = (bytesRecv / (1024 * 1024)).toFixed(1);
        
        // 更新网络图表
        if (systemResourceCharts.network) {
            const now = new Date().toLocaleTimeString();
            systemResourceCharts.network.data.labels.push(now);
            systemResourceCharts.network.data.datasets[0].data.push(sentRate);
            
            // 保持最多60个数据点
            if (systemResourceCharts.network.data.labels.length > 60) {
                systemResourceCharts.network.data.labels.shift();
                systemResourceCharts.network.data.datasets[0].data.shift();
            }
            
            systemResourceCharts.network.update('none');
        }
        
        // 保存当前统计用于下次计算（只在有有效时间戳时保存）
        if (currentTime > 0) {
            lastNetworkStats = {
                bytesSent: bytesSent,
                bytesRecv: bytesRecv,
                timestamp: currentTime
            };
        } else if (!lastNetworkStats.timestamp) {
            // 第一次调用，初始化
            lastNetworkStats = {
                bytesSent: bytesSent,
                bytesRecv: bytesRecv,
                timestamp: Date.now() / 1000
            };
        }
    }
}

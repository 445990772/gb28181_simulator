// API基础URL配置
const API_BASE_URL = 'http://localhost:5000/api';
let autoScroll = true;
let statusCheckInterval = null;
let monitorInterval = null;
let messageInterval = null;
let isMonitorPaused = false;
let deviceCharts = {}; // 存储设备图表的对象
let systemResourcesInterval = null; // 系统资源轮询（已废弃，改用SSE）
let systemResourcesEventSource = null; // 系统资源SSE连接
let systemResourceCharts = {}; // 系统资源图表
let lastNetworkStats = { bytesSent: 0, bytesRecv: 0, timestamp: 0 }; // 上次网络统计，用于计算速率
let isBackendConnected = false; // 是否已连接到后端

// 状态保存和恢复
function saveState() {
    try {
        const state = {
            isBackendConnected: isBackendConnected,
            timestamp: Date.now()
        };
        localStorage.setItem('gb28181_simulator_state', JSON.stringify(state));
    } catch (e) {
        console.error('保存状态失败:', e);
    }
}

function restoreState() {
    try {
        const stateStr = localStorage.getItem('gb28181_simulator_state');
        if (stateStr) {
            const state = JSON.parse(stateStr);
            // 检查状态是否过期（超过1小时则忽略）
            if (Date.now() - state.timestamp < 3600000) {
                return state;
            }
        }
    } catch (e) {
        console.error('恢复状态失败:', e);
    }
    return null;
}

function clearState() {
    try {
        localStorage.removeItem('gb28181_simulator_state');
    } catch (e) {
        console.error('清除状态失败:', e);
    }
}

// 初始化
document.addEventListener('DOMContentLoaded', function() {
    initializeEventListeners();
    initializeSystemResourceCharts();
    
    // 尝试恢复之前的状态
    const savedState = restoreState();
    if (savedState && savedState.isBackendConnected) {
        addLog('检测到之前的连接状态，正在恢复...', 'info');
        // 延迟一下，确保UI已初始化
        setTimeout(() => {
            restoreBackendConnection();
        }, 500);
    } else {
        addLog('请点击"检查后端服务"按钮检查服务状态', 'info');
    }
    
    // 确保初始状态
    if (!isBackendConnected) {
        const statusBar = document.getElementById('statusBar');
        const statusText = document.getElementById('statusText');
        if (statusBar) statusBar.className = 'status-bar';
        if (statusText) statusText.textContent = '未连接';
    }
});

// 恢复后端连接
async function restoreBackendConnection() {
    const statusBar = document.getElementById('statusBar');
    const statusText = document.getElementById('statusText');
    const checkBackendBtn = document.getElementById('checkBackend');
    const connectBtn = document.getElementById('connectBackend');
    const disconnectBtn = document.getElementById('disconnectBackend');
    
    try {
        // 先检查后端服务是否可用
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);
        
        const response = await fetch(`${API_BASE_URL}/health`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            },
            signal: controller.signal
        });
        
        clearTimeout(timeoutId);
        
        if (response.ok) {
            // 检查模拟器是否在运行
            const statusResponse = await fetch(`${API_BASE_URL}/status`, {
                method: 'GET',
                headers: {
                    'Content-Type': 'application/json'
                }
            });
            
            if (statusResponse.ok) {
                const statusData = await statusResponse.json();
                
                // 恢复连接状态
                isBackendConnected = true;
                statusBar.className = 'status-bar connected';
                statusText.textContent = `✓ 已连接到后端服务`;
                
                if (checkBackendBtn) checkBackendBtn.disabled = true;
                if (connectBtn) {
                    connectBtn.disabled = true;
                    connectBtn.classList.remove('show');
                }
                if (disconnectBtn) {
                    disconnectBtn.disabled = false;
                    disconnectBtn.classList.add('show');
                }
                
                addLog(`✓ 已恢复后端连接`, 'success');
                
                // 如果模拟器在运行，恢复UI状态
                if (statusData.running) {
                    document.getElementById('startBtn').disabled = true;
                    document.getElementById('stopBtn').disabled = false;
                    
                    // 显示监控和消息区域
                    const monitorSection = document.getElementById('monitorSection');
                    const messageSection = document.getElementById('messageSection');
                    if (monitorSection) monitorSection.style.display = 'block';
                    if (messageSection) messageSection.style.display = 'block';
                    
                    // 开始状态轮询
                    startStatusPolling();
                    startMonitorPolling();
                    startMessagePolling();
                    startSystemResourcesPolling();
                    
                    // 显示设备列表
                    if (statusData.devices && statusData.devices.length > 0) {
                        displayDeviceList(statusData.devices);
                    }
                    
                    addLog(`✓ 模拟器正在运行，已恢复监控`, 'success');
                }
                
                // 保存状态
                saveState();
            } else {
                throw new Error('无法获取状态');
            }
        } else {
            throw new Error('后端服务不可用');
        }
    } catch (error) {
        // 恢复失败，清除保存的状态
        clearState();
        isBackendConnected = false;
        statusBar.className = 'status-bar';
        statusText.textContent = '未连接';
        addLog(`恢复连接失败: ${error.message}，请手动连接`, 'warning');
    }
}

function initializeEventListeners() {
    // 检查后端服务（只检查是否存活，不建立连接）
    document.getElementById('checkBackend').addEventListener('click', checkBackendService);
    
    // 连接后端
    const connectBtn = document.getElementById('connectBackend');
    if (connectBtn) {
        connectBtn.addEventListener('click', connectBackend);
    }
    
    // 断开后端连接
    const disconnectBtn = document.getElementById('disconnectBackend');
    if (disconnectBtn) {
        disconnectBtn.addEventListener('click', disconnectBackend);
    }

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

// 检查后端服务（只检查服务是否存活，不建立连接）
async function checkBackendService() {
    const statusBar = document.getElementById('statusBar');
    const statusText = document.getElementById('statusText');
    const connectBtn = document.getElementById('connectBackend');
    
    addLog(`正在检查后端服务状态...`, 'info');
    
    try {
        // 创建超时控制器
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);
        
        const response = await fetch(`${API_BASE_URL}/health`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            },
            signal: controller.signal
        });
        
        clearTimeout(timeoutId);

        if (response.ok) {
            const data = await response.json();
            statusBar.className = 'status-bar';
            statusText.textContent = `✓ 后端服务运行正常`;
            addLog(`后端服务检查成功: ${data.message || 'OK'}`, 'success');
            addLog('可以点击"连接后端"按钮建立连接', 'info');
            
            // 服务可用时，启用连接按钮
            if (connectBtn) {
                connectBtn.disabled = false;
                connectBtn.classList.add('show');
            }
        } else {
            throw new Error('服务不可用');
        }
    } catch (error) {
        statusBar.className = 'status-bar error';
        statusText.textContent = `✗ 后端服务不可用`;
            addLog(`后端服务检查失败: ${error.message}`, 'error');
            addLog(`请确保后端服务正在运行`, 'warning');
        
        // 服务不可用时，禁用连接按钮
        if (connectBtn) {
            connectBtn.disabled = true;
            connectBtn.classList.remove('show');
        }
    }
}

// 连接后端（建立连接）
async function connectBackend() {
    // 如果已经连接到后端，提示用户先断开
    if (isBackendConnected) {
        addLog('⚠ 已连接到后端服务，请先点击"断开后端连接"按钮', 'warning');
        return;
    }
    
    const statusBar = document.getElementById('statusBar');
    const statusText = document.getElementById('statusText');
    const checkBackendBtn = document.getElementById('checkBackend');
    const connectBtn = document.getElementById('connectBackend');
    const disconnectBtn = document.getElementById('disconnectBackend');
    
    addLog(`正在连接后端服务...`, 'info');
    
    // 确保所有旧连接已完全清理
    stopStatusPolling();
    stopMonitorPolling();
    stopMessagePolling();
    stopSystemResourcesPolling();
    
    try {
        // 创建超时控制器
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);
        
        const response = await fetch(`${API_BASE_URL}/health`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            },
            signal: controller.signal
        });
        
        clearTimeout(timeoutId);

        if (response.ok) {
            const data = await response.json();
            statusBar.className = 'status-bar connected';
            statusText.textContent = `✓ 已连接到后端服务`;
            addLog(`后端连接成功: ${data.message || 'OK'}`, 'success');
            
            // 连接成功后，禁用检查按钮，隐藏连接按钮，启用断开连接按钮
            isBackendConnected = true;
            if (checkBackendBtn) checkBackendBtn.disabled = true;
            if (connectBtn) {
                connectBtn.disabled = true;
                connectBtn.classList.remove('show');
            }
            if (disconnectBtn) {
                disconnectBtn.disabled = false;
                disconnectBtn.classList.add('show');
            }
            
            // 保存状态
            saveState();
        } else {
            throw new Error('连接失败');
        }
    } catch (error) {
        statusBar.className = 'status-bar error';
        statusText.textContent = `✗ 无法连接到后端服务`;
        addLog(`后端连接失败: ${error.message}`, 'error');
        addLog(`请确保后端服务正在运行`, 'warning');
        
        // 连接失败时，保持按钮可用
        isBackendConnected = false;
        if (checkBackendBtn) checkBackendBtn.disabled = false;
        if (connectBtn) {
            connectBtn.disabled = false;
            connectBtn.classList.add('show');
        }
        if (disconnectBtn) {
            disconnectBtn.disabled = true;
            disconnectBtn.classList.remove('show');
        }
    }
}

// 断开后端连接
function disconnectBackend() {
    // 检查模拟器是否正在运行
    const stopBtn = document.getElementById('stopBtn');
    if (!stopBtn.disabled) {
        addLog('⚠ 请先停止模拟器，然后才能断开后端连接', 'warning');
        return;
    }
    
    const statusBar = document.getElementById('statusBar');
    const statusText = document.getElementById('statusText');
    const checkBackendBtn = document.getElementById('checkBackend');
    const disconnectBtn = document.getElementById('disconnectBackend');
    
    // 完全停止所有轮询和连接
    stopStatusPolling();
    stopMonitorPolling();
    stopMessagePolling();
    stopSystemResourcesPolling();
    
    // 确保所有EventSource连接都已关闭
    if (systemResourcesEventSource) {
        try {
            systemResourcesEventSource.close();
        } catch (e) {
            console.error('关闭SSE连接时出错:', e);
        }
        systemResourcesEventSource = null;
    }
    
    // 清理所有图表数据
    clearAllCharts();
    
    // 重置连接状态
    isBackendConnected = false;
    statusBar.className = 'status-bar';
    statusText.textContent = '未连接';
    
    // 启用检查按钮，隐藏连接和断开按钮
    if (checkBackendBtn) checkBackendBtn.disabled = false;
    const connectBtn = document.getElementById('connectBackend');
    if (connectBtn) {
        connectBtn.disabled = true;
        connectBtn.classList.remove('show');
    }
    if (disconnectBtn) {
        disconnectBtn.disabled = true;
        disconnectBtn.classList.remove('show');
    }
    
    addLog('✓ 已断开后端连接，可以重新连接', 'success');
    
    // 清除保存的状态
    clearState();
}

async function startSimulator() {
    // 检查是否已连接到后端
    if (!isBackendConnected) {
        addLog('⚠ 请先连接后端服务，然后才能启动模拟器', 'warning');
        addLog('请先点击"检查后端服务"，然后点击"连接后端"按钮', 'info');
        return;
    }
    
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
        const response = await fetch(`${API_BASE_URL}/start`, {
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
                    const statusResponse = await fetch(`${API_BASE_URL}/status`, {
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
            
            // 保存状态
            saveState();
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
        const response = await fetch(`${API_BASE_URL}/stop`, {
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
            
            // 保存状态（模拟器已停止，但后端连接仍然保持）
            saveState();
            
            // 提示可以断开后端连接
            addLog('提示：模拟器已停止，可以断开后端连接以切换后端版本', 'info');
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
        const response = await fetch(`${API_BASE_URL}/status`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        if (response.ok) {
            const data = await response.json();
            
            // 如果后端有响应，说明已连接
            isBackendConnected = true;
            const checkBackendBtn = document.getElementById('checkBackend');
            const disconnectBtn = document.getElementById('disconnectBackend');
            if (checkBackendBtn) checkBackendBtn.disabled = true;
            if (disconnectBtn) {
                disconnectBtn.disabled = false;
                disconnectBtn.classList.add('show');
            }
            
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
        // 如果同步失败，重置连接状态
        isBackendConnected = false;
        const checkBackendBtn = document.getElementById('checkBackend');
        const disconnectBtn = document.getElementById('disconnectBackend');
        if (checkBackendBtn) checkBackendBtn.disabled = false;
        if (disconnectBtn) {
            disconnectBtn.disabled = true;
            disconnectBtn.classList.remove('show');
        }
    }
}

async function getStatus() {
    try {
        const response = await fetch(`${API_BASE_URL}/status`, {
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
            const response = await fetch(`${API_BASE_URL}/status`, {
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
            const response = await fetch(`${API_BASE_URL}/stats`, {
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
            window.location.href = `device-detail.html?deviceId=${device.deviceId}`;
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

// 开始系统资源SSE推送
function startSystemResourcesPolling() {
    // 先完全停止旧的连接
    stopSystemResourcesPolling();
    
    // 等待一小段时间确保旧连接完全关闭
    setTimeout(() => {
        // 检查是否仍然连接（可能在等待期间断开了）
        if (!isBackendConnected) {
            return;
        }
        
        // 建立SSE连接
        const url = `${API_BASE_URL}/system/resources/stream`;
        try {
            systemResourcesEventSource = new EventSource(url);
            
            systemResourcesEventSource.onmessage = function(event) {
                try {
                    const data = JSON.parse(event.data);
                    if (data.type === 'update') {
                        updateSystemResources(data);
                    } else if (data.type === 'error') {
                        console.error('系统资源SSE错误:', data.message);
                    }
                } catch (error) {
                    console.error('解析系统资源数据失败:', error);
                }
            };
            
            systemResourcesEventSource.onerror = function(error) {
                console.error('系统资源SSE连接错误:', error);
                // 只有在仍然连接时才尝试重新连接
                if (isBackendConnected && systemResourcesEventSource && systemResourcesEventSource.readyState === EventSource.CLOSED) {
                    setTimeout(() => {
                        if (isBackendConnected) {
                            startSystemResourcesPolling();
                        }
                    }, 3000);
                }
            };
        } catch (error) {
            console.error('创建系统资源SSE连接失败:', error);
        }
    }, 100);
}

// 停止系统资源SSE推送
function stopSystemResourcesPolling() {
    if (systemResourcesInterval) {
        clearInterval(systemResourcesInterval);
        systemResourcesInterval = null;
    }
    if (systemResourcesEventSource) {
        try {
            // 检查连接状态，确保正确关闭
            if (systemResourcesEventSource.readyState !== EventSource.CLOSED) {
                systemResourcesEventSource.close();
            }
        } catch (e) {
            console.error('关闭系统资源SSE连接时出错:', e);
        } finally {
            systemResourcesEventSource = null;
        }
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
        
        // 如果SSE已经计算了速率，直接使用；否则自己计算
        let sentRate = data.network.sentRate || 0;
        let recvRate = data.network.recvRate || 0;
        
        const currentTime = data.timestamp || Date.now() / 1000;
        
        // 如果SSE没有提供速率，则自己计算
        if (sentRate === 0 && lastNetworkStats.timestamp) {
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

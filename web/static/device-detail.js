// 设备详情页JavaScript逻辑

// API基础URL配置
const API_BASE_URLS = {
    python: 'http://localhost:5000/api',
    java: 'http://localhost:5001/api'
};

// 从URL获取后端类型（从localStorage或默认）
let currentBackend = localStorage.getItem('backendType') || 'python';
let deviceId = null;
let deviceInfo = null;
let channels = [];
let trafficChart = null;
let isStatsPaused = false;
let autoScrollMessages = true;
let selectedChannel = 'all';
let selectedStatsChannel = 'all'; // 流量统计通道选择
let lastMessageCount = 0;
let eventSource = null;

// 通道颜色配置（GitHub风格）
const CHANNEL_COLORS = [
    '#0969da', '#2da44e', '#da3633', '#8250df', '#cf222e',
    '#1f883d', '#0969da', '#8250df', '#0969da', '#cf222e'
];

// 初始化
document.addEventListener('DOMContentLoaded', function() {
    initializePage();
});

function initializePage() {
    // 获取设备ID和后端类型
    const urlParams = new URLSearchParams(window.location.search);
    deviceId = urlParams.get('deviceId');
    const backendParam = urlParams.get('backend');
    if (backendParam) {
        currentBackend = backendParam;
        localStorage.setItem('backendType', backendParam);
    }
    
    if (!deviceId) {
        alert('缺少设备ID参数');
        window.location.href = 'index.html';
        return;
    }
    
    console.log('设备ID:', deviceId, '后端:', currentBackend);

    // 绑定事件
    document.getElementById('backBtn').addEventListener('click', () => {
        window.location.href = 'index.html';
    });

    document.getElementById('autoScrollMessages').addEventListener('change', function(e) {
        autoScrollMessages = e.target.checked;
    });

    document.getElementById('channelFilter').addEventListener('change', function(e) {
        selectedChannel = e.target.value;
        filterMessages();
        // 通道改变时重新连接SSE
        stopSSE();
        startSSE();
    });

    // 流量统计通道选择器
    document.getElementById('statsChannelFilter').addEventListener('change', function(e) {
        selectedStatsChannel = e.target.value;
        updateChartVisibility();
    });

    document.getElementById('clearMessagesBtn').addEventListener('click', function() {
        document.getElementById('imMessageContainer').innerHTML = '<div class="message-placeholder">消息已清空</div>';
        lastMessageCount = 0;
    });

    document.getElementById('pauseStatsBtn').addEventListener('click', function() {
        isStatsPaused = true;
        this.style.display = 'none';
        document.getElementById('resumeStatsBtn').style.display = 'inline-block';
    });

    document.getElementById('resumeStatsBtn').addEventListener('click', function() {
        isStatsPaused = false;
        this.style.display = 'none';
        document.getElementById('pauseStatsBtn').style.display = 'inline-block';
    });

    document.getElementById('clearStatsBtn').addEventListener('click', function() {
        if (trafficChart) {
            trafficChart.data.labels = [];
            trafficChart.data.datasets.forEach(dataset => {
                dataset.data = [];
            });
            trafficChart.update();
        }
    });

    // 加载设备信息（异步，完成后初始化图表和开始SSE）
    loadDeviceInfo().then(() => {
        // 开始SSE推送
        startPolling();
    });
    
    // 页面卸载时关闭SSE连接
    window.addEventListener('beforeunload', () => {
        stopSSE();
    });
}

async function loadDeviceInfo() {
    try {
        if (!deviceId) {
            throw new Error('设备ID未指定');
        }
        
        const url = `${API_BASE_URLS[currentBackend]}/device/${deviceId}/info`;
        console.log('请求设备信息:', url);
        
        const response = await fetch(url, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        });

        // 检查响应类型
        const contentType = response.headers.get('content-type');
        if (!contentType || !contentType.includes('application/json')) {
            const text = await response.text();
            console.error('收到非JSON响应:', text.substring(0, 200));
            throw new Error(`服务器返回了非JSON响应 (${response.status}): ${contentType}`);
        }

        if (response.ok) {
            const data = await response.json();
            deviceInfo = data;
            channels = data.channels || [];
            
            // 更新页面标题
            document.getElementById('deviceTitle').textContent = 
                `${data.deviceName || data.deviceId} - 设备详情`;
            
            // 更新状态
            const statusBadge = document.getElementById('statusBadge');
            statusBadge.textContent = data.isRegistered ? '已注册' : '未注册';
            statusBadge.className = `status-badge ${data.isRegistered ? 'registered' : 'stopped'}`;
            
            // 更新通道过滤器（消息收发区和流量统计区）
            updateChannelFilter();
            updateStatsChannelFilter();
            
            // 初始化图表（需要通道信息）
            if (channels.length > 0) {
                initTrafficChart();
            }
        } else {
            // 尝试解析错误响应
            let errorMessage = '未知错误';
            try {
                const errorData = await response.json();
                errorMessage = errorData.error || errorMessage;
            } catch (e) {
                // 如果不是 JSON，使用状态文本
                errorMessage = `HTTP ${response.status}: ${response.statusText}`;
            }
            alert('加载设备信息失败: ' + errorMessage);
            window.location.href = 'index.html';
        }
    } catch (error) {
        console.error('加载设备信息失败:', error);
        alert('加载设备信息失败: ' + error.message);
        window.location.href = 'index.html';
    }
}

function updateChannelFilter() {
    const filter = document.getElementById('channelFilter');
    filter.innerHTML = '<option value="all">所有通道</option>';
    
    channels.forEach(channel => {
        const option = document.createElement('option');
        option.value = channel.id;
        option.textContent = `${channel.name || channel.id}`;
        filter.appendChild(option);
    });
}

function updateStatsChannelFilter() {
    const filter = document.getElementById('statsChannelFilter');
    if (!filter) return;
    
    filter.innerHTML = '<option value="all">所有通道</option>';
    
    channels.forEach(channel => {
        const option = document.createElement('option');
        option.value = channel.id;
        option.textContent = `${channel.name || channel.id}`;
        filter.appendChild(option);
    });
}

function updateChartVisibility() {
    if (!trafficChart || !trafficChart.data || !trafficChart.data.datasets) {
        return;
    }
    
    // 根据选择的通道显示/隐藏对应的数据集
    trafficChart.data.datasets.forEach((dataset, index) => {
        if (selectedStatsChannel === 'all') {
            // 显示所有通道
            dataset.hidden = false;
        } else {
            // 只显示选中的通道
            const channelId = String(channels[index]?.id);
            dataset.hidden = channelId !== selectedStatsChannel;
        }
    });
    
    trafficChart.update();
    updateChannelLegend();
}

function initTrafficChart() {
    const ctx = document.getElementById('trafficChart').getContext('2d');
    
    // 为每个通道创建数据集
    const datasets = channels.map((channel, index) => ({
        label: channel.name || channel.id,
        data: [],
        borderColor: CHANNEL_COLORS[index % CHANNEL_COLORS.length],
        backgroundColor: CHANNEL_COLORS[index % CHANNEL_COLORS.length] + '20',
        borderWidth: 2,
        fill: false,
        tension: 0.4
    }));
    
    trafficChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: [],
            datasets: datasets
        },
        options: {
            responsive: true,
            maintainAspectRatio: true,
            plugins: {
                legend: {
                    display: false // 使用自定义图例
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
                        maxTicksLimit: 20,
                        autoSkip: true
                    }
                }
            },
            animation: false
        }
    });
    
    // 初始化图表可见性
    updateChartVisibility();
}

function updateChannelLegend() {
    const legend = document.getElementById('channelLegend');
    if (!legend) return;
    
    legend.innerHTML = '';
    
    channels.forEach((channel, index) => {
        const channelId = String(channel.id);
        const dataset = trafficChart?.data.datasets[index];
        
        // 如果选择了特定通道，只显示该通道的图例
        if (selectedStatsChannel !== 'all' && channelId !== selectedStatsChannel) {
            return;
        }
        
        // 如果数据集被隐藏，也不显示图例
        if (dataset && dataset.hidden) {
            return;
        }
        
        const legendItem = document.createElement('div');
        legendItem.className = 'legend-item';
        
        const color = CHANNEL_COLORS[index % CHANNEL_COLORS.length];
        const lastValue = dataset?.data[dataset.data.length - 1] || 0;
        
        legendItem.innerHTML = `
            <div class="legend-color" style="background-color: ${color}"></div>
            <span class="legend-label">${channel.name || channel.id}</span>
            <span class="legend-value">${lastValue.toFixed(3)} MB/s</span>
        `;
        
        legend.appendChild(legendItem);
    });
}

function startPolling() {
    // 使用SSE替代定时轮询
    startSSE();
}

function startSSE() {
    // 关闭已存在的连接
    if (eventSource) {
        eventSource.close();
    }
    
    // 清空消息容器，只显示SSE连接建立后的新消息
    const messageContainer = document.getElementById('imMessageContainer');
    if (messageContainer) {
        messageContainer.innerHTML = '<div class="message-placeholder">等待消息...</div>';
    }
    
    // 构建URL参数
    const params = new URLSearchParams();
    if (selectedChannel !== 'all') {
        params.append('channelId', selectedChannel);
    }
    params.append('newOnly', 'true');
    
    // 添加参数告诉后端只发送新消息（不发送历史消息）
    const url = `${API_BASE_URLS[currentBackend]}/device/${deviceId}/stream?${params.toString()}`;
    
    eventSource = new EventSource(url);
    
    // 标记是否是第一次收到数据（用于忽略历史消息）
    let isFirstUpdate = true;
    
    eventSource.onmessage = function(event) {
        try {
            const data = JSON.parse(event.data);
            
            if (data.type === 'update') {
                // 第一次更新时，忽略历史消息，只处理后续的新消息
                if (isFirstUpdate) {
                    isFirstUpdate = false;
                    // 清空消息容器，确保不显示历史消息
                    const messageContainer = document.getElementById('imMessageContainer');
                    if (messageContainer) {
                        messageContainer.innerHTML = '<div class="message-placeholder">等待消息...</div>';
                    }
                    // 只更新统计数据，不处理消息
                    if (!isStatsPaused && data.stats && data.stats.channels) {
                        updateStatsFromSSE(data.stats.channels);
                    }
                    // 第一次更新时，即使有消息也忽略（因为可能是历史消息）
                    console.log('SSE连接已建立，忽略历史消息，等待新消息...');
                    return;
                }
                
                // 更新统计数据
                if (!isStatsPaused && data.stats && data.stats.channels) {
                    updateStatsFromSSE(data.stats.channels);
                }
                
                // 更新消息：只处理SSE连接建立后的新消息
                if (data.messages && Array.isArray(data.messages) && data.messages.length > 0) {
                    console.log(`收到 ${data.messages.length} 条新消息`);
                    data.messages.forEach(msg => {
                        addMessage(msg);
                    });
                }
            } else if (data.type === 'error') {
                console.error('SSE错误:', data.message);
            }
        } catch (error) {
            console.error('解析SSE数据失败:', error);
        }
    };
    
    eventSource.onopen = function() {
        console.log('SSE连接已建立:', url);
    };
    
    eventSource.onerror = function(error) {
        console.error('SSE连接错误:', error, 'URL:', url);
        console.error('SSE状态:', eventSource?.readyState);
        // 尝试重连
        setTimeout(() => {
            if (eventSource && eventSource.readyState === EventSource.CLOSED) {
                console.log('尝试重新连接SSE...');
                startSSE();
            }
        }, 3000);
    };
}

function stopSSE() {
    if (eventSource) {
        eventSource.close();
        eventSource = null;
    }
}

function updateStatsFromSSE(channelsStats) {
    if (!trafficChart || channels.length === 0) {
        console.log('图表未初始化或通道为空', { trafficChart: !!trafficChart, channelsLength: channels.length });
        return;
    }
    
    const now = new Date().toLocaleTimeString();
    
    // 更新图表数据
    trafficChart.data.labels.push(now);
    
    // 为每个通道更新数据
    channels.forEach((channel, index) => {
        // 确保通道ID匹配（转换为字符串进行比较）
        const channelId = String(channel.id);
        // 后端返回的channelId可能包含@IP:端口后缀，需要提取纯通道ID进行匹配
        const channelStat = channelsStats.find(c => {
            const statChannelId = String(c.channelId);
            // 如果统计数据的channelId包含@符号，提取@前面的部分
            const pureStatId = statChannelId.includes('@') ? statChannelId.split('@')[0] : statChannelId;
            return pureStatId === channelId;
        });
        const mbps = channelStat ? channelStat.mbps : 0;
        
        console.log(`更新通道 ${channelId} (${channel.name || channel.id}) 数据:`, {
            index,
            channelId,
            mbps,
            hasDataset: !!trafficChart.data.datasets[index],
            channelsStatsLength: channelsStats.length,
            matchedStat: channelStat ? { channelId: channelStat.channelId, mbps: channelStat.mbps } : null,
            channelsStats: channelsStats.map(c => ({ channelId: c.channelId, mbps: c.mbps }))
        });
        
        if (trafficChart.data.datasets[index]) {
            trafficChart.data.datasets[index].data.push(mbps);
        } else {
            console.warn(`通道 ${channelId} 的数据集不存在，索引: ${index}, 数据集数量: ${trafficChart.data.datasets.length}`);
        }
    });
    
    // 保持最多20个数据点，超出时删除旧数据
    const MAX_DATA_POINTS = 20;
    
    // 当数据点超过20个时，删除最旧的数据点，只保留最新的20个
    while (trafficChart.data.labels.length > MAX_DATA_POINTS) {
        trafficChart.data.labels.shift();
        trafficChart.data.datasets.forEach(dataset => {
            dataset.data.shift();
        });
    }
    
    trafficChart.update('none');
    updateChartVisibility(); // 更新图表可见性
    updateChannelLegend();
}

function addMessage(msg) {
    const container = document.getElementById('imMessageContainer');
    
    // 移除占位符
    const placeholder = container.querySelector('.message-placeholder');
    if (placeholder) {
        placeholder.remove();
    }
    
    const messageDiv = document.createElement('div');
    const direction = msg.direction || 'recv';
    messageDiv.className = `im-message ${direction}`;
    
    // 设置通道ID属性，用于过滤功能
    const channelId = msg.channelId || '';
    if (channelId) {
        messageDiv.setAttribute('data-channel-id', channelId);
    }
    
    // 确定通道显示名称的优先级：
    // 1. SSE返回的channelName（最优先）
    // 2. 从channels数组查找匹配的通道名称
    // 3. 使用channelId本身（去掉@后缀）- 确保不显示"未知通道"
    
    // 提取纯通道ID（去掉@后缀），用于匹配和显示
    const pureChannelId = (channelId && typeof channelId === 'string' && channelId.includes('@')) 
        ? channelId.split('@')[0] 
        : (channelId || '');
    
    // 调试信息
    if (direction === 'send') {
        console.log('处理send消息，通道信息:', {
            channelId: channelId,
            pureChannelId: pureChannelId,
            channelName: msg.channelName,
            hasChannelId: !!channelId && channelId.trim() !== '',
            channelIdType: typeof channelId,
            channelsLength: channels.length,
            msg: msg
        });
    }
    
    let channelDisplayName = null;
    
    // 1. 优先使用SSE返回的channelName
    if (msg.channelName && msg.channelName.trim() !== '') {
        channelDisplayName = msg.channelName;
    } 
    // 2. 如果有channelId（非空字符串），尝试从channels数组查找
    else if (channelId && channelId.trim() !== '') {
        // 从channels数组查找（支持纯通道ID匹配，也支持带@后缀的匹配）
        const channel = channels.find(c => {
            const cId = String(c.id);
            const cPureId = cId.includes('@') ? cId.split('@')[0] : cId;
            // 匹配：完全匹配、纯ID匹配
            return cId === channelId || cId === pureChannelId || cPureId === pureChannelId;
        });
        
        if (channel) {
            channelDisplayName = channel.name || channel.id;
        } else {
            // 如果找不到匹配的通道，使用纯通道ID作为显示名称
            channelDisplayName = pureChannelId || channelId;
        }
    }
    
    // 3. 如果还是没有，但有channelId，使用channelId（确保不显示"未知通道"）
    if (!channelDisplayName || channelDisplayName.trim() === '') {
        if (channelId && channelId.trim() !== '') {
            channelDisplayName = pureChannelId || channelId;
        } else {
            // 只有在完全没有channelId的情况下，才显示"未知通道"
            channelDisplayName = '未知通道';
        }
    }
    
    // 最终安全检查：如果channelId存在，绝不显示"未知通道"
    if (channelDisplayName === '未知通道' && channelId && channelId.trim() !== '') {
        channelDisplayName = pureChannelId || channelId;
        console.warn('检测到channelId存在但显示为"未知通道"，已修正为:', channelDisplayName);
    }
    
    // 调试信息
    if (direction === 'send') {
        console.log('最终通道显示名称:', channelDisplayName, 'channelId:', channelId);
    }
    
    const messageId = `msg-${Date.now()}-${Math.random()}`;
    
    // 格式化时间：带上年月日
    let timestamp;
    if (msg.timestamp) {
        const date = new Date(msg.timestamp * 1000);
        timestamp = date.toLocaleString('zh-CN', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        });
    } else {
        const date = new Date();
        timestamp = date.toLocaleString('zh-CN', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        });
    }
    
    // 前端清理消息内容：删除多余的空格和空行
    // 注意：此清理逻辑对所有消息都生效，包括模拟器发送的消息（direction='send'）和接收的消息（direction='recv'）
    let messageContent = msg.content || msg.summary || '';
    
    if (!messageContent) {
        messageContent = '';
        return; // 如果消息为空，直接返回
    }
    
    // 1. 删除每行开头的空格和制表符（包括首行）
    let lines = messageContent.split('\n');
    let cleanedLines = lines.map(line => {
        // 删除行首的所有空白字符（空格、制表符等）
        return line.replace(/^[\s\t]+/, '');
    });
    messageContent = cleanedLines.join('\n');
    
    // 2. 找到消息头和消息体的分界点
    // SIP消息格式：消息头 + 空行 + 消息体
    // 需要找到最后一个header行之后的第一个空行（通常是Content-Length之后）
    // 先尝试用 \r\n\r\n 分割（SIP标准格式）
    let headerEndIndex = -1;
    let separator = '';
    let hasBody = false;
    let header = '';
    let body = '';
    
    // 查找 \r\n\r\n
    let rnrnIndex = messageContent.indexOf('\r\n\r\n');
    if (rnrnIndex !== -1) {
        headerEndIndex = rnrnIndex;
        separator = '\r\n\r\n';
    } else {
        // 如果没有 \r\n\r\n，尝试用 \n\n 分割
        // 但要注意：消息头内部可能也有空行，所以需要找到最后一个header行之后的空行
        // 通常Content-Length是最后一个header，所以找到Content-Length之后的第一个空行
        let contentLengthIndex = messageContent.indexOf('Content-Length:');
        if (contentLengthIndex !== -1) {
            // 找到Content-Length行之后的下一个空行
            let afterContentLength = messageContent.substring(contentLengthIndex);
            let nextEmptyLine = afterContentLength.indexOf('\n\n');
            if (nextEmptyLine !== -1) {
                headerEndIndex = contentLengthIndex + nextEmptyLine;
                separator = '\n\n';
            } else {
                // 如果没找到 \n\n，尝试找 \r\n\r\n
                nextEmptyLine = afterContentLength.indexOf('\r\n\r\n');
                if (nextEmptyLine !== -1) {
                    headerEndIndex = contentLengthIndex + nextEmptyLine;
                    separator = '\r\n\r\n';
                }
            }
        }
        
        // 如果还是没找到，尝试找第一个 \n\n
        if (headerEndIndex === -1) {
            let nnIndex = messageContent.indexOf('\n\n');
            if (nnIndex !== -1) {
                headerEndIndex = nnIndex;
                separator = '\n\n';
            }
        }
    }
    
    // 3. 分割消息头和消息体
    if (headerEndIndex !== -1) {
        header = messageContent.substring(0, headerEndIndex);
        body = messageContent.substring(headerEndIndex + separator.length);
        hasBody = true;
    } else {
        // 如果没有明确的分界，整个消息都是消息头
        header = messageContent;
    }
    
    // 4. 清理消息头：删除所有空行（保留非空行）
    header = header.split('\n').filter(line => line.trim()).join('\n');
    
    // 5. 清理消息体：删除所有空行（保留非空行）
    if (hasBody && body) {
        body = body.split('\n').filter(line => line.trim()).join('\n');
        // 如果消息体不为空，保留消息头和消息体之间的分隔
        messageContent = header + (separator || '\n\n') + body;
    } else {
        messageContent = header;
    }
    
    // 6. 删除末尾的空行和空格
    messageContent = messageContent.replace(/[\s\n\r]*$/, '');
    
    // 7. 删除开头的空行和空格
    messageContent = messageContent.replace(/^[\s\n\r\t]+/, '');
    
    // 确保清理后的消息不为空
    if (!messageContent || messageContent.trim() === '') {
        messageContent = msg.summary || '空消息';
    }
    
    // 使用createElement创建DOM元素，不使用innerHTML
    // 白色气泡（recv，来自平台）：左上角显示"平台"，右上角不显示通道
    // 紫色气泡（send，来自设备）：右上角显示通道名称，左上角不显示"平台"
    const messageBubble = document.createElement('div');
    messageBubble.className = 'message-bubble';
    
    const messageHeader = document.createElement('div');
    messageHeader.className = 'message-header';
    
    if (direction === 'recv') {
        const senderSpan = document.createElement('span');
        senderSpan.className = 'message-sender';
        senderSpan.textContent = '平台';
        messageHeader.appendChild(senderSpan);
    } else {
        const channelSpan = document.createElement('span');
        channelSpan.className = 'message-channel';
        channelSpan.textContent = channelDisplayName;
        messageHeader.appendChild(channelSpan);
    }
    
    const messageContentDiv = document.createElement('div');
    messageContentDiv.className = 'message-content';
    messageContentDiv.id = messageId;
    messageContentDiv.textContent = messageContent;
    
    const messageFooter = document.createElement('div');
    messageFooter.className = 'message-footer';
    
    const timeSpan = document.createElement('span');
    timeSpan.className = 'message-time';
    timeSpan.textContent = timestamp;
    messageFooter.appendChild(timeSpan);
    
    messageBubble.appendChild(messageHeader);
    messageBubble.appendChild(messageContentDiv);
    messageBubble.appendChild(messageFooter);
    
    messageDiv.appendChild(messageBubble);
    container.appendChild(messageDiv);
    
    // 不限制消息数量，显示所有消息（根据用户需求，先不考虑保留历史数据的问题）
    // 如果需要限制，可以在这里添加逻辑
    
    if (autoScrollMessages) {
        container.scrollTop = container.scrollHeight;
    }
}

// 移除 toggleMessage 函数，因为不再需要展开/收起功能

function filterMessages() {
    const container = document.getElementById('imMessageContainer');
    const messages = container.querySelectorAll('.im-message');
    
    messages.forEach(msg => {
        if (selectedChannel === 'all') {
            msg.style.display = 'flex';
        } else {
            const msgChannelId = msg.getAttribute('data-channel-id');
            if (msgChannelId === selectedChannel) {
                msg.style.display = 'flex';
            } else {
                msg.style.display = 'none';
            }
        }
    });
}

// escapeHtml函数已不再使用，改为直接使用textContent


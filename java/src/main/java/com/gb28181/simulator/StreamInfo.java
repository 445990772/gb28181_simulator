package com.gb28181.simulator;

/**
 * 流统计信息
 */
class StreamInfo {
    long bytesTotal;
    long bytesLastSec;
    long lastUpdateTime;
    long startTime;

    StreamInfo(long currentTime) {
        this.bytesTotal = 0;
        this.bytesLastSec = 0;
        this.lastUpdateTime = currentTime;
        this.startTime = currentTime;
    }
}


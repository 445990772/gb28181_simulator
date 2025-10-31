#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GB28181 CLI启动脚本
从项目外部运行命令行版本时使用此脚本
"""

import sys
import os

# 添加当前目录到路径
current_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, current_dir)

# 运行CLI版本
if __name__ == "__main__":
    from gb28181_device_simulator import main
    main()


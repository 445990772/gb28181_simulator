#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""工具函数模块"""

import sys
import os


def clear_console():
    """清屏（跨平台）"""
    if sys.platform.startswith('win'):
        os.system('cls')
    else:
        os.system('clear')


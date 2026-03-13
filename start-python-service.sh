#!/bin/bash

echo "启动 SoulX-FlashHead Python 服务..."
echo "服务将在 http://localhost:5000 启动"
echo "使用 conda 环境: flashhead"

cd /home/yukun/SoulX-FlashHead
source /home/yukun/miniconda3/etc/profile.d/conda.sh
conda activate flashhead
python app.py

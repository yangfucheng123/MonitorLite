#!/bin/bash
# ============================================================
# MonitorLite 通用版一键构建脚本
# 1. 编译 React 前端
# 2. 复制到 Spring Boot static 目录
# 3. 构建最终 JAR
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_DIR="$SCRIPT_DIR/frontend"
STATIC_DIR="$SCRIPT_DIR/src/main/resources/static"

echo "========================================"
echo " MonitorLite 通用版构建"
echo "========================================"

# Step 1: Build frontend
echo ""
echo "[1/3] 构建 React 前端..."
cd "$FRONTEND_DIR"
if [ ! -d "node_modules" ]; then
  echo "  安装依赖..."
  npm install --legacy-peer-deps
fi
npx vite build
echo "  前端构建完成"

# Step 2: Copy to static
echo ""
echo "[2/3] 复制静态文件到 Spring Boot..."
rm -rf "$STATIC_DIR"/*
cp -r "$FRONTEND_DIR/dist/"* "$STATIC_DIR/"
echo "  静态文件已复制: $STATIC_DIR"

# Step 3: Build JAR
echo ""
echo "[3/3] 构建 Spring Boot JAR..."
cd "$SCRIPT_DIR"
if [ -f "mvnw" ]; then
  ./mvnw clean package -DskipTests
elif command -v mvn &> /dev/null; then
  mvn clean package -DskipTests
else
  echo "  警告: 未找到 Maven, 跳过 JAR 构建"
  echo "  请手动执行: mvn clean package -DskipTests"
fi

echo ""
echo "========================================"
echo " 构建完成!"
echo " JAR: target/monitor-lite-generic-1.0.0.jar"
echo " 启动: java -jar target/monitor-lite-generic-1.0.0.jar"
echo " 访问: http://localhost:8090"
echo "========================================"

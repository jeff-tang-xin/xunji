#!/bin/bash
# xuanji-server 启动脚本（v2：判活唯一真相 = 端口监听）
# 用法: ./start.sh {start|stop|restart|status}
#
# v1 的 pgrep -f "$APP_NAME" 判活有两个实锤缺陷：
#   1) ssh 命令串含 jar 名时，sshd 的 bash -c cmdline 含 jar 名 → pgrep 匹配到 bash 自身 → 误判 already running（PID 每次变）
#   2) kill 后 JVM 优雅关闭期间（端口已释放、进程未退出）pgrep 仍命中 → 误判 already running（PID 恒定）
# pid 文件判活也有 PID 复用失真风险。故 v2 一律以 ss 拿到的监听 PID 为准。

APP_NAME="xuanji-server-0.1.0-SNAPSHOT.jar"
LOG_FILE="xuanji.log"
PID_FILE="xuanji.pid"
PORT=8321
JAVA_OPTS="-Xms256m -Xmx512m"

cd "$(dirname "$0")"

# 唯一可信判据：端口监听 PID（与部署验证四件套同款）
get_pid() {
    ss -tlnp 2>/dev/null | grep ":$PORT " | grep -oP 'pid=\K[0-9]+' | head -1
}

start() {
    local pid
    pid=$(get_pid)
    if [ -n "$pid" ]; then
        echo "[INFO] $APP_NAME is already running (PID: $pid, port $PORT)"
        return 0
    fi
    rm -f "$PID_FILE"
    echo "[INFO] Starting $APP_NAME ..."
    # >> 追加写：重启不丢上次日志（v1 的 > 覆盖会吞掉崩溃现场）
    nohup java $JAVA_OPTS -jar "$APP_NAME" >> "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    # 轮询端口最多 40s：Spring Boot 启动 20-25s，端口起来才算真成功
    for i in $(seq 1 40); do
        pid=$(get_pid)
        if [ -n "$pid" ]; then
            echo "[OK] $APP_NAME started successfully (PID: $pid, port $PORT)"
            return 0
        fi
        sleep 1
    done
    echo "[ERROR] Failed to start $APP_NAME within 40s, check $LOG_FILE"
    return 1
}

stop() {
    local pid
    pid=$(get_pid)
    if [ -z "$pid" ]; then
        echo "[INFO] $APP_NAME is not running"
        rm -f "$PID_FILE"
        return 0
    fi
    echo "[INFO] Stopping $APP_NAME (PID: $pid) ..."
    kill "$pid"
    # 等端口释放（最多 30s）：端口释放 ≠ 进程退出，但端口释放才允许下一步
    for i in $(seq 1 30); do
        if [ -z "$(get_pid)" ]; then
            break
        fi
        sleep 1
    done
    if [ -n "$(get_pid)" ]; then
        echo "[WARN] Graceful shutdown timeout, force killing ..."
        kill -9 "$pid" 2>/dev/null
        sleep 2
    fi
    rm -f "$PID_FILE"
    echo "[OK] $APP_NAME stopped"
}

restart() {
    stop
    sleep 1
    start
}

status() {
    local pid
    pid=$(get_pid)
    if [ -n "$pid" ]; then
        echo "[INFO] $APP_NAME is running (PID: $pid, port $PORT)"
    else
        echo "[INFO] $APP_NAME is not running"
    fi
}

case "$1" in
    start)   start   ;;
    stop)    stop    ;;
    restart) restart ;;
    status)  status  ;;
    *)
        echo "Usage: $0 {start|stop|restart|status}"
        exit 1
        ;;
esac

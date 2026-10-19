#!/system/bin/sh
set -eu

PKG="${1:-com.netease.wyclx}"
MODEL="${2:-Shenhe}"
INPUT_DB="${3:-0}"
OUTPUT_DB="${4:-0}"
THRESHOLD_DB="${5:--60}"
TOKEN="virtual-mic-$(date +%s)"
LOG=/data/local/tmp/virtual_mic.log
rm -f "$LOG"

APK=$(pm path com.voicestudio.phone | sed 's/^package://')
nohup /system/bin/sh -c "tail -f /dev/null | CLASSPATH='$APK' exec /system/bin/app_process /system/bin --nice-name=virtual_mic dev.virtualmic.Main --package '$PKG' --mic --seconds 0 --bridge '$TOKEN' --watch-parent" > "$LOG" 2>&1 &
PID=$!

PORT=""
i=0
while [ "$i" -lt 100 ]; do
  PORT=$(sed -n 's/.*BRIDGE_LISTENING port=\([0-9][0-9]*\).*/\1/p' "$LOG" | tail -n 1)
  [ -n "$PORT" ] && break
  kill -0 "$PID" 2>/dev/null || break
  sleep 0.2
  i=$((i + 1))
done

if [ -z "$PORT" ]; then
  echo "FAILED token=$TOKEN pid=$PID"
  cat "$LOG" 2>/dev/null || true
  exit 1
fi

echo "BRIDGE_READY token=$TOKEN port=$PORT pid=$PID"
am force-stop com.voicestudio.phone
am start -n com.voicestudio.phone/.MainActivity \
  --es virtual_mic_socket "$TOKEN" \
  --ei virtual_mic_port "$PORT" \
  --es model "$MODEL" \
  --ef input_gain_db "$INPUT_DB" \
  --ef gain_db "$OUTPUT_DB" \
  --ef threshold_db "$THRESHOLD_DB" \
  --ez bounded_logs true \
  --ez persistent_mic true

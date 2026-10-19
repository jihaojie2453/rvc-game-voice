#!/system/bin/sh

APP_UID="${1:-10311}"
DIR="/sys/fs/cgroup/apps/uid_${APP_UID}"
STOP=/data/local/tmp/virtual_mic_unfreeze.stop
LOG=/data/local/tmp/virtual_mic_unfreeze.log

rm -f "$STOP"
: > "$LOG"

while [ ! -e "$STOP" ]; do
  for f in "$DIR/cgroup.freeze" "$DIR"/pid_*/cgroup.freeze; do
    [ -r "$f" ] || continue
    [ "$(cat "$f" 2>/dev/null)" = "1" ] || continue
    if echo 0 > "$f" 2>/dev/null; then
      echo "$(date +%H:%M:%S) thaw $f" >> "$LOG"
    fi
  done
  sleep 0.2
done

rm -f "$STOP"

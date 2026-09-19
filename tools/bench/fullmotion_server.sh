#!/usr/bin/env bash
# A local VNC server, so full-motion performance can be measured **reproducibly**.
#
# 🔑 Why it exists: the old baseline figures were taken against somebody's desktop with a video
#    playing, which cannot be reproduced. Here, mpv loops the same ffmpeg-generated clip forever.
# ⚠️ It uses port 5900 because that is the port already open in the firewall - this script does not
#    touch firewall rules. It therefore takes down an existing :2 test server for the duration and
#    brings it back on stop.
# 🔑 Authentication is VncAuth. 🚫 Never open an unauthenticated server on a LAN.
#    `start` generates a **one-time** password into ~/.vnc/bench.passwd and writes the same value
#    into vnc.dev.* in local.properties (untracked) so the app picks it up.
#    🚫 The plaintext is never printed and never written into the repository.
set -euo pipefail

# 🔴 With `VNC_WM` empty, `~/.config/tigervnc/xstartup` falls through to `startxfce4` at the end.
#    On a host with neither xfce nor fbsetroot the session dies with status 127 and vncserver
#    returns 255 - but start() sends output to /dev/null, so **nothing appears and nothing is said.**
#    That happened for real on 2026-09-16. openbox is available, so it is made the default.
export VNC_WM="${VNC_WM:-OPENBOX}"

DISP=":9"
PORT=5900
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCALPROPS="$HERE/../../local.properties"
PASSFILE="$HOME/.vnc/bench.passwd"
# 🔑 VNCBENCH_CLIP points at a different clip - for example one built by make_stress_clip.sh.
#    A clip given explicitly is never generated: if it is missing, this fails immediately.
CLIP="${VNCBENCH_CLIP:-/tmp/vncbench_testsrc.mp4}"
PIDFILE="/tmp/vncbench_mpv.pid"
# 🔑 Remembers whether a :2 test server was running before start() took it down, so stop()
#    can put the host back the way it found it rather than the way it assumed it was.
HAD2FILE="/tmp/vncbench_had_disp2"

make_clip() {
  [ -f "$CLIP" ] && return
  [ -n "${VNCBENCH_CLIP:-}" ] && { echo "[bench] the specified clip does not exist: $CLIP" >&2; exit 1; }
  echo "[bench] generating the test clip: $CLIP"
  ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=1920x1080:rate=30 \
         -t 10 -pix_fmt yuv420p -c:v libx264 -preset veryfast "$CLIP"
}

set_dev_props() {   # $1 = host, $2 = port, $3 = password
  python3 - "$LOCALPROPS" "$1" "$2" "$3" <<'PY'
import sys, io, os
path, host, port, pw = sys.argv[1:5]
lines = []
if os.path.exists(path):
    lines = [l for l in io.open(path, encoding='utf-8').read().splitlines()
             if not l.startswith(('vnc.dev.host', 'vnc.dev.port', 'vnc.dev.password'))]
lines += ['vnc.dev.host=' + host, 'vnc.dev.port=' + port, 'vnc.dev.password=' + pw]
io.open(path, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
PY
  chmod 600 "$LOCALPROPS"
}

start() {
  make_clip
  mkdir -p "$HOME/.vnc"
  # 🔑 VNC authentication uses at most 8 bytes of the password.
  # 🔑 A tr|head pipeline trips `set -o pipefail`: head closes the pipe and tr dies with SIGPIPE (141).
  local pw; pw="$(python3 -c 'import secrets,string; print("".join(secrets.choice(string.ascii_letters+string.digits) for _ in range(8)))')"
  printf '%s\n' "$pw" | vncpasswd -f > "$PASSFILE" 2>/dev/null
  chmod 600 "$PASSFILE"

  # 🔴 Record whether a :2 test server was **actually** running before taking it down.
  #    `stop` used to start one unconditionally, so a host that had none got one anyway - a VNC
  #    server left listening on the LAN that nobody asked for. Same shape as the bug fixed in the
  #    probes on 2026-09-19: a cleanup that *assumes* the original state instead of recording it.
  # ⚠ `vncserver -list` prints the display **without** the colon and space-padded
  #    ("2         <TAB>5900<TAB>..."), so a `^:2` pattern silently never matches - and a detector
  #    that never fires looks exactly like "there was nothing to restore".
  if vncserver -list 2>/dev/null | grep -qE '^:?2[[:space:]]'; then
    printf 'yes\n' > "$HAD2FILE"
  else
    rm -f "$HAD2FILE"
  fi
  vncserver -kill :2 >/dev/null 2>&1 || true
  vncserver -kill "$DISP" >/dev/null 2>&1 || true
  vncserver "$DISP" -rfbport "$PORT" -geometry 1920x1080 -depth 24 \
            -localhost no -AlwaysShared -SecurityTypes VncAuth \
            -PasswordFile "$PASSFILE" >/dev/null 2>&1
  sleep 2
  DISPLAY="$DISP" mpv --really-quiet --no-audio --loop-file=inf --fs \
          --vo=x11 --profile=sw-fast "$CLIP" >/dev/null 2>&1 &
  echo $! > "$PIDFILE"
  sleep 2

  local ip; ip="$(hostname -I | awk '{print $1}')"
  set_dev_props "$ip" "$PORT" "$pw"
  echo "[bench] ready - ${ip}:${PORT}, full-motion 30fps, local.properties updated"
  echo "[bench] 🔴 rebuild the app - the new target only takes effect at build time."
}

stop() {
  [ -f "$PIDFILE" ] && { kill "$(cat "$PIDFILE")" 2>/dev/null || true; rm -f "$PIDFILE"; }
  vncserver -kill "$DISP" >/dev/null 2>&1 || true
  rm -f "$PASSFILE"
  set_dev_props "" "" ""
  # 🔑 Bring :2 back **only if it was there to begin with** - see the note in start().
  if [ -f "$HAD2FILE" ]; then
    vncserver :2 -rfbport 5900 -geometry 1920x1080 -depth 24 \
              -localhost no -AlwaysShared >/dev/null 2>&1 || true
    rm -f "$HAD2FILE"
    echo "[bench] cleaned up - one-time password deleted, local.properties restored, :2 back"
  else
    echo "[bench] cleaned up - one-time password deleted, local.properties restored."
    echo "[bench]   (there was no :2 server before this ran, so none was started)"
  fi
}

case "${1:-}" in
  start) start ;;
  stop)  stop ;;
  *) echo "usage: $0 {start|stop}" >&2; exit 2 ;;
esac

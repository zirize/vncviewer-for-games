#!/usr/bin/env bash
# Builds stress clips for VNC: full-screen updates that JPEG cannot compress away.
#
# 🔑 Why testsrc2 is not enough: the clip fullmotion_server.sh uses moves only part of the screen
#    and has large flat areas, which JPEG handles well. These are the opposite:
#    **every pixel changes every frame, and there is no high-frequency detail for the DCT to discard.**
#
# 🔑 Five stages, because each one attacks a different place and that is how you find where it
#    breaks:
#    flash  : almost no bytes, but the whole screen is dirty every frame -> measures dirty-region
#             detection and per-frame round-trip cost alone
#    plasma : saturated colour churning across the whole screen -> chroma subsampling stops helping
#    mosaic : colour blocks refreshed every frame -> closest to real content (confetti, particles)
#    stripes: a few-pixel primary-colour grid inverting every frame -> worst case for JPEG's 8x8
#             DCT; the ringing is visible
#    noise  : per-pixel random -> **incompressible**. Measures the hard ceiling of bandwidth and decoder
#
# ⚠️ These files are **large** - being incompressible is the point (20s of 1080p30 is about 300MB).
#    They are therefore never committed; the default output is /tmp.
# 🔑 Lowering the source quality (raising crf) removes high-frequency detail and weakens **the test
#    itself**. If size is a problem, reduce --secs before touching crf: the intensity stays.
set -euo pipefail

W=1920; H=1080; FPS=30; SECS=4; CRF=20
STRIPE=6          # stripes grid width in px. 3 is harsher; 12 makes the colours easier to see
MOSAIC=12         # mosaic block size in px
NOISEBLK=2        # noise block size in px. 1 gives pure per-pixel random (and enormous files)
LABEL=1
OUT=/tmp/vncbench_stress.mp4
STAGES="flash plasma mosaic stripes noise"
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf

usage() {
  cat <<'EOF'
usage: make_stress_clip.sh [options]

  --stage <name[,name...]> which stages to build (default: flash,plasma,mosaic,stripes,noise)
  --size <WxH>             resolution (default 1920x1080, matching the server geometry)
  --fps <n>                frame rate (default 30; 60 doubles the load)
  --secs <n>               seconds per stage, integer (default 4)
  --crf <n>                x264 quality (default 20; raising it also weakens the test)
  --stripe <px>            stripes grid width (default 6)
  --mosaic <px>            mosaic block size (default 12)
  --noise-block <px>       noise block size (default 2; 1 = pure per-pixel random)
  --no-label               omit the stage label and frame counter in the top-left corner
  --out <path>             output file (default /tmp/vncbench_stress.mp4)

examples:
  make_stress_clip.sh                        # all five stages, 20s combined
  make_stress_clip.sh --stage noise --secs 10 --noise-block 1   # just the worst case, longer
  make_stress_clip.sh --fps 60 --secs 3      # a 60fps version
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --stage) STAGES="$(echo "$2" | tr ',' ' ')"; shift 2 ;;
    --size)  W="${2%x*}"; H="${2#*x}"; shift 2 ;;
    --fps)   FPS="$2"; shift 2 ;;
    --secs)  SECS="$2"; shift 2 ;;
    --crf)   CRF="$2"; shift 2 ;;
    --stripe) STRIPE="$2"; shift 2 ;;
    --mosaic) MOSAIC="$2"; shift 2 ;;
    --noise-block) NOISEBLK="$2"; shift 2 ;;
    --no-label) LABEL=0; shift ;;
    --out)   OUT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

command -v ffmpeg >/dev/null || { echo "no ffmpeg" >&2; exit 1; }
case "$SECS" in *[!0-9]*|'') echo "--secs must be an integer (the raw stage needs an exact byte count)" >&2; exit 2 ;; esac
[ -f "$FONT" ] || { echo "[i] no font at $FONT - labels disabled" >&2; LABEL=0; }

TMPDIR_="$(mktemp -d /tmp/vncstress.XXXXXX)"
trap 'rm -rf "$TMPDIR_"' EXIT

# 🔑 The frame counter in the label deliberately changes every frame, so that on the viewer you
#    can tell "the picture has stopped" from "the picture is slow" by eye.
mk_label() {   # $1 = stage n/total  $2 = stage name  $3 = one-line description
  [ "$LABEL" = 1 ] || { echo ""; return; }
  local fs=$(( H / 27 ))
  printf ",drawtext=fontfile=%s:text='%s %s — %s':fontsize=%d:fontcolor=white:box=1:boxcolor=black@0.85:boxborderw=%d:x=%d:y=%d" \
    "$FONT" "$1" "$2" "$3" "$fs" $(( fs / 3 )) $(( W / 60 )) $(( H / 40 ))
  printf ",drawtext=fontfile=%s:text='stage frame %%{n}':fontsize=%d:fontcolor=white:box=1:boxcolor=black@0.85:boxborderw=%d:x=%d:y=%d" \
    "$FONT" "$fs" $(( fs / 3 )) $(( W / 60 )) $(( H / 40 + fs * 2 ))
}

enc() { ffmpeg -loglevel error -y "$@" -c:v libx264 -preset ultrafast -crf "$CRF" -pix_fmt yuv420p; }

# Renders one lavfi scene: $1 = source, $2 = filter, $3 = output
bake_lavfi() { enc -f lavfi -i "$1" -t "$SECS" -vf "$2,format=yuv420p" "$3"; }

# Renders random frames: $1 = source width, $2 = source height, $3 = trailing filter, $4 = output
#   🔑 /dev/urandom is fed in as rawvideo: faster than geq(random()), and genuinely uniform, so the
#      compression ratio comes out honestly at its worst.
bake_rand() {
  local sw="$1" sh="$2" tail="$3" out="$4"
  head -c $(( sw * sh * 3 * FPS * SECS )) /dev/urandom \
    | enc -f rawvideo -pixel_format rgb24 -video_size "${sw}x${sh}" -framerate "$FPS" -i - \
          -vf "scale=$W:$H:flags=neighbor$tail,format=yuv420p" "$out"
}

total=$(echo "$STAGES" | wc -w)
idx=0
LIST="$TMPDIR_/list.txt"; : > "$LIST"

for st in $STAGES; do
  idx=$(( idx + 1 ))
  part="$TMPDIR_/$(printf '%02d' "$idx")_$st.mp4"
  tag="$idx/$total"
  echo "[stress] ($tag) $st …"
  case "$st" in
    # A flat colour flipping every frame. Computed at 2x2 and scaled up with neighbour, so geq
    # only runs on four pixels.
    # sin(N * irrational) is a reproducible pseudo-random: the same arguments give the same clip
    # on any host.
    flash)
      bake_lavfi "color=c=black:s=2x2:r=$FPS" \
        "geq=r='128+127*sin(N*12.9898)':g='128+127*sin(N*78.233)':b='128+127*sin(N*37.719)',scale=$W:$H:flags=neighbor$(mk_label "$tag" FLASH 'full-screen solid color flip')" \
        "$part" ;;
    # Computed small and scaled up bilinearly: a smooth, saturated gradient flowing quickly.
    plasma)
      bake_lavfi "color=c=black:s=320x180:r=$FPS" \
        "geq=r='128+127*sin(2*PI*(X/60+Y/90+N/8))':g='128+127*sin(2*PI*(X/45-Y/70+N/6+0.33))':b='128+127*sin(2*PI*(X/80+Y/40-N/5+0.66))',scale=$W:$H:flags=bilinear$(mk_label "$tag" PLASMA 'saturated color field, fast drift')" \
        "$part" ;;
    mosaic)
      bake_rand $(( W / MOSAIC )) $(( H / MOSAIC )) "$(mk_label "$tag" MOSAIC "random ${MOSAIC}px color blocks")" "$part" ;;
    # X, Y and X+Y drive the three channels separately, so three overlaid grids invert on
    # different periods.
    stripes)
      bake_lavfi "color=c=black:s=${W}x${H}:r=$FPS" \
        "geq=r='if(mod(floor(X/$STRIPE)+N,2),255,0)':g='if(mod(floor(Y/$STRIPE)+N,2),255,0)':b='if(mod(floor((X+Y)/$STRIPE)+N,2),255,0)'$(mk_label "$tag" STRIPES "${STRIPE}px primary grid, inverts every frame")" \
        "$part" ;;
    noise)
      bake_rand $(( W / NOISEBLK )) $(( H / NOISEBLK )) "$(mk_label "$tag" NOISE "${NOISEBLK}px uniform RGB noise — incompressible")" "$part" ;;
    *) echo "unknown stage: $st" >&2; exit 2 ;;
  esac
  echo "file '$part'" >> "$LIST"
  printf '           %s\n' "$(du -h "$part" | cut -f1)"
done

if [ "$(wc -l < "$LIST")" = 1 ]; then
  cp "$(sed "s/^file '//; s/'$//" "$LIST")" "$OUT"
else
  # 🔑 Concatenated with -c copy: re-encoding would soften it again and weaken the test.
  ffmpeg -loglevel error -y -f concat -safe 0 -i "$LIST" -c copy "$OUT"
fi

dur="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT" 2>/dev/null | cut -d. -f1)"
echo "[stress] done - $OUT  (${dur}s, ${W}x${H}@${FPS}, $(du -h "$OUT" | cut -f1))"
echo "[stress] play:   mpv --loop-file=inf --no-audio --fs --profile=sw-fast $OUT"
echo "[stress] serve:  VNCBENCH_CLIP=$OUT $(dirname "$0")/fullmotion_server.sh start"

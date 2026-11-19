#!/bin/bash
# usage: render.sh <composition> <en|fr> <name>   -> out/final/<name>.mp4 (video untouched, sound mastered to -14 LUFS, -1 dBTP)
set -e
comp="$1"; lang="$2"; name="$3"
mkdir -p out/final out/raw
npx remotion render src/index.ts "$comp" "out/raw/$name.mp4" --codec h264 --crf 16 --concurrency 3 --props="{\"lang\":\"$lang\"}" > "out/raw/$name.log" 2>&1
ffmpeg -v error -y -i "out/raw/$name.mp4" -c:v copy -af "loudnorm=I=-14:TP=-1:LRA=11" -ar 48000 -c:a aac -b:a 256k -movflags +faststart "out/final/$name.mp4"
ls -la "out/final/$name.mp4"

#!/bin/bash
# The walk-in worlds' models (v0.2): each Seed3D GLB shrunk for the headset at its real size (scene_models.tsv),
# given a glow of its own colours (full immersion, one light), and measured into assets/scenes/sizes.txt.
# Skips models already prepared (FORCE=1 redoes them all). Run from the repository root.
cd /home/workspace/orca/projects/VRGames_Claude
BL=("$HOME/.local/blender-dl/blender-4.2.3-linux-x64/blender")
if [[ -x "$HOME/.local/bin/vps-heavy-run" ]]; then
  BL=("$HOME/.local/bin/vps-heavy-run" render -- "${BL[@]}")
fi
SRC=assets_lab/huazhong/seed3d
WORK=assets_lab/huazhong/prep
OUT=Pico_Vibe_Lab/HuaZhongYou/app/app/src/main/assets/scenes
mkdir -p $WORK $OUT
touch $OUT/sizes.txt
grep -v '^#' Pico_Vibe_Lab/HuaZhongYou/tools/scene_models.tsv | while IFS=$'\t' read -r n size axis faces tex yaw glow; do
  [ -z "$n" ] && continue
  [ -f "$SRC/$n.glb" ] || continue
  [ -z "$FORCE" ] && [ -f "$OUT/$n.glb" ] && continue
  [ -n "$ONLY" ] && ! echo " $ONLY " | grep -q " $n " && continue
  "${BL[@]}" -b --factory-startup --python tools/blender/prep_ai.py -- "$SRC/$n.glb" "$WORK/$n.glb" "$size" "$faces" "$tex" "$axis" "$yaw" > "$WORK/$n.log" 2>&1 || { echo "prep $n FAILED"; continue; }
  "${BL[@]}" -b --factory-startup --python tools/blender/glow.py -- "$WORK/$n.glb" "$OUT/$n.glb" "$glow" >> "$WORK/$n.log" 2>&1 || { echo "glow $n FAILED"; continue; }
  d=$("${BL[@]}" -b --factory-startup --python tools/blender/dims.py -- "$OUT/$n.glb" "$n" 2>/dev/null | grep '^DIMS' | cut -d' ' -f2-)
  grep -v "^$n " $OUT/sizes.txt > $OUT/sizes.tmp; echo "$d" >> $OUT/sizes.tmp; mv $OUT/sizes.tmp $OUT/sizes.txt
  echo "prep $n $d $(du -k $OUT/$n.glb | cut -f1) KB"
  [ -n "$REVIEW" ] && "${BL[@]}" -b --factory-startup --python tools/blender/review_views.py -- "$OUT/$n.glb" "$REVIEW/$n.png" 420 2>/dev/null | grep "^review"
done
echo PREP_DONE

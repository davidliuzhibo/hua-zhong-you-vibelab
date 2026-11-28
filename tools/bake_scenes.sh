#!/bin/bash
# v0.4.0 (PICO 2026-11-23: 「模型都比较粗糙……用高模烘焙低模」): every scene model baked from its Seed3D raw (~1M triangles)
# into a near version (assets scenes/<n>.glb), a far one (<n>_lo.glb: far off at life size, and the miniature) and, for
# the walkers, a walk cycle (<n>_walk.glb, tools/blender/walk.py on a baked walk base). Targets in scene_models.tsv.
# Then sizes.txt and tris.txt, and a review sheet per model (0.3.3 / near / far / walk frame) in $REVIEW.
# Run from anywhere. ONLY="a b" for some models; SKIP_DONE=1 skips models whose near file is newer than the script.
cd /home/workspace/orca/projects/VRGames_Claude
BL=("$HOME/.local/blender-dl/blender-4.2.3-linux-x64/blender")
[[ -x "$HOME/.local/bin/vps-heavy-run" && -z "$NO_GUARD" ]] && BL=("$HOME/.local/bin/vps-heavy-run" render -- "${BL[@]}")
H=Pico_Vibe_Lab/HuaZhongYou
SRC=assets_lab/huazhong/seed3d
OLD=${OLD:-assets_lab/huazhong/scenes_v033_backup}
WORK=${WORK:-assets_lab/huazhong/bake040}
OUT=${OUT:-$H/app/app/src/main/assets/scenes}
REVIEW=${REVIEW:-assets_lab/huazhong/review040}
mkdir -p $WORK $REVIEW
grep -v '^#' ${TSV:-$H/tools/scene_models.tsv} | grep -v '# procedural' | while IFS=$'\t' read -r n size axis yaw glow near far walk npx fpx tone; do
  [ -z "$n" ] && continue
  [ -n "$ONLY" ] && ! echo " $ONLY " | grep -q " $n " && continue
  # SKIP_WALK=1: near and far only, the walk cycle kept as it is.
  [ -n "$SKIP_WALK" ] && walk=0
  [ -n "$SKIP_DONE" ] && [ -f "$WORK/$n.glb" ] && [ "$WORK/$n.glb" -nt "$H/tools/scene_models.tsv" ] && { echo "skip $n"; continue; }
  # v0.4.1: the regenerated people's robes have slits and layered hems: rays start a little further out.
  case "$n" in p_*) export BAKE_CAGE=0.4;; *) unset BAKE_CAGE;; esac
  # 汴河's two-storey shop: its lattice screens fold into black spikes when decimated much below its near count (from the
  # raw or from the near mesh, 0.4.0 and 0.4.1 alike): its far version is the near mesh with 1024 maps.
  case "$n" in qm_shop2) export BAKE_FAR_FROM_NEAR=1;; *) unset BAKE_FAR_FROM_NEAR;; esac
  # v0.4.1: the models regenerated from new references (the best Seed3D candidate of each) replace the 0.4.0 raws.
  raw="$SRC/$n.glb"; [ -f "assets_lab/huazhong/seed3d041/final/$n.glb" ] && raw="assets_lab/huazhong/seed3d041/final/$n.glb"
  # v0.5.2 (near-field HD): regenerated from new high-detail references (Seedream 5.5 → Seed3D high).
  [ -f "assets_lab/huazhong/hd3d/seed3d052/$n.glb" ] && raw="assets_lab/huazhong/hd3d/seed3d052/$n.glb"
  "${BL[@]}" -b --factory-startup --python tools/blender/bake_scene.py -- "$raw" "$WORK" "$n" "$size" "$axis" "$yaw" "$glow" "$near" "$far" "$npx" "$fpx" "$walk" "$npx" "${tone:--}" > "$WORK/$n.log" 2>&1 \
    || { echo "bake $n FAILED"; tail -5 "$WORK/$n.log"; continue; }
  grep '^BAKE ' "$WORK/$n.log"
  cp "$WORK/$n.glb" "$WORK/${n}_lo.glb" $OUT/
  # v0.4.1: parts built in Blender (tools/parts.tsv: the peach tree's blossoms, the fishing boat's net).
  grep -v '^#' $H/tools/parts.tsv | while IFS=$'\t' read -r pn pv ps pa; do
    [ "$pn" = "$n" ] && { [ "$pv" = near ] || [ "$pv" = far ]; } || continue
    sfx=""; [ "$pv" = far ] && sfx="_lo"
    "${BL[@]}" -b --factory-startup --python tools/blender/$ps -- "$WORK/$n$sfx.glb" "$OUT/$n$sfx.glb" $pa > "$WORK/$n${sfx}_part.log" 2>&1 || echo "part $n$sfx FAILED"
    grep -hE '^(BLOSSOMS|NET|CLEAN_TINT)' "$WORK/$n${sfx}_part.log"
  done
  rows=("$OLD/$n.glb" "$OUT/$n.glb" "$OUT/${n}_lo.glb")
  if [ "$walk" != "0" ]; then
    cfg=$(python3 -c "import json,sys; d=json.load(open('$H/tools/walk_models.json')); c=d['$n']; c['faces']=int($walk*1.2); print(json.dumps(c))")
    # v0.4.1: parts for the walk base (tools/parts.tsv, version "wbase": the woman's underskirt and still skirt).
    wb="$WORK/${n}_wbase.glb"
    while IFS=$'\t' read -r pn pv ps pa; do
      [ "$pn" = "$n" ] && [ "$pv" = wbase ] || continue
      "${BL[@]}" -b --factory-startup --python tools/blender/$ps -- "$wb" "$WORK/${n}_wbase_part.glb" $pa > "$WORK/${n}_wbase_part.log" 2>&1 && wb="$WORK/${n}_wbase_part.glb" || echo "part $n wbase FAILED"
      grep -h '^UNDERSKIRT' "$WORK/${n}_wbase_part.log"
    done < <(grep -v '^#' $H/tools/parts.tsv)
    "${BL[@]}" -b --factory-startup --python tools/blender/walk.py -- "$wb" "$WORK/${n}_walk.glb" "$cfg" "$REVIEW/${n}_walk.png" > "$WORK/${n}_walk.log" 2>&1 \
      || { echo "walk $n FAILED"; tail -5 "$WORK/${n}_walk.log"; }
    grep -E '^WALK_DONE|^STRIDE' "$WORK/${n}_walk.log"
    [ -f "$WORK/${n}_walk.glb" ] && cp "$WORK/${n}_walk.glb" $OUT/ && rows+=("$OUT/${n}_walk.glb")
    # v0.4.1: parts for the finished walk cycle (tools/parts.tsv, version "walk": the scholar's robe stain painted out).
    while IFS=$'\t' read -r pn pv ps pa; do
      [ "$pn" = "$n" ] && [ "$pv" = walk ] || continue
      "${BL[@]}" -b --factory-startup --python tools/blender/$ps -- "$WORK/${n}_walk.glb" "$OUT/${n}_walk.glb" $pa > "$WORK/${n}_walk_part.log" 2>&1 || echo "part $n walk FAILED"
      grep -h '^CLEAN_TINT' "$WORK/${n}_walk_part.log"
    done < <(grep -v '^#' $H/tools/parts.tsv)
  fi
  d=$("${BL[@]}" -b --factory-startup --python tools/blender/dims.py -- "$OUT/$n.glb" "$n" 2>/dev/null | grep '^DIMS' | cut -d' ' -f2-)
  grep -v "^$n " $OUT/sizes.txt > $OUT/sizes.tmp; echo "$d" >> $OUT/sizes.tmp; mv $OUT/sizes.tmp $OUT/sizes.txt
  echo "size $d"
  "${BL[@]}" -b --factory-startup --python tools/blender/review_bake.py -- "$REVIEW/$n.png" 300 "${rows[@]}" > "$WORK/${n}_review.log" 2>&1 || echo "review $n FAILED"
done
[ -z "$NO_TRIS" ] && python3 $H/tools/model_tris.py
echo BAKE_ALL_DONE

#!/bin/bash
# v0.5.0: the light-baked models written again from their kept bakes (assets_lab/huazhong/lit/<n>.glb.dn.exr), with the
# fixed exposure of their kind (as bake_light_all.sh now bakes them): quick, no baking.
cd /home/workspace/orca/projects/VRGames_Claude
B=~/.local/blender-dl/blender-4.2.3-linux-x64/blender
SRC=assets_lab/huazhong/scenes_v042_backup
OUT=assets_lab/huazhong/lit
for g in $OUT/*.glb; do
  b=$(basename $g .glb); base=${b%_lo}; base=${base%_walk}
  [ -n "$ONLY" ] && ! echo " $ONLY " | grep -q " $base " && continue
  [ -f $g.dn.exr ] || { echo "no bake kept for $b"; continue; }
  case "$base" in
    qm_tavern|qm_shop|qm_shop2|qm_cailou|qm_gate|qm_bridge|qm_warehouse|qm_farm|qm_house|qm_stall|qm_teashed|ql_pavilion|ql_longbridge|ql_cottage|ql_mill) m=building; ex=0.21;;
    t_*|ql_pine|ql_mount1|ql_mount2|ql_hill) m=terrain; ex=0.21;;
    *) m=figure; ex=0.26;;
  esac
  px=2048; case "$b" in *_lo) px=1024;; esac
  BL_REUSE=1 BL_EXPO=$ex BL_GAIN=${BL_GAIN:-1.35} BL_SAT=${BL_SAT:-1.12} timeout 600 nice $B -b --factory-startup -P tools/blender/bake_light.py -- $SRC/$b.glb $g $px 64 $m 2>&1 | grep -E "^LIT [a-z_]+\.glb|Error|Assert" | sed "s/^/$(date +%T) /"
done
echo "REEXPORT DONE"

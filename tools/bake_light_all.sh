#!/bin/bash
# v0.5.0 (PICO 1127: 「进入 full space 后，模型精度和视觉效果还有较大提升空间」): the light baked into every scene model's
# colour map (tools/blender/bake_light.py; unlit in the game, no tone mapping): the near and far versions and the walk
# cycles, from the 0.4.2 models (assets_lab/huazhong/scenes_v042_backup). Out: assets_lab/huazhong/lit/, then copied
# into assets scenes/ by hand after the review. ONLY="a b" for some; SKIP_DONE=1 skips outputs already there.
cd /home/workspace/orca/projects/VRGames_Claude
B=~/.local/blender-dl/blender-4.2.3-linux-x64/blender
SRC=assets_lab/huazhong/scenes_v042_backup
OUT=assets_lab/huazhong/lit
mkdir -p $OUT
mode_of() {
  case "$1" in
    qm_tavern|qm_shop|qm_shop2|qm_cailou|qm_gate|qm_bridge|qm_warehouse|qm_farm|qm_house|qm_stall|qm_teashed|ql_pavilion|ql_longbridge|ql_cottage|ql_mill) echo building;;
    t_*|ql_pine|ql_mount1|ql_mount2|ql_hill) echo terrain;;
    *) echo figure;;
  esac
}
for f in $SRC/*.glb; do
  b=$(basename $f .glb)
  base=${b%_lo}; base=${base%_walk}
  case "$base" in lantern_*) continue;; esac
  [ -n "$ONLY" ] && ! echo " $ONLY " | grep -q " $base " && continue
  [ -n "$SKIP_DONE" ] && [ -f $OUT/$b.glb ] && [ $OUT/$b.glb -nt $SRC/$b.glb ] && continue
  px=2048; case "$b" in *_lo) px=1024;; esac
  m=$(mode_of $base)
  # One exposure per kind of light (measured per model it strayed: the child came out twice as bright as the porter).
  case $m in building) ex=0.21;; figure) ex=0.26;; *) ex=0.21;; esac
  t0=$(date +%s)
  BL_EXPO=${BL_EXPO:-$ex} BL_GAIN=${BL_GAIN:-1.35} BL_SAT=${BL_SAT:-1.12} timeout 1800 nice $B -b --factory-startup -P tools/blender/bake_light.py -- $f $OUT/$b.glb $px 64 $m > $OUT/$b.log 2>&1 || echo "FAILED $b"
  echo "$(date +%T) $b ($m, $px): $(grep -h '^LIT [a-z]' $OUT/$b.log | grep -v reusing | tail -1) $(( $(date +%s) - t0 )) s"
  :
done
echo "ALL DONE"

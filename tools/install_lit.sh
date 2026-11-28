#!/bin/bash
# v0.5.0: the light-baked buildings, bridges, hills and trees (assets_lab/huazhong/lit) into the game's scenes/. People,
# animals, carts, boats and trees keep their 0.4.2 lit models: baked, they came out worse (dark faces under hair and
# hats, white robes grey; a boat no better; the willow's leaves dark olive), and only better ones replace (user 2026-11-27).
cd /home/workspace/orca/projects/VRGames_Claude
OUT=assets_lab/huazhong/lit
DST=Pico_Vibe_Lab/HuaZhongYou/app/app/src/main/assets/scenes
n=0
for base in qm_tavern qm_shop qm_shop2 qm_cailou qm_gate qm_bridge qm_warehouse qm_farm qm_house qm_stall qm_teashed \
            ql_pavilion ql_longbridge ql_cottage ql_mill ql_mount1 ql_mount2 ql_hill; do
  for s in "" _lo; do
    f=$OUT/$base$s.glb
    [ -f $f ] || { echo "missing $base$s"; continue; }
    cp $f $DST/$base$s.glb; n=$((n+1))
  done
done
echo "installed $n light-baked models"

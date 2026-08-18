#!/bin/bash
# Offline javac build for gridbridge (skips the broken NeoGradle neoForm pipeline)
set -e
PROJ=/home/chen/gridbridge-mdk/MDK-archive-1.21
LIB=/mnt/g/Minecraft/.minecraft/libraries
MODS="/mnt/g/Minecraft/.minecraft/versions/你好，新蒸程！V1.7 正式版/mods"
OUT=$PROJ/build/offline
rm -rf $OUT
mkdir -p $OUT/classes/META-INF $OUT/jarjar $OUT/extract

CREATE="$MODS/[机械动力] create-1.21.1-6.0.10.jar"
cd $OUT/extract
unzip -o -q "$CREATE" 'META-INF/jarjar/*'
for j in META-INF/jarjar/*.jar; do
  unzip -o -q "$j" -d $OUT/jarjar
done
cd $PROJ
echo "jarjar dirs: $(ls $OUT/jarjar | tr '\n' ' ')"

MC="$LIB/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar"
NF="$LIB/net/neoforged/neoforge/21.1.236/neoforge-21.1.236-universal.jar"
MIXIN="$LIB/net/fabricmc/sponge-mixin/0.15.2+mixin.0.8.7/sponge-mixin-0.15.2+mixin.0.8.7.jar"
BUS="$LIB/net/neoforged/bus/8.0.5/bus-8.0.5.jar"
FASTUTIL="$LIB/it/unimi/dsi/fastutil/8.5.12/fastutil-8.5.12.jar"
SLF4J="$LIB/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"
BRIGADIER="$LIB/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar"
FML="$LIB/net/neoforged/fancymodloader/loader/4.0.43/loader-4.0.43.jar"
DFU="$LIB/com/mojang/datafixerupper/8.0.16/datafixerupper-8.0.16.jar"
CEE="$MODS/electroenergetics-1.21.1-1.1.1.jar"
PG="$MODS/[机械动力：交错电网] powergrid-mc1.21.1-0.6.0.1.jar"
SABLE="$MODS/sable-neoforge-1.21.1-2.0.3.jar"
ARCH="$MODS/architectury-13.0.11-neoforge.jar"

CP="$MC:$NF:$MIXIN:$BUS:$FASTUTIL:$SLF4J:$BRIGADIER:$FML:$DFU:$CEE:$PG:$CREATE:$SABLE:$ARCH:$OUT/jarjar"

find $PROJ/src/main/java -name '*.java' > $OUT/sources.txt
javac -proc:none -encoding UTF-8 -g -cp "$CP" -d $OUT/classes @$OUT/sources.txt
echo "=== compile OK ==="

python3 - "$PROJ/src/main/resources/META-INF/neoforge.mods.toml" "$OUT/classes/META-INF/neoforge.mods.toml" <<'PYEOF'
import sys
src, dst = sys.argv[1], sys.argv[2]
props = {
    'loader_version_range': '[4,)',
    'mod_license': 'MIT',
    'mod_id': 'gridbridge',
    'mod_version': '1.0.2',
    'mod_name': 'Grid Bridge',
    'mod_authors': 'Hermes',
    'mod_description': 'Lets PowerGrid wires connect to Create Electro Energetics double switches, with bidirectional power conversion.',
    'neo_version_range': '[21.1.0,)',
    'minecraft_version_range': '[1.21.1,1.22)',
}
s = open(src, encoding='utf-8').read()
for k, v in props.items():
    s = s.replace('${' + k + '}', v)
open(dst, 'w', encoding='utf-8').write(s)
print('mods.toml expanded')
PYEOF

cp $PROJ/src/main/resources/gridbridge.mixins.json $PROJ/src/main/resources/pack.mcmeta $OUT/classes/
mkdir -p $PROJ/build/libs
jar cf $PROJ/build/libs/gridbridge-1.0.2.jar -C $OUT/classes .
echo "=== jar built ==="
ls -la $PROJ/build/libs/gridbridge-1.0.2.jar
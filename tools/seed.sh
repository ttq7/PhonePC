#!/system/bin/sh
# Seed plausible file-manager content on the device. Runs on-device so that
# non-ASCII filenames never travel through a Windows command line.

mk() {
  f="$1"; s="$2"; t="$3"
  if command -v truncate >/dev/null 2>&1; then
    truncate -s "$s" "$f" 2>/dev/null || dd if=/dev/zero of="$f" bs=1 count=0 seek="$s" 2>/dev/null
  else
    dd if=/dev/zero of="$f" bs=1 count=0 seek="$s" 2>/dev/null
  fi
  [ -n "$t" ] && touch -t "$t" "$f" 2>/dev/null
  return 0
}

cd /sdcard || exit 1

mkdir -p Download/work
mkdir -p Documents/项目资料
mkdir -p Pictures/Screenshots
mkdir -p DCIM/Camera
mkdir -p Music Movies

mk "Download/手机助手.apk"                    1258291  202602120955
mk "Download/安装包.zip"                      348160   202602141830
mk "Download/系统镜像.iso"                    734003200 202601201410
mk "Download/work/季度报告.docx"              253952   202602181026
mk "Download/work/预算表.xlsx"                1044480  202602171640
mk "Download/work/演示文稿.pptx"              2516582  202602161205
mk "Download/work/会议纪要.txt"               4096     202602180845
mk "Documents/说明书.pdf"                     4508877  202602151312
mk "Documents/产品手册.pdf"                   2097152  202601301520
mk "Documents/notes.txt"                      2048     202602180902
mk "Documents/config.json"                    1024     202602101730
mk "Documents/数据备份.db"                    5242880  202601251100
mk "Documents/项目资料/需求.md"               6144     202602171415
mk "Documents/项目资料/MainActivity.kt"       14336    202602181550
mk "Documents/项目资料/图标集.zip"            1782579  202602181605
mk "Pictures/IMG_20260218.jpg"                3984588  202602180831
mk "Pictures/IMG_20260217.jpg"                3040870  202602171922
mk "Pictures/IMG_20260216.jpg"                2411724  202602161144
mk "Pictures/设计稿.psd"                      15728640 202602131633
mk "Pictures/Screenshots/Screenshot_1.png"    901120   202602181221
mk "Pictures/Screenshots/Screenshot_2.png"    786432   202602181534
mk "DCIM/Camera/IMG_0001.jpg"                 4299161  202602181001
mk "DCIM/Camera/IMG_0002.jpg"                 3670016  202602181003
mk "DCIM/Camera/VID_0001.mp4"                 18874368 202602181005
mk "Music/夜曲.mp3"                           8808038  202601302205
mk "Music/晴天.flac"                          33554432 202601281930
mk "Music/交响乐.wav"                         52428800 202601151020
mk "Movies/旅行记录.mp4"                      190840832 202602091822
mk "Movies/教程.webm"                         47185920 202602051400

echo "---- /sdcard ----"
ls -la /sdcard
echo "---- done ----"

"""Generate the promo voiceover with edge-tts.

Each line is written to its own MP3 so it can be placed at an exact time on the
timeline; a single continuous take would drift out of sync with the cuts.
"""
import asyncio
import os

import edge_tts

OUT = r'E:\此电脑\promo\vo'
os.makedirs(OUT, exist_ok=True)

VOICE = 'zh-CN-YunjianNeural'      # energetic male, the usual promo register
RATE = '+14%'

# (start second in the film, file stem, text)
LINES = [
    (0.5,  '01', '手机上的文件管理器。'),
    (3.2,  '02', '标签页、面包屑、命令栏，和电脑上一样。'),
    (8.2,  '03', '找文件不用记得文件名。'),
    (13.2, '04', '手机变成文件服务器，电脑浏览器直接读写。'),
    (17.7, '05', '长按，就是右键菜单。'),
    (21.0, '06', '此手机。安卓原生，无广告不联网。'),
]


async def main():
    for start, stem, text in LINES:
        path = os.path.join(OUT, f'{stem}.mp3')
        for attempt in range(4):
            try:
                await edge_tts.Communicate(text, VOICE, rate=RATE).save(path)
                if os.path.getsize(path) > 1000:
                    break
            except Exception as e:
                print('  retry %s (%d): %s' % (stem, attempt + 1, e))
                await asyncio.sleep(2)
        print('  %-4s %6.2fs  %s' % (stem, start, text))


asyncio.run(main())

# report durations so the mix can be checked before rendering
import subprocess
print()
for start, stem, text in LINES:
    p = os.path.join(OUT, f'{stem}.mp3')
    d = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration',
                        '-of', 'csv=p=0', p], capture_output=True, text=True)
    dur = float(d.stdout.strip())
    print('  %s  start=%5.1fs  dur=%4.1fs  ends=%5.1fs  %s' %
          (stem, start, dur, start + dur, 'OK' if start + dur < 25 else 'OVERRUNS'))

import subprocess, numpy as np, os, sys
from PIL import Image

vid = sys.argv[1] if len(sys.argv) > 1 else '/tmp/stair.mp4'
tag = sys.argv[2] if len(sys.argv) > 2 else 'st'
real = np.array([157.8, 102.0, 89.6])
picks = []
for i in range(60):
    t = i * 0.16
    p = f'/tmp/{tag}_{i}.png'
    subprocess.run(['ffmpeg', '-v', 'error', '-noautorotate', '-ss', f'{t:.2f}', '-i', vid,
                    '-frames:v', '1', '-vf', 'scale=96:54', '-y', p], check=False)
    if not os.path.exists(p):
        continue
    a = np.asarray(Image.open(p)).astype(np.float32)
    m = a.reshape(-1, 3).mean(0)
    picks.append((t, m, np.abs(m - real).max()))
ours = [(t, m) for t, m, d in picks if d > 20]
print(f'{len(picks)} 帧中"我们主导"的 {len(ours)} 帧')
for t, m in ours[:6]:
    print(f'  t={t:4.2f} mean=({m[0]:6.1f},{m[1]:6.1f},{m[2]:6.1f})')
if not ours:
    print('  样例均值:', [f'{m[0]:.0f},{m[1]:.0f},{m[2]:.0f}' for _, m, _ in picks[:5]])
    sys.exit(0)

t = ours[0][0]
subprocess.run(['ffmpeg', '-v', 'error', '-noautorotate', '-ss', f'{t:.2f}', '-i', vid,
                '-frames:v', '1', '-y', f'/tmp/{tag}_full.png'], check=True)
a = np.asarray(Image.open(f'/tmp/{tag}_full.png')).astype(np.float32)
rowm = a.mean(axis=(1, 2))
colm = a.mean(axis=(0, 2))
print('帧 mean', a.reshape(-1, 3).mean(0).round(1),
      '行极差 %.1f 列极差 %.1f' % (rowm.max() - rowm.min(), colm.max() - colm.min()))
n = 32
lo = rowm.min()
prof = [rowm[i * len(rowm) // n:(i + 1) * len(rowm) // n].mean() for i in range(n)]
for i, v in enumerate(prof):
    print(f'   行段{i:2d} {v:6.1f} ' + '#' * int(max(0.0, v - lo) / 2))
Image.open(f'/tmp/{tag}_full.png').resize((480, 270)).save(f'/tmp/{tag}_view.png')

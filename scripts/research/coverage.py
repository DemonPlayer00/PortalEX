import subprocess, numpy as np, os, sys
from PIL import Image

vid = sys.argv[1]
tag = sys.argv[2] if len(sys.argv) > 2 else 'cv'
real = np.array([161.0, 103.0, 86.0])
cov = []
for i in range(45):
    t = i * 0.2
    p = f'/tmp/{tag}_{i}.png'
    subprocess.run(['ffmpeg', '-v', 'error', '-noautorotate', '-ss', f'{t:.2f}', '-i', vid,
                    '-frames:v', '1', '-vf', 'scale=384:216', '-y', p], check=False)
    if not os.path.exists(p):
        continue
    a = np.asarray(Image.open(p)).astype(np.float32)
    lum = a.mean(2)
    # 锐度：相邻像素梯度（我们的合成卡是"硬边"，实景是模糊的）
    gx = np.abs(np.diff(lum, axis=1))
    H, W = gx.shape
    blocks = []
    for r in range(12):
        for c in range(12):
            blk = gx[r*H//12:(r+1)*H//12, c*W//12:(c+1)*W//12]
            blocks.append(blk.mean())
    blocks = np.array(blocks)
    # 以该帧自身的分布判断"哪些块是合成的"：取最高的那部分
    thr = np.percentile(blocks, 80)
    ours_blocks = (blocks > max(thr, 6.0)).mean()
    cov.append((t, float(blocks.mean()), float(ours_blocks)))
m = np.array([c[1] for c in cov]); o = np.array([c[2] for c in cov])
print(f'{vid}: {len(cov)} 帧  平均锐度 {m.mean():.2f}  合成块占比 均值 {o.mean():.3f} 最大 {o.max():.3f}')
hi = [c for c in cov if c[2] > 0.3]
print(f'  "合成块占比 >30%" 的帧：{len(hi)} / {len(cov)}')
for t, mm, oo in cov[:12]:
    print(f'    t={t:4.2f} 锐度 {mm:6.2f} 合成块 {oo:.3f} ' + '#' * int(oo * 40))

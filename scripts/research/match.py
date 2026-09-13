"""判断"按某种布局编码的模拟帧"是否真的落到了画面上。

做法：把录下来的帧与**我们期望的测试卡**（缩到同一尺寸）逐列带做相关。
布局对了 ⇒ 某些列带（我们抢下来的那些）相关很高；布局错了 ⇒ 到处都是低相关。
"""
import subprocess, numpy as np, os, sys
from PIL import Image, ImageDraw

def expected(W=3840, H=2160):
    img=Image.new('RGB',(W,H),(24,24,24)); d=ImageDraw.Draw(img)
    bars=[(255,255,255),(255,255,0),(0,255,255),(0,255,0),(255,0,255),(255,0,0),(0,0,255),(16,16,16)]
    for i,c in enumerate(bars): d.rectangle([i*W//8,0,(i+1)*W//8,300],fill=c)
    d.rectangle([0,320,W,400],fill=(128,128,128))
    d.rectangle([40,440,440,840],fill=(255,255,255))
    d.rectangle([W-440,H-840,W-40,H-440],fill=(255,0,0))
    d.ellipse([W//2-500,H//2-500,W//2+500,H//2+500],outline=(0,255,0),width=40)
    a=np.asarray(img).astype(np.float32).mean(2)
    return a

def corr_bands(frame_lum, exp_lum, nbands=12):
    H,W=frame_lum.shape
    out=[]
    for b in range(nbands):
        x0,x1=b*W//nbands,(b+1)*W//nbands
        f=frame_lum[:,x0:x1].reshape(-1); e=exp_lum[:,x0:x1].reshape(-1)
        f=f-f.mean(); e=e-e.mean()
        den=(np.linalg.norm(f)*np.linalg.norm(e))
        out.append(float((f@e)/den) if den>1e-6 else 0.0)
    return out

def run(vid, tag, maxframes=30):
    exp=expected()
    exp_small=np.asarray(Image.fromarray(exp.astype(np.uint8)).resize((384,216))).astype(np.float32)
    best=(-2,None,None); rows=[]
    for i in range(maxframes):
        t=i*0.08
        p=f'/tmp/{tag}_{i}.png'
        subprocess.run(['ffmpeg','-v','error','-noautorotate','-ss',f'{t:.2f}','-i',vid,
                        '-frames:v','1','-vf','scale=384:216','-y',p],check=False)
        if not os.path.exists(p): continue
        lum=np.asarray(Image.open(p)).astype(np.float32).mean(2)
        bands=corr_bands(lum, exp_small)
        m=max(bands)
        rows.append((t,m,bands))
        if m>best[0]: best=(m,t,bands)
    if not rows:
        print(f'{vid}: 取不到帧'); return
    print(f'{vid}: {len(rows)} 帧   最高列带相关 {best[0]:+.3f} (t={best[1]:.2f})')
    print('   该帧各列带相关:', ' '.join(f'{v:+.2f}' for v in best[2]))
    hi=[r for r in rows if r[1]>0.5]
    print(f'   列带相关 >0.5 的帧: {len(hi)}/{len(rows)}')

if __name__=='__main__':
    run(sys.argv[1], sys.argv[2] if len(sys.argv)>2 else 'mm')

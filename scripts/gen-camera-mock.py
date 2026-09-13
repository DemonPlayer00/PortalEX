#!/usr/bin/env python3
"""按 ACE5（OnePlus PKG110 / ColorOS 16）实测布局，把一张设计稿生成可注入的相机缓冲素材。

三个目标面各有各的布局与显示变换，**全部来自实测**（标定过程与证据见 docs/camera-mock.md §47）：

  mode            缓冲总长     Y 面                       色度面              显示变换（缓冲→画面）
  -------------   ---------   ------------------------   -----------------   ---------------------------
  rear-preview    4,038,656   @0    行距2176 ×1200 行     @2,611,200 NV21     顺时针 90°，无镜像
  rear-video      12,591,104  瓦片 NV12（见 §43/§46）     ——                  ——（用 §46 的瓦片化路径）
  front-preview   4,038,656   @0    行距2176 ×**1256**行  @2,733,056 NV21     rot90cw **再上下翻**（见下）
  front-video     3,194,880   @0    行距1920 ×**1088**行  @2,088,960 NV21     画面 = fliplr(rot90(缓冲, +1))
                                                                              ⇒ 缓冲 = rot90(fliplr(设计稿), 3)

ℹ️ `front-preview` 的圆盘：**这是 App 自己的渲染，不是我们的缺陷**（§48）——
关掉全部注入后，前置**实景**取景器上同样有那个圆盘（中央清晰、盘外发白去饱和）。
注入内容在**盘内逐像素准确**（探针：缓冲 (128,U200,V90) → 屏幕 (77,132,254)，理论 (69,133,255)）。
前置预览比后置预览还多一层**上下翻转**，本模式已按实测（手性 'F' 探针）代为翻正。

用法：
    python3 gen-camera-mock.py <mode> <设计稿.png|.jpg> <输出.raw>
设计稿按"人眼最终看到的画面"排版（front-video 用 1080x1920 竖屏，其余用 1264x2221 的可见区）。
"""
import sys

import numpy as np
from PIL import Image

# ---------------------------------------------------------------- 各目标面的实测参数
MODES = {
    # 后置预览：Y 面 1200 行无填充，色度紧随其后；显示为顺时针 90°
    'rear-preview': dict(
        total=4038656, w=2136, h=1200, stride=2176, yrows=1200,
        uvoff=2176 * 1200, uvrows=600, display='rot90cw', fill=128,
        vis=(352, 2573),                         # 屏幕上真正可见的 y 区间
    ),
    # 前置预览：Y 面 1256 行（1200 有效 + 56 填充），色度在 2176*1256（**实测全零行分界**）
    'front-preview': dict(
        total=4038656, w=2136, h=1200, stride=2176, yrows=1256,
        uvoff=2176 * 1256, uvrows=600, display='rot90cw_flipud', fill=128,
        vis=(352, 2573),
        warn='取景器的圆盘是 App 自身渲染（§48）：盘内逐像素准确，盘外会被提亮去饱和',
    ),
    # 前置录像：1080p、线性 NV12，缓冲 1080 有效行 + 8 填充行
    'front-video': dict(
        total=3194880, w=1920, h=1080, stride=1920, yrows=1088,
        uvoff=1920 * 1088, uvrows=544, display='fliplr_rot90',
        vis=None,
    ),
}

SCREEN_W, SCREEN_H = 1264, 2780
A, B = 1260.5, 1.0524          # 屏幕X = A - B*buffer_y      （后置/前置预览实测）
C, D = 351.9, 1.04             # 屏幕Y = C + D*buffer_x


def cover(img, tw, th):
    w, h = img.size
    s = max(tw / w, th / h)
    img = img.resize((max(1, round(w * s)), max(1, round(h * s))), Image.LANCZOS)
    x0, y0 = (img.width - tw) // 2, (img.height - th) // 2
    return img.crop((x0, y0, x0 + tw, y0 + th))


def rgb_to_yuv(rgb):
    r = rgb[..., 0].astype(np.float32)
    g = rgb[..., 1].astype(np.float32)
    b = rgb[..., 2].astype(np.float32)
    y = 16 + 0.257 * r + 0.504 * g + 0.098 * b
    u = 128 - 0.148 * r - 0.291 * g + 0.439 * b
    v = 128 + 0.439 * r - 0.368 * g - 0.071 * b
    return (np.clip(y, 0, 255).astype(np.uint8),
            np.clip(u, 0, 255).astype(np.uint8),
            np.clip(v, 0, 255).astype(np.uint8))


def nv21_plane(u, v, rows, stride, w):
    """NV21：**V 在前 U 在后**（前置预览/前置录像实测；探针写成 U,V 时红↔蓝对调）。
    `rows` 是色度面的行数（≥ h//2；多出来的填充行保持 128 中性）。"""
    uv = np.full((rows, stride), 128, dtype=np.uint8)
    n = min(rows, u.shape[0] // 2)
    uv[:n, 0:w:2] = v[0:2 * n:2, 0::2][:, :(w + 1) // 2]
    uv[:n, 1:w:2] = u[0:2 * n:2, 0::2][:, :(w + 1) // 2]
    return uv


def build(mode, design_path, out_path):
    p = MODES[mode]
    design = Image.open(design_path).convert('RGB')
    if p['display'] in ('rot90cw', 'rot90cw_flipud'):
        # 设计稿按屏幕可见区排版，再反变换写回缓冲。
        # ⚠️ 前置预览比后置多一层**上下翻转**（实测：带手性 'F' 的探针，屏幕 = flipud(设计稿)，
        #    IoU 0.68 vs 次优 0.35）—— 这是前置传感器 180° 安装的体现。要"屏幕上正着"就得先翻一次。
        if p['display'] == 'rot90cw_flipud':
            design = design.transpose(Image.FLIP_TOP_BOTTOM)
        vis_top, vis_bot = p['vis']
        design = cover(design, SCREEN_W, vis_bot - vis_top)
        canvas = Image.new('RGB', (SCREEN_W, SCREEN_H), (0, 0, 0))
        canvas.paste(design, (0, vis_top))
        S = np.asarray(canvas, dtype=np.uint8)
        ys = np.arange(p['h'])
        xs = np.arange(p['w'])
        Xs = np.clip(np.round(A - B * ys), 0, SCREEN_W - 1).astype(np.int32)
        Ys = np.clip(np.round(C + D * xs), 0, SCREEN_H - 1).astype(np.int32)
        rgb = S[np.ix_(Ys, Xs)]
        rgb = np.transpose(rgb, (1, 0, 2))     # np.ix_ 出来的是 (x, y) 轴序，转回 (y, x)
    elif p['display'] == 'fliplr_rot90':
        # 画面 = fliplr(rot90(缓冲, +1))  ⇒  缓冲 = rot90(fliplr(设计稿), 3)
        rgb = np.rot90(np.asarray(design, dtype=np.uint8)[:, ::-1], 3)
    else:
        raise SystemExit(f'未知显示变换 {p["display"]}')
    rgb = np.ascontiguousarray(rgb)

    y, u, v = rgb_to_yuv(rgb)
    buf = np.full(p['total'], p.get('fill', 0), dtype=np.uint8)
    plane = np.zeros((p['yrows'], p['stride']), dtype=np.uint8)
    plane[:p['h'], :p['w']] = y
    buf[:p['stride'] * p['yrows']] = plane.reshape(-1)
    uv = nv21_plane(u, v, p['uvrows'], p['stride'], p['w'])
    end = p['uvoff'] + uv.size
    if end > p['total']:
        uv = uv[:p['uvrows'] - ((end - p['total']) + p['stride'] - 1) // p['stride']]
        end = p['uvoff'] + uv.size
    buf[p['uvoff']:end] = uv.reshape(-1)
    buf.tofile(out_path)
    print(f'{mode} → {out_path}：{buf.size} 字节；Y@0(行距{p["stride"]},{p["yrows"]}行) '
          f'UV@{p["uvoff"]}(NV21)')
    if p.get('warn'):
        print(f'  ⚠️ {p["warn"]}')


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        print('可选 mode：' + ', '.join(MODES))
        raise SystemExit(2)
    mode, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    if mode not in MODES:
        raise SystemExit(f'未知 mode：{mode}（可选：{", ".join(MODES)}）')
    build(mode, src, dst)


if __name__ == '__main__':
    main()

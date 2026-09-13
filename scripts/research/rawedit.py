"""RAW10（4096x3072，MIPI 打包）读写 + 加标记的小工具。

用法（命令行）：
    python3 rawedit.py <源raw> <输出raw> <x,y,暗化倍数;x,y,倍数;...>
标记是带羽化的方块，默认边长 400、羽化 80。
"""
import numpy as np, sys

W, H = 4096, 3072


def load(path):
    d = np.fromfile(path, dtype=np.uint8)
    pk = d.reshape(H, W // 4, 5)
    lo = pk[:, :, 4].copy()
    px = np.zeros((H, W), dtype=np.uint16)
    for k in range(4):
        px[:, k::4] = pk[:, :, k]
    px <<= 2
    for k in range(4):
        px[:, k::4] |= (lo >> (2 * k)) & 3
    return px


def save(px, path):
    hi = (px >> 2).astype(np.uint8)
    low = (px & 3).astype(np.uint8)
    out = np.zeros((H, W // 4, 5), dtype=np.uint8)
    for k in range(4):
        out[:, :, k] = hi[:, k::4]
    out[:, :, 4] = ((low[:, 3::4]) << 6) | ((low[:, 2::4]) << 4) | \
                   ((low[:, 1::4]) << 2) | low[:, 0::4]
    open(path, 'wb').write(out.tobytes())
    return len(out.tobytes())


def mark(px, cx, cy, factor=0.10, size=400, feather=80):
    y0, y1 = cy - size // 2, cy + size // 2
    x0, x1 = cx - size // 2, cx + size // 2
    yy, xx = np.mgrid[y0:y1, x0:x1]
    fy = np.clip((yy - y0) / feather, 0, 1) * np.clip((y1 - yy) / feather, 0, 1)
    fx = np.clip((xx - x0) / feather, 0, 1) * np.clip((x1 - xx) / feather, 0, 1)
    w = fy * fx
    px[y0:y1, x0:x1] = (px[y0:y1, x0:x1] * (1 - w) +
                        (64 + (px[y0:y1, x0:x1] - 64) * factor) * w).astype(np.uint16)


if __name__ == '__main__':
    src, dst, spec = sys.argv[1], sys.argv[2], sys.argv[3]
    px = load(src)
    for item in spec.split(';'):
        if not item.strip():
            continue
        x, y, f = item.split(',')
        mark(px, int(x), int(y), float(f))
        print(f'  标记 RAW({x},{y}) 暗化×{f}')
    n = save(px, dst)
    print(f'已写出 {dst}（{n} 字节）')

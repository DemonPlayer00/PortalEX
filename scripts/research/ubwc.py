"""UBWC（Qualcomm 平铺/压缩布局）线性 <-> 瓦片 换算。

按 Mesa freedreno `fd6_tiled_memcpy.cc`（fdl6 布局）逐条移植：
- `get_pixel_offset`：块内像素 swizzle（x 出 bit0/2/4-6，y 出 bit1/3/7-9）
- `get_block_size`：每种 cpp 的块尺寸（cpp=1 ⇒ 32x8；cpp=2 且 r8g8 ⇒ 16x8）
- `block_x_xormask` / `block_y_xormask`：块坐标 → 字节偏移的异或掩码（含 bank swizzle）
- 宏瓦片（macrotile）= 块 × 4；宏瓦片行距 = macrotile_stride * 4096

块/宏瓦片几何与 bank swizzle 参数（macrotile_mode、highest_bank_bit、
bank_swizzle_levels、pitch）在板上都不可见，所以全部作为参数**搜**，
用"解出来像不像一张图"（相邻行/列差）当选优依据。
"""
import numpy as np

BLOCK_BYTES = 256


def pixel_offset(x, y):
    return ((x & 1) | ((y & 1) << 1) | (((x & 2) >> 1) << 2) | (((y & 2) >> 1) << 3)
            | (((x & 0x1C) >> 2) << 4) | (((y & 4) >> 2) << 7))


def block_dims(cpp, r8g8):
    if cpp == 1:
        return 32, 8
    if cpp == 2:
        return (16, 8) if r8g8 else (32, 4)
    if cpp == 4:
        return 16, 4
    if cpp == 8:
        return 8, 4
    if cpp == 16:
        return 4, 4
    if cpp == 32:
        return 4, 2
    if cpp == 64:
        return 2, 2
    raise ValueError(cpp)


def x_xormask(xb, cpp, mode):
    if mode == 4:
        return ((((xb & 1) * 0b111) ^ ((xb & 0b010) if cpp < 16 else 0)
                 ^ ((xb >> 1) << 3)) << 8)
    return ((((xb & 1) * 0b111) ^ ((xb & 0b110) if cpp < 16 else 0)
             ^ ((xb >> 1) << 3)) << 8)


def y_xormask(yb, cpp, mode, bank_mask, bank_shift):
    base = (((yb & 1) * 0b110) ^ (((yb >> 1) & 1) * 0b011))
    if mode == 8 and cpp < 16:
        base ^= (yb & 0b100)
    return ((base << 8) | ((yb & bank_mask) << bank_shift))


def bank_params(pitch, cpp, r8g8, mode, hbb, levels):
    """按 Mesa fdl6_get_bank_mask/fdl6_get_bank_shift 算出 (bank_mask, bank_shift)。"""
    bw, _ = block_dims(cpp, r8g8)
    macrotile_width = bw * 4
    macrotile_stride = pitch // (macrotile_width * cpp)
    offset = 1 if (r8g8 or cpp == 1) else 0
    mask = 0
    if (levels & 0x2) and (macrotile_stride & ((1 << (hbb - 12 + offset)) - 1)) == 0:
        mask |= 0b1
    if (levels & 0x4) and (macrotile_stride & ((1 << (hbb - 11 + offset)) - 1)) == 0:
        mask |= 0b10
    if (levels & 0x1) and (macrotile_stride & ((1 << (hbb - 10 + offset)) - 1)) == 0:
        mask |= 0b100
    return mask << 2, hbb - 1 - 2, macrotile_stride


def plane_offsets(width, height, cpp, r8g8, mode, bank_mask, bank_shift,
                  macrotile_stride, pitch):
    """返回 shape=(height,width) 的字节偏移表（该平面内）。"""
    bw, bh = block_dims(cpp, r8g8)
    xs = np.arange(width)
    ys = np.arange(height)
    xb = xs // bw
    yb = ys // bh
    px = xs % bw
    py = ys % bh

    po = np.array([pixel_offset(int(v), 0) for v in range(bw)])[px]
    po = po[:, None] + np.array([pixel_offset(0, int(v)) for v in range(bh)])[py][None, :]
    # 上面按 [x, y] 排；转成 [y, x]
    po = po.T

    xm = np.array([x_xormask(int(v), cpp, mode) for v in xb])
    ym = np.array([y_xormask(int(v), cpp, mode, bank_mask, bank_shift) for v in yb])
    bo = (xm[None, :] ^ ym[:, None]).astype(np.int64)
    mt = (yb >> 2).astype(np.int64) * (macrotile_stride * 4096)
    return mt[:, None] + bo + po.astype(np.int64) * cpp


def decode_plane(buf, width, height, base, cpp, r8g8, mode, hbb, levels, pitch):
    bank_mask, bank_shift, mts = bank_params(pitch, cpp, r8g8, mode, hbb, levels)
    off = plane_offsets(width, height, cpp, r8g8, mode, bank_mask, bank_shift, mts, pitch)
    off = off + base
    flat = off.reshape(-1)
    if flat.max() >= len(buf) or flat.min() < 0:
        return None
    return buf[flat].reshape(height, width)


def score(img):
    """越大越像一张图：相邻行 + 相邻列的平均差（取负），并给一点对比度奖励。"""
    if img is None or img.size == 0:
        return -1e9
    a = img.astype(np.float32)
    drow = np.abs(np.diff(a, axis=0)).mean()
    dcol = np.abs(np.diff(a, axis=1)).mean()
    contrast = a.std()
    return -(drow + dcol) + 0.1 * contrast

# 摄像头模拟 — 研究工具（归档）

这些是推进 `docs/camera-mock.md` 里那些结论时用的**量具**。它们本身与设备无关，
只依赖 numpy / Pillow / ffmpeg；参数（尺寸、偏移、布局常量）在各自的注释与文档章节里都有出处。

| 文件 | 用途 | 相关章节 |
| --- | --- | --- |
| `ubwc.py` | **UBWC / 平铺布局换算**：按 Mesa `fd6_tiled_memcpy.cc` 移植的 `pixel_offset` / 块异或掩码 / `plane_offsets` / `decode_plane` / `score`。用来把一段缓冲按"瓦片假设"解成图，并用"像不像一张图"当参数搜索的判据。 | §43、§46、§55 |
| `rawedit.py` | **RAW10 读写 + 加标记**（MIPI 打包，4 像素 5 字节）。照片短路径就是"取真帧 → 改一处 → 回注"。 | §41、§47.6 |
| `analyze.py` | 把录像按帧抽出来、按"是不是我们的内容"分类，并画出行/列剖面。 | §35、§43 |
| `coverage.py` | 用**绝对阈值**判"合成块占比"，避免早期那个"被饿死的流水线"造成的假高覆盖率。 | §35.3 |
| `match.py` | 把录下来的帧与**期望的测试卡**逐列带做相关：布局对了某些列带相关很高，错了到处都低。 | §43、§46 |
| `designs/mock-preview-card.png` | 预览用测试卡设计稿（竖屏，按"人眼看到的画面"排版）。 | §50 |
| `designs/mock-video-card.png` | 录像用测试卡设计稿（含 `TOP/BOTTOM/LEFT/RIGHT` 与 TL/TR/BL/BR 手性标记）。 | §50.1 |

生成可注入素材用仓库里的 `scripts/gen-camera-mock.py`：

```bash
python3 scripts/gen-camera-mock.py front-video   scripts/research/designs/mock-video-card.png   out.raw
python3 scripts/gen-camera-mock.py front-preview scripts/research/designs/mock-preview-card.png out.raw
python3 scripts/gen-camera-mock.py rear-preview  scripts/research/designs/mock-preview-card.png out.raw
```

运行期部署（不重新打包模块时用）：`scripts/deploy-camera-hal-pineapple.sh "<cfg 行>"`。

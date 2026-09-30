# Play 商店图片

[Google Play 上架材料](../PLAY_LISTING.md) 用到的图片。源文件在 `src/`，改完后按下面的命令重新导出，再把 PNG 一起提交。

| 文件 | 源文件 | 规格 |
| --- | --- | --- |
| `icon-512.png` | `src/icon-512.html` | 512 × 512，32 位 PNG，和启动图标同一图案 |
| `feature-graphic-en.png` | `src/feature-graphic.html#en` | 1024 × 500，24 位 PNG |
| `feature-graphic-zh.png` | `src/feature-graphic.html#zh` | 1024 × 500，24 位 PNG |

导出（macOS，用本机的 Chrome；置顶大图的字体从 Google Fonts 在线加载，需要联网）：

```bash
cd docs/store
C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$C" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --window-size=512,512 --screenshot="$PWD/icon-512.png" "file://$PWD/src/icon-512.html"
python3 -c "from PIL import Image; Image.open('icon-512.png').convert('RGBA').save('icon-512.png', optimize=True)"
for lang in en zh; do "$C" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --virtual-time-budget=8000 --window-size=1024,500 --screenshot="$PWD/feature-graphic-$lang.png" "file://$PWD/src/feature-graphic.html#$lang"; done
```

Chrome 导出的全不透明 PNG 不带透明通道，Play 要求图标是 32 位 PNG，所以第二行用 Pillow 补上（像素不变）。

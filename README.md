# USBCA-ERAPRO UVC v16

FrameCallback 诊断版。

v14/v15 已经证明：权限、openCamera、startPreview 都能走到，但 Surface/Texture 都没有实际画面。

v16 不再走 Surface/Texture 预览，而是：
- IFrameCallback 拿 BGR 帧
- 统计 frameCount
- 每 5 帧转 Bitmap 画到 ImageView

判断：
- 如果 frame 数增加并出现图像：预览 Surface 链路有问题，后续 AI 直接走回调帧即可。
- 如果 frame 数不增加：底层没有出帧，需要换 UVC 库或切换 MJPEG/YUYV 格式协商。

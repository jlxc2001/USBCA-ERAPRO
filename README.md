# USBCA-ERAPRO UVC v17

v16 显示 `无帧 / lastAge=-1ms`，说明只有 FrameCallback 不足以启动底层取流。

v17 增加一个不显示到屏幕的伪 Surface：

- 创建 SurfaceTexture
- setDefaultBufferSize(width, height)
- addSurface(dummySurface, false)
- 同时启用 IFrameCallback
- startPreview

判断：
- 如果 v17 frame 数开始增加：说明库需要 Surface 才会真正出帧。
- 如果仍然无帧：当前 UVCAndroid/libuvc 组合和这颗摄像头的格式协商不兼容，下一步应换库/接 AndroidUSBCamera/libausbc。

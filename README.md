# USBCA-ERAPRO UVC v12

关键修复：Android 对 USB Video Class 设备会额外要求 App 已获得运行时 CAMERA 权限。

你的日志里系统反复输出：

```text
UsbUserPermissionManager: Camera permission required for USB video class devices
```

因此即使你点了 USB 弹窗的“允许”，系统仍会把 USB 权限结果判为 denied。

v12 在启动 UVC 前会先请求相机权限。

## 测试流程

1. 卸载旧版。
2. 安装 v12。
3. 打开 App，确认标题是 `USBCA-ERAPRO UVC v12 - 相机权限版`。
4. 点“启动UVC引擎”。
5. 如果弹“相机权限”，必须允许。
6. 再点“启动UVC引擎”。
7. 插入摄像头。
8. 点“打开UVC”。
9. USB 弹窗点确定/允许。

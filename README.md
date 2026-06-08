# UVCAI UVC Direct

这是一个从零重写的 **UVC 摄像头直连预览 App**。

这版只做一件事：

```text
USB UVC 摄像头 → 本 App 直接打开 → 本 App 内显示画面
```

不再做：

```text
不录屏
不调用第三方 USB Camera App
不走 Camera2
不叠 ncnn / NanoDet
不做悬浮窗
```

## 功能

- 插入 UVC 摄像头后自动请求 USB 权限
- 点击“打开UVC”可手动枚举并打开第一个 UVC 设备
- 显示 UVC 预览画面
- 支持关闭摄像头
- 支持旋转：自动 / 0° / 90° / 180° / 270°
- 支持切换摄像头支持的分辨率

## 构建

上传到 GitHub 后：

1. Settings → Actions → General → Workflow permissions → Read and write permissions
2. Actions → release-apk → Run workflow
3. 到 Releases 下载 APK

## 依赖

UVC 部分使用：

```gradle
implementation 'com.herohan:UVCAndroid:1.0.12'
```

该库是用于非 root Android 设备访问 UVC 摄像头的开源库。


## v2 修复

修复 Kotlin stdlib 依赖冲突：删除 appcompat，强制 Kotlin stdlib 统一为 1.8.22，并排除旧的 kotlin-stdlib-jdk7/jdk8 1.6.21。


## v3 safeopen 修复

进入页面后不再自动枚举/打开摄像头，避免部分车机 USB 栈在主线程卡住。流程改为：启动UVC引擎 → 打开UVC。枚举设备放到后台线程，并增加 10 秒超时提示。


## v4 asyncopen 修复

修复点击“打开UVC”后 UI 卡住：不再调用 UVCAndroid 的 `getDeviceList()` 做枚举，改用系统 `UsbManager.getDeviceList()`；同时把 `selectDevice`、`openCamera`、`startPreview`、`addSurface` 等重操作尽量放到后台线程，主线程只更新 UI。


## v5 dualabi-lazyinit 修复

- APK 同时包含 `armeabi-v7a` 和 `arm64-v8a`
- 移除 `Application.onCreate()` 中的 UVC 初始化
- `UVCUtils.init()` 改为点击“启动UVC引擎”后后台执行
- 目标：避免部分 64 位设备刚打开 App 就卡死


## v6 permissionfix 修复

修复部分车机上“用户实际允许 USB 权限，但 UVC 库仍回调 onCancel”的问题：

- 打开设备时优先使用 `cameraHelper.getDeviceList()` 的设备对象
- 若收到 `onCancel`，用 `UsbManager.hasPermission(device)` 二次确认
- 如果系统实际已经授权，则忽略取消回调并继续 `openCamera()`


## v7 compilefix-v2

补回 `findFirstUvcDeviceByCameraHelperFirst()` 方法，修复 Javac 找不到符号导致编译失败。


## v8 hardfix

- 确认 `MainActivity.java` 内同时包含方法调用和方法定义：
  - `findFirstUvcDeviceByCameraHelperFirst()`
- Workflow 增加 `verify-mainactivity-source`，构建前会打印并检查该方法是否存在，避免旧文件/未覆盖文件继续参与编译。

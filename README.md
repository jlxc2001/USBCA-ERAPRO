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

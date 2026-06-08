
package com.jlxc.uvcai;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.herohan.uvcapp.CameraException;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.Size;
import com.serenegiant.utils.UVCUtils;
import com.serenegiant.widget.AspectRatioSurfaceView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * UVCAI UVC 直连 - 自管 USB 权限版
 *
 * 重点修复：
 * 部分车机上，UVCAndroid 内部申请 USB 权限会出现“用户点了允许，但回调 onCancel，
 * UsbManager.hasPermission=false”的情况。
 *
 * 这一版不再完全依赖 UVCAndroid 内部权限请求，而是 App 自己先通过
 * UsbManager.requestPermission() 请求权限；拿到 ACTION_USB_PERMISSION 且 granted=true 后，
 * 再交给 CameraHelper.selectDevice()。
 */
public class MainActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "UVCAI-UVC";
    private static final String ACTION_USB_PERMISSION = "com.jlxc.uvcai.USB_PERMISSION";

    private FrameLayout previewContainer;
    private AspectRatioSurfaceView cameraView;
    private TextView statusText;
    private Button initButton;
    private Button openButton;
    private Button closeButton;
    private Button rotateButton;
    private Button nextSizeButton;

    private ICameraHelper cameraHelper;
    private UsbManager usbManager;
    private UsbDevice currentDevice;
    private UsbDevice pendingPermissionDevice;

    private final List<Size> supportedSizes = new ArrayList<Size>();
    private int selectedSizeIndex = -1;

    // -1 = 自动；0/90/180/270 = 手动
    private int rotateMode = -1;
    private int currentRotation = 0;
    private boolean surfaceReady = false;
    private Surface currentSurface;

    private HandlerThread workerThread;
    private Handler workerHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean helperStarted = false;
    private volatile boolean opening = false;
    private long openToken = 0L;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }

            final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            final boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

            if (device == null) {
                opening = false;
                openButtonPostEnabled(true);
                status("USB 权限回调异常：device=null");
                return;
            }

            if (!granted) {
                opening = false;
                openButtonPostEnabled(true);
                status("系统 USB 权限被拒绝：" + safeDeviceName(device) + "。如果你明明点了允许，请拔插摄像头后重试。");
                return;
            }

            currentDevice = device;
            status("系统 USB 权限已授权：" + safeDeviceName(device) + "，继续打开 UVC...");
            selectDeviceAfterSystemPermission(device);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        registerUsbPermissionReceiver();

        workerThread = new HandlerThread("uvc-worker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        status("自管权限版。先点“启动UVC引擎”，再点“打开UVC”。如果系统弹窗，请点允许。");
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(usbPermissionReceiver);
        } catch (Throwable ignored) {
        }

        releaseCameraHelper();

        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }

        super.onDestroy();
    }

    private void registerUsbPermissionReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("UVCAI UVC 直连 - 自管权限版");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(14), 0, dp(14), 0);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
        ));

        previewContainer = new FrameLayout(this);
        previewContainer.setBackgroundColor(Color.BLACK);

        cameraView = new AspectRatioSurfaceView(this);
        cameraView.setAspectRatio(640, 480);
        cameraView.getHolder().addCallback(surfaceCallback);

        previewContainer.addView(cameraView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(110, 255, 170));
        statusText.setTextSize(14f);
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setText("等待启动");
        statusText.setBackgroundColor(Color.argb(150, 0, 0, 0));

        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        previewContainer.addView(statusText, statusLp);

        root.addView(previewContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(8), dp(6), dp(8), dp(6));
        controls.setBackgroundColor(Color.rgb(18, 20, 30));

        initButton = makeButton("启动UVC引擎");
        openButton = makeButton("打开UVC");
        closeButton = makeButton("关闭");
        rotateButton = makeButton("旋转:自动");
        nextSizeButton = makeButton("分辨率");

        controls.addView(initButton);
        controls.addView(openButton);
        controls.addView(closeButton);
        controls.addView(rotateButton);
        controls.addView(nextSizeButton);

        root.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
        ));

        setContentView(root);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setOnClickListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            surfaceReady = true;
            currentSurface = holder.getSurface();
            addPreviewSurfaceIfReady();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            currentSurface = holder.getSurface();
            applyRotationAndScale();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            final Surface s = currentSurface;
            currentSurface = null;
            runUvcJob("removeSurface", new Runnable() {
                @Override
                public void run() {
                    safeRemoveSurface(s);
                }
            });
        }
    };

    private void startUvcEngineSafe() {
        if (helperStarted || cameraHelper != null) {
            status("UVC 引擎已启动。现在可以点“打开UVC”。");
            return;
        }

        status("正在初始化 UVC 引擎...");

        if (workerHandler == null) {
            status("后台线程未就绪，无法初始化 UVC");
            return;
        }

        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    UVCUtils.init(getApplicationContext());

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                cameraHelper = new CameraHelper();
                                cameraHelper.setStateCallback(stateCallback);
                                helperStarted = true;
                                status("UVC 引擎启动完成。现在点“打开UVC”。");
                            } catch (Throwable t) {
                                status("CameraHelper 创建失败：" + shortError(t));
                                Log.e(TAG, "CameraHelper create failed", t);
                            }
                        }
                    });
                } catch (Throwable t) {
                    postStatus("UVC 初始化失败：" + shortError(t));
                    Log.e(TAG, "UVCUtils.init failed", t);
                }
            }
        });
    }

    private void releaseCameraHelper() {
        try {
            if (cameraHelper != null) {
                final ICameraHelper helper = cameraHelper;
                final Surface surface = currentSurface;
                runUvcJob("release", new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (surface != null) {
                                helper.removeSurface(surface);
                            }
                        } catch (Throwable ignored) {
                        }
                        try {
                            helper.closeCamera();
                        } catch (Throwable ignored) {
                        }
                        try {
                            helper.release();
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }

        cameraHelper = null;
        helperStarted = false;
        opening = false;
        currentDevice = null;
        pendingPermissionDevice = null;
        supportedSizes.clear();
        selectedSizeIndex = -1;
    }

    private void openUvcAsync() {
        if (!helperStarted || cameraHelper == null) {
            status("请先点“启动UVC引擎”。");
            return;
        }

        if (opening) {
            status("正在打开中。如果长时间无反应，点“关闭”后拔插摄像头再试。");
            return;
        }

        opening = true;
        final long token = ++openToken;
        openButton.setEnabled(false);

        status("正在枚举 USB 设备...");

        runUvcJob("openUvcAsync", new Runnable() {
            @Override
            public void run() {
                final UsbDevice device = findFirstUvcDeviceBySystemUsbManager();
                if (device == null) {
                    postOpenFailed(token, "系统 USB 列表里没找到 UVC 摄像头。请确认摄像头已插入、USB Host 正常。");
                    return;
                }

                currentDevice = device;

                if (hasUsbPermission(device)) {
                    postStatus("系统已拥有 USB 权限，直接打开：" + safeDeviceName(device));
                    selectDeviceAfterSystemPermission(device);
                } else {
                    postStatus("准备请求系统 USB 权限：" + safeDeviceName(device));
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            requestUsbPermission(device);
                        }
                    });
                }
            }
        });

        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (opening && token == openToken) {
                    opening = false;
                    openButton.setEnabled(true);
                    status("打开UVC超时。请拔插摄像头后重试。");
                }
            }
        }, 15000);
    }

    private void requestUsbPermission(UsbDevice device) {
        if (usbManager == null || device == null) {
            opening = false;
            openButtonPostEnabled(true);
            status("无法请求 USB 权限：UsbManager 或 device 为空");
            return;
        }

        try {
            pendingPermissionDevice = device;

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                // Android 12+ 必须 mutable，否则系统可能无法回填 EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED。
                flags |= PendingIntent.FLAG_MUTABLE;
            }

            Intent intent = new Intent(ACTION_USB_PERMISSION);
            intent.setPackage(getPackageName());

            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 6257, intent, flags);
            usbManager.requestPermission(device, pendingIntent);

            status("已发起系统 USB 权限请求，请在弹窗中点“允许”。");
        } catch (Throwable t) {
            opening = false;
            openButtonPostEnabled(true);
            status("请求 USB 权限失败：" + shortError(t));
            Log.e(TAG, "requestUsbPermission failed", t);
        }
    }

    private void selectDeviceAfterSystemPermission(final UsbDevice device) {
        if (cameraHelper == null || device == null) {
            postOpenFailed(openToken, "UVC 引擎或设备为空，无法打开");
            return;
        }

        runUvcJob("selectDeviceAfterSystemPermission", new Runnable() {
            @Override
            public void run() {
                try {
                    // 即使系统已经授权，CameraHelper 内部仍需要 selectDevice 来绑定设备。
                    cameraHelper.selectDevice(device);
                } catch (Throwable t) {
                    if (hasUsbPermission(device)) {
                        forceOpenAfterPermission(device, "selectDevice 异常但系统已有 USB 权限");
                    } else {
                        postOpenFailed(openToken, "selectDevice 失败：" + shortError(t));
                    }
                }
            }
        });
    }

    private void forceOpenAfterPermission(final UsbDevice device, final String reason) {
        if (cameraHelper == null || device == null) {
            postOpenFailed(openToken, "权限已授权，但 UVC 引擎或设备为空");
            return;
        }

        postStatus(reason + "，尝试直接 openCamera...");

        runUvcJob("forceOpenAfterPermission", new Runnable() {
            @Override
            public void run() {
                try {
                    cameraHelper.openCamera();
                } catch (Throwable t) {
                    postOpenFailed(openToken, "授权后 openCamera 失败：" + shortError(t));
                }
            }
        });
    }

    private UsbDevice findFirstUvcDeviceBySystemUsbManager() {
        try {
            UsbManager manager = usbManager != null ? usbManager : (UsbManager) getSystemService(Context.USB_SERVICE);
            if (manager == null) return null;

            HashMap<String, UsbDevice> map = manager.getDeviceList();
            if (map == null || map.isEmpty()) return null;

            UsbDevice fallback = null;

            for (UsbDevice device : map.values()) {
                if (device == null) continue;

                if (isUvcDevice(device)) {
                    return device;
                }

                // 有些摄像头是复合设备，device class 不是 14，但接口里有视频类。
                if (fallback == null) {
                    fallback = device;
                }
            }

            return fallback;
        } catch (Throwable t) {
            Log.e(TAG, "findFirstUvcDeviceBySystemUsbManager failed", t);
            return null;
        }
    }

    private boolean isUvcDevice(UsbDevice device) {
        if (device == null) return false;

        try {
            if (device.getDeviceClass() == 14) return true;

            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                if (intf != null && intf.getInterfaceClass() == 14) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private boolean hasUsbPermission(UsbDevice device) {
        if (device == null) return false;
        try {
            UsbManager manager = usbManager != null ? usbManager : (UsbManager) getSystemService(Context.USB_SERVICE);
            return manager != null && manager.hasPermission(device);
        } catch (Throwable t) {
            return false;
        }
    }

    private void postOpenFailed(final long token, final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (token == openToken) {
                    opening = false;
                    openButton.setEnabled(true);
                    status(msg);
                }
            }
        });
    }

    private final ICameraHelper.StateCallback stateCallback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            status("检测到 USB 设备：" + safeDeviceName(device) + "。请点“打开UVC”。");
        }

        @Override
        public void onDeviceOpen(final UsbDevice device, boolean isFirstOpen) {
            postStatus("UVC 库设备已打开，后台 openCamera...");

            runUvcJob("openCamera", new Runnable() {
                @Override
                public void run() {
                    try {
                        cameraHelper.openCamera();
                    } catch (Throwable t) {
                        postOpenFailed(openToken, "openCamera 失败：" + shortError(t));
                    }
                }
            });
        }

        @Override
        public void onCameraOpen(final UsbDevice device) {
            postStatus("摄像头已打开，后台启动预览...");

            runUvcJob("startPreview", new Runnable() {
                @Override
                public void run() {
                    try {
                        cameraHelper.startPreview();
                        updateSupportedSizes();
                        updatePreviewSizeInfoOnMain();
                        addPreviewSurfaceIfReady();
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                opening = false;
                                openButton.setEnabled(true);
                                status("UVC 预览中：" + previewSizeText() + " / " + rotationText());
                            }
                        });
                    } catch (Throwable t) {
                        postOpenFailed(openToken, "startPreview 失败：" + shortError(t));
                    }
                }
            });
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            opening = false;
            postStatus("摄像头已关闭");
            openButtonPostEnabled(true);
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
            opening = false;
            postStatus("USB 设备已关闭");
            openButtonPostEnabled(true);
        }

        @Override
        public void onDetach(UsbDevice device) {
            opening = false;
            postStatus("USB 摄像头已拔出：" + safeDeviceName(device));
            openButtonPostEnabled(true);
        }

        @Override
        public void onCancel(final UsbDevice device) {
            // UVC 库仍可能误报 cancel。此处以系统 UsbManager 权限为准。
            if (hasUsbPermission(device)) {
                forceOpenAfterPermission(device, "UVC 库回调权限取消，但系统已有权限");
                return;
            }

            opening = false;
            postStatus("UVC 库回调权限取消，且系统检测 hasPermission=false：" + safeDeviceName(device));
            openButtonPostEnabled(true);
        }

        @Override
        public void onError(UsbDevice device, CameraException e) {
            opening = false;
            String msg = e != null ? e.getMessage() : "unknown";
            postStatus("UVC 错误：" + msg);
            openButtonPostEnabled(true);
        }
    };

    private void addPreviewSurfaceIfReady() {
        final Surface s = currentSurface;
        if (cameraHelper == null || s == null || !surfaceReady) return;

        runUvcJob("addSurface", new Runnable() {
            @Override
            public void run() {
                try {
                    if (cameraHelper != null && cameraHelper.isCameraOpened()) {
                        cameraHelper.addSurface(s, false);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                applyRotationAndScale();
                                status("UVC 预览中：" + previewSizeText() + " / " + rotationText());
                            }
                        });
                    }
                } catch (Throwable t) {
                    postStatus("添加预览 Surface 失败：" + shortError(t));
                    Log.e(TAG, "addSurface failed", t);
                }
            }
        });
    }

    private void safeRemoveSurface(Surface surface) {
        try {
            if (cameraHelper != null && surface != null) {
                cameraHelper.removeSurface(surface);
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateSupportedSizes() {
        supportedSizes.clear();
        selectedSizeIndex = -1;

        if (cameraHelper == null) return;

        try {
            List list = cameraHelper.getSupportedSizeList();
            if (list != null) {
                for (Object o : list) {
                    if (o instanceof Size) {
                        supportedSizes.add((Size) o);
                    }
                }
            }

            Collections.sort(supportedSizes, new Comparator<Size>() {
                @Override
                public int compare(Size a, Size b) {
                    int pa = a.width * a.height;
                    int pb = b.width * b.height;
                    return pb - pa;
                }
            });

            Size current = cameraHelper.getPreviewSize();
            if (current != null) {
                for (int i = 0; i < supportedSizes.size(); i++) {
                    Size size = supportedSizes.get(i);
                    if (size.width == current.width && size.height == current.height && size.type == current.type) {
                        selectedSizeIndex = i;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateSupportedSizes failed", t);
        }
    }

    private void updatePreviewSizeInfoOnMain() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Size size = cameraHelper != null ? cameraHelper.getPreviewSize() : null;
                    if (size != null) {
                        cameraView.setAspectRatio(size.width, size.height);
                        applyRotationAndScale();
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private String previewSizeText() {
        if (cameraHelper == null) return "unknown";
        try {
            Size size = cameraHelper.getPreviewSize();
            if (size != null) {
                return size.width + "x" + size.height + "@" + size.fps;
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private void switchToNextSize() {
        if (cameraHelper == null || !cameraHelper.isCameraOpened()) {
            status("摄像头还没打开，不能切分辨率");
            return;
        }

        if (supportedSizes.isEmpty()) {
            updateSupportedSizes();
        }

        if (supportedSizes.isEmpty()) {
            status("没有拿到摄像头支持的分辨率列表");
            return;
        }

        selectedSizeIndex = (selectedSizeIndex + 1) % supportedSizes.size();
        final Size target = supportedSizes.get(selectedSizeIndex);

        status("后台切换分辨率：" + target.width + "x" + target.height);

        runUvcJob("switchSize", new Runnable() {
            @Override
            public void run() {
                try {
                    final Surface s = currentSurface;
                    if (s != null) {
                        safeRemoveSurface(s);
                    }
                    cameraHelper.stopPreview();
                    cameraHelper.setPreviewSize(target);
                    cameraHelper.startPreview();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            cameraView.setAspectRatio(target.width, target.height);
                            applyRotationAndScale();
                        }
                    });
                    addPreviewSurfaceIfReady();
                } catch (Throwable t) {
                    postStatus("切换分辨率失败：" + shortError(t));
                }
            }
        });
    }

    private void cycleRotationMode() {
        if (rotateMode == -1) {
            rotateMode = 0;
        } else if (rotateMode == 0) {
            rotateMode = 90;
        } else if (rotateMode == 90) {
            rotateMode = 180;
        } else if (rotateMode == 180) {
            rotateMode = 270;
        } else {
            rotateMode = -1;
        }

        rotateButton.setText("旋转:" + rotationText());
        applyRotationAndScale();
        status("旋转模式：" + rotationText() + " / " + previewSizeText());
    }

    private String rotationText() {
        if (rotateMode == -1) return "自动";
        return rotateMode + "°";
    }

    private void applyRotationAndScale() {
        if (cameraView == null || previewContainer == null) return;

        int rotation = resolveRotation();
        currentRotation = rotation;

        cameraView.setRotation(rotation);

        previewContainer.post(new Runnable() {
            @Override
            public void run() {
                int w = previewContainer.getWidth();
                int h = previewContainer.getHeight();

                if (w <= 0 || h <= 0) return;

                if (currentRotation == 90 || currentRotation == 270) {
                    float scale = Math.min(w / (float) h, h / (float) w);
                    cameraView.setScaleX(scale);
                    cameraView.setScaleY(scale);
                } else {
                    cameraView.setScaleX(1f);
                    cameraView.setScaleY(1f);
                }
            }
        });
    }

    private int resolveRotation() {
        if (rotateMode != -1) {
            return rotateMode;
        }

        int displayW = previewContainer != null ? previewContainer.getWidth() : 0;
        int displayH = previewContainer != null ? previewContainer.getHeight() : 0;

        Size size = null;
        try {
            size = cameraHelper != null ? cameraHelper.getPreviewSize() : null;
        } catch (Throwable ignored) {
        }

        if (displayW <= 0 || displayH <= 0 || size == null) {
            return 0;
        }

        boolean screenLandscape = displayW >= displayH;
        boolean cameraLandscape = size.width >= size.height;

        if (screenLandscape != cameraLandscape) {
            return 90;
        }
        return 0;
    }

    private String safeDeviceName(UsbDevice device) {
        if (device == null) return "unknown";
        try {
            return device.getDeviceName()
                    + " VID:" + device.getVendorId()
                    + " PID:" + device.getProductId()
                    + " CLS:" + device.getDeviceClass()
                    + " IF:" + device.getInterfaceCount();
        } catch (Throwable t) {
            return String.valueOf(device);
        }
    }

    private String shortError(Throwable t) {
        if (t == null) return "unknown";
        String msg = t.getMessage();
        if (msg == null || msg.length() == 0) msg = t.toString();
        return t.getClass().getSimpleName() + " / " + msg;
    }

    private void status(final String s) {
        Log.d(TAG, s);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (statusText != null) statusText.setText(s);
        } else {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (statusText != null) statusText.setText(s);
                }
            });
        }
    }

    private void postStatus(final String s) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                status(s);
            }
        });
    }

    private void openButtonPostEnabled(final boolean enabled) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (openButton != null) openButton.setEnabled(enabled);
            }
        });
    }

    private void runUvcJob(String name, Runnable r) {
        if (workerHandler == null) {
            status("后台线程未就绪：" + name);
            return;
        }

        workerHandler.post(r);
    }

    @Override
    public void onClick(View v) {
        if (v == initButton) {
            startUvcEngineSafe();
        } else if (v == openButton) {
            openUvcAsync();
        } else if (v == closeButton) {
            opening = false;
            openToken++;
            openButton.setEnabled(true);
            if (cameraHelper != null) {
                runUvcJob("closeCamera", new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final Surface s = currentSurface;
                            if (s != null) safeRemoveSurface(s);
                            cameraHelper.closeCamera();
                            postStatus("已请求关闭摄像头");
                        } catch (Throwable t) {
                            postStatus("关闭失败：" + shortError(t));
                        }
                    }
                });
            } else {
                status("UVC 引擎未启动");
            }
        } else if (v == rotateButton) {
            cycleRotationMode();
        } else if (v == nextSizeButton) {
            switchToNextSize();
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}

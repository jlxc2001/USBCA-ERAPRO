
package com.jlxc.uvcai;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
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
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.herohan.uvcapp.CameraException;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.utils.UVCUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * USBCA-ERAPRO UVC v17 - 回调+伪Surface版
 *
 * v14/v15 权限、openCamera、startPreview 已经走通，但 SurfaceView/TextureView 都黑屏。
 * v16 不再靠 Surface/Texture 显示，改成 IFrameCallback 取帧：
 *
 * - 如果 frameCount 增长：说明底层有帧，问题是预览 Surface 渲染链路。
 * - 如果 frameCount 不增长：说明 startPreview 虽然显示成功，但底层没拿到帧，需要继续换 UVC 库或格式。
 */
public class MainActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "USBCA-UVC";
    private static final String ACTION_USB_PERMISSION = "com.jlxc.usbcaerapro.USB_PERMISSION";
    private static final int REQUEST_CAMERA_PERMISSION = 6258;

    private FrameLayout previewContainer;
    private ImageView imageView;
    private TextView statusText;
    private Button initButton;
    private Button openButton;
    private Button closeButton;
    private Button rotateButton;
    private Button nextSizeButton;

    private ICameraHelper cameraHelper;
    private UsbManager usbManager;
    private UsbDevice currentDevice;

    private final List<Size> supportedSizes = new ArrayList<Size>();
    private int selectedSizeIndex = -1;
    private Size currentPreviewSize;

    private int rotateMode = -1;

    private HandlerThread workerThread;
    private Handler workerHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean helperStarted = false;
    private volatile boolean opening = false;
    private long openToken = 0L;

    private volatile long frameCount = 0;
    private volatile long lastFrameTime = 0;
    private volatile String lastFrameInfo = "无帧";

    private Bitmap frameBitmap;
    private int[] argbBuffer;
    private byte[] frameBytes;

    // 有些 UVC 库只有在 addSurface() 之后才真正启动取流；
    // v16 只有 FrameCallback 没有 Surface，可能导致 startPreview 成功但不出帧。
    private SurfaceTexture dummySurfaceTexture;
    private Surface dummySurface;
    private boolean dummySurfaceAdded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();

        workerThread = new HandlerThread("uvc-worker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        handleUsbPermissionIntent(getIntent());
        handleUsbAttachIntent(getIntent());

        status("v17 回调+伪Surface版。先点“启动UVC引擎”。如果首次弹相机权限，必须允许。");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleUsbPermissionIntent(intent);
        handleUsbAttachIntent(intent);
    }

    @Override
    protected void onDestroy() {
        releaseCameraHelper();

        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }

        super.onDestroy();
    }

    private void handleUsbPermissionIntent(Intent intent) {
        if (intent == null || !ACTION_USB_PERMISSION.equals(intent.getAction())) {
            return;
        }

        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

        if (device == null) {
            opening = false;
            openButtonPostEnabled(true);
            status("USB 权限结果异常：device=null");
            return;
        }

        if (!granted) {
            opening = false;
            openButtonPostEnabled(true);
            status("系统返回 USB 权限被拒绝：" + safeDeviceName(device));
            return;
        }

        currentDevice = device;
        status("系统 USB 权限已授权，准备打开：" + safeDeviceName(device));
        selectDeviceAfterPermission(device);
    }

    private void handleUsbAttachIntent(Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            return;
        }

        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null) {
            currentDevice = device;
            status("系统已把 USB 设备交给本 App：" + safeDeviceName(device) + "。点“启动UVC引擎”再“打开UVC”。");
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("USBCA-ERAPRO UVC v17 - 回调+伪Surface版");
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

        imageView = new ImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewContainer.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(110, 255, 170));
        statusText.setTextSize(14f);
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setText("等待启动");
        statusText.setBackgroundColor(Color.argb(170, 0, 0, 0));

        previewContainer.addView(statusText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        ));

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
        rotateButton = makeButton("刷新状态");
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

    private boolean hasCameraRuntimePermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensureCameraPermissionThenStop(String reason) {
        if (hasCameraRuntimePermission()) {
            return true;
        }

        status("需要先允许相机权限，否则系统会拒绝 USB 摄像头：" + reason);

        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                status("相机权限已授权。现在点“启动UVC引擎”，再点“打开UVC”。");
            } else {
                status("相机权限被拒绝：系统会拒绝 USB Video Class 设备授权。");
            }
        }
    }

    private void startUvcEngineSafe() {
        if (!ensureCameraPermissionThenStop("启动UVC引擎")) {
            return;
        }

        if (helperStarted || cameraHelper != null) {
            status("UVC 引擎已启动。现在点“打开UVC”。");
            return;
        }

        status("正在初始化 UVC 引擎...");

        runUvcJob("init", new Runnable() {
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

    private void openUvcAsync() {
        if (!ensureCameraPermissionThenStop("打开UVC")) {
            opening = false;
            openButtonPostEnabled(true);
            return;
        }

        if (!helperStarted || cameraHelper == null) {
            status("请先点“启动UVC引擎”。");
            return;
        }

        if (opening) {
            status("正在打开中...");
            return;
        }

        opening = true;
        final long token = ++openToken;
        openButton.setEnabled(false);

        status("正在枚举 USB 摄像头...");

        runUvcJob("openUvcAsync", new Runnable() {
            @Override
            public void run() {
                UsbDevice device = currentDevice != null ? currentDevice : findFirstUvcDeviceBySystemUsbManager();
                if (device == null) {
                    postOpenFailed(token, "系统 USB 列表里没找到 UVC 摄像头。");
                    return;
                }

                currentDevice = device;

                if (hasUsbPermission(device)) {
                    postStatus("系统已有 USB 权限，准备打开：" + safeDeviceName(device));
                    selectDeviceAfterPermission(device);
                } else {
                    postStatus("准备请求系统 USB 权限：" + safeDeviceName(device));
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            requestUsbPermission(currentDevice);
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
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }

            Intent intent = new Intent(this, MainActivity.class);
            intent.setAction(ACTION_USB_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent pendingIntent = PendingIntent.getActivity(this, 6257, intent, flags);
            usbManager.requestPermission(device, pendingIntent);

            status("已请求 USB 权限，请点允许/确定。");
        } catch (Throwable t) {
            opening = false;
            openButtonPostEnabled(true);
            status("请求 USB 权限失败：" + shortError(t));
            Log.e(TAG, "requestUsbPermission failed", t);
        }
    }

    private void selectDeviceAfterPermission(final UsbDevice device) {
        runUvcJob("selectDeviceAfterPermission", new Runnable() {
            @Override
            public void run() {
                try {
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
        postStatus(reason + "，尝试直接 openCamera...");
        runUvcJob("forceOpen", new Runnable() {
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

    private final ICameraHelper.StateCallback stateCallback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            status("检测到 USB 设备：" + safeDeviceName(device));
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
            postStatus("摄像头已打开，准备 FrameCallback...");

            runUvcJob("startFrameCallback", new Runnable() {
                @Override
                public void run() {
                    try {
                        final Size safeSize = chooseSafePreviewSize();
                        currentPreviewSize = safeSize;

                        if (safeSize != null) {
                            cameraHelper.setPreviewSize(safeSize);
                            postStatus("已选择回调分辨率：" + safeSize.width + "x" + safeSize.height);
                            prepareDummySurface(safeSize);
                        }

                        resetFrameCounter();

                        cameraHelper.setFrameCallback(new IFrameCallback() {
                            @Override
                            public void onFrame(ByteBuffer frame) {
                                onUvcFrame(frame);
                            }
                        }, UVCCamera.PIXEL_FORMAT_BGR);

                        cameraHelper.startPreview();

                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                opening = false;
                                openButton.setEnabled(true);
                                status("FrameCallback 已启动：" + previewSizeText() + "。等待帧...");
                            }
                        }, 300);
                    } catch (Throwable t) {
                        postOpenFailed(openToken, "FrameCallback/startPreview 失败：" + shortError(t));
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
        public void onCancel(UsbDevice device) {
            if (hasUsbPermission(device)) {
                forceOpenAfterPermission(device, "UVC 库回调取消但系统已有权限");
                return;
            }

            opening = false;
            postStatus("USB 权限被取消/拒绝：" + safeDeviceName(device));
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


    private void prepareDummySurface(Size size) {
        releaseDummySurface();

        if (size == null) {
            return;
        }

        try {
            // 使用一个不显示到屏幕的 SurfaceTexture，专门喂给 UVC native 预览线程。
            dummySurfaceTexture = new SurfaceTexture(10);
            dummySurfaceTexture.setDefaultBufferSize(size.width, size.height);
            dummySurface = new Surface(dummySurfaceTexture);

            if (cameraHelper != null) {
                cameraHelper.addSurface(dummySurface, false);
                dummySurfaceAdded = true;
                postStatus("伪Surface已加入：" + size.width + "x" + size.height + "，继续启动FrameCallback");
            }
        } catch (Throwable t) {
            dummySurfaceAdded = false;
            postStatus("伪Surface创建/加入失败：" + shortError(t));
            Log.e(TAG, "prepareDummySurface failed", t);
        }
    }

    private void releaseDummySurface() {
        try {
            if (cameraHelper != null && dummySurface != null && dummySurfaceAdded) {
                cameraHelper.removeSurface(dummySurface);
            }
        } catch (Throwable ignored) {
        }

        dummySurfaceAdded = false;

        try {
            if (dummySurface != null) {
                dummySurface.release();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (dummySurfaceTexture != null) {
                dummySurfaceTexture.release();
            }
        } catch (Throwable ignored) {
        }

        dummySurface = null;
        dummySurfaceTexture = null;
    }

    private void resetFrameCounter() {
        frameCount = 0;
        lastFrameTime = 0;
        lastFrameInfo = "无帧";
    }

    private void onUvcFrame(ByteBuffer frame) {
        if (frame == null || currentPreviewSize == null) {
            return;
        }

        final int width = currentPreviewSize.width;
        final int height = currentPreviewSize.height;
        final int need = width * height * 3;

        int len = frame.remaining();
        if (len <= 0) {
            len = frame.capacity();
        }

        frameCount++;
        lastFrameTime = System.currentTimeMillis();
        lastFrameInfo = "frame=" + frameCount + " len=" + len + " size=" + width + "x" + height;

        // 每 5 帧画一次，避免车机太卡。
        if (frameCount % 5 != 0) {
            return;
        }

        if (len < need) {
            final String info = lastFrameInfo + "，长度不足BGR(" + need + ")";
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    status(info);
                }
            });
            return;
        }

        try {
            if (frameBytes == null || frameBytes.length < need) {
                frameBytes = new byte[need];
            }
            if (argbBuffer == null || argbBuffer.length < width * height) {
                argbBuffer = new int[width * height];
            }

            ByteBuffer dup = frame.duplicate();
            dup.clear();
            dup.get(frameBytes, 0, need);

            int src = 0;
            int dst = 0;

            for (int i = 0; i < width * height; i++) {
                int b = frameBytes[src++] & 0xff;
                int g = frameBytes[src++] & 0xff;
                int r = frameBytes[src++] & 0xff;
                argbBuffer[dst++] = 0xff000000 | (r << 16) | (g << 8) | b;
            }

            if (frameBitmap == null || frameBitmap.getWidth() != width || frameBitmap.getHeight() != height) {
                frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }

            frameBitmap.setPixels(argbBuffer, 0, width, 0, 0, width, height);

            final Bitmap bitmapToShow = frameBitmap;
            final String infoToShow = lastFrameInfo + "，已绘制Bitmap";

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    imageView.setImageBitmap(bitmapToShow);
                    status(infoToShow);
                }
            });
        } catch (Throwable t) {
            final String err = "处理帧失败：" + shortError(t) + " / " + lastFrameInfo;
            Log.e(TAG, err, t);
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    status(err);
                }
            });
        }
    }

    private Size chooseSafePreviewSize() {
        updateSupportedSizes();

        if (supportedSizes.isEmpty()) {
            return null;
        }

        int[][] preferred = new int[][]{
                {640, 480},
                {800, 600},
                {960, 540},
                {1024, 768},
                {1280, 720},
                {1280, 960}
        };

        for (int[] wh : preferred) {
            for (Size s : supportedSizes) {
                if (s.width == wh[0] && s.height == wh[1]) {
                    selectedSizeIndex = supportedSizes.indexOf(s);
                    return s;
                }
            }
        }

        Size best = null;
        int bestPixels = 0;
        for (Size s : supportedSizes) {
            int pixels = s.width * s.height;
            if (s.width <= 1280 && s.height <= 960 && pixels > bestPixels) {
                best = s;
                bestPixels = pixels;
            }
        }

        if (best != null) {
            selectedSizeIndex = supportedSizes.indexOf(best);
            return best;
        }

        Size smallest = supportedSizes.get(0);
        int smallestPixels = smallest.width * smallest.height;
        for (Size s : supportedSizes) {
            int pixels = s.width * s.height;
            if (pixels < smallestPixels) {
                smallest = s;
                smallestPixels = pixels;
            }
        }
        selectedSizeIndex = supportedSizes.indexOf(smallest);
        return smallest;
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
        currentPreviewSize = target;

        status("切换 FrameCallback 分辨率：" + target.width + "x" + target.height);

        runUvcJob("switchSize", new Runnable() {
            @Override
            public void run() {
                try {
                    try {
                        cameraHelper.stopPreview();
                    } catch (Throwable ignored) {
                    }

                    resetFrameCounter();
                    cameraHelper.setPreviewSize(target);
                    prepareDummySurface(target);
                    cameraHelper.setFrameCallback(new IFrameCallback() {
                        @Override
                        public void onFrame(ByteBuffer frame) {
                            onUvcFrame(frame);
                        }
                    }, UVCCamera.PIXEL_FORMAT_BGR);
                    cameraHelper.startPreview();
                } catch (Throwable t) {
                    postStatus("切换分辨率失败：" + shortError(t));
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
                if (isUvcDevice(device)) return device;
                if (fallback == null) fallback = device;
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

    private String rotationText() {
        return "frame=" + frameCount;
    }

    private void refreshFrameStatus() {
        long now = System.currentTimeMillis();
        long age = lastFrameTime == 0 ? -1 : now - lastFrameTime;
        status("状态：" + previewSizeText() + " / " + lastFrameInfo + " / lastAge=" + age + "ms");
    }

    private void releaseCameraHelper() {
        try {
            if (cameraHelper != null) {
                final ICameraHelper helper = cameraHelper;
                runUvcJob("release", new Runnable() {
                    @Override
                    public void run() {
                        try {
                            helper.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_BGR);
                        } catch (Throwable ignored) {
                        }
                        try {
                            releaseDummySurface();
                        } catch (Throwable ignored) {
                        }
                        try {
                            helper.stopPreview();
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
        supportedSizes.clear();
        selectedSizeIndex = -1;
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
                runUvcJob("close", new Runnable() {
                    @Override
                    public void run() {
                        try {
                            cameraHelper.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_BGR);
                        } catch (Throwable ignored) {
                        }
                        try {
                            releaseDummySurface();
                        } catch (Throwable ignored) {
                        }
                        try {
                            cameraHelper.stopPreview();
                        } catch (Throwable ignored) {
                        }
                        try {
                            cameraHelper.closeCamera();
                        } catch (Throwable t) {
                            postStatus("关闭失败：" + shortError(t));
                            return;
                        }
                        postStatus("已请求关闭摄像头");
                    }
                });
            } else {
                status("UVC 引擎未启动");
            }
        } else if (v == rotateButton) {
            refreshFrameStatus();
        } else if (v == nextSizeButton) {
            switchToNextSize();
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}

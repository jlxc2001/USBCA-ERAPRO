
package com.jlxc.uvcai;

import android.app.Activity;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
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
import com.serenegiant.widget.AspectRatioSurfaceView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

// 纯 UVC 直连预览 App：不录屏，不调用第三方 USB Camera App，不走 Camera2。
// safeopen 版：进入页面不自动枚举/打开摄像头，避免部分车机 USB 栈在主线程卡死。
public class MainActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "UVCAI-UVC";

    private FrameLayout previewContainer;
    private AspectRatioSurfaceView cameraView;
    private TextView statusText;
    private Button initButton;
    private Button openButton;
    private Button closeButton;
    private Button rotateButton;
    private Button nextSizeButton;

    private ICameraHelper cameraHelper;
    private UsbDevice currentDevice;
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
    private boolean opening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        workerThread = new HandlerThread("uvc-worker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        status("已进入 UVC 页面。为避免车机卡死，本版不会自动打开摄像头。先点“启动UVC引擎”，再点“打开UVC”。");
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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("UVCAI UVC 直连 - 安全启动版");
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
        statusText.setBackgroundColor(Color.argb(140, 0, 0, 0));

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
            if (cameraHelper != null && currentSurface != null) {
                safeRemoveSurface(currentSurface);
            }
            currentSurface = null;
        }
    };

    private void startUvcEngineSafe() {
        if (helperStarted || cameraHelper != null) {
            status("UVC 引擎已启动。现在可以点“打开UVC”。");
            return;
        }

        status("正在启动 UVC 引擎... 如果这里卡住，说明当前库初始化不兼容该车机系统。");

        // 放主线程延后执行，避免刚进入 Activity 时和 Surface 创建抢资源。
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    cameraHelper = new CameraHelper();
                    cameraHelper.setStateCallback(stateCallback);
                    helperStarted = true;
                    status("UVC 引擎启动完成。请插入摄像头后点“打开UVC”。");
                } catch (Throwable t) {
                    status("UVC 引擎启动失败：" + t.getClass().getSimpleName() + " / " + t.getMessage());
                    Log.e(TAG, "startUvcEngineSafe failed", t);
                }
            }
        }, 300);
    }

    private void releaseCameraHelper() {
        try {
            if (cameraHelper != null) {
                if (currentSurface != null) {
                    safeRemoveSurface(currentSurface);
                }
                try {
                    cameraHelper.closeCamera();
                } catch (Throwable ignored) {
                }
                cameraHelper.release();
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

    private void autoOpenFirstDeviceSafe() {
        if (!helperStarted || cameraHelper == null) {
            status("请先点“启动UVC引擎”。");
            return;
        }

        if (opening) {
            status("正在打开中，请稍等...");
            return;
        }

        opening = true;
        status("正在后台枚举 USB 摄像头...");

        if (workerHandler == null) {
            status("后台线程未就绪");
            opening = false;
            return;
        }

        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                UsbDevice found = null;
                String error = null;

                try {
                    List list = cameraHelper.getDeviceList();
                    if (list != null && !list.isEmpty()) {
                        found = (UsbDevice) list.get(0);
                    }
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName() + " / " + t.getMessage();
                    Log.e(TAG, "getDeviceList failed", t);
                }

                final UsbDevice resultDevice = found;
                final String resultError = error;

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (resultDevice == null) {
                            opening = false;
                            if (resultError != null) {
                                status("枚举 USB 摄像头失败：" + resultError);
                            } else {
                                status("未检测到 UVC 摄像头。请确认已插入、车机 USB Host 正常、并且系统弹过 USB 权限。");
                            }
                            return;
                        }

                        selectDeviceSafe(resultDevice);
                    }
                });
            }
        });

        // 10 秒兜底，避免一直显示“打开中”
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (opening) {
                    opening = false;
                    status("打开超时。请拔插摄像头后重试，或点“关闭”再点“启动UVC引擎”。");
                }
            }
        }, 10000);
    }

    private void selectDeviceSafe(UsbDevice device) {
        currentDevice = device;
        status("选择设备：" + safeDeviceName(device) + "，等待 USB 权限弹窗...");

        try {
            cameraHelper.selectDevice(device);
        } catch (Throwable t) {
            opening = false;
            status("selectDevice 失败：" + t.getClass().getSimpleName() + " / " + t.getMessage());
            Log.e(TAG, "selectDevice failed", t);
        }
    }

    private final ICameraHelper.StateCallback stateCallback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            status("检测到 UVC 设备：" + safeDeviceName(device) + "。请点“打开UVC”。");
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            status("USB 权限已允许，正在打开摄像头...");
            try {
                cameraHelper.openCamera();
            } catch (Throwable t) {
                opening = false;
                status("openCamera 失败：" + t.getClass().getSimpleName() + " / " + t.getMessage());
                Log.e(TAG, "openCamera failed", t);
            }
        }

        @Override
        public void onCameraOpen(UsbDevice device) {
            status("摄像头已打开，启动预览...");
            try {
                cameraHelper.startPreview();
                updateSupportedSizes();
                updatePreviewSizeInfo();
                addPreviewSurfaceIfReady();
                opening = false;
            } catch (Throwable t) {
                opening = false;
                status("启动预览失败：" + t.getClass().getSimpleName() + " / " + t.getMessage());
                Log.e(TAG, "startPreview failed", t);
            }
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            opening = false;
            status("摄像头已关闭");
            if (currentSurface != null) {
                safeRemoveSurface(currentSurface);
            }
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
            opening = false;
            status("USB 设备已关闭");
        }

        @Override
        public void onDetach(UsbDevice device) {
            opening = false;
            status("UVC 摄像头已拔出：" + safeDeviceName(device));
        }

        @Override
        public void onCancel(UsbDevice device) {
            opening = false;
            status("USB 权限被取消：" + safeDeviceName(device));
        }

        @Override
        public void onError(UsbDevice device, CameraException e) {
            opening = false;
            String msg = e != null ? e.getMessage() : "unknown";
            status("UVC 错误：" + msg);
        }
    };

    private void addPreviewSurfaceIfReady() {
        if (cameraHelper == null || currentSurface == null || !surfaceReady) return;

        try {
            if (cameraHelper.isCameraOpened()) {
                cameraHelper.addSurface(currentSurface, false);
                applyRotationAndScale();
                status("UVC 预览中：" + previewSizeText() + " / " + rotationText());
            }
        } catch (Throwable t) {
            status("添加预览 Surface 失败：" + t.getClass().getSimpleName() + " / " + t.getMessage());
            Log.e(TAG, "addSurface failed", t);
        }
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
                    Size s = supportedSizes.get(i);
                    if (s.width == current.width && s.height == current.height && s.type == current.type) {
                        selectedSizeIndex = i;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateSupportedSizes failed", t);
        }
    }

    private void updatePreviewSizeInfo() {
        if (cameraHelper == null) return;

        try {
            Size size = cameraHelper.getPreviewSize();
            if (size != null) {
                cameraView.setAspectRatio(size.width, size.height);
                applyRotationAndScale();
            }
        } catch (Throwable ignored) {
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

        status("切换分辨率：" + target.width + "x" + target.height + "，如果黑屏请再点一次");

        try {
            if (currentSurface != null) {
                safeRemoveSurface(currentSurface);
            }
            cameraHelper.stopPreview();
            cameraHelper.setPreviewSize(target);
            cameraHelper.startPreview();
            cameraView.setAspectRatio(target.width, target.height);
            addPreviewSurfaceIfReady();
        } catch (Throwable t) {
            status("切换分辨率失败：" + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
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
            return device.getDeviceName() + " VID:" + device.getVendorId() + " PID:" + device.getProductId();
        } catch (Throwable t) {
            return String.valueOf(device);
        }
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

    @Override
    public void onClick(View v) {
        if (v == initButton) {
            startUvcEngineSafe();
        } else if (v == openButton) {
            autoOpenFirstDeviceSafe();
        } else if (v == closeButton) {
            opening = false;
            if (cameraHelper != null) {
                try {
                    cameraHelper.closeCamera();
                    status("已请求关闭摄像头");
                } catch (Throwable t) {
                    status("关闭失败：" + t.getClass().getSimpleName() + " / " + t.getMessage());
                }
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

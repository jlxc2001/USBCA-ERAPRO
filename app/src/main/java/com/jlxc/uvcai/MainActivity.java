package com.jlxc.uvcai;

import android.app.Activity;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
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
public class MainActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "UVCAI-UVC";

    private FrameLayout previewContainer;
    private AspectRatioSurfaceView cameraView;
    private TextView statusText;
    private Button openButton;
    private Button closeButton;
    private Button rotateButton;
    private Button nextSizeButton;

    private ICameraHelper cameraHelper;
    private UsbDevice currentDevice;
    private final List<Size> supportedSizes = new ArrayList<>();
    private int selectedSizeIndex = -1;

    // -1 = 自动；0/90/180/270 = 手动
    private int rotateMode = -1;
    private int currentRotation = 0;
    private boolean surfaceReady = false;
    private Surface currentSurface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        initCameraHelper();
    }

    @Override
    protected void onStop() {
        super.onStop();
        releaseCameraHelper();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("UVCAI UVC 直连");
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
        statusText.setText("插入 UVC 摄像头，或点击“打开UVC”。");
        statusText.setBackgroundColor(Color.argb(120, 0, 0, 0));

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

        openButton = makeButton("打开UVC");
        closeButton = makeButton("关闭");
        rotateButton = makeButton("旋转:自动");
        nextSizeButton = makeButton("分辨率");

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
        b.setTextSize(14f);
        b.setAllCaps(false);
        b.setOnClickListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
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
                try {
                    cameraHelper.removeSurface(currentSurface);
                } catch (Exception ignored) {
                }
            }
            currentSurface = null;
        }
    };

    private void initCameraHelper() {
        if (cameraHelper != null) return;

        cameraHelper = new CameraHelper();
        cameraHelper.setStateCallback(stateCallback);

        status("UVC 引擎已启动。插入摄像头会自动请求权限。");

        // 如果摄像头已经插着，主动枚举一次。
        autoOpenFirstDevice();
    }

    private void releaseCameraHelper() {
        if (cameraHelper != null) {
            try {
                cameraHelper.release();
            } catch (Exception ignored) {
            }
            cameraHelper = null;
        }
        currentDevice = null;
        supportedSizes.clear();
        selectedSizeIndex = -1;
    }

    private void autoOpenFirstDevice() {
        if (cameraHelper == null) return;

        try {
            List list = cameraHelper.getDeviceList();
            if (list != null && !list.isEmpty()) {
                UsbDevice device = (UsbDevice) list.get(0);
                selectDevice(device);
            } else {
                status("未检测到 UVC 摄像头。请插入 USB 摄像头。");
            }
        } catch (Exception e) {
            status("枚举 USB 摄像头失败：" + e.getMessage());
        }
    }

    private void selectDevice(UsbDevice device) {
        currentDevice = device;
        status("选择设备：" + safeDeviceName(device) + "，等待 USB 权限...");
        cameraHelper.selectDevice(device);
    }

    private final ICameraHelper.StateCallback stateCallback = new ICameraHelper.StateCallback() {
        @Override
        public void onAttach(UsbDevice device) {
            status("检测到 UVC 设备：" + safeDeviceName(device));
            selectDevice(device);
        }

        @Override
        public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
            status("USB 权限已允许，正在打开摄像头...");
            try {
                cameraHelper.openCamera();
            } catch (Exception e) {
                status("openCamera 失败：" + e.getMessage());
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
            } catch (Exception e) {
                status("启动预览失败：" + e.getMessage());
            }
        }

        @Override
        public void onCameraClose(UsbDevice device) {
            status("摄像头已关闭");
            if (cameraHelper != null && currentSurface != null) {
                try {
                    cameraHelper.removeSurface(currentSurface);
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void onDeviceClose(UsbDevice device) {
            status("USB 设备已关闭");
        }

        @Override
        public void onDetach(UsbDevice device) {
            status("UVC 摄像头已拔出：" + safeDeviceName(device));
        }

        @Override
        public void onCancel(UsbDevice device) {
            status("USB 权限被取消：" + safeDeviceName(device));
        }

        @Override
        public void onError(UsbDevice device, CameraException e) {
            String msg = e != null ? e.getMessage() : "unknown";
            status("UVC 错误：" + msg + "。建议重插摄像头后再试。");
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
        } catch (Exception e) {
            status("添加预览 Surface 失败：" + e.getMessage());
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
        } catch (Exception e) {
            Log.w(TAG, "updateSupportedSizes failed", e);
        }
    }

    private void updatePreviewSizeInfo() {
        if (cameraHelper == null) return;

        Size size = cameraHelper.getPreviewSize();
        if (size != null) {
            cameraView.setAspectRatio(size.width, size.height);
            applyRotationAndScale();
        }
    }

    private String previewSizeText() {
        if (cameraHelper == null) return "unknown";
        try {
            Size size = cameraHelper.getPreviewSize();
            if (size != null) {
                return size.width + "x" + size.height + "@" + size.fps;
            }
        } catch (Exception ignored) {
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

        status("切换分辨率：" + target.width + "x" + target.height + "，如果黑屏请再点一次或重开摄像头");

        try {
            if (currentSurface != null) {
                cameraHelper.removeSurface(currentSurface);
            }
            cameraHelper.stopPreview();
            cameraHelper.setPreviewSize(target);
            cameraHelper.startPreview();
            cameraView.setAspectRatio(target.width, target.height);
            addPreviewSurfaceIfReady();
        } catch (Exception e) {
            status("切换分辨率失败：" + e.getMessage());
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

        // 简单保守处理：旋转 90/270 时缩小到完整可见，避免画面被裁切。
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
        } catch (Exception ignored) {
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
        } catch (Exception e) {
            return String.valueOf(device);
        }
    }

    private void status(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(s);
                Log.d(TAG, s);
            }
        });
    }

    @Override
    public void onClick(View v) {
        if (v == openButton) {
            autoOpenFirstDevice();
        } else if (v == closeButton) {
            if (cameraHelper != null) {
                try {
                    cameraHelper.closeCamera();
                } catch (Exception e) {
                    status("关闭失败：" + e.getMessage());
                }
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

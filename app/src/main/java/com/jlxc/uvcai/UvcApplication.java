package com.jlxc.uvcai;

import android.app.Application;

// 双 ABI 懒加载版：
// 不在 Application.onCreate 里初始化 UVC，避免部分 64 位系统一打开 App 就卡住。
// UVC 初始化移动到 MainActivity 的“启动UVC引擎”按钮里。
public class UvcApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
    }
}

package com.jlxc.uvcai;

import android.app.Application;

import com.serenegiant.utils.UVCUtils;

public class UvcApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        UVCUtils.init(this);
    }
}

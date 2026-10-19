package com.tencentx.pinyin;

import android.app.Application;

import com.tencentx.pinyin.sync.SyncScheduler;
import com.tencentx.pinyin.util.theme.NightMode;

public class App
        extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        NightMode.init(this);
        SyncScheduler.rescheduleAll(this);
    }
}

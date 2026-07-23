package com.tencent.mm.plugin.voip.widget;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class VoipForegroundService extends Service {

    public VoipForegroundService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.helper.urlfeeder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent intent) {
        OperationLog.init(c);
        OperationLog.event("BOOT","RECEIVE","START",OperationLog.intent(intent));
        if (intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)) {
            SharedPreferences p = c.getSharedPreferences("pf", Context.MODE_PRIVATE);
            if (p.getBoolean("guard_on", false)) {
                try {
                    Intent s = new Intent(c, GuardService.class);
                    s.setAction("start");
                    if (android.os.Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
                    else c.startService(s);
                    OperationLog.event("BOOT","START_GUARD","OK",OperationLog.intent(s));
                } catch (Exception e) { OperationLog.event("BOOT","START_GUARD","FAIL",OperationLog.stack(e)); }
            }
        }
    }
}

package com.helper.urlfeeder;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;

/** Service base that records lifecycle and outbound component operations. */
public abstract class LoggedService extends Service {
    private String source() { return getClass().getSimpleName(); }

    @Override public void onCreate() {
        OperationLog.init(this);
        super.onCreate();
        OperationLog.event("SERVICE", "ON_CREATE", "OK", "service=" + source());
    }

    @Override public void onDestroy() {
        OperationLog.event("SERVICE", "ON_DESTROY", "OK", "service=" + source());
        super.onDestroy();
    }

    @Override public void startActivity(Intent intent) {
        OperationLog.Span span = OperationLog.begin("INTENT", "START_ACTIVITY",
                "source=" + source() + "\n" + OperationLog.intent(intent));
        try { super.startActivity(intent); OperationLog.result(span, "DISPATCHED", "framework accepted startActivity request"); }
        catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }

    @Override public ComponentName startService(Intent service) {
        OperationLog.Span span = OperationLog.begin("SERVICE", "START_SERVICE",
                "source=" + source() + "\n" + OperationLog.intent(service));
        try {
            ComponentName name = super.startService(service);
            OperationLog.result(span, "DISPATCHED", "component=" + (name == null ? "" : name.flattenToShortString())); return name;
        } catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }

    @Override public ComponentName startForegroundService(Intent service) {
        OperationLog.Span span = OperationLog.begin("SERVICE", "START_FOREGROUND_SERVICE",
                "source=" + source() + "\n" + OperationLog.intent(service));
        try {
            ComponentName name = super.startForegroundService(service);
            OperationLog.result(span, "DISPATCHED", "component=" + (name == null ? "" : name.flattenToShortString())); return name;
        } catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }

    @Override public boolean stopService(Intent service) {
        OperationLog.Span span = OperationLog.begin("SERVICE", "STOP_SERVICE",
                "source=" + source() + "\n" + OperationLog.intent(service));
        try { boolean value = super.stopService(service); OperationLog.ok(span, "stopped=" + value); return value; }
        catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }

    @Override public boolean bindService(Intent service, ServiceConnection connection, int flags) {
        OperationLog.Span span = OperationLog.begin("SERVICE", "BIND_SERVICE",
                "source=" + source() + " flags=0x" + Integer.toHexString(flags) + "\n" + OperationLog.intent(service));
        try { boolean value = super.bindService(service, connection, flags); OperationLog.ok(span, "bound=" + value); return value; }
        catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }

    @Override public void unbindService(ServiceConnection connection) {
        OperationLog.Span span = OperationLog.begin("SERVICE", "UNBIND_SERVICE", "source=" + source());
        try { super.unbindService(connection); OperationLog.ok(span, "unbound=true"); }
        catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }
}

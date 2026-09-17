package com.helper.urlfeeder;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;

import java.util.Arrays;

/** Activity base that records lifecycle, Intent, permission and service operations. */
public abstract class LoggedActivity extends Activity {
    private String source() { return getClass().getSimpleName(); }

    @Override protected void onCreate(Bundle state) {
        OperationLog.init(this);
        OperationLog.event("ACTIVITY", "ON_CREATE", "OK",
                "activity=" + source() + " restored=" + (state != null) + "\n" + OperationLog.intent(getIntent()));
        super.onCreate(state);
    }

    @Override protected void onStart() {
        super.onStart(); OperationLog.event("ACTIVITY", "ON_START", "OK", "activity=" + source());
    }

    @Override protected void onResume() {
        super.onResume(); OperationLog.event("ACTIVITY", "ON_RESUME", "OK", "activity=" + source());
    }

    @Override protected void onPause() {
        OperationLog.event("ACTIVITY", "ON_PAUSE", "OK", "activity=" + source()); super.onPause();
    }

    @Override protected void onStop() {
        OperationLog.event("ACTIVITY", "ON_STOP", "OK", "activity=" + source()); super.onStop();
    }

    @Override protected void onDestroy() {
        OperationLog.event("ACTIVITY", "ON_DESTROY", "OK",
                "activity=" + source() + " finishing=" + isFinishing()); super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        OperationLog.event("ACTIVITY", "ON_NEW_INTENT", "OK",
                "activity=" + source() + "\n" + OperationLog.intent(intent));
        super.onNewIntent(intent);
    }

    @Override public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        OperationLog.Span span = OperationLog.begin("INTENT", "START_ACTIVITY",
                "source=" + source() + " requestCode=" + requestCode + "\n" + OperationLog.intent(intent));
        try {
            super.startActivityForResult(intent, requestCode, options);
            OperationLog.result(span, "DISPATCHED", "framework accepted startActivity request");
        } catch (RuntimeException error) {
            OperationLog.fail(span, error); throw error;
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        String result=resultCode==RESULT_OK?"OK":(resultCode==RESULT_CANCELED?"CANCELED":"RESULT_"+resultCode);
        OperationLog.event("INTENT", "ACTIVITY_RESULT", result,
                "target=" + source() + " requestCode=" + requestCode + " resultCode=" + resultCode
                        + "\n" + OperationLog.intent(data));
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        int granted=0;
        if(results!=null) for(int value:results) if(value==android.content.pm.PackageManager.PERMISSION_GRANTED) granted++;
        String result=results==null||results.length==0?"CANCELED":(granted==results.length?"OK":(granted==0?"DENIED":"PARTIAL"));
        OperationLog.event("PERMISSION", "REQUEST_RESULT", result,
                "activity=" + source() + " requestCode=" + requestCode
                        + " permissions=" + Arrays.toString(permissions) + " results=" + Arrays.toString(results));
        super.onRequestPermissionsResult(requestCode, permissions, results);
    }

    @Override public ComponentName startService(Intent service) {
        OperationLog.Span span = OperationLog.begin("SERVICE", "START_SERVICE",
                "source=" + source() + "\n" + OperationLog.intent(service));
        try {
            ComponentName name = super.startService(service);
            OperationLog.result(span, "DISPATCHED", "component=" + (name == null ? "" : name.flattenToShortString()));
            return name;
        } catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }

    @Override public ComponentName startForegroundService(Intent service) {
        OperationLog.Span span = OperationLog.begin("SERVICE", "START_FOREGROUND_SERVICE",
                "source=" + source() + "\n" + OperationLog.intent(service));
        try {
            ComponentName name = super.startForegroundService(service);
            OperationLog.result(span, "DISPATCHED", "component=" + (name == null ? "" : name.flattenToShortString()));
            return name;
        } catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }

    @Override public boolean stopService(Intent service) {
        OperationLog.Span span = OperationLog.begin("SERVICE", "STOP_SERVICE",
                "source=" + source() + "\n" + OperationLog.intent(service));
        try {
            boolean stopped = super.stopService(service); OperationLog.ok(span, "stopped=" + stopped); return stopped;
        } catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }

    @Override public boolean bindService(Intent service, ServiceConnection connection, int flags) {
        OperationLog.Span span = OperationLog.begin("SERVICE", "BIND_SERVICE",
                "source=" + source() + " flags=0x" + Integer.toHexString(flags) + "\n" + OperationLog.intent(service));
        try {
            boolean bound = super.bindService(service, connection, flags); OperationLog.ok(span, "bound=" + bound); return bound;
        } catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }

    @Override public void unbindService(ServiceConnection connection) {
        OperationLog.Span span = OperationLog.begin("SERVICE", "UNBIND_SERVICE", "source=" + source());
        try { super.unbindService(connection); OperationLog.ok(span, "unbound=true"); }
        catch (RuntimeException error) { OperationLog.fail(span, error); throw error; }
    }
}

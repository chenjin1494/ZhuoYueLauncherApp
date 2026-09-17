package com.helper.urlfeeder;

import android.app.Application;

/** Installs the process-wide operation logger before any component starts. */
public class UrlFeederApp extends Application {
    private final rikka.shizuku.Shizuku.OnBinderReceivedListener binderReceived =
            new rikka.shizuku.Shizuku.OnBinderReceivedListener() {
                public void onBinderReceived() { OperationLog.event("SHIZUKU","BINDER_RECEIVED","OK","binder available"); }
            };
    private final rikka.shizuku.Shizuku.OnBinderDeadListener binderDead =
            new rikka.shizuku.Shizuku.OnBinderDeadListener() {
                public void onBinderDead() { OperationLog.event("SHIZUKU","BINDER_DEAD","WARN","binder disconnected"); }
            };
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener permissionResult =
            new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    OperationLog.event("SHIZUKU","PERMISSION_RESULT",
                            grantResult == 0 ? "OK" : "DENIED",
                            "requestCode="+requestCode+" grantResult="+grantResult);
                }
            };

    @Override public void onCreate() {
        super.onCreate();
        OperationLog.init(this);
        OperationLog.event("PROCESS", "APPLICATION_CREATE", "OK",
                "package=" + getPackageName() + " pid=" + android.os.Process.myPid());
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(binderReceived);
            rikka.shizuku.Shizuku.addBinderDeadListener(binderDead);
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionResult);
        } catch (Throwable error) {
            OperationLog.event("SHIZUKU","REGISTER_LISTENERS","FAIL",OperationLog.stack(error));
        }
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable error) {
                OperationLog.event("ERROR", "UNCAUGHT_EXCEPTION", "FAIL",
                        "thread=" + (thread == null ? "?" : thread.getName()) + "\n" + OperationLog.stack(error));
                if (previous != null) previous.uncaughtException(thread, error);
                else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }
}

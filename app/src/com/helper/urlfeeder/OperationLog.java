package com.helper.urlfeeder;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;

/** Process-wide structured operation logging facade. */
public final class OperationLog {
    private static volatile Context app;
    private static final java.util.concurrent.atomic.AtomicLong SPAN_IDS =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    private OperationLog() {}

    public static void init(Context context) {
        if (context != null) app = context.getApplicationContext();
    }

    public static final class Span {
        final long id;
        final String category;
        final String action;
        final String startDetail;
        final long started;

        Span(String category, String action, String detail) {
            this.id = SPAN_IDS.incrementAndGet();
            this.category = safe(category);
            this.action = safe(action);
            this.startDetail = safe(detail);
            this.started = SystemClock.elapsedRealtime();
        }
    }

    public static Span begin(String category, String action, String detail) {
        Span span = new Span(category, action, detail);
        write(span.category, span.action, "START", -1L,
                "correlationId=" + span.id + (span.startDetail.length() == 0 ? "" : "\n" + span.startDetail));
        return span;
    }

    public static void ok(Span span, String detail) {
        finish(span, "OK", detail);
    }

    public static void fail(Span span, String detail) {
        finish(span, "FAIL", detail);
    }

    public static void fail(Span span, Throwable error) {
        finish(span, "FAIL", stack(error));
    }

    public static void result(Span span, String result, String detail) {
        finish(span, result, detail);
    }

    private static void finish(Span span, String result, String detail) {
        if (span == null) {
            event("APP", "UNKNOWN", result, detail);
            return;
        }
        String value = "correlationId=" + span.id;
        String supplied = safe(detail);
        if (supplied.length() > 0) value += "\n" + supplied;
        write(span.category, span.action, result,
                Math.max(0L, SystemClock.elapsedRealtime() - span.started), value);
    }

    public static void event(String category, String action, String result, String detail) {
        write(category, action, result, -1L, detail);
    }

    private static void write(String category, String action, String result, long duration, String detail) {
        Context context = app;
        if (context == null) return;
        String value = safe(detail);
        try {
            String line="["+safe(category)+"/"+safe(action)+"] "+safe(result)
                    +(duration>=0?" "+duration+"ms":"")+"\n"+value;
            mirrorLog(result,line);
            AuditLog.operation(context, safe(category), safe(action), safe(result), duration, value);
        } catch (Throwable ignored) {}
    }

    private static void mirrorLog(String result, String text) {
        int offset=0, part=1;
        while(offset<text.length() || (text.length()==0 && part==1)) {
            int end=Math.min(text.length(),offset+3000);
            String chunk=(text.length()>3000?"part="+part+"\n":"")+text.substring(offset,end);
            if("FAIL".equals(result)||"ERROR".equals(result)) android.util.Log.e("WFT-Operation",chunk);
            else if("WARN".equals(result)||"DENIED".equals(result)||"CANCELED".equals(result)) android.util.Log.w("WFT-Operation",chunk);
            else android.util.Log.i("WFT-Operation",chunk);
            if(end>=text.length()) break;
            offset=end; part++;
        }
    }

    public static String intent(Intent intent) {
        if (intent == null) return "intent=null";
        StringBuilder out = new StringBuilder();
        out.append("action=").append(safe(intent.getAction()));
        out.append("\ncomponent=").append(intent.getComponent() == null ? "" : intent.getComponent().flattenToShortString());
        out.append("\npackage=").append(safe(intent.getPackage()));
        out.append("\ndata=").append(intent.getDataString() == null ? "" : intent.getDataString());
        out.append("\ntype=").append(safe(intent.getType()));
        out.append("\nflags=0x").append(Integer.toHexString(intent.getFlags()));
        try {
            Bundle extras = intent.getExtras();
            if (extras != null && !extras.isEmpty()) {
                ArrayList<String> keys = new ArrayList<String>(extras.keySet());
                Collections.sort(keys);
                out.append("\nextrasKeys=").append(keys);
            }
        } catch (Throwable error) {
            out.append("\nextrasKeys=<unavailable:").append(error.getClass().getSimpleName()).append('>');
        }
        if (intent.getCategories() != null) out.append("\ncategories=").append(intent.getCategories());
        return out.toString();
    }

    public static String command(String[] args) {
        if (args == null) return "";
        StringBuilder out = new StringBuilder();
        for (String arg : args) {
            if (out.length() > 0) out.append(' ');
            String value = arg == null ? "" : arg;
            boolean quote = value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0;
            if (quote) out.append('\'');
            out.append(value.replace("'", "'\\''"));
            if (quote) out.append('\'');
        }
        return out.toString();
    }

    public static String stack(Throwable error) {
        if (error == null) return "error=null";
        try {
            StringWriter text = new StringWriter();
            PrintWriter writer = new PrintWriter(text);
            error.printStackTrace(writer);
            writer.flush();
            return text.toString();
        } catch (Throwable ignored) { return error.toString(); }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}

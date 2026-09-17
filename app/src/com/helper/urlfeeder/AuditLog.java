package com.helper.urlfeeder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Small, process-thread-safe audit trail stored in the app's internal files. */
public final class AuditLog {
    private static final String TAG = "AuditLog";
    private static final String FILE_NAME = "audit.log";
    private static final long ROTATE_BYTES = 8L * 1024L * 1024L;
    private static final int KEEP_BYTES = 6 * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis());

    private AuditLog() {}

    public static final class Entry {
        public final long timestamp;
        public final long sequence;
        public final String category;
        public final String action;
        public final String result;
        public final long durationMs;
        public final String thread;
        public final String detail;

        Entry(long timestamp, long sequence, String category, String action, String result,
              long durationMs, String thread, String detail) {
            this.timestamp = timestamp;
            this.sequence = sequence;
            this.category = category;
            this.action = action;
            this.result = result;
            this.durationMs = durationMs;
            this.thread = thread;
            this.detail = detail;
        }

        public String displayTime() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                    .format(new Date(timestamp));
        }

        public String displayText() {
            StringBuilder out = new StringBuilder();
            out.append(displayTime()).append("  #").append(sequence).append("  [").append(category);
            if (action.length() > 0) out.append('/').append(action);
            out.append("] ").append(result);
            if (durationMs >= 0) out.append("  ").append(durationMs).append(" ms");
            if (thread.length() > 0) out.append("  {").append(thread).append('}');
            if (detail.length() > 0) out.append('\n').append(detail);
            return out.toString();
        }
    }

    /** Appends one structured operation record. */
    public static boolean operation(Context context, String category, String action, String result,
                                    long durationMs, String detail) {
        if (context == null) return false;
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                File file = logFile(context);
                byte[] record = encode(System.currentTimeMillis(), SEQUENCE.incrementAndGet(),
                        category, action, result, durationMs, Thread.currentThread().getName(), detail);
                if (file.length() + record.length > ROTATE_BYTES) rotate(file);
                out = new FileOutputStream(file, true);
                out.write(record);
                out.flush();
                return true;
            } catch (Exception e) {
                Log.e(TAG, "append failed", e);
                return false;
            } finally {
                closeQuietly(out);
            }
        }
    }

    /** Appends a compatibility event record. */
    public static boolean append(Context context, String category, String result, String detail) {
        return operation(context, category, "EVENT", result, -1L, detail);
    }

    /** Semantic alias used by operation code. */
    public static boolean record(Context context, String category, String result, String detail) {
        return append(context, category, result, detail);
    }

    /** Returns at most {@code max} valid records, newest first. */
    public static List<Entry> read(Context context, int max) {
        if (context == null || max <= 0) return Collections.emptyList();
        synchronized (LOCK) {
            File file = logFile(context);
            if (!file.isFile()) return Collections.emptyList();
            ArrayList<Entry> all = new ArrayList<Entry>();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    Entry entry = parse(line);
                    if (entry != null) all.add(entry);
                }
            } catch (Exception e) {
                Log.e(TAG, "read failed", e);
                return Collections.emptyList();
            } finally {
                closeQuietly(reader);
            }
            ArrayList<Entry> newest = new ArrayList<Entry>(Math.min(max, all.size()));
            for (int i = all.size() - 1; i >= 0 && newest.size() < max; i--) newest.add(all.get(i));
            return newest;
        }
    }

    public static boolean clear(Context context) {
        if (context == null) return false;
        synchronized (LOCK) {
            File file = logFile(context);
            return !file.exists() || file.delete() || truncate(file);
        }
    }

    /** Exports a stable snapshot and returns its user-facing location. */
    public static String export(Context context) throws IOException {
        if (context == null) throw new IOException("context is null");
        final byte[] data;
        synchronized (LOCK) {
            data = renderExport(logFile(context)).getBytes("UTF-8");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = "operation_log_" + stamp + ".log";
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return exportMediaStore(context, name, data);
            } catch (Exception e) {
                Log.w(TAG, "MediaStore export failed; using app external files", e);
            }
        }
        return exportAppExternal(context, name, data);
    }

    public static Set<String> categories(List<Entry> entries) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (entries != null) for (Entry entry : entries) out.add(entry.category);
        return out;
    }

    private static File logFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    private static byte[] encode(long timestamp, long sequence, String category, String action,
                                 String result, long durationMs, String thread, String detail)
            throws IOException {
        String line = "v2\t" + timestamp + "\t" + sequence + "\t" + escape(category) + "\t"
                + escape(action) + "\t" + escape(result) + "\t" + durationMs + "\t"
                + escape(thread) + "\t" + escape(detail) + "\n";
        return line.getBytes("UTF-8");
    }

    private static String cleanLegacy(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String unescape(String value) {
        if (value == null || value.indexOf('\\') < 0) return value == null ? "" : value;
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!escaped) {
                if (ch == '\\') escaped = true; else out.append(ch);
            } else {
                if (ch == 'n') out.append('\n');
                else if (ch == 'r') out.append('\r');
                else if (ch == 't') out.append('\t');
                else out.append(ch);
                escaped = false;
            }
        }
        if (escaped) out.append('\\');
        return out.toString();
    }

    private static Entry parse(String line) {
        String[] fields = line.split("\t", -1);
        try {
            if (fields.length == 9 && "v2".equals(fields[0])) {
                return new Entry(Long.parseLong(fields[1]), Long.parseLong(fields[2]),
                        unescape(fields[3]), unescape(fields[4]), unescape(fields[5]),
                        Long.parseLong(fields[6]), unescape(fields[7]), unescape(fields[8]));
            }
            if (fields.length == 4) {
                long timestamp = Long.parseLong(fields[0]);
                return new Entry(timestamp, timestamp, fields[1], "LEGACY", fields[2],
                        -1L, "", cleanLegacy(fields[3]));
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private static void rotate(File file) throws IOException {
        if (!file.isFile() || file.length() <= KEEP_BYTES) return;
        RandomAccessFile source = null;
        FileOutputStream target = null;
        File temp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try {
            source = new RandomAccessFile(file, "r");
            long start = Math.max(0L, source.length() - KEEP_BYTES);
            source.seek(start);
            if (start > 0) source.readLine(); // Drop the partial oldest record.
            target = new FileOutputStream(temp, false);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) target.write(buffer, 0, count);
            target.flush();
        } finally {
            closeQuietly(source);
            closeQuietly(target);
        }
        if (!file.delete() || !temp.renameTo(file)) {
            temp.delete();
            throw new IOException("could not rotate audit log");
        }
    }

    private static boolean truncate(File file) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, false);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "clear failed", e);
            return false;
        } finally {
            closeQuietly(out);
        }
    }

    private static String renderExport(File file) throws IOException {
        ArrayList<String> records=new ArrayList<String>();
        int corrupt=0;
        BufferedReader reader=null;
        try{
            if(file.isFile()){
                reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),"UTF-8"));
                String line;
                while((line=reader.readLine())!=null){
                    Entry entry=parse(line);
                    if(entry!=null) records.add(entry.displayText());
                    else { corrupt++; records.add("[CORRUPT_RAW_RECORD]\n"+line); }
                }
            }
        }finally{ closeQuietly(reader); }
        StringBuilder text=new StringBuilder();
        text.append("万能转发器 完整操作日志\n");
        text.append("导出时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.getDefault()).format(new Date())).append('\n');
        text.append("格式：时间 #序号 [分类/动作] 结果 耗时 {线程}\n");
        text.append("敏感信息提示：完整 URL 可能含查询凭据或令牌；命令和输出可能含个人数据、账户信息及系统数据。\n");
        text.append("记录数：").append(records.size()).append("（最新在前）；保留的异常原始记录：").append(corrupt).append('\n');
        for(int i=records.size()-1;i>=0;i--) text.append("\n────────────────────────\n").append(records.get(i)).append('\n');
        return text.toString();
    }

    private static byte[] readBytes(File file) throws IOException {
        if (!file.isFile()) return new byte[0];
        FileInputStream in = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), ROTATE_BYTES));
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static String exportMediaStore(Context context, String name, byte[] data) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/万能转发器日志");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("could not create download");
        OutputStream out = null;
        boolean published = false;
        try {
            out = resolver.openOutputStream(uri, "w");
            if (out == null) throw new IOException("could not open download");
            out.write(data);
            out.flush();
            closeQuietly(out); out=null;
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if(resolver.update(uri, ready, null, null)!=1) throw new IOException("could not publish download");
            published=true;
            return "Download/万能转发器日志/" + name;
        } finally {
            closeQuietly(out);
            if (!published) resolver.delete(uri, null, null);
        }
    }

    private static String exportAppExternal(Context context, String name, byte[] data) throws IOException {
        File root = context.getExternalFilesDir(null);
        if (root == null) throw new IOException("external files directory unavailable");
        File dir = new File(root, "logs");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("could not create backup directory");
        File file = new File(dir, name);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, false);
            out.write(data);
            out.flush();
        } finally {
            closeQuietly(out);
        }
        return file.getAbsolutePath();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) {}
    }
}

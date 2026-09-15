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

/** Small, process-thread-safe audit trail stored in the app's internal files. */
public final class AuditLog {
    private static final String TAG = "AuditLog";
    private static final String FILE_NAME = "audit.log";
    private static final long ROTATE_BYTES = 512L * 1024L;
    private static final int KEEP_BYTES = 384 * 1024;
    private static final Object LOCK = new Object();

    private AuditLog() {}

    public static final class Entry {
        public final long timestamp;
        public final String category;
        public final String result;
        public final String detail;

        Entry(long timestamp, String category, String result, String detail) {
            this.timestamp = timestamp;
            this.category = category;
            this.result = result;
            this.detail = detail;
        }

        public String displayTime() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(timestamp));
        }

        public String displayText() {
            return displayTime() + "  [" + category + "] " + result + "\n" + detail;
        }
    }

    /** Appends one sanitized record. Logging failures are reported through the return value. */
    public static boolean append(Context context, String category, String result, String detail) {
        if (context == null) return false;
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                File file = logFile(context);
                byte[] record = encode(System.currentTimeMillis(), category, result, detail);
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
            data = readBytes(logFile(context));
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = "audit_" + stamp + ".log";
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

    private static byte[] encode(long timestamp, String category, String result, String detail)
            throws IOException {
        String line = timestamp + "\t" + clean(category) + "\t" + clean(result) + "\t"
                + clean(detail) + "\n";
        return line.getBytes("UTF-8");
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static Entry parse(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 4) return null;
        try {
            return new Entry(Long.parseLong(fields[0]), fields[1], fields[2], fields[3]);
        } catch (NumberFormatException e) {
            return null;
        }
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
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/万能转发器备份");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("could not create download");
        OutputStream out = null;
        boolean complete = false;
        try {
            out = resolver.openOutputStream(uri, "w");
            if (out == null) throw new IOException("could not open download");
            out.write(data);
            out.flush();
            complete = true;
        } finally {
            closeQuietly(out);
            if (!complete) resolver.delete(uri, null, null);
        }
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, ready, null, null);
        return "Download/万能转发器备份/" + name;
    }

    private static String exportAppExternal(Context context, String name, byte[] data) throws IOException {
        File root = context.getExternalFilesDir(null);
        if (root == null) throw new IOException("external files directory unavailable");
        File dir = new File(root, "backup");
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

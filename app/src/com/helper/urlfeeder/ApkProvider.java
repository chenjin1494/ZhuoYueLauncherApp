package com.helper.urlfeeder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 ContentProvider：把 App 私有缓存目录里的文件以 content:// 形式提供给系统安装器。
 * （工程不带 androidx，故不使用 FileProvider；authority = com.helper.urlfeeder.apk）
 * 仅允许访问缓存目录下的单层文件名，防止路径穿越。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = Updater.AUTHORITY;

    public boolean onCreate() { return true; }

    public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) {
        String name = u.getLastPathSegment();
        File f = safeFile(name);
        String[] cols = (proj == null || proj.length == 0)
            ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : proj;
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = name;
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f == null ? 0L : f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }
    public String getType(Uri u) {
        String name=u==null?null:u.getLastPathSegment();
        if(name!=null&&name.toLowerCase().endsWith(".js")) return "application/javascript";
        if(name!=null&&(name.toLowerCase().endsWith(".txt")||name.toLowerCase().endsWith(".log"))) return "text/plain";
        return "application/vnd.android.package-archive";
    }
    public Uri insert(Uri u, ContentValues v) { return null; }
    public int delete(Uri u, String sel, String[] args) { return 0; }
    public int update(Uri u, ContentValues v, String sel, String[] args) { return 0; }

    private File safeFile(String name) {
        if (name == null || name.length() == 0 || name.indexOf('/') >= 0 || name.contains("..")) return null;
        return new File(getContext().getCacheDir(), name);
    }

    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        File f = safeFile(u.getLastPathSegment());
        if (f == null) throw new FileNotFoundException("bad name");
        if (!f.exists()) throw new FileNotFoundException(u.getLastPathSegment());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}

package com.helper.urlfeeder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

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

    public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) { return null; }
    public String getType(Uri u) { return "application/vnd.android.package-archive"; }
    public Uri insert(Uri u, ContentValues v) { return null; }
    public int delete(Uri u, String sel, String[] args) { return 0; }
    public int update(Uri u, ContentValues v, String sel, String[] args) { return 0; }

    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        String name = u.getLastPathSegment();
        if (name == null || name.length() == 0 || name.indexOf('/') >= 0 || name.contains(".."))
            throw new FileNotFoundException("bad name");
        File f = new File(getContext().getCacheDir(), name);
        if (!f.exists()) throw new FileNotFoundException(name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}

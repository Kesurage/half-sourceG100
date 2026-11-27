package com.z.halfsourceg100;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * Minimal file provider (v0.4.2) — serves files from the logs dir so they
 * can be shared as stream attachments (file:// URIs are banned since API 24
 * and this no-gradle build has no androidx to borrow FileProvider from).
 *
 * URI shape: content://com.z.halfsourceg100.logs/<file-name>
 * Only files inside the logs dir (or the engine console.log under the
 * content root) are ever exposed — nothing else on storage is reachable.
 */
public class LogFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.z.halfsourceg100.logs";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Build the share URI for a log file. */
    public static Uri uriFor(File f) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(f.getName())
                .build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals(".."))
            throw new FileNotFoundException("invalid name");
        // logs dir (shared or fallback, same resolution as LogStore)
        File logs = LogStore.logDir(getContext());
        File f = new File(logs, name);
        if (f.isFile()) return f;
        // the engine console.log lives in the game dir, expose it too
        File console = new File(ModScanner.ROOT + "/hl2", name);
        if (console.isFile() && name.equals("console.log")) return console;
        throw new FileNotFoundException(name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        File f = fileFor(uri);
        return ParcelFileDescriptor.open(f,
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".zip"))
            return "application/zip";
        return "text/plain";
    }

    // ---- not used by ACTION_SEND, but the framework requires them ----

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}

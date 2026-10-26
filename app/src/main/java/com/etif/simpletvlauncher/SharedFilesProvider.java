package com.etif.simpletvlauncher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/** Read-only sharing for existing files selected in the TV explorer. */
public final class SharedFilesProvider extends ContentProvider {
    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            if (!"file".equals(uri.getPathSegments().get(0)) || uri.getPathSegments().size() != 2)
                throw new FileNotFoundException("Invalid URI");
            File file = new File(uri.getPathSegments().get(1)).getCanonicalFile();
            File root = Environment.getExternalStorageDirectory().getCanonicalFile();
            // Solo archivos del almacenamiento compartido, nunca /Android/data u obb.
            if (!file.toPath().startsWith(root.toPath()) || !file.isFile())
                throw new FileNotFoundException("Fuera del almacenamiento compartido");
            String relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
            if (relative.equals("Android/data") || relative.startsWith("Android/data/") ||
                relative.equals("Android/obb") || relative.startsWith("Android/obb/"))
                throw new FileNotFoundException("Carpeta protegida");
            return file;
        } catch (IOException | RuntimeException ex) { throw new FileNotFoundException(ex.toString()); }
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        try {
            String name = resolve(uri).getName();
            int dot = name.lastIndexOf('.');
            String type = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            return type == null ? "application/octet-stream" : type;
        } catch (FileNotFoundException ex) { return "application/octet-stream"; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolve(uri);
            String[] cols = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor c = new MatrixCursor(cols);
            Object[] values = new Object[cols.length];
            for (int i=0; i<cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) values[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) values[i] = file.length();
            }
            c.addRow(values);
            return c;
        } catch (FileNotFoundException ex) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}

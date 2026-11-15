package com.arrradu.aa900.installer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Serves the one report file, read-only, to the app the user shares it with. */
public class ReportProvider extends ContentProvider {

    static final String FILE_NAME = "raport-aa-lynk.txt";
    static final Uri URI = Uri.parse("content://com.arrradu.aa900.installer.report/" + FILE_NAME);

    static File file(Context context) {
        return new File(context.getCacheDir(), FILE_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{FILE_NAME, file(getContext()).length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
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
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

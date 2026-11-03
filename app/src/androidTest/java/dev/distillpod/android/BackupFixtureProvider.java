package dev.distillpod.android;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Synthetic files only. Java keeps the separate test-provider process independent of the target APK's Kotlin runtime. */
public final class BackupFixtureProvider extends DocumentsProvider {
    private File folder() {
        File folder = new File(getContext().getCacheDir(), "backup-provider-fixtures");
        folder.mkdirs();
        return folder;
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES });
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Root.COLUMN_ROOT_ID, "fixture"); row.add(Root.COLUMN_DOCUMENT_ID, "root");
        row.add(Root.COLUMN_TITLE, "DistillPod test files");
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY);
        row.add(Root.COLUMN_MIME_TYPES, "application/json"); row.add(Root.COLUMN_AVAILABLE_BYTES, 100_000_000L);
        return cursor;
    }
    private File file(String id) {
        if (id.isEmpty() || id.contains("/") || id.contains("\\") || id.equals(".") || id.equals("..")) throw new IllegalArgumentException();
        return new File(folder(), id);
    }
    private MatrixCursor rows(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[] { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED });
    }
    private void addRow(MatrixCursor cursor, String id) {
        boolean root = id.equals("root");
        File file = root ? folder() : file(id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, id); row.add(Document.COLUMN_DISPLAY_NAME, root ? "DistillPod test files" : id);
        row.add(Document.COLUMN_MIME_TYPE, root ? Document.MIME_TYPE_DIR : "application/json");
        row.add(Document.COLUMN_FLAGS, root ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE);
        row.add(Document.COLUMN_SIZE, file.length()); row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = rows(projection); addRow(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) {
        if (!parent.equals("root")) throw new IllegalArgumentException();
        MatrixCursor cursor = rows(projection);
        File[] files = folder().listFiles();
        if (files != null) for (File file : files) if (file.isFile()) addRow(cursor, file.getName());
        return cursor;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!parent.equals("root")) throw new IllegalArgumentException();
        try {
            String candidate = name; int suffix = 0;
            while (!file(candidate).createNewFile()) candidate = (++suffix) + "-" + name;
            return candidate;
        } catch (IOException error) { throw new FileNotFoundException("Fixture storage unavailable"); }
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        File file = file(id);
        if (!file.exists()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (!file(id).delete()) throw new FileNotFoundException();
    }
    @Override public boolean isChildDocument(String parent, String id) { return parent.equals("root") && !id.equals("root"); }
}

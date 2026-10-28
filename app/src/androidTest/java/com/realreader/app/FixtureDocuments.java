package com.realreader.app;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Test APK only. Plain Android/Java so this provider can run in its own process. */
public class FixtureDocuments extends DocumentsProvider {
    private File root() { File r=new File(getContext().getFilesDir(),"saf-fixture"); r.mkdirs(); return r; }
    private File file(String id) throws FileNotFoundException {
        File f=id.equals("root")?root():new File(root(),id.substring("root/".length()));
        try { if(!f.getCanonicalPath().equals(root().getCanonicalPath()) && !f.getCanonicalPath().startsWith(root().getCanonicalPath()+"/"))throw new FileNotFoundException(); }
        catch(IOException e){throw new FileNotFoundException(e.getMessage());} return f;
    }
    @Override public boolean onCreate() {
        File nested=new File(root(),"nested");nested.mkdirs();
        try {for(String name:new String[]{"Test #1.cbz","unrelated.txt"})try(FileOutputStream out=new FileOutputStream(new File(nested,name))){out.write("Index fixture".getBytes(StandardCharsets.UTF_8));}}
        catch(IOException e){throw new IllegalStateException(e);} return true;
    }
    private void grantTestTree() {
        Uri tree=DocumentsContract.buildTreeDocumentUri("com.realreader.fixture.documents","root");
        getContext().grantUriPermission("com.realreader.app",tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    }
    @Override public boolean isChildDocument(String parent,String child) {try{return file(child).getCanonicalPath().startsWith(file(parent).getCanonicalPath()+"/");}catch(IOException e){return false;}}
    @Override public Cursor queryRoots(String[] projection){MatrixCursor c=new MatrixCursor(new String[]{Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS});c.addRow(new Object[]{"root","root","Comic fixture",Root.FLAG_LOCAL_ONLY});return c;}
    private MatrixCursor cursor(){return new MatrixCursor(new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE});}
    private void add(MatrixCursor c,File f){String rel=root().toURI().relativize(f.toURI()).getPath();String id=rel.isEmpty()?"root":"root/"+rel.replaceAll("/$","");c.addRow(new Object[]{id,f.getName(),f.isDirectory()?Document.MIME_TYPE_DIR:"application/octet-stream",0,f.length()});}
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {grantTestTree();MatrixCursor c=cursor();add(c,file(id));return c;}
    @Override public Cursor queryChildDocuments(String id,String[] projection,String sortOrder) throws FileNotFoundException {MatrixCursor c=cursor();File[] files=file(id).listFiles();if(files!=null)for(File f:files)add(c,f);return c;}
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.MODE_READ_ONLY);}
}

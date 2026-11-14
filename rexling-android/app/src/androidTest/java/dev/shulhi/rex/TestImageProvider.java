package dev.shulhi.rex;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Test APK providers run separately, without the target APK's Kotlin runtime. */
public final class TestImageProvider extends ContentProvider {
    public static final Uri uri=Uri.parse("content://"+BuildConfig.APPLICATION_ID+".test.images/image.png");
    public static final byte[] png=Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aP2sAAAAASUVORK5CYII=",Base64.DEFAULT);
    @Override public boolean onCreate() {return true;}
    @Override public String getType(Uri value) {return "image/png";}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String order) {return null;}
    @Override public Uri insert(Uri u,ContentValues values) {return null;}
    @Override public int update(Uri u,ContentValues v,String s,String[] a) {return 0;}
    @Override public int delete(Uri u,String s,String[] a) {return 0;}
    @Override public ParcelFileDescriptor openFile(Uri value,String mode) throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            new Thread(()-> {
                try(ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    out.write("bad".equals(value.getLastPathSegment()) ? new byte[]{1,2,3} : png);
                } catch(IOException ignored) { /* The reader may cancel. */ }
            }).start();
            return pipe[0];
        } catch(IOException error) {throw new FileNotFoundException(error.getMessage());}
    }
}

package com.realreader.app;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.DocumentsContract;
/** Starts the fixture from its owner UID, then issues the same scoped tree grant as a picker. Test APK only. */
public class FixtureGrantReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context,Intent intent) {
  try(Cursor ignored=context.getContentResolver().query(DocumentsContract.buildDocumentUri("com.realreader.fixture.documents","root"),null,null,null,null)) {}
 }
}

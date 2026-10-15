package org.shadowice.flocke.andotp.securityclient;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Runs in a different UID/signing identity, not inside the target's instrumentation UID. */
public final class AttackActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String target = getIntent().getStringExtra("target");
        if (!"org.shadowice.flocke.andotp.dev".equals(target)) { finish(); return; }
        try {
            startActivity(new Intent("info.guardianproject.panic.action.TRIGGER")
                    .setClassName(target, "org.shadowice.flocke.andotp.Activities.PanicResponderActivity"));
            Log.i("andOTP-SecurityClient", "PANIC_ALLOWED");
        } catch (SecurityException expected) {
            Log.i("andOTP-SecurityClient", "PANIC_DENIED");
        }
        Log.i("andOTP-SecurityClient", "BROADCAST_ATTEMPTED");
        try {
            sendBroadcast(new Intent("org.shadowice.flocke.andotp.broadcast.ENCRYPTED_BACKUP")
                    .setClassName(target, "org.shadowice.flocke.andotp.Receivers.EncryptedBackupBroadcastReceiver"));
            Log.i("andOTP-SecurityClient", "BROADCAST_SENT");
        } catch (SecurityException expected) {
            Log.i("andOTP-SecurityClient", "BROADCAST_DENIED");
        }
        finish();
    }
}

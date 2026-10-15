/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Activities;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.widget.Toolbar;

import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewStub;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;
import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.Dialogs.PasswordEntryDialog;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult;
import org.shadowice.flocke.andotp.Tasks.EncryptedBackupTask;
import org.shadowice.flocke.andotp.Tasks.EncryptedRestoreTask;
import org.shadowice.flocke.andotp.Tasks.PGPBackupTask;
import org.shadowice.flocke.andotp.Tasks.PGPRestoreTask;
import org.shadowice.flocke.andotp.Tasks.UiBasedBackgroundTask;
import org.shadowice.flocke.andotp.Utilities.BackupHelper;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper;
import org.shadowice.flocke.andotp.Utilities.VaultRestoreOperation;
import org.shadowice.flocke.andotp.Utilities.VaultReadResult;
import org.shadowice.flocke.andotp.Utilities.VaultSession;
import org.shadowice.flocke.andotp.Utilities.BoundedOutput;
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader;
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper;
import org.shadowice.flocke.andotp.Utilities.Tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import javax.crypto.SecretKey;

/** Backup/restore screen for password-encrypted and OpenPGP backups. */
public class BackupActivity extends BackgroundTaskActivity<BackupTaskResult> {
    private final static String TAG = BackupActivity.class.getSimpleName();

    private Constants.BackupType backupType = Constants.BackupType.ENCRYPTED;
    private SecretKey encryptionKey = null;
    private VaultRestoreOperation restoreOperation;

    private OpenPgpServiceConnection pgpServiceConnection;
    private String pgpEncryptionUserIDs;

    private Uri encryptTargetFile;
    private Uri decryptSourceFile;

    private Button btnBackup;
    private Button btnRestore;
    private TextView txtBackupLabel;
    private TextView txtBackupWarning;
    private SwitchMaterial swReplace;
    private CheckBox chkOldFormat;
    private ProgressBar progressBackup;
    private ProgressBar progressRestore;

    private boolean reload = false;
    private boolean allowExit = true;

    private BackupTaskResult.ResultType currentTask = BackupTaskResult.ResultType.NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle(R.string.backup_activity_title);
        setContentView(R.layout.activity_container);

        Toolbar toolbar = findViewById(R.id.container_toolbar);
        setSupportActionBar(toolbar);

        ViewStub stub = findViewById(R.id.container_stub);
        stub.setLayoutResource(R.layout.content_backup);
        View v = stub.inflate();

        encryptionKey = VaultSession.getKey();
        if (encryptionKey == null) { finish(); return; }
        restoreOperation = new VaultRestoreOperation(this);

        Spinner spBackupType = v.findViewById(R.id.backupType);
        btnBackup = v.findViewById(R.id.buttonBackup);
        btnRestore = v.findViewById(R.id.buttonRestore);
        txtBackupLabel = v.findViewById(R.id.backupLabel);
        txtBackupWarning = v.findViewById(R.id.backupErrorLabel);
        swReplace = v.findViewById(R.id.backup_replace);
        chkOldFormat = v.findViewById(R.id.restoreOldCrypt);
        progressBackup = v.findViewById(R.id.progressBarBackup);
        progressRestore = v.findViewById(R.id.progressBarRestore);

        setupBackupType(settings.getDefaultBackupType());
        spBackupType.setSelection(backupType.ordinal());

        spBackupType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                Constants.BackupType type = Constants.BackupType.values()[i];
                setupBackupType(type);
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) { }
        });

        btnBackup.setOnClickListener(view -> {
            switch (backupType) {
                case ENCRYPTED:
                    showSaveFileSelector(Constants.BACKUP_MIMETYPE_CRYPT, Constants.BackupType.ENCRYPTED, Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT);
                    break;
                case OPEN_PGP:
                    showSaveFileSelector(Constants.BACKUP_MIMETYPE_PGP, Constants.BackupType.OPEN_PGP, Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP);
                    break;
            }
        });

        btnRestore.setOnClickListener(view -> {
            switch (backupType) {
                case ENCRYPTED:
                    if (chkOldFormat.isChecked())
                        showOpenFileSelector(Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT_OLD);
                    else
                        showOpenFileSelector(Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT);
                    break;
                case OPEN_PGP:
                    showOpenFileSelector(Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP);
                    break;
            }
        });
    }

    /** Shows the controls and warnings for the selected backup type and remembers the choice. */
    private void setupBackupType(Constants.BackupType type) {
        switch (type) {
            case ENCRYPTED:
                txtBackupLabel.setText(R.string.backup_label_crypt);

                chkOldFormat.setVisibility(View.VISIBLE);
                txtBackupWarning.setVisibility(View.GONE);

                btnBackup.setEnabled(true);
                btnRestore.setEnabled(true);

                break;
            case OPEN_PGP:
                txtBackupLabel.setText(R.string.backup_label_pgp);

                chkOldFormat.setVisibility(View.GONE);

                String PGPProvider = settings.getOpenPGPProvider();
                pgpEncryptionUserIDs = settings.getOpenPGPEncryptionUserIDs();

                if (TextUtils.isEmpty(PGPProvider)) {
                    txtBackupWarning.setText(R.string.backup_desc_openpgp_provider);
                    txtBackupWarning.setVisibility(View.VISIBLE);

                    btnBackup.setEnabled(false);
                    btnRestore.setEnabled(false);
                } else if (TextUtils.isEmpty(pgpEncryptionUserIDs)){
                    txtBackupWarning.setText(R.string.backup_desc_openpgp_keyid);
                    txtBackupWarning.setVisibility(View.VISIBLE);

                    btnBackup.setEnabled(false);
                    btnRestore.setEnabled(false);
                } else {
                    txtBackupWarning.setVisibility(View.GONE);

                    btnBackup.setEnabled(true);
                    btnRestore.setEnabled(true);

                    pgpServiceConnection = new OpenPgpServiceConnection(BackupActivity.this.getApplicationContext(), PGPProvider);
                    pgpServiceConnection.bindToService();
                }

                break;
        }

        backupType = type;
        settings.setDefaultBackupType(type);
    }

    /** Finishes the activity, reporting to MainActivity whether entries had to be reloaded. */
    public void finishWithResult() {
        Intent data = new Intent();
        data.putExtra("reload", reload);
        setResult(RESULT_OK, data);
        finish();
    }

    // Go back to the main activity
    @Override
    public boolean onSupportNavigateUp() {
        if (allowExit)
            finishWithResult();

        return true;
    }

    @Override
    public void onBackPressed() {
        if (allowExit) {
            finishWithResult();
            super.onBackPressed();
        }
    }

    @Override
    public void onDestroy() {
        encryptionKey = null;
        super.onDestroy();

        if (pgpServiceConnection != null)
            pgpServiceConnection.unbindFromService();
    }

    @Override protected boolean requiresVaultSession() { return true; }
    @Override protected void onVaultLocked() {
        encryptionKey = null;
        cancelBackgroundTask();
        finish();
    }

    private boolean canOperate() { return !isFinishing() && encryptionKey != null && VaultSession.getKey() == encryptionKey; }

    // TODO: Show more information about the finished backup (e.g. a notification with the file name)
    private void notifyBackupState(int msgId) {
        Toast.makeText(this, msgId, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onTaskFailure(org.shadowice.flocke.andotp.Tasks.TaskFailure error) {
        notifyBackupState(currentTask == BackupTaskResult.ResultType.RESTORE
                ? R.string.backup_toast_import_failed : R.string.backup_toast_export_failed);
    }

    private ArrayList<Entry> readOwnedEntries() {
        VaultReadResult result = DatabaseHelper.readVault(this, encryptionKey);
        if (!(result instanceof VaultReadResult.Loaded))
            throw new IllegalStateException("Vault unavailable");
        return ((VaultReadResult.Loaded) result).takeEntries();
    }

    @Override
    void onTaskResult(BackupTaskResult backupTaskResult) {
        if (!canOperate()) return;
        if (backupTaskResult.resultType == BackupTaskResult.ResultType.BACKUP)
            handleBackupTaskResult(backupTaskResult);
        else if (backupTaskResult.resultType == BackupTaskResult.ResultType.RESTORE)
            handleRestoreTaskResult(backupTaskResult);
    }

    private void handleBackupTaskResult(BackupTaskResult result) {
        if (result.messageId != 0)
            notifyBackupState(result.messageId);
        else
            if (result.success)
                notifyBackupState(R.string.backup_toast_export_success);
            else
                notifyBackupState(R.string.backup_toast_export_failed);

        if (result.success)
            finishWithResult();
    }

    private void handleRestoreTaskResult(BackupTaskResult result) {
        if (result.success) {
            if (result.isPGP) {
                InputStream is = new ByteArrayInputStream(result.payload.getBytes(StandardCharsets.UTF_8));
                ByteArrayOutputStream os = new BoundedOutput(BoundedInputReader.MAX_DOCUMENT_BYTES);

                OpenPgpApi api = new OpenPgpApi(this, pgpServiceConnection.getService());
                Intent resultIntent;
                try { resultIntent = api.executeApi(result.decryptIntent, is, os); }
                catch (RuntimeException error) { notifyBackupState(R.string.backup_toast_import_failed); return; }
                handleOpenPGPResult(resultIntent, os, result.uri, Constants.INTENT_BACKUP_DECRYPT_PGP);
            } else {
                if (restoreEntries(result.payload, false)) finishWithResult();
            }
        } else {
            if (result.messageId != 0)
                notifyBackupState(result.messageId);
            else
                notifyBackupState(R.string.backup_toast_import_failed);
        }


    }

    @Override
    protected void setupUiForTaskState(boolean running) {
        if (currentTask == BackupTaskResult.ResultType.BACKUP)
            showBackupProgress(running);
        else if (currentTask == BackupTaskResult.ResultType.RESTORE)
            showRestoreProgress(running);
    }

    /** Starts a task and remembers which progress indicator belongs to it. */
    protected void startBackgroundTask(UiBasedBackgroundTask<BackupTaskResult> task, BackupTaskResult.ResultType type) {
        currentTask = type;
        startBackgroundTask(task);
    }

    private void toggleInProgressMode(boolean running) {
        allowExit = !running;

        btnBackup.setEnabled(!running);
        btnRestore.setEnabled(!running);
        chkOldFormat.setEnabled(!running);
        swReplace.setEnabled(!running);
    }

    private void showBackupProgress(boolean running) {
        toggleInProgressMode(running);
        progressBackup.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    private void showRestoreProgress(boolean running) {
        toggleInProgressMode(running);
        progressRestore.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    // Results from external activities
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        if (requestCode == Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT && resultCode == RESULT_OK) {
            if (intent != null) {
                doRestoreCrypt(intent.getData(), false);
            }
        } else if (requestCode == Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT_OLD && resultCode == RESULT_OK) {
            if (intent != null) {
                doRestoreCrypt(intent.getData(), true);
            }
        } else if (requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT && resultCode == RESULT_OK) {
            if (intent != null) {
                doBackupCrypt(intent.getData());
            }
        } else if (requestCode == Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP && resultCode == RESULT_OK) {
            if (intent != null)
                restoreEncryptedWithPGP(intent.getData(), null);
        } else if (requestCode == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP && resultCode == RESULT_OK) {
            if (intent != null)
                backupEncryptedWithPGP(intent.getData(), null);
        } else if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP && resultCode == RESULT_OK) {
            backupEncryptedWithPGP(encryptTargetFile, intent);
        } else if (requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP && resultCode == RESULT_OK) {
            restoreEncryptedWithPGP(decryptSourceFile, intent);
        }
    }

    /* Generic functions for all backup/restore options */

    private void showOpenFileSelector(int intentId) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        try {
            startActivityForResult(intent, intentId);
            return;
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Failed to use ACTION_OPEN_DOCUMENT, no matching activity found!");
        }

        intent.setAction(Intent.ACTION_GET_CONTENT);

        try {
            startActivityForResult(intent, intentId);
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Failed to use ACTION_GET_CONTENT, no matching activity found!");
            Toast.makeText(this, R.string.backup_toast_file_selection_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Uses the system file picker, or backs up straight to the configured location when "ask" is off. */
    private void showSaveFileSelector(String mimeType, Constants.BackupType backupType, int intentId) {
        if (settings.getBackupAsk()) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_TITLE, BackupHelper.backupFilename(this, backupType));
            startActivityForResult(intent, intentId);
        } else {
            if (settings.isBackupLocationSet()) {
                if (intentId == Constants.INTENT_BACKUP_SAVE_DOCUMENT_CRYPT) {
                    doBackupCrypt(null);
                } else if (intentId == Constants.INTENT_BACKUP_SAVE_DOCUMENT_PGP) {
                    backupEncryptedWithPGP(null, null);
                }
            } else {
                Toast.makeText(this, R.string.backup_toast_no_location, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Adds the restored entries (replacing the current ones if selected) and saves the database. */
    private boolean restoreEntries(String text, boolean finish) {
        if (!canOperate()) return false;
        VaultRestoreOperation.Result result = restoreOperation.restore(text, swReplace.isChecked(),
                encryptionKey, VaultSession.generation(), this::canOperate);
        if (result == VaultRestoreOperation.Result.SAVED) {
            reload = true;
            notifyBackupState(R.string.backup_toast_import_success);
            if (finish) finishWithResult();
            return true;
        }
        if (result == VaultRestoreOperation.Result.EMPTY_IMPORT)
            notifyBackupState(R.string.backup_toast_import_no_entries);
        else if (result == VaultRestoreOperation.Result.SAVE_FAILED
                || result == VaultRestoreOperation.Result.SESSION_REVOKED
                || result == VaultRestoreOperation.Result.RECOVERY_REQUIRED)
            notifyBackupState(R.string.backup_toast_import_save_failed);
        else
            notifyBackupState(R.string.backup_toast_import_failed);
        return false;
    }

    /* Encrypted backup functions */

    private void doRestoreCrypt(final Uri uri, final boolean old_format) {
        String password = settings.getBackupPasswordEnc();

        if (password.isEmpty()) {
            PasswordEntryDialog pwDialog = new PasswordEntryDialog(this, PasswordEntryDialog.Mode.ENTER, settings.getBlockAccessibility(), settings.getBlockAutofill(), newPassword -> doRestoreCryptWithPassword(uri, newPassword, old_format));
            pwDialog.show();
        } else {
            doRestoreCryptWithPassword(uri, password, old_format);
        }
    }

    private void doRestoreCryptWithPassword(Uri uri, String password, boolean old_format) {
        if (Tools.isExternalStorageReadable()) {
            EncryptedRestoreTask task = new EncryptedRestoreTask(this, uri, password, old_format);
            startBackgroundTask(task, BackupTaskResult.ResultType.RESTORE);
        } else {
            Toast.makeText(this, R.string.backup_toast_storage_not_accessible, Toast.LENGTH_LONG).show();
        }
    }

    private void doBackupCrypt(final Uri uri) {
        String password = settings.getBackupPasswordEnc();

        if (password.isEmpty()) {
            PasswordEntryDialog pwDialog = new PasswordEntryDialog(this, PasswordEntryDialog.Mode.UPDATE, settings.getBlockAccessibility(), settings.getBlockAutofill(), newPassword -> doBackupCryptWithPassword(uri, newPassword));
            pwDialog.show();
        } else {
            doBackupCryptWithPassword(uri, password);
        }
    }

    private void doBackupCryptWithPassword(Uri uri, String password) {
        if (!canOperate()) return;
        try {
            ArrayList<Entry> entries = readOwnedEntries();
            try { startBackgroundTask(new EncryptedBackupTask(this, entries, password, uri), BackupTaskResult.ResultType.BACKUP); }
            finally { for (Entry entry : entries) entry.destroy(); }
        } catch (RuntimeException error) { notifyBackupState(R.string.backup_toast_export_failed); }
    }

    private void restoreEncryptedWithPGP(Uri uri, Intent decryptIntent) {
        if (decryptIntent == null)
            decryptIntent = new Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY);

        PGPRestoreTask task = new PGPRestoreTask(this, uri, decryptIntent);
        startBackgroundTask(task, BackupTaskResult.ResultType.RESTORE);
    }

    private void doBackupEncrypted(Uri uri, String data) {
        if (Tools.isExternalStorageWritable()) {
            PGPBackupTask task = new PGPBackupTask(this, data, uri);
            startBackgroundTask(task, BackupTaskResult.ResultType.BACKUP);
        } else {
            Toast.makeText(this, R.string.backup_toast_storage_not_accessible, Toast.LENGTH_LONG).show();
        }
    }

    private void backupEncryptedWithPGP(Uri uri, Intent encryptIntent) {
        if (!canOperate()) return;
        ArrayList<Entry> entries;
        String plainJSON;
        try {
            entries = readOwnedEntries();
            try { plainJSON = DatabaseHelper.entriesToString(entries); }
            finally { for (Entry entry : entries) entry.destroy(); }
        } catch (RuntimeException error) { notifyBackupState(R.string.backup_toast_export_failed); return; }

        if (encryptIntent == null) {
            encryptIntent = new Intent();

            if (settings.getOpenPGPSigningKey() != 0) {
                encryptIntent.setAction(OpenPgpApi.ACTION_SIGN_AND_ENCRYPT);
                encryptIntent.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, settings.getOpenPGPSigningKey());
            } else {
                encryptIntent.setAction(OpenPgpApi.ACTION_ENCRYPT);
            }

            encryptIntent.putExtra(OpenPgpApi.EXTRA_USER_IDS, pgpEncryptionUserIDs.split(","));
            encryptIntent.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);
        }

        InputStream is = new ByteArrayInputStream(plainJSON.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream os = new BoundedOutput(BoundedInputReader.MAX_DOCUMENT_BYTES);
        OpenPgpApi api = new OpenPgpApi(this, pgpServiceConnection.getService());
        Intent result;
        try { result = api.executeApi(encryptIntent, is, os); }
        catch (RuntimeException error) { notifyBackupState(R.string.backup_toast_export_failed); return; }
        handleOpenPGPResult(result, os, uri, Constants.INTENT_BACKUP_ENCRYPT_PGP);
    }

    /** Reads the OpenPGP API's output stream as UTF-8 text. */
    public String outputStreamToString(ByteArrayOutputStream os) {
        return new String(os.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Handles the OpenPGP API result; a pending user interaction is passed to the OpenPGP app. */
    public void handleOpenPGPResult(Intent result, ByteArrayOutputStream os, Uri file, int requestCode) {
        if (!canOperate() || result == null || (os instanceof BoundedOutput && ((BoundedOutput) os).overflowed())) {
            notifyBackupState(R.string.backup_toast_import_failed); return;
        }
        if (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR) == OpenPgpApi.RESULT_CODE_SUCCESS) {
            if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP) {
                if (os != null)
                    doBackupEncrypted(file, outputStreamToString(os));
            } else if (requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP) {
                if (os != null) {
                    if (settings.getOpenPGPVerify()) {
                        OpenPgpSignatureResult sigResult = result.getParcelableExtra(OpenPgpApi.RESULT_SIGNATURE);

                        if (sigResult != null && sigResult.getResult() == OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED) {
                            restoreEntries(outputStreamToString(os), true);
                        } else {
                            Toast.makeText(this, R.string.backup_toast_openpgp_not_verified, Toast.LENGTH_LONG).show();
                        }
                    } else {
                        restoreEntries(outputStreamToString(os), true);
                    }
                }
            }
        } else if (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR) == OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED) {
            PendingIntent pi = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT);

            // Can't do anything without this intent
            if (pi == null)
                return;

            // Small hack to keep the target file even after user interaction
            if (requestCode == Constants.INTENT_BACKUP_ENCRYPT_PGP) {
                encryptTargetFile = file;
            } else if (requestCode == Constants.INTENT_BACKUP_DECRYPT_PGP) {
                decryptSourceFile = file;
            }

            try {
                startIntentSenderForResult(pi.getIntentSender(), requestCode, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                e.printStackTrace();
            }
        } else if (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR) == OpenPgpApi.RESULT_CODE_ERROR) {
            OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);

            if (error != null)
                Toast.makeText(this, String.format(getString(R.string.backup_toast_openpgp_error), error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected boolean shouldDestroyOnScreenOff() {
        return allowExit;   // Don't destroy the backup activity as long as a backup task is running
    }
}

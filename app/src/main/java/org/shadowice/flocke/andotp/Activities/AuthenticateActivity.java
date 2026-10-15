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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.google.android.material.textfield.TextInputLayout;

import androidx.appcompat.widget.Toolbar;

import android.text.Editable;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewStub;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Tasks.AuthenticationTask;
import org.shadowice.flocke.andotp.Tasks.AuthenticationOutcome;
import org.shadowice.flocke.andotp.Tasks.AuthenticationError;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.EditorActionHelper;
import org.shadowice.flocke.andotp.Utilities.VaultSession;
import java.util.Arrays;
import org.shadowice.flocke.andotp.View.AutoFillable.AutoFillableTextInputEditText;

import static org.shadowice.flocke.andotp.Utilities.Constants.AuthMethod;

/** Asks for the password/PIN (or the device lock) and returns the derived database key. */
public class AuthenticateActivity extends BackgroundTaskActivity<AuthenticationOutcome>
        implements EditText.OnEditorActionListener, View.OnClickListener {
    private final AutoFillableTextInputEditText.AutoFillTextListener autoFillTextListener = text -> startAuthTask(text.toString());

    private AuthMethod authMethod;
    private String newEncryption = "";
    private String existingAuthCredentials;
    private boolean isAuthUpgrade = false;

    private TextInputLayout passwordLayout;
    AutoFillableTextInputEditText passwordInput;
    private Button unlockButton;
    private ProgressBar unlockProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (! settings.getScreenshotsEnabled())
            getWindow().setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE);

        authMethod = settings.getAuthMethod();
        newEncryption = getIntent().getStringExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION);
        existingAuthCredentials = settings.getAuthCredentials();
        if (existingAuthCredentials.isEmpty()) {
            existingAuthCredentials = settings.getOldCredentials(authMethod);
            isAuthUpgrade = true;
        }

        // If our password is still empty at this point, we can't do anything.
        if (existingAuthCredentials.isEmpty()) {
            int missingPwResId = (authMethod == AuthMethod.PASSWORD)
                    ? R.string.auth_toast_password_missing : R.string.auth_toast_pin_missing;
            Toast.makeText(this, missingPwResId, Toast.LENGTH_LONG).show();
            finishWithResult(false, null);
            return;
        }
        // If we're not using password or PIN as the auth method, we have nothing to authenticate here.
        if (authMethod != AuthMethod.PASSWORD && authMethod != AuthMethod.PIN) {
            finishWithResult(false, null);
            return;
        }

        setTitle(R.string.auth_activity_title);
        setContentView(R.layout.activity_container);
        initToolbar();
        initPasswordViews();

        setBroadcastCallback(() -> {
                if (settings.getRelockOnScreenOff()) {
                    cancelBackgroundTask();
                }
            });

        getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void initToolbar() {
        Toolbar toolbar = findViewById(R.id.container_toolbar);
        toolbar.setNavigationIcon(null);
        setSupportActionBar(toolbar);
    }

    private void initPasswordViews() {
        ViewStub stub = findViewById(R.id.container_stub);
        stub.setLayoutResource(R.layout.content_authenticate);
        View v = stub.inflate();

        initPasswordLabelView(v);
        initPasswordLayoutView(v);
        initPasswordInputView(v);
        initUnlockViews(v);
    }

    private void initPasswordLabelView(View v) {
        int labelMsg = getIntent().getIntExtra(Constants.EXTRA_AUTH_MESSAGE, R.string.auth_msg_authenticate);
        TextView passwordLabel = v.findViewById(R.id.passwordLabel);
        passwordLabel.setText(labelMsg);
    }

    private void initPasswordLayoutView(View v) {
        passwordLayout = v.findViewById(R.id.passwordLayout);
        int hintResId = (authMethod == AuthMethod.PASSWORD) ? R.string.auth_hint_password : R.string.auth_hint_pin;
        passwordLayout.setHint(getString(hintResId));
        if (settings.getBlockAccessibility()) {
            passwordLayout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        if (settings.getBlockAutofill()) {
            passwordLayout.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
    }

    private void initPasswordInputView(View v) {
        passwordInput = v.findViewById(R.id.passwordEdit);
        passwordInput.setSaveEnabled(false);
        int inputType = (authMethod == AuthMethod.PASSWORD)
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        passwordInput.setInputType(inputType);
        passwordInput.setTransformationMethod(new PasswordTransformationMethod());
        passwordInput.setOnEditorActionListener(this);
    }

    private void initUnlockViews(View v) {
        unlockButton = v.findViewById(R.id.buttonUnlock);
        unlockButton.setOnClickListener(this);
        unlockProgress = v.findViewById(R.id.unlockProgress);
    }

    @Override
    protected boolean cancelTaskOnScreenOff() {
        return true;
    }

    @Override
    protected void onReturnToCanceledTask() {
        passwordInput.setText("");
        passwordInput.requestFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboard.showSoftInput(passwordInput, 0);
    }

    @Override
    protected void setupUiForTaskState(boolean isTaskRunning) {
        if (passwordLayout == null) return;
        passwordLayout.setEnabled(!isTaskRunning);
        passwordInput.setEnabled(!isTaskRunning);
        unlockButton.setEnabled(!isTaskRunning);
        unlockButton.setVisibility(isTaskRunning? View.INVISIBLE : View.VISIBLE);
        unlockProgress.setVisibility(isTaskRunning ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onClick(View view) {
        Editable text = passwordInput.getText();
        startAuthTask(text != null ? text.toString() : "");
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (EditorActionHelper.isActionDoneOrKeyboardEnter(actionId, event)) {
            startAuthTask(v.getText().toString());
            return true;
        }
        return false;
    }

    private void startAuthTask(String plainPassword) {
        AuthenticationTask task = new AuthenticationTask(this, isAuthUpgrade, existingAuthCredentials, plainPassword);
        startBackgroundTask(task);
    }

    @Override
    protected void onTaskFailure(org.shadowice.flocke.andotp.Tasks.TaskFailure error) {
        finishWithResult(false, null);
    }

    @Override
    void onTaskResult(AuthenticationOutcome outcome) {
        if (outcome instanceof AuthenticationOutcome.Rejected) {
            if (((AuthenticationOutcome.Rejected) outcome).getError() == AuthenticationError.LEGACY_MIGRATION_FAILED)
                Toast.makeText(this, R.string.settings_toast_auth_upgrade_failed, Toast.LENGTH_LONG).show();
            finishWithResult(false, null);
            return;
        }
        AuthenticationOutcome.Verified verified = (AuthenticationOutcome.Verified) outcome;
        try {
            if (!VaultSession.isCurrent(verified.getGeneration())) finishWithResult(false, null);
            else finishWithResult(true, verified.getKey().takeBytes(), verified.getGeneration());
        } finally { verified.close(); }
    }

    private void finishWithResult(boolean success, byte[] encryptionKey) {
        finishWithResult(success, encryptionKey, VaultSession.generation());
    }

    private void finishWithResult(boolean success, byte[] encryptionKey, long expected) {
        Intent data = new Intent();
        if (newEncryption != null && !newEncryption.isEmpty())
            data.putExtra(Constants.EXTRA_AUTH_NEW_ENCRYPTION, newEncryption);
        if (encryptionKey != null) {
            success = success && VaultSession.offerDerivedKey(encryptionKey, expected);
            Arrays.fill(encryptionKey, (byte) 0);
        }
        if (success)
            setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWithResult(false, null);
        super.onBackPressed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (passwordInput != null && settings.getAutoUnlockAfterAutofill()) {
            passwordInput.setAutoFillTextListener(autoFillTextListener);
        }
    }

    @Override
    protected void onStop() {
        if (passwordInput != null) passwordInput.setAutoFillTextListener(null);
        super.onStop();
    }

    @Override protected void onVaultLocked() {
        cancelBackgroundTask();
        if (passwordInput != null) passwordInput.setText("");
        finish();
    }

    @Override
    protected boolean shouldDestroyOnScreenOff() {
        return false;
    }
}

/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 * Copyright (C) 2015 Bruno Bierbaumer
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

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;

import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.CheckedTextView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.leinardi.android.speeddial.SpeedDialView;

import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.Dialogs.HideableDialog;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.EncryptionHelper;
import org.shadowice.flocke.andotp.Utilities.DatabaseHelper;
import org.shadowice.flocke.andotp.Utilities.VaultReadResult;
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult;
import org.shadowice.flocke.andotp.Utilities.VaultCommitDecision;
import org.shadowice.flocke.andotp.Utilities.VaultSession;
import org.shadowice.flocke.andotp.Utilities.SessionCommandResult;
import org.shadowice.flocke.andotp.Utilities.SessionFailure;
import org.shadowice.flocke.andotp.Utilities.SessionLockReason;
import java.util.Arrays;
import org.shadowice.flocke.andotp.Utilities.KeyStoreHelper;
import org.shadowice.flocke.andotp.Utilities.NotificationHelper;
import org.shadowice.flocke.andotp.Utilities.ScanQRCodeFromFile;
import org.shadowice.flocke.andotp.Utilities.TokenCalculator;
import org.shadowice.flocke.andotp.View.EntriesCardAdapter;
import org.shadowice.flocke.andotp.View.ItemTouchHelper.SimpleItemTouchHelperCallback;
import org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog;
import org.shadowice.flocke.andotp.View.TagsAdapter;
import org.shadowice.flocke.andotp.Utilities.ScreenTimerController;
import org.shadowice.flocke.andotp.Utilities.HandlerTimerScheduler;

import java.util.ArrayList;
import java.util.HashMap;

import javax.crypto.SecretKey;

import static org.shadowice.flocke.andotp.Utilities.Constants.AuthMethod;
import static org.shadowice.flocke.andotp.Utilities.Constants.EncryptionType;
import static org.shadowice.flocke.andotp.Utilities.Constants.SortMode;

/** Main screen: the entry list with search, tag drawer and authentication handling. */
public class MainActivity extends BaseActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static long animatorDuration = 1000;

    private static final String INTENT_SCAN_QR = "org.shadowice.flocke.andotp.intent.SCAN_QR";
    private static final String INTENT_IMPORT_QR = "org.shadowice.flocke.andotp.intent.IMPORT_QR";
    private static final String INTENT_ENTER_DETAILS = "org.shadowice.flocke.andotp.intent.ENTER_DETAILS";

    private EntriesCardAdapter adapter;
    private SpeedDialView speedDial;
    private MenuItem sortMenu;
    private MenuItem searchMenu;
    private SimpleItemTouchHelperCallback touchHelperCallback;

    private EncryptionType encryptionType = EncryptionType.KEYSTORE;
    private boolean requireAuthentication = false;
    private boolean authenticationPending;
    private long authenticationGeneration;
    private final ProcessLifecycleObserver processObserver = new ProcessLifecycleObserver();

    private boolean recreateActivity = false;
    private boolean focusSearchOnCreate = false;

    private ScreenTimerController screenTimers;

    private DrawerLayout tagsDrawerLayout;
    private ListView tagsDrawerListView;
    private TagsAdapter tagsDrawerAdapter;
    private ActionBarDrawerToggle tagsToggle;
    private String filterString;

    private ProgressBar progressBar;
    private TextView emptyListView;

    private void scanQRCode(){
        new IntentIntegrator(MainActivity.this)
                .setOrientationLocked(false)
                .setBeepEnabled(false)
                .setCaptureActivity(SecureCaptureActivity.class)
                .initiateScan();
    }

    private long introGeneration;

    private void showFirstTimeWarning() {
        introGeneration = VaultSession.generation();
        Intent introIntent = new Intent(this, IntroScreenActivity.class);
        startActivityForResult(introIntent, Constants.INTENT_MAIN_INTRO);
    }

    /** Starts the configured authentication flow, showing {@code messageId} as prompt. */
    public void authenticate(int messageId) {
        if (authenticationPending) return;
        long attempt = VaultSession.beginUnlock().getGeneration();
        requireAuthentication = false;
        authenticationPending = true;
        authenticationGeneration = attempt;
        AuthMethod authMethod = settings.getAuthMethod();
        if (authMethod == AuthMethod.DEVICE) {
            KeyguardManager manager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (manager == null || !manager.isKeyguardSecure()) { vaultUnavailable(); return; }
            Intent challenge = manager.createConfirmDeviceCredentialIntent(getString(R.string.dialog_title_auth), getString(R.string.dialog_msg_auth));
            if (challenge == null) { vaultUnavailable(); return; }
            startActivityForResult(challenge, Constants.INTENT_MAIN_AUTHENTICATE);
        } else if (authMethod == AuthMethod.PASSWORD || authMethod == AuthMethod.PIN) {
            Intent challenge = new Intent(this, AuthenticateActivity.class);
            challenge.putExtra(Constants.EXTRA_AUTH_MESSAGE, messageId);
            startActivityForResult(challenge, Constants.INTENT_MAIN_AUTHENTICATE);
        } else { vaultUnavailable(); }
    }

    private void vaultUnavailable() {
        authenticationPending = false;
        VaultSession.fail(SessionFailure.VAULT_UNAVAILABLE);
        new AlertDialog.Builder(this).setTitle(R.string.security_vault_error_title)
                .setMessage(R.string.security_vault_error).setCancelable(false)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> finishAndRemoveTask()).show();
    }

    @Override protected void onVaultLocked() {
        authenticationPending = false;
        requireAuthentication = true;
        if (adapter != null) adapter.lockEntries();
        if (screenTimers != null) screenTimers.pause();
        View cards = findViewById(R.id.cardList);
        if (cards != null) cards.setVisibility(View.INVISIBLE);
    }

    // Everything that should happen after the app is unlocked goes here
    private void afterAuthentication() {
        if (VaultSession.getKey() == null || isFinishing()) return;
        checkAutomaticTime();
    }

    private void restoreSortMode() {
        if (settings != null && adapter != null && touchHelperCallback != null) {
            SortMode mode = settings.getSortMode();
            adapter.setSortMode(mode);

            touchHelperCallback.setDragEnabled(mode == SortMode.UNSORTED);
        }
    }

    private void saveSortMode(SortMode mode) {
        if (settings != null)
            settings.setSortMode(mode);
    }

    /** Loads the entries into the adapter and refreshes the tag drawer. */
    private void populateAdapter() {
        if (!adapter.loadEntries()) { vaultUnavailable(); return; }
        tagsDrawerAdapter.setTags(TagsAdapter.createTagsMap(adapter.getEntries(), settings));
        adapter.filterByTags(tagsDrawerAdapter.getActiveTags());
    }

    private void checkAutomaticTime() {
        int autoTime = Settings.Global.getInt(getContentResolver(), Settings.Global.AUTO_TIME, 0);

        if (autoTime == 0)
            HideableDialog.ShowHidableDialog(this, R.string.dialog_title_auto_time, R.string.dialog_msg_auto_time, R.string.settings_key_dialog_hide_auto_time);
    }

    // Initialize the main application
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle(R.string.app_name);

        if (! settings.getScreenshotsEnabled())
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        settings.registerPreferenceChangeListener(this);

        encryptionType = settings.getEncryption();

        // Activity state is not proof of an unlock; process death/recreation must reauthenticate.
        VaultSession.lock();
        requireAuthentication = settings.getAuthMethod() != AuthMethod.NONE || encryptionType == EncryptionType.PASSWORD;

        setBroadcastCallback(() -> { if (settings.getRelockOnScreenOff()) onVaultLocked(); });

        ProcessLifecycleOwner.get().getLifecycle().addObserver(processObserver);

        if (!settings.getFirstTimeWarningShown())
            showFirstTimeWarning();
        else if (!requireAuthentication)
            afterAuthentication();

        speedDial = findViewById(R.id.speedDial);
        speedDial.inflate(R.menu.menu_fab);

        speedDial.getMainFab().setContentDescription(getString(R.string.button_add));

        speedDial.setOnActionSelectedListener(speedDialActionItem -> {
            if (VaultSession.getKey() == null) return false;
            int actionId = speedDialActionItem.getId();

            if (actionId == R.id.fabScanQR)
                scanQRCode();
            else if (actionId == R.id.fabEnterDetails)
                ManualEntryDialog.show(MainActivity.this, settings, adapter);
            else if (actionId == R.id.fabScanQRFromImage)
                showOpenFileSelector(Constants.INTENT_MAIN_QR_OPEN_IMAGE);

            return false;
        });

        speedDial.setOnChangeListener(new SpeedDialView.OnChangeListener() {
            @Override
            public boolean onMainActionSelected() {
                return false;
            }

            @Override
            public void onToggleChanged(boolean isOpen) {
                if (isOpen) {
                    speedDial.getMainFab().setContentDescription(getString(R.string.button_close_menu));
                } else {
                    speedDial.getMainFab().setContentDescription(getString(R.string.button_add));
                }
            }
        });

        progressBar = findViewById(R.id.progressBar);
        emptyListView = findViewById(R.id.emptyListView);

        RecyclerView recList = findViewById(R.id.cardList);
        recList.setHasFixedSize(true);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setOrientation(LinearLayoutManager.VERTICAL);
        recList.setLayoutManager(llm);

        tagsDrawerAdapter = new TagsAdapter(this, new HashMap<>());
        adapter = new EntriesCardAdapter(this, tagsDrawerAdapter);
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                super.onChanged();
                hideProgressBar();
            }

            @Override
            public void onItemRangeChanged(int positionStart, int itemCount) {
                super.onItemRangeChanged(positionStart, itemCount);
                hideProgressBar();
            }

            @Override
            public void onItemRangeChanged(int positionStart, int itemCount, @Nullable Object payload) {
                super.onItemRangeChanged(positionStart, itemCount, payload);
                hideProgressBar();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                hideProgressBar();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                super.onItemRangeRemoved(positionStart, itemCount);
                hideProgressBar();
            }

            @Override
            public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
                super.onItemRangeMoved(fromPosition, toPosition, itemCount);
                hideProgressBar();
            }
        });

        recList.setAdapter(adapter);

        touchHelperCallback = new SimpleItemTouchHelperCallback(adapter);
        ItemTouchHelper touchHelper = new ItemTouchHelper(touchHelperCallback);
        touchHelper.attachToRecyclerView(recList);

        NotificationHelper.initializeNotificationChannels(this);
        restoreSortMode();

        float durationScale = android.provider.Settings.Global.getFloat(this.getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0);
        if (durationScale == 0)
            durationScale = 1;

        animatorDuration = (long) (1000 / durationScale);

        adapter.setCallback(new EntriesCardAdapter.Callback() {
            @Override
            public void onMoveEventStart() {
                stopUpdater();
            }

            @Override
            public void onMoveEventStop() {
                startUpdater();
            }
        });

        screenTimers = new ScreenTimerController(new HandlerTimerScheduler(), this::renderTokenTick,
                () -> authenticate(R.string.auth_msg_authenticate));

        setupDrawer();

        if (savedInstanceState != null) {
            setFilterString(savedInstanceState.getString("filterString", ""));
        }

        if (settings.isFocusSearchOnStartEnabled())
            focusSearchMenu();
    }

    private void checkIntent() {
        Intent callingIntent = getIntent();
        if (callingIntent != null && callingIntent.getAction() != null) {
            // Cache and reset the action to prevent the same intent from being evaluated multiple times
            String intentAction = callingIntent.getAction();
            callingIntent.setAction(null);

            switch (intentAction) {
                case INTENT_SCAN_QR:
                    scanQRCode();
                    break;
                case INTENT_IMPORT_QR:
                    showOpenFileSelector(Constants.INTENT_MAIN_QR_OPEN_IMAGE);
                    break;
                case INTENT_ENTER_DETAILS:
                    ManualEntryDialog.show(MainActivity.this, settings, adapter);
                    break;
                case Intent.ACTION_VIEW:
                    try {
                        Entry entry = new Entry(callingIntent.getDataString());
                        long epoch = VaultSession.generation();
                        new AlertDialog.Builder(this).setTitle(R.string.security_import_title)
                                .setMessage(entry.getIssuer() + "\n" + entry.getLabel())
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                    if (!VaultSession.isCurrent(epoch) || VaultSession.getKey() == null) { entry.destroy(); return; }
                                    try { entry.updateOTP(false); entry.setLastUsed(System.currentTimeMillis()); adapter.addEntry(entry); refreshTags(); }
                                    catch (RuntimeException error) { entry.destroy(); Toast.makeText(this, R.string.toast_intent_creation_failed, Toast.LENGTH_LONG).show(); }
                                }).setNegativeButton(android.R.string.cancel, (dialog, which) -> entry.destroy())
                                .setOnCancelListener(dialog -> entry.destroy()).show();
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.toast_intent_creation_failed, Toast.LENGTH_LONG).show();
                    }
                    break;
            }
        }
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        tagsToggle.syncState();
    }

    // Controls for the updater background task
    public void stopUpdater() {
        if (screenTimers != null) screenTimers.stopTicks();
    }

    public void startUpdater() {
        if (screenTimers != null) screenTimers.startTicks();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (isFinishing() || authenticationPending) return;
        if (!settings.getFirstTimeWarningShown()) return;
        if (requireAuthentication) {
            if (settings.getAuthMethod() != AuthMethod.NONE || settings.getEncryption() == EncryptionType.PASSWORD) {
                authenticate(R.string.auth_msg_authenticate);
                return;
            }
            requireAuthentication = false;
        }
        if (adapter.getEncryptionKey() == null) updateEncryption(null);
        else populateAdapter();
        if (authenticationPending || adapter.getEncryptionKey() == null || isFinishing()) return;
        checkIntent();
        restartInactivityTimer();

        if (filterString != null) {
            // Keep the search filter applied after a resume
            setFilterString(this.filterString);
        }

        View cardList = findViewById(R.id.cardList);
        if(cardList.getVisibility() == View.INVISIBLE)
            cardList.setVisibility(View.VISIBLE);

        startUpdater();
    }

    @Override
    public void onPause() {
        if(settings.getAuthMethod() == AuthMethod.DEVICE)
            runOnUiThread(() -> findViewById(R.id.cardList).setVisibility(View.INVISIBLE));
        super.onPause();
        stopUpdater();

        if (screenTimers != null) screenTimers.pause();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("filterString", filterString);


    }

    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key.equals(getString(R.string.settings_key_label_size)) ||
                key.equals(getString(R.string.settings_key_label_display)) ||
                key.equals(getString(R.string.settings_key_split_group_size)) ||
                key.equals(getString(R.string.settings_key_thumbnail_size))) {
            adapter.notifyDataSetChanged();
        } else if (key.equals(getString(R.string.settings_key_search_includes))) {
            adapter.clearFilter();
        } else if (key.equals(getString(R.string.settings_key_tap_single)) ||
                key.equals(getString(R.string.settings_key_tap_double)) ||
                key.equals(getString(R.string.settings_key_theme)) ||
                key.equals(getString(R.string.settings_key_lang)) ||
                key.equals(getString(R.string.settings_key_enable_screenshot)) ||
                key.equals(getString(R.string.settings_key_tag_functionality)) ||
                key.equals(getString(R.string.settings_key_label_highlight_token)) ||
                key.equals(getString(R.string.settings_key_card_layout)) ||
                key.equals(getString(R.string.settings_key_theme_mode)) ||
                key.equals(getString(R.string.settings_key_theme_black_auto)) ||
                key.equals(getString(R.string.settings_key_hide_global_timeout)) ||
                key.equals(getString(R.string.settings_key_hide_issuer)) ||
                key.equals(getString(R.string.settings_key_show_prev_token))) {
            recreateActivity = true;
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        tagsToggle.onConfigurationChanged(newConfig);
    }

    // Activity results
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
        if(result != null) {
            if(result.getContents() != null) {
                addQRCode(result.getContents());
            }
        } else if (requestCode == Constants.INTENT_MAIN_BACKUP && resultCode == RESULT_OK) {
            if (intent != null && intent.getBooleanExtra("reload", false)) {
                adapter.loadEntries();
                refreshTags();
            }
        } else if (requestCode == Constants.INTENT_MAIN_SETTINGS && resultCode == RESULT_OK) {
            boolean encryptionChanged = intent != null && intent.getBooleanExtra(Constants.EXTRA_SETTINGS_ENCRYPTION_CHANGED, false);
            if (encryptionChanged) {
                SecretKey key = VaultSession.getKey();
                if (key != null) adapter.setEncryptionKey(key);
                else requireAuthentication = true;
            }

            if (recreateActivity) {
                recreate();
            }
        } else if (requestCode == Constants.INTENT_MAIN_AUTHENTICATE) {
            if (resultCode != RESULT_OK || !authenticationPending || !VaultSession.isCurrent(authenticationGeneration)) {
                Toast.makeText(getBaseContext(), R.string.toast_auth_failed_fatal, Toast.LENGTH_LONG).show();
                finishAndRemoveTask();
            } else {
                authenticationPending = false;
                requireAuthentication = false;
                byte[] authKey = VaultSession.takeDerivedKey(authenticationGeneration);
                try { updateEncryption(authKey, authenticationGeneration); }
                finally { if (authKey != null) Arrays.fill(authKey, (byte) 0); }
                afterAuthentication();
            }
        } else if (requestCode == Constants.INTENT_MAIN_QR_OPEN_IMAGE && resultCode == RESULT_OK) {
            if (intent != null) {
                addQRCode(ScanQRCodeFromFile.scanQRImage(this, intent.getData()));
            }
        } else if (requestCode == Constants.INTENT_MAIN_INTRO) {
            boolean setupFinished = false;

            if (resultCode == RESULT_OK && intent != null)
                setupFinished = intent.getBooleanExtra(Constants.EXTRA_INTRO_FINISHED, false);

            if (setupFinished) {
                requireAuthentication = false;

                byte[] key = VaultSession.takeDerivedKey(introGeneration);
                try { updateEncryption(key, introGeneration); }
                finally { if (key != null) Arrays.fill(key, (byte) 0); }

                afterAuthentication();
            } else {
                finishAndRemoveTask();
            }
        }
    }

    /** (Re)creates the adapter's encryption key and reloads the entries. */
    private void updateEncryption(byte[] newKey) {
        updateEncryption(newKey, VaultSession.generation());
    }

    private void updateEncryption(byte[] newKey, long expectedGeneration) {
        synchronized (DatabaseHelper.DatabaseFileLock) {
            if (!VaultSession.isCurrent(expectedGeneration)) { vaultUnavailable(); return; }
            SecretKey encryptionKey = null;

            encryptionType = settings.getEncryption();

            if (encryptionType == EncryptionType.KEYSTORE) {
                encryptionKey = KeyStoreHelper.loadEncryptionKeyFromKeyStore(this, false);
            } else if (encryptionType == EncryptionType.PASSWORD) {
                if (newKey != null && newKey.length > 0) {
                    encryptionKey = EncryptionHelper.generateSymmetricKey(newKey);
                } else {
                    authenticate(R.string.auth_msg_confirm_encryption);
                }
            }

            if (encryptionKey == null) {
                if (!authenticationPending) vaultUnavailable();
                return;
            }
            VaultReadResult loaded = DatabaseHelper.readVault(this, encryptionKey);
            if (!(loaded instanceof VaultReadResult.Loaded)) { vaultUnavailable(); return; }
            ((VaultReadResult.Loaded) loaded).close();
            if (!(VaultSession.activate(encryptionKey, expectedGeneration) instanceof SessionCommandResult.Applied)) { vaultUnavailable(); return; }
            adapter.setEncryptionKey(encryptionKey);
            populateAdapter();
        }
    }

    // Options menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        sortMenu = menu.findItem(R.id.menu_sort);

        if (adapter != null) {
            SortMode mode = adapter.getSortMode();

            if (mode == SortMode.UNSORTED) {
                sortMenu.setIcon(R.drawable.ic_sort_inverted_white);
                menu.findItem(R.id.menu_sort_none).setChecked(true);
            } else if (mode == SortMode.ISSUER) {
                sortMenu.setIcon(R.drawable.ic_sort_inverted_label_white);
                menu.findItem(R.id.menu_sort_issuer).setChecked(true);
            } else if (mode == SortMode.LABEL) {
                sortMenu.setIcon(R.drawable.ic_sort_inverted_label_white);
                menu.findItem(R.id.menu_sort_label).setChecked(true);
            } else if (mode == SortMode.LAST_USED) {
                sortMenu.setIcon(R.drawable.ic_sort_inverted_time_white);
                menu.findItem(R.id.menu_sort_last_used).setChecked(true);
            } else if (mode == SortMode.MOST_USED) {
                sortMenu.setIcon(R.drawable.ic_sort_inverted_time_white);
                menu.findItem(R.id.menu_sort_most_used).setChecked(true);
            }
        }

        searchMenu = menu.findItem(R.id.menu_search);
        SearchView searchView = (SearchView) searchMenu.getActionView();
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                setFilterString(newText);
                return false;
            }
        });

        searchMenu.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(MenuItem menuItem) {
                speedDial.setVisibility(View.GONE);
                touchHelperCallback.setDragEnabled(false);
                if (sortMenu != null)
                    sortMenu.setVisible(false);
                return true;
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem menuItem) {
                speedDial.setVisibility(View.VISIBLE);

                if (adapter == null || adapter.getSortMode() == SortMode.UNSORTED)
                    touchHelperCallback.setDragEnabled(true);

                if (sortMenu != null)
                    sortMenu.setVisible(true);

                return true;
            }
        });

        if (focusSearchOnCreate) {
            searchMenu.expandActionView();
            focusSearchOnCreate = false;
        }

        return true;
    }

    /** Expands the search view (deferred to the next menu creation if needed). */
    private void focusSearchMenu() {
        if (searchMenu != null)
            searchMenu.expandActionView();
        else
            focusSearchOnCreate = true;
    }

    /** Applies the search text, or the tag filter when the search is empty. */
    private void setFilterString(String newText) {
        if (newText.isEmpty())
            adapter.filterByTags(tagsDrawerAdapter.getActiveTags());
        else
            adapter.getFilter().filter(newText);

        this.filterString = newText;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_backup) {
            Intent backupIntent = new Intent(this, BackupActivity.class);
            if (VaultSession.getKey() == null) return true;
            startActivityForResult(backupIntent, Constants.INTENT_MAIN_BACKUP);
        } else if (id == R.id.action_settings) {
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            if (VaultSession.getKey() == null) return true;
            startActivityForResult(settingsIntent, Constants.INTENT_MAIN_SETTINGS);
        } else if (id == R.id.action_about){
            Intent aboutIntent = new Intent(this, AboutActivity.class);
            startActivity(aboutIntent);
            return true;
        } else if (id == R.id.menu_sort_none) {
            item.setChecked(true);
            sortMenu.setIcon(R.drawable.ic_sort_inverted_white);
            saveSortMode(SortMode.UNSORTED);
            if (adapter != null) {
                adapter.setSortMode(SortMode.UNSORTED);
                touchHelperCallback.setDragEnabled(true);
            }
        } else if (id == R.id.menu_sort_issuer) {
            item.setChecked(true);
            sortMenu.setIcon(R.drawable.ic_sort_inverted_label_white);
            saveSortMode(SortMode.ISSUER);
            if(adapter != null) {
                adapter.setSortMode(SortMode.ISSUER);
                touchHelperCallback.setDragEnabled(false);
            }
        } else if (id == R.id.menu_sort_label) {
            item.setChecked(true);
            sortMenu.setIcon(R.drawable.ic_sort_inverted_label_white);
            saveSortMode(SortMode.LABEL);
            if (adapter != null) {
                adapter.setSortMode(SortMode.LABEL);
                touchHelperCallback.setDragEnabled(false);
            }
        } else if (id == R.id.menu_sort_last_used) {
            item.setChecked(true);
            sortMenu.setIcon(R.drawable.ic_sort_inverted_time_white);
            saveSortMode(SortMode.LAST_USED);
            if (adapter != null) {
                adapter.setSortMode(SortMode.LAST_USED);
                touchHelperCallback.setDragEnabled(false);
            }
            if (! settings.getUsedTokensDialogShown())
                showUsedTokensDialog();
        } else if (id == R.id.menu_sort_most_used) {
            item.setChecked(true);
            sortMenu.setIcon(R.drawable.ic_sort_inverted_time_white);
            saveSortMode(SortMode.MOST_USED);
            if (adapter != null) {
                adapter.setSortMode(SortMode.MOST_USED);
                touchHelperCallback.setDragEnabled(false);
            }
            if (! settings.getUsedTokensDialogShown())
                showUsedTokensDialog();
        } else if (tagsToggle.onOptionsItemSelected(item)) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showUsedTokensDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.dialog_title_used_tokens)
                .setMessage(R.string.dialog_msg_used_tokens)
                .setPositiveButton(android.R.string.ok, (DialogInterface dialogInterface, int i) -> settings.setUsedTokensDialogShown(true))
                .create()
                .show();
    }

    private void setupDrawer() {
        tagsDrawerListView = findViewById(R.id.tags_list_in_drawer);
        tagsDrawerLayout = findViewById(R.id.drawer_layout);

        tagsToggle = new ActionBarDrawerToggle(this, tagsDrawerLayout, R.string.drawer_open, R.string.drawer_close) {
            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);

                if (getSupportActionBar() != null)
                    getSupportActionBar().setTitle(R.string.label_tags);

                invalidateOptionsMenu();
            }

            @Override
            public void onDrawerClosed(View view) {
                super.onDrawerClosed(view);

                if (getSupportActionBar() != null)
                    getSupportActionBar().setTitle(R.string.app_name);

                invalidateOptionsMenu();
            }
        };

        tagsToggle.setDrawerIndicatorEnabled(true);
        tagsDrawerLayout.addDrawerListener(tagsToggle);

        final CheckedTextView noTagsButton = findViewById(R.id.no_tags_entries);
        final CheckedTextView allTagsButton = findViewById(R.id.all_tags_in_drawer);

        allTagsButton.setOnClickListener(view -> {
            CheckedTextView checkedTextView = ((CheckedTextView)view);
            checkedTextView.setChecked(!checkedTextView.isChecked());

            settings.setAllTagsToggle(checkedTextView.isChecked());

            for(int i = 0; i < tagsDrawerListView.getChildCount(); i++) {
                CheckedTextView childCheckBox = (CheckedTextView) tagsDrawerListView.getChildAt(i);
                childCheckBox.setChecked(checkedTextView.isChecked());
            }

            for (String tag: tagsDrawerAdapter.getTags()) {
                tagsDrawerAdapter.setTagState(tag, checkedTextView.isChecked());
                settings.setTagToggle(tag, checkedTextView.isChecked());
            }

            if(checkedTextView.isChecked()) {
                adapter.filterByTags(tagsDrawerAdapter.getActiveTags());
            } else {
                adapter.filterByTags(new ArrayList<>());
            }
        });
        allTagsButton.setChecked(settings.getAllTagsToggle());

        noTagsButton.setOnClickListener(view -> {
            CheckedTextView checkedTextView = ((CheckedTextView)view);
            checkedTextView.setChecked(!checkedTextView.isChecked());

            if(settings.getTagFunctionality() == Constants.TagFunctionality.SINGLE) {
                checkedTextView.setChecked(true);
                allTagsButton.setChecked(false);
                settings.setAllTagsToggle(false);

                for (String tag: tagsDrawerAdapter.getTags()) {
                    settings.setTagToggle(tag, false);
                    tagsDrawerAdapter.setTagState(tag, false);
                }
            }

            settings.setNoTagsToggle(checkedTextView.isChecked());
            adapter.filterByTags(tagsDrawerAdapter.getActiveTags());
        });
        noTagsButton.setChecked(settings.getNoTagsToggle());

        tagsDrawerListView.setAdapter(tagsDrawerAdapter);
        tagsDrawerListView.setOnItemClickListener((parent, view, position, id) -> {
            CheckedTextView checkedTextView = ((CheckedTextView)view);

            if(settings.getTagFunctionality() == Constants.TagFunctionality.SINGLE) {
                allTagsButton.setChecked(false);
                settings.setAllTagsToggle(false);
                noTagsButton.setChecked(false);
                settings.setNoTagsToggle(false);

                for (String tag: tagsDrawerAdapter.getTags()) {
                    settings.setTagToggle(tag, false);
                    tagsDrawerAdapter.setTagState(tag, false);
                }
                checkedTextView.setChecked(true);
            }else {
                checkedTextView.setChecked(!checkedTextView.isChecked());
            }

            settings.setTagToggle(checkedTextView.getText().toString(), checkedTextView.isChecked());
            tagsDrawerAdapter.setTagState(checkedTextView.getText().toString(), checkedTextView.isChecked());

            if (! checkedTextView.isChecked()) {
                allTagsButton.setChecked(false);
                settings.setAllTagsToggle(false);
            }

            if (tagsDrawerAdapter.allTagsActive()) {
                allTagsButton.setChecked(true);
                settings.setAllTagsToggle(true);
            }

            adapter.filterByTags(tagsDrawerAdapter.getActiveTags());
        });

        adapter.filterByTags(tagsDrawerAdapter.getActiveTags());
    }

    /** Rebuilds the tag drawer after entries were added or removed. */
    public void refreshTags() {
        HashMap<String, Boolean> tagsHashMap = new HashMap<>();
        for(String tag: tagsDrawerAdapter.getTags()) {
            tagsHashMap.put(tag, false);
        }
        for(String tag: tagsDrawerAdapter.getActiveTags()) {
            tagsHashMap.put(tag, true);
        }
        for(String tag: adapter.getTags()) {
            if(!tagsHashMap.containsKey(tag))
                tagsHashMap.put(tag, true);
        }
        tagsDrawerAdapter.setTags(tagsHashMap);
        adapter.filterByTags(tagsDrawerAdapter.getActiveTags());
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartInactivityTimer();
    }

    /** The screen translates preferences to policy; the timer owns only scheduling. */
    private void restartInactivityTimer() {
        if (screenTimers == null) return;
        long delay = settings.getAuthMethod() == AuthMethod.NONE || !settings.getAuthInactivity()
                ? 0 : 1000L * settings.getAuthInactivityDelay();
        screenTimers.restartInactivity(delay);
    }

    /** Rendering stays in the Activity; scheduling and teardown do not. */
    private void renderTokenTick() {
        if (!settings.isHideGlobalTimeoutEnabled()) {
            int progress = (int) (TokenCalculator.TOTP_DEFAULT_PERIOD - (System.currentTimeMillis() / 1000) % TokenCalculator.TOTP_DEFAULT_PERIOD);
            progressBar.setProgress(progress * 100);
            ObjectAnimator animation = ObjectAnimator.ofInt(progressBar, "progress", (progress - 1) * 100);
            animation.setDuration(animatorDuration);
            animation.setInterpolator(new LinearInterpolator());
            animation.start();
        }
        adapter.updateTimeBasedTokens();
    }

    @SuppressWarnings("SameParameterValue")
    private void showOpenFileSelector(int intentId){
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, intentId);
    }
    
    /** Adds the entry encoded in the given otpauth URI. */
    private void addQRCode(String result){
        if(!TextUtils.isEmpty(result)) {
            try {
                Entry e = new Entry(result);
                e.updateOTP(false);
                e.setLastUsed(System.currentTimeMillis());
                adapter.addEntry(e);
                refreshTags();
            } catch (Exception e) {
                Toast.makeText(this, R.string.toast_invalid_qr_code, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Locks the app when it is sent to the background (if enabled). */
    private class ProcessLifecycleObserver implements DefaultLifecycleObserver {
        @Override
        public void onStop(@NonNull LifecycleOwner owner) {
            // No key is loaded while the system device-credential challenge is pending.
            if (settings.getRelockOnBackground() && !(authenticationPending && settings.getAuthMethod() == AuthMethod.DEVICE)) VaultSession.lock(SessionLockReason.BACKGROUND);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (speedDial.isOpen()) {
                speedDial.close();
                return true;
            }

            if (tagsDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                tagsDrawerLayout.closeDrawer(GravityCompat.START);
                return true;
            }

            return super.onKeyDown(keyCode, event);
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        settings.unregisterPreferenceChangeListener(this);
        ProcessLifecycleOwner.get().getLifecycle().removeObserver(processObserver);
        if (adapter != null) adapter.lockEntries();
        if (screenTimers != null) screenTimers.close();
        if (isFinishing()) VaultSession.lock();
        super.onDestroy();
    }

    /**
     * Hides the progress bar (also when the global timeout is turned off) and shows the
     * "how to add tokens" hint while the list is empty.
     */
    private void hideProgressBar(){
        int itemCount = adapter.getItemCount();
        progressBar.setVisibility((settings.isHideGlobalTimeoutEnabled() || itemCount <= 0) ? View.GONE : View.VISIBLE);
        emptyListView.setVisibility(itemCount > 0 ? View.GONE : View.VISIBLE);

    }

    @Override
    protected boolean shouldDestroyOnScreenOff() {
        return false;
    }
}

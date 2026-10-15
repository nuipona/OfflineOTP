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

package org.shadowice.flocke.andotp.View;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Handler;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import org.shadowice.flocke.andotp.Activities.MainActivity;
import org.shadowice.flocke.andotp.Database.Entry;
import org.shadowice.flocke.andotp.Database.EntryList;
import org.shadowice.flocke.andotp.Dialogs.ManualEntryDialog;
import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Tasks.BackupTaskResult;
import org.shadowice.flocke.andotp.Tasks.TaskOutcome;
import org.shadowice.flocke.andotp.Utilities.EntryPersistence;
import org.shadowice.flocke.andotp.Utilities.Constants;
import org.shadowice.flocke.andotp.Utilities.VaultReadResult;
import org.shadowice.flocke.andotp.Utilities.VaultWriteResult;
import org.shadowice.flocke.andotp.Utilities.VaultSession;
import org.shadowice.flocke.andotp.Utilities.InputPolicy;
import org.shadowice.flocke.andotp.Utilities.Settings;
import org.shadowice.flocke.andotp.Utilities.Tools;
import org.shadowice.flocke.andotp.View.ItemTouchHelper.ItemTouchHelperAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

import javax.crypto.SecretKey;

import static org.shadowice.flocke.andotp.Utilities.Constants.SortMode;

/**
 * Adapter for the entry cards; keeps the persisted {@link EntryList} and the (sorted and
 * filtered) list that is actually displayed.
 */
public class EntriesCardAdapter extends RecyclerView.Adapter<EntryViewHolder>
    implements ItemTouchHelperAdapter, Filterable {
    private final Context context;
    private final Handler taskHandler;
    private EntryFilter filter;
    private final EntryList entries;
    private final EntryListPresentation presentation;
    private final EntryPersistence persistence;
    private final EntryActionDialogs dialogs;
    private Callback callback;

    private SecretKey encryptionKey = null;

    private final TagsAdapter tagsFilterAdapter;
    private final Settings settings;

    private static final int ESTABLISH_PIN_MENU_INDEX = 4;

    public EntriesCardAdapter(Context context, TagsAdapter tagsFilterAdapter) {
        this.context = context;
        this.tagsFilterAdapter = tagsFilterAdapter;
        this.settings = new Settings(context);
        this.taskHandler = new Handler(Looper.getMainLooper());
        this.entries = new EntryList();
        this.presentation = new EntryListPresentation(entries);
        this.persistence = new EntryPersistence(context);
        this.dialogs = new EntryActionDialogs(context, settings);

        setHasStableIds(true);
    }

    public void setEncryptionKey(SecretKey key) {
        encryptionKey = key;
    }

    public SecretKey getEncryptionKey() {
        return encryptionKey;
    }

    @Override
    public int getItemCount() {
        return presentation.size();
    }

    @Override
    public long getItemId(int position) {
        return presentation.get(position).getListId();
    }

    public ArrayList<Entry> getEntries() {
        return entries.getEntries();
    }

    public void saveAndRefresh(boolean auto_backup) {
        saveAndRefresh(auto_backup, RecyclerView.NO_POSITION);
    }

    public void saveAndRefresh(boolean auto_backup, int itemPos) {
        updateTagsFilter();
        entriesChanged(itemPos);
        saveEntries(auto_backup);
    }

    /** Adds the entry (with a toast if it is already present) and saves the database. */
    public void addEntry(Entry e) {
        if (encryptionKey == null || VaultSession.getKey() != encryptionKey) throw new IllegalStateException("Vault locked");
        e.validate();
        if (entries.getEntries().size() >= InputPolicy.MAX_ENTRIES) throw new IllegalArgumentException("Too many entries");
        if (entries.addEntry(e)) {
            saveAndRefresh(settings.getAutoBackupEncryptedPasswordsEnabled());
        } else {
            Toast.makeText(context, R.string.toast_entry_exists, Toast.LENGTH_LONG).show();
        }
    }

    /** Maps a position in the displayed list to the position in the persisted list. */
    private int getRealIndex(int displayPosition) {
        return presentation.sourceIndex(displayPosition);
    }

    /** Re-sorts and re-filters the displayed list and notifies the view. */
    private void entriesChanged(int itemPos) {
        clearFilter();
        presentation.refreshTags(settings.getNoTagsToggle(), settings.getTagFunctionality());
        notifyDataSetChanged();

        if (itemPos == RecyclerView.NO_POSITION)
            notifyDataSetChanged();
        else
            notifyItemChanged(itemPos);
    }

    /** Rebuilds the tag drawer from the tags that are actually in use. */
    public void updateTagsFilter() {
        tagsFilterAdapter.setTags(presentation.reconcileTags(
                tagsFilterAdapter.getTags(), tagsFilterAdapter.getActiveTags()));
        presentation.selectTags(tagsFilterAdapter.getActiveTags());
    }

    /** Saves the database and, if enabled, writes an encrypted automatic backup. */
    public void saveEntries(boolean auto_backup) {
        synchronized (VaultSession.MutationLock) {
            VaultWriteResult result = persistence.save(entries.getEntries(), encryptionKey, auto_backup,
                    this::handleBackupOutcome);
            if (!(result instanceof VaultWriteResult.Saved)) {
                // Revert unsaved UI mutations; never export them as a successful backup.
                loadEntries();
                Toast.makeText(context, R.string.backup_toast_import_save_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void handleBackupOutcome(TaskOutcome<BackupTaskResult> outcome) {
        if (outcome instanceof TaskOutcome.Value)
            handleTaskResult(((TaskOutcome.Value<BackupTaskResult>) outcome).getValue());
        else if (outcome instanceof TaskOutcome.Failure)
            Toast.makeText(context, R.string.backup_toast_export_failed, Toast.LENGTH_LONG).show();
        // Cancellation is not a successful export and must not emit a success toast.
    }

    private void handleTaskResult(BackupTaskResult result) {
        if (result.success) {
            Toast.makeText(context, R.string.backup_toast_export_success, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(context, result.messageId, Toast.LENGTH_LONG).show();
        }
    }

    /** Clears secrets held by adapters/views and invalidates outstanding filters/timers. */
    public void lockEntries() {
        encryptionKey = null;
        dialogs.dismissAll();
        taskHandler.removeCallbacksAndMessages(null);
        for (Entry entry : entries.getEntries()) entry.destroy();
        entries.updateEntries(new ArrayList<>(), false);
        presentation.clear();
        filter = null;
        notifyDataSetChanged();
    }

    public boolean loadEntries() {
        synchronized (VaultSession.MutationLock) {
            if (encryptionKey == null || VaultSession.getKey() != encryptionKey) { lockEntries(); return false; }
            VaultReadResult result = persistence.load(encryptionKey);
            if (!(result instanceof VaultReadResult.Loaded)) { lockEntries(); return false; }
            dialogs.dismissAll();
            taskHandler.removeCallbacksAndMessages(null);
            for (Entry entry : entries.getEntries()) entry.destroy();
            entries.updateEntries(new ArrayList<>(), false);
            entries.updateEntries(((VaultReadResult.Loaded) result).takeEntries(), true);
            clearFilter();
            entriesChanged(RecyclerView.NO_POSITION);
            return true;
        }
    }

    /** Shows only the entries matching the given tags. */
    public void filterByTags(List<String> tags) {
        clearFilter();
        presentation.filterByTags(tags, settings.getNoTagsToggle(), settings.getTagFunctionality());

        notifyDataSetChanged();
    }

    /** Refreshes all time-based tokens; the view is only notified when something changed. */
    public void updateTimeBasedTokens() {
        boolean change = false;

        for (Entry e : entries.getEntries()) {
            if (e.isTimeBased()) {
                boolean cardVisible = !settings.getTapToReveal() || e.isVisible();

                boolean item_changed = e.updateOTP(false);
                boolean color_changed = false;

                // Check color change only if highlighting token feature is enabled and the entry is visible
                if(settings.isHighlightTokenOptionEnabled())
                    color_changed = cardVisible && e.hasColorChanged();

                change = change || item_changed || color_changed ||
                        (cardVisible && (e.hasNonDefaultPeriod() || settings.isShowIndividualTimeoutsEnabled()));
            }
        }

        if (change)
            notifyDataSetChanged();
    }

    @Override
    public void onBindViewHolder(@NonNull EntryViewHolder entryViewHolder, int i) {
        Entry entry = presentation.get(i);

        if (!entry.isTimeBased())
            entry.updateOTP(false);

        if(settings.isHighlightTokenOptionEnabled())
            entryViewHolder.updateColor(entry.getColor());

        entryViewHolder.updateValues(entry);

        entryViewHolder.setLabelSize(settings.getLabelSize());
        entryViewHolder.setLabelScroll(settings.getLabelDisplay(), settings.getCardLayout());

        if(settings.getThumbnailVisible())
            entryViewHolder.setThumbnailSize(settings.getThumbnailSize());
    }

    @Override @NonNull
    public EntryViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
        int cardLayout = R.layout.component_card_default;

        Constants.CardLayouts layout = settings.getCardLayout();

        if (layout == Constants.CardLayouts.COMPACT) {
            cardLayout = R.layout.component_card_compact;
        } else if (layout == Constants.CardLayouts.FULL) {
            cardLayout = R.layout.component_card_full;
        }

        View itemView = LayoutInflater.from(viewGroup.getContext()).inflate(cardLayout, viewGroup, false);

        EntryViewHolder viewHolder = new EntryViewHolder(context, itemView, settings.getTapToReveal());
        viewHolder.setCallback(new EntryViewHolder.Callback() {
            @Override
            public void onMoveEventStart() {
                if (callback != null)
                    callback.onMoveEventStart();
            }

            @Override
            public void onMoveEventStop() {
                if (callback != null)
                    callback.onMoveEventStop();
            }

            @Override
            public void onMenuButtonClicked(View parentView, int position) {
                showPopupMenu(parentView, position);
            }

            @Override
            public void onCopyButtonClicked(String text, int position) {
                copyHandler(position, text, settings.isMinimizeAppOnCopyEnabled());
            }

            @Override
            public void onCardSingleClicked(final int position, final String text) {
                switch (settings.getTapSingle()) {
                    case REVEAL:
                        establishPinIfNeeded(position);
                        cardTapToRevealHandler(position);
                        break;
                    case COPY:
                        establishPinIfNeeded(position);
                        copyHandler(position, text, false);
                        break;
                    case COPY_BACKGROUND:
                        establishPinIfNeeded(position);
                        copyHandler(position, text, true);
                        break;
                    case SEND_KEYSTROKES:
                        establishPinIfNeeded(position);
                        sendKeystrokes(position);
                        break;
                    default:
                        // If tap-to-reveal is disabled a single tap still needs to establish the PIN
                        if (!settings.getTapToReveal())
                            establishPinIfNeeded(position);
                        break;
                }
            }

            @Override
            public void onCardDoubleClicked(final int position, final String text) {
                switch (settings.getTapDouble()) {
                    case REVEAL:
                        establishPinIfNeeded(position);
                        cardTapToRevealHandler(position);
                        break;
                    case COPY:
                        establishPinIfNeeded(position);
                        copyHandler(position, text, false);
                        break;
                    case COPY_BACKGROUND:
                        establishPinIfNeeded(position);
                        copyHandler(position, text, true);
                        break;
                    case SEND_KEYSTROKES:
                        establishPinIfNeeded(position);
                        sendKeystrokes(position);
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onCounterClicked(int position) {
                updateEntry(presentation.get(position), entries.getEntry(getRealIndex(position)), position);
            }

            @Override
            public void onCounterLongPressed(int position) {
                setCounter(position);
            }
        });

        return viewHolder;
    }

    private void establishPinIfNeeded(int position) {
        final Entry entry = presentation.get(position);

        if (entry.getType() == Entry.OTPType.MOTP && entry.getPin().isEmpty())
            establishPIN(position);
    }

    private void copyHandler(final int position, final String text, final boolean dropToBackground) {
        Tools.copyToClipboard(context, text);
        updateLastUsedAndFrequency(position, getRealIndex(position));
        if (dropToBackground) {
            ((MainActivity)context).moveTaskToBack(true);
        }
    }

    private void cardTapToRevealHandler(final int position) {
        final Entry entry = presentation.get(position);
        final int realIndex = entries.indexOf(entry);

        if (entry.isVisible()) {
            hideEntry(entry);
        } else {
            entries.getEntry(realIndex).setHideTask(() -> hideEntry(entry));
            taskHandler.postDelayed(entries.getEntry(realIndex).getHideTask(), settings.getTapToRevealTimeout() * 1000);

            if (entry.isCounterBased()) {
                updateEntry(entry, entries.getEntry(realIndex), position);
            }
            entry.setVisible(true);
            notifyItemChanged(position);
        }
    }

    private void updateEntry(Entry entry, Entry realEntry, final int position) {
        if (entry.getCounter() >= Long.MAX_VALUE - 1) return;
        long counter = entry.getCounter() + 1;

        entry.setCounter(counter);
        entry.updateOTP(false);
        notifyItemChanged(position);

        realEntry.setCounter(counter);
        realEntry.updateOTP(false);
        
        saveEntries(settings.getAutoBackupEncryptedFullEnabled());
    }

    private void hideEntry(Entry entry) {
        int pos = presentation.indexOf(entry);
        int realIndex = entries.indexOf(entry);

        if (realIndex >= 0) {
            entries.getEntry(realIndex).setVisible(false);
            taskHandler.removeCallbacks(entries.getEntry(realIndex).getHideTask());
            entries.getEntry(realIndex).setHideTask(null);
        }

        boolean updateNeeded = updateLastUsedAndFrequency(pos, realIndex);

        if (pos >= 0) {
            presentation.get(pos).setVisible(false);

            if (updateNeeded)
                notifyItemChanged(pos);
        }
    }

    private void showActionDialog(int position, BiConsumer<EntryActionTarget, Entry> show) {
        synchronized (VaultSession.MutationLock) {
            if (encryptionKey == null || VaultSession.getKey() != encryptionKey
                    || position < 0 || position >= presentation.size()) return;
            Entry entry = presentation.get(position);
            show.accept(new EntryActionTarget(entry, VaultSession.generation()), entry);
        }
    }

    /** Commands from the extracted dialogs re-resolve identity and authority before mutation. */
    private void withTarget(EntryActionTarget target, Consumer<Entry> command) {
        synchronized (VaultSession.MutationLock) {
            if (encryptionKey == null || VaultSession.getKey() != encryptionKey) return;
            Entry entry = target.resolve(entries, VaultSession.generation());
            if (entry != null) command.accept(entry);
        }
    }

    private void notifyEntryChanged(Entry entry) {
        int position = presentation.indexOf(entry);
        if (position >= 0) notifyItemChanged(position);
    }

    private void setCounter(int position) {
        showActionDialog(position, (target, entry) -> dialogs.counter(entry.getCounter(), value ->
                withTarget(target, actual -> {
                    actual.setCounter(value);
                    notifyEntryChanged(actual);
                    saveEntries(settings.getAutoBackupEncryptedFullEnabled());
                })));
    }

    /** Records the usage; returns false when the resorting also updated the view. */
    private boolean updateLastUsedAndFrequency(int position, int realIndex) {
        long timeStamp = System.currentTimeMillis();
        long entryUsedFrequency = entries.getEntry(realIndex).getUsedFrequency();

        if (position >= 0) {
            long displayEntryUsedFrequency = presentation.get(position).getUsedFrequency();
            presentation.get(position).setLastUsed(timeStamp);
            presentation.get(position).setUsedFrequency(displayEntryUsedFrequency + 1);
        }

        entries.getEntry(realIndex).setLastUsed(timeStamp);
        entries.getEntry(realIndex).setUsedFrequency(entryUsedFrequency + 1);
        saveEntries(false);

        if (presentation.resortAfterUsage()) {
            notifyDataSetChanged();
            return false;
        }

        return true;
    }

    /** Swaps two entries after a drag (only in the unsorted, unfiltered view). */
    @Override
    public boolean onItemMove(int fromPosition, int toPosition) {
        if (presentation.canDrag()) {
            entries.swapEntries(fromPosition, toPosition);

            presentation.showReorderedEntries();
            notifyItemMoved(fromPosition, toPosition);

            saveEntries(false);
        }

        return true;
    }

    /** Dialogs render inputs; identity-based commands stay at the authorized mutation boundary. */
    public void changeThumbnail(int position) {
        showActionDialog(position, (target, entry) -> dialogs.thumbnail(entry.getIssuer(), entry.getLabel(), value ->
                withTarget(target, actual -> {
                    actual.setThumbnail(value);
                    saveEntries(settings.getAutoBackupEncryptedFullEnabled());
                    notifyEntryChanged(actual);
                })));
    }

    public void establishPIN(int position) {
        showActionDialog(position, (target, entry) -> dialogs.pin(entry.getPin(), value ->
                withTarget(target, actual -> {
                    actual.setPin(value);
                    actual.updateOTP(true);
                    notifyEntryChanged(actual);
                })));
    }

    public void removeItem(int position) {
        showActionDialog(position, (target, entry) -> dialogs.delete(entry.getLabel(), () ->
                withTarget(target, actual -> {
                    int index = entries.indexOf(actual);
                    int displayedIndex = presentation.indexOf(actual);
                    if (displayedIndex >= 0) {
                        presentation.removeDisplayed(displayedIndex);
                        notifyItemRemoved(displayedIndex);
                    }
                    entries.removeEntry(index);
                    if (actual.getHideTask() != null) taskHandler.removeCallbacks(actual.getHideTask());
                    actual.setHideTask(null);
                    try { saveEntries(settings.getAutoBackupEncryptedFullEnabled()); }
                    finally { actual.destroy(); }
                })));
    }

    /** Sends the current token as an ACTION_SEND intent of type "text/x-keystrokes", which apps
     * such as KDE Connect can pick up (e.g. to type it on a connected device). */
    private void sendKeystrokes(final int pos) {
        String otp = presentation.get(pos).getCurrentOTP();
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/x-keystrokes");
        sendIntent.putExtra(Intent.EXTRA_TEXT, otp);
        if (sendIntent.resolveActivity(this.context.getPackageManager()) != null) {
            this.context.startActivity(sendIntent);
        }
    }

    /** Shows the entry as a QR code (if the type can be encoded). */
    private void showQRCode(final int pos) {
        Uri uri = presentation.get(pos).toUri();
        if (uri != null) {
            Bitmap bitmap;
            try {
                bitmap = new BarcodeEncoder().encodeBitmap(uri.toString(), BarcodeFormat.QR_CODE, 0, 0);
            } catch(Exception ignored) {
                Toast.makeText(context, R.string.toast_qr_failed_to_generate, Toast.LENGTH_LONG).show();
                return;
            }
            BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
            drawable.setFilterBitmap(false);

            ImageView image = new ImageView(context);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(drawable);

            new AlertDialog.Builder(context)
                    .setTitle(R.string.dialog_title_qr_code)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {})
                    .setView(image)
                    .create()
                    .show();
        } else {
            Toast.makeText(context, R.string.toast_qr_unsuported, Toast.LENGTH_LONG).show();
        }
    }

    /** Shows the per-entry context menu. */
    private void showPopupMenu(View view, final int pos) {
        View menuItemView = view.findViewById(R.id.menuButton);
        PopupMenu popup = new PopupMenu(view.getContext(), menuItemView);
        MenuInflater inflate = popup.getMenuInflater();
        inflate.inflate(R.menu.menu_popup, popup.getMenu());

        if (presentation.get(pos).getType() == Entry.OTPType.MOTP){
             MenuItem item = popup.getMenu().getItem(ESTABLISH_PIN_MENU_INDEX);
             item.setVisible(true);
        }

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();

            if (id == R.id.menu_popup_edit) {
                ManualEntryDialog.show((MainActivity) context, settings, EntriesCardAdapter.this, entries.getEntry(getRealIndex(pos)), () -> saveAndRefresh(settings.getAutoBackupEncryptedFullEnabled(), pos));
                return true;
            } else if(id == R.id.menu_popup_changeImage) {
                changeThumbnail(pos);
                return true;
            } else if (id == R.id.menu_popup_establishPin) {
                establishPIN(pos);
                return true;
            } else if (id == R.id.menu_popup_remove) {
                removeItem(pos);
                return true;
            } else if (id == R.id.menu_popup_show_qr_code) {
                showQRCode(pos);
                return true;
            } else if (id == R.id.menu_send_keystrokes) {
                sendKeystrokes(pos);
                return true;
            } else {
                return false;
            }
        });
        popup.show();
    }

    /** Changes the sort order of the displayed entries. */
    public void setSortMode(SortMode mode) {
        presentation.setSortMode(mode);
        entriesChanged(RecyclerView.NO_POSITION);
    }

    public SortMode getSortMode() {
        return presentation.getSortMode();
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public EntryFilter getFilter() {
        if (filter == null)
            filter = new EntryFilter();

        return filter;
    }

    /** Drops the current search filter. */
    public void clearFilter() {
        presentation.invalidateSearch();
        if (filter != null)
            filter = null;
    }

    public List<String> getTags() {
        return entries.getAllTags();
    }

    /** Search filter that applies the configured search fields. */
    public class EntryFilter extends Filter {
        private final long epoch = VaultSession.generation();
        private final List<Constants.SearchIncludes> filterValues = settings.getSearchValues();

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            EntryListPresentation.SearchResult filtered = presentation.search(constraint, filterValues);

            final FilterResults filterResults = new FilterResults();
            filterResults.count = filtered.size();
            filterResults.values = filtered;

            return filterResults;
        }

        @Override
        protected void publishResults(CharSequence constraint, @NonNull FilterResults results) {
            if (!(results.values instanceof EntryListPresentation.SearchResult)) return;
            EntryListPresentation.SearchResult projection = (EntryListPresentation.SearchResult) results.values;
            synchronized (VaultSession.MutationLock) {
                if (filter != this || !VaultSession.isCurrent(epoch) || encryptionKey == null || VaultSession.getKey() != encryptionKey) {
                    projection.close();
                    return;
                }
                if (presentation.publishSearch(projection)) notifyDataSetChanged();
            }
        }
    }

    /** Lets the activity react to drag/drop events (pausing the token updater). */
    public interface Callback {
        void onMoveEventStart();
        void onMoveEventStop();
    }
}

package org.shadowice.flocke.andotp.View;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;

import org.shadowice.flocke.andotp.R;
import org.shadowice.flocke.andotp.Utilities.EntryThumbnail;
import org.shadowice.flocke.andotp.Utilities.Settings;
import org.shadowice.flocke.andotp.Utilities.Tools;
import org.shadowice.flocke.andotp.Utilities.UIHelper;

import java.util.ArrayList;
import java.util.Locale;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Screen-owned dialog rendering/input only. No direct Entry, position, key or vault access.
 * The caller validates each delayed command. Dismissal wipes editable text and drops tracked windows.
 */
public final class EntryActionDialogs {
    private final Context context;
    private final Settings settings;
    private final HashMap<AlertDialog, EditText> open = new HashMap<>();

    public EntryActionDialogs(Context context, Settings settings) {
        this.context = context;
        this.settings = settings;
    }

    private EditText input() {
        EditText input = new EditText(context);
        input.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        input.setSingleLine();
        input.setSaveEnabled(false);
        return input;
    }

    private FrameLayout container(android.view.View child) {
        FrameLayout container = new FrameLayout(context);
        int medium = context.getResources().getDimensionPixelSize(R.dimen.activity_margin_medium);
        int small = context.getResources().getDimensionPixelSize(R.dimen.activity_margin_small);
        container.setPaddingRelative(medium, small, medium, 0);
        container.addView(child);
        return container;
    }

    private void show(AlertDialog dialog, EditText input) {
        open.put(dialog, input);
        dialog.setOnDismissListener(ignored -> {
            if (input != null) { UIHelper.hideKeyboard(context, input); input.setText(""); }
            open.remove(dialog);
        });
        dialog.show();
    }

    public void dismissAll() {
        for (AlertDialog dialog : new ArrayList<>(open.keySet())) {
            EditText input = open.get(dialog);
            if (input != null) { UIHelper.hideKeyboard(context, input); input.setText(""); }
            dialog.dismiss();
        }
        open.clear();
    }

    public void counter(long counter, LongConsumer save) {
        EditText input = input();
        input.setText(String.format(Locale.ENGLISH, "%d", counter));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.dialog_title_counter)
                .setView(container(input))
                .setPositiveButton(R.string.button_save, (ignored, which) -> save.accept(Long.parseLong(input.getText().toString())))
                .setNegativeButton(android.R.string.cancel, (ignored, which) -> {})
                .create();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(validCounter(text.toString()));
            }
        });
        show(dialog, input);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(validCounter(input.getText().toString()));
    }

    private static boolean validCounter(String text) {
        try { return !text.isEmpty() && Long.parseLong(text) >= 0; }
        catch (NumberFormatException error) { return false; }
    }

    public void pin(String current, Consumer<String> save) {
        EditText input = input();
        input.setRawInputType(InputType.TYPE_NUMBER_VARIATION_PASSWORD | InputType.TYPE_CLASS_NUMBER);
        input.setText(current);
        input.setTransformationMethod(new PasswordTransformationMethod());
        input.requestFocus();
        UIHelper.showKeyboard(context, input, true);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.dialog_title_pin)
                .setCancelable(false).setView(container(input))
                .setPositiveButton(R.string.button_accept, (ignored, which) -> {
                    save.accept(input.getText().toString());
                    UIHelper.hideKeyboard(context, input);
                })
                .setNegativeButton(android.R.string.cancel, (ignored, which) -> UIHelper.hideKeyboard(context, input))
                .create();
        show(dialog, input);
    }

    @android.annotation.SuppressLint("StringFormatInvalid")
    public void delete(String label, Runnable confirm) {
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.dialog_title_remove)
                .setMessage(context.getString(R.string.dialog_msg_confirm_delete, label))
                .setPositiveButton(R.string.yes, (ignored, which) -> confirm.run())
                .setNegativeButton(R.string.no, (ignored, which) -> {})
                .create();
        show(dialog, null);
    }

    public void thumbnail(String issuer, String label, Consumer<EntryThumbnail.EntryThumbnails> select) {
        ThumbnailSelectionAdapter thumbnails = new ThumbnailSelectionAdapter(context, issuer, label);
        EditText input = input();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) { thumbnails.filter(text.toString()); }
        });
        GridView grid = new GridView(context);
        grid.setAdapter(thumbnails);
        grid.setBackgroundColor(Tools.getThemeColor(context, R.attr.thumbnailBackground));
        int small = context.getResources().getDimensionPixelSize(R.dimen.activity_margin_small);
        int medium = context.getResources().getDimensionPixelSize(R.dimen.activity_margin_medium);
        grid.setPadding(small, small, small, small);
        grid.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        grid.setColumnWidth(settings.getThumbnailSize());
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setVerticalSpacing(medium);
        grid.setHorizontalSpacing(medium);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(input);
        layout.addView(grid);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.menu_popup_change_image)
                .setView(container(layout)).setNegativeButton(android.R.string.cancel, (ignored, which) -> {})
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            EntryThumbnail.EntryThumbnails value = EntryThumbnail.EntryThumbnails.Default;
            try { value = EntryThumbnail.EntryThumbnails.values()[thumbnails.getRealIndex(position)]; }
            catch (IndexOutOfBoundsException ignored) { /* Retain the existing default-icon fallback. */ }
            select.accept(value);
            dialog.cancel();
        });
        show(dialog, input);
    }
}

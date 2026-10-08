package com.tvremoteime;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

/** Minimal TV IME: local web input + tiny bottom-right overlay. */
public class RemoteImeService extends InputMethodService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextView status;
    private String remoteText = "";
    private EditorInfo currentEditorInfo;
    private RemoteWebServer.Listener listener;

    @Override
    public void onCreate() {
        super.onCreate();
        RemoteWebServer.ensureStarted(this);
        listener = new RemoteWebServer.Listener() {
            @Override public void onText(String text) { main.post(() -> applyRemoteText(text)); }
            @Override public void onAction(String action) { main.post(() -> handleAction(action)); }
            @Override public void onClientCountChanged(int count) { main.post(() -> updateStatus(count)); }
        };
        RemoteWebServer.setListener(listener);
        tuneWindow();
    }

    private void tuneWindow() {
        // FIX(v0.3 同样存在): InputMethodService.getWindow() 返回 android.app.Dialog
        Dialog d = getWindow();
        if (d == null) return;
        Window w = d.getWindow();
        if (w == null) return;
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        w.setBackgroundDrawableResource(android.R.color.transparent);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.dimAmount = 0f;
        w.setAttributes(lp);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public View onCreateInputView() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(10), dp(10), dp(10));
        card.setBackground(round(Color.argb(235, 20, 20, 20), dp(18)));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, 0, dp(10), 0);

        TextView title = new TextView(this);
        title.setText("手机网页输入");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        left.addView(title, new LinearLayout.LayoutParams(dp(175), -2));

        status = new TextView(this);
        status.setText("等待手机连接\n" + RemoteWebServer.getUrl(this));
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(11);
        status.setMaxLines(3);
        left.addView(status, new LinearLayout.LayoutParams(dp(175), -2));
        card.addView(left);

        try {
            Bitmap q = qr(RemoteWebServer.getUrl(this), dp(128));
            ImageView image = new ImageView(this);
            image.setImageBitmap(q);
            image.setContentDescription("扫码打开手机输入页面");
            card.addView(image, new LinearLayout.LayoutParams(dp(128), dp(128)));
        } catch (Exception e) {
            TextView fallback = new TextView(this);
            fallback.setText(RemoteWebServer.getUrl(this));
            fallback.setTextColor(Color.WHITE);
            fallback.setTextSize(11);
            card.addView(fallback, new LinearLayout.LayoutParams(dp(128), dp(128)));
        }

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.END | Gravity.BOTTOM);
        cp.setMargins(0, 0, dp(16), dp(16));
        root.addView(card, cp);
        return root;
    }

    private void applyRemoteText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        String target = text == null ? "" : text;
        String current = extractCurrentText(ic);
        if (current == null) current = remoteText;
        if (target.equals(current)) {
            remoteText = target;
            return;
        }

        int prefix = 0;
        int maxPrefix = Math.min(current.length(), target.length());
        while (prefix < maxPrefix && current.charAt(prefix) == target.charAt(prefix)) prefix++;

        int suffix = 0;
        int remainingOld = current.length() - prefix;
        int remainingNew = target.length() - prefix;
        while (suffix < remainingOld && suffix < remainingNew
                && current.charAt(current.length() - 1 - suffix)
                == target.charAt(target.length() - 1 - suffix)) {
            suffix++;
        }

        int replaceEnd = current.length() - suffix;
        String replacement = target.substring(prefix, target.length() - suffix);

        try {
            ic.beginBatchEdit();
            ic.setSelection(prefix, replaceEnd);
            if (replaceEnd > prefix) ic.deleteSurroundingText(replaceEnd - prefix, 0);
            if (!replacement.isEmpty()) ic.commitText(replacement, 1);
            ic.endBatchEdit();
            remoteText = target;
            RemoteWebServer.sendTextState(target);
        } catch (Throwable ignored) {
            try { ic.endBatchEdit(); } catch (Throwable ignoredAgain) {}
        }
        updateStatus(RemoteWebServer.clientCount());
    }

    private String extractCurrentText(InputConnection ic) {
        try {
            ExtractedTextRequest req = new ExtractedTextRequest();
            ExtractedText et = ic.getExtractedText(req, 0);
            return et != null && et.text != null ? et.text.toString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void handleAction(String action) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || action == null) return;
        if ("clear".equals(action)) {
            applyRemoteText("");
            return;
        }
        if ("enter".equals(action)) {
            boolean handled = false;
            if (currentEditorInfo != null && currentEditorInfo.actionId != EditorInfo.IME_ACTION_NONE) {
                handled = ic.performEditorAction(currentEditorInfo.actionId);
            }
            if (!handled) ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            if (!handled) ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            return;
        }
        if ("back".equals(action)) {
            requestHideSelf(0);
        }
    }

    private void updateStatus(int count) {
        if (status == null) return;
        String url = RemoteWebServer.getUrl(this);
        if (count > 0) status.setText("已连接手机 ×" + count + "\n" + url);
        else status.setText("等待手机连接\n" + url);
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        currentEditorInfo = info;
        InputConnection ic = getCurrentInputConnection();
        String text = ic == null ? null : extractCurrentText(ic);
        remoteText = text == null ? "" : text;
        RemoteWebServer.sendTextState(remoteText);
        updateStatus(RemoteWebServer.clientCount());
    }

    @Override
    public void onFinishInput() {
        currentEditorInfo = null;
        super.onFinishInput();
    }

    @Override
    public void onDestroy() {
        RemoteWebServer.clearListener(listener);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private Bitmap qr(String value, int size) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bitmap;
    }

    private android.graphics.drawable.GradientDrawable round(int color, float radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }
}

package com.tvremoteime;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        RemoteWebServer.ensureStarted(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40,40,40,40);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Color.rgb(20,20,20));

        TextView title = text("TVRemoteIME Lite", 28, Color.WHITE);
        root.addView(title, wrap());
        root.addView(text("电视端 IME + 手机浏览器输入（局域网）", 18, Color.LTGRAY), wrap());

        String url = RemoteWebServer.getUrl(this);
        TextView urlView = text("网页地址：\n" + url, 20, Color.WHITE);
        urlView.setPadding(0, 32, 0, 32);
        root.addView(urlView, wrap());

        Button ime = new Button(this);
        ime.setText("打开系统输入法设置");
        ime.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(ime, wrap());

        Button details = new Button(this);
        details.setText("打开本机网页（测试）");
        details.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
        root.addView(details, wrap());

        root.addView(text("首次：启用本输入法并设为默认输入法。\n输入框出现时，电视右下角会出现小面板和二维码。\n手机扫一次即可长期使用该网址。", 16, Color.LTGRAY), wrap());

        setContentView(root);
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
    public static String url(Context c) { return RemoteWebServer.getUrl(c); }
}

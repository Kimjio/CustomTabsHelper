package com.kimjio.customtabs.sample;

import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;

import android.net.Uri;
import android.os.Bundle;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.Toast;

import com.kimjio.customtabs.CustomTabActivityHelper;
import com.kimjio.customtabs.CustomTabsHelper;
import com.kimjio.customtabs.WebViewFallback;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.button).setOnClickListener(v -> {

            String url = ((EditText) findViewById(R.id.edit)).getText().toString();
            if (URLUtil.isValidUrl(url)) {
                CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build();
                CustomTabsHelper.addKeepAliveExtra(this, customTabsIntent.intent);
                CustomTabActivityHelper.openCustomTab(
                        this,
                        customTabsIntent,
                        Uri.parse(url),
                        new WebViewFallback());
            } else {
                Toast.makeText(this, "Not valid url", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
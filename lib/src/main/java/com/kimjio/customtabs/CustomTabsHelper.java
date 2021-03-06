// Copyright 2015 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.kimjio.customtabs;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.SimpleAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.browser.customtabs.CustomTabsService;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper class for Custom Tabs.
 */
public class CustomTabsHelper {
    static final String WAIT_FOR_SELECT = "WAIT_FOR_SELECT";
    private static final String TAG = "CustomTabsHelper";
    private static final String EXTRA_CUSTOM_TABS_KEEP_ALIVE =
            "android.support.customtabs.extra.KEEP_ALIVE";
    private static String sPackageNameToUse;

    private static ArrayList<OnSelectedItemListener> onSelectedItemListeners = new ArrayList<>();

    private static HelperLifecycle lifecycle;

    private CustomTabsHelper() {
    }

    public static void addKeepAliveExtra(Context context, Intent intent) {
        @SuppressWarnings("ConstantConditions") Intent keepAliveIntent = new Intent().setClassName(
                context.getPackageName(), KeepAliveService.class.getCanonicalName());
        intent.putExtra(EXTRA_CUSTOM_TABS_KEEP_ALIVE, keepAliveIntent);
    }

    /**
     * Goes through all apps that handle VIEW intents and have a warm up service. Picks the one
     * chosen by the user if there is one, otherwise makes a best effort to return a valid package
     * name.
     *
     * <p>This is <strong>not</strong> threadsafe.
     *
     * @param context {@link Context} to use for accessing {@link PackageManager}.
     * @return The package name recommended to use for connecting to custom tabs related components.
     */
    public static String getPackageNameToUse(final Context context) {
        if (sPackageNameToUse != null) return sPackageNameToUse;

        PackageManager pm = context.getPackageManager();
        // Get default VIEW intent handler.
        Intent activityIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://android.com"));
        ResolveInfo defaultViewHandlerInfo = pm.resolveActivity(activityIntent, 0);
        String defaultViewHandlerPackageName = null;
        if (defaultViewHandlerInfo != null) {
            defaultViewHandlerPackageName = defaultViewHandlerInfo.activityInfo.packageName;
        }

        // Get all apps that can handle VIEW intents.
        final List<ResolveInfo> resolvedActivityList = pm.queryIntentActivities(activityIntent, 0);
        final List<Map<String, Object>> dialogItemList = new ArrayList<>();
        final List<String> packagesSupportingCustomTabs = new ArrayList<>();
        for (ResolveInfo info : resolvedActivityList) {
            Intent serviceIntent = new Intent();
            serviceIntent.setAction(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION);
            serviceIntent.setPackage(info.activityInfo.packageName);

            if (pm.resolveService(serviceIntent, 0) != null) {
                packagesSupportingCustomTabs.add(info.activityInfo.packageName);

                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("icon", info.loadIcon(pm));
                itemMap.put("name", info.loadLabel(pm));
                dialogItemList.add(itemMap);
            }
        }

        // Now packagesSupportingCustomTabs contains all apps that can handle both VIEW intents
        // and service calls.

        // 사용 가능한 앱이 여러 개일 경우 AlertDialog 표시
        if (packagesSupportingCustomTabs.isEmpty()) {
            sPackageNameToUse = null;
        } else if (packagesSupportingCustomTabs.size() == 1) {
            sPackageNameToUse = packagesSupportingCustomTabs.get(0);
        } else if (!TextUtils.isEmpty(defaultViewHandlerPackageName)
                && !hasSpecializedHandlerIntents(context, activityIntent)
                && packagesSupportingCustomTabs.contains(defaultViewHandlerPackageName)) {
            sPackageNameToUse = defaultViewHandlerPackageName;
        } else {
            SimpleAdapter simpleAdapter =
                    new SimpleAdapter(
                            context,
                            dialogItemList,
                            R.layout.item_dialog_app_list,
                            new String[]{"icon", "name"},
                            new int[]{R.id.app_icon, R.id.app_name});
            simpleAdapter.setViewBinder(
                    new SimpleAdapter.ViewBinder() {
                        @Override
                        public boolean setViewValue(View view, Object data, String textRepresentation) {
                            if (view.getId() == R.id.app_icon) {
                                ImageView imageView = (ImageView) view;
                                Drawable drawable = (Drawable) data;

                                imageView.setImageDrawable(drawable);

                                return true;
                            }
                            return false;
                        }
                    });

            onSelectedItemListeners.add(new OnSelectedItemListener() {
                @Override
                public void onSelected(String pkg) {
                    sPackageNameToUse = null;
                }
            });

            ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(lifecycle = new HelperLifecycle(
                    new AlertDialog.Builder(context)
                            .setTitle(R.string.choose_customtabs)
                            .setAdapter(
                                    simpleAdapter,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            sPackageNameToUse = packagesSupportingCustomTabs.get(which);
                                            ((Application) context.getApplicationContext()).unregisterActivityLifecycleCallbacks(lifecycle);
                                            lifecycle = null;
                                            if (!onSelectedItemListeners.isEmpty())
                                                onSelectedItemSelected(sPackageNameToUse);
                                        }
                                    })
                            .setOnCancelListener(
                                    new DialogInterface.OnCancelListener() {
                                        @Override
                                        public void onCancel(DialogInterface dialog) {
                                            ((Application) context.getApplicationContext()).unregisterActivityLifecycleCallbacks(lifecycle);
                                            lifecycle = null;
                                            if (!onSelectedItemListeners.isEmpty())
                                                onSelectedItemSelected(null);
                                        }
                                    })
                            .show()));

            return WAIT_FOR_SELECT;
        }
        return sPackageNameToUse;
    }

    /**
     * Used to check whether there is a specialized handler for a given intent.
     *
     * @param intent The intent to check with.
     * @return Whether there is a specialized handler for the given intent.
     */
    private static boolean hasSpecializedHandlerIntents(Context context, Intent intent) {
        try {
            PackageManager pm = context.getPackageManager();
            List<ResolveInfo> handlers =
                    pm.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER);
            if (handlers.size() == 0) {
                return false;
            }
            for (ResolveInfo resolveInfo : handlers) {
                IntentFilter filter = resolveInfo.filter;
                if (filter == null) continue;
                if (filter.countDataAuthorities() == 0 || filter.countDataPaths() == 0) continue;
                if (resolveInfo.activityInfo == null) continue;
                return true;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Runtime exception while getting specialized handlers");
        }
        return false;
    }

    public static void addOnSelectedItemListener(OnSelectedItemListener onSelectedItemListener) {
        onSelectedItemListeners.add(onSelectedItemListener);
    }

    public static void removeOnSelectedItemListener(OnSelectedItemListener onSelectedItemListener) {
        onSelectedItemListeners.remove(onSelectedItemListener);
    }

    private static void onSelectedItemSelected(String pkg) {
        for (OnSelectedItemListener o : onSelectedItemListeners) {
            o.onSelected(pkg);
        }
    }

    public interface OnSelectedItemListener {
        void onSelected(String pkg);
    }

    private static class HelperLifecycle implements Application.ActivityLifecycleCallbacks {

        private WeakReference<Dialog> dialog;

        public HelperLifecycle(Dialog dialog) {
            this.dialog = new WeakReference<>(dialog);
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            Dialog dialog;
            if ((dialog = this.dialog.get()) != null)
                dialog.cancel();
        }
    }
}

/*
 * Copyright (C) 2023-2024 the risingOS Android Project           
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chaldeaprjkt.gamespace.preferences;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.ListView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceDialogFragmentCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import io.chaldeaprjkt.gamespace.R;
import io.github.miner7222.gamespace.library.AppPickerView;

public class QuickStartAppPreferenceDialogFragment extends PreferenceDialogFragmentCompat {

    public interface QuickStartAppListener {
        String getSavedQuickStartApps();
        void saveQuickStartApps(String apps);
    }

    private Set<String> selectedPackages = new HashSet<>();

    private QuickStartAppListener listener;
    private boolean restoredSelection;
    private String query = "";

    public void setListener(QuickStartAppListener listener) {
        this.listener = listener;
    }

    public static QuickStartAppPreferenceDialogFragment newInstance(String key) {
        final QuickStartAppPreferenceDialogFragment fragment = new QuickStartAppPreferenceDialogFragment();
        final Bundle b = new Bundle(1);
        b.putString(ARG_KEY, key);
        fragment.setArguments(b);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            restoredSelection = true;
            ArrayList<String> saved = savedInstanceState.getStringArrayList("selected_packages");
            if (saved != null) selectedPackages.addAll(saved);
            query = savedInstanceState.getString("query", "");
        }
        if (listener == null && getTargetFragment() instanceof QuickStartAppListener) {
            listener = (QuickStartAppListener) getTargetFragment();
        }
    }

    @Override
    protected void onPrepareDialogBuilder(@NonNull AlertDialog.Builder builder) {
        QuickStartAppPreference preference = (QuickStartAppPreference) getPreference();
        Context context = getContext();
        if (preference == null || context == null || listener == null) {
            return;
        }

        String savedApps = restoredSelection ? null : listener.getSavedQuickStartApps();
        if (savedApps != null && !savedApps.isEmpty()) {
            String[] savedAppsArray = savedApps.split(",");
            for (String app : savedAppsArray) {
                selectedPackages.add(app);
            }
        }

        builder.setTitle(R.string.quick_start_apps_title)
            .setView(AppPickerView.create(context, preference.getAppNames(),
                preference.getAppPackageNames(), preference.getAppIcons(), selectedPackages,
                query, value -> query = value, (packageName, checked) -> {
                    if (checked) selectedPackages.add(packageName);
                    else selectedPackages.remove(packageName);
                }))
            .setPositiveButton(android.R.string.ok, (dialog, which) -> saveSelectedApps())
            .setNegativeButton(android.R.string.cancel, null);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putStringArrayList("selected_packages", new ArrayList<>(selectedPackages));
        outState.putString("query", query);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {}

    private void saveSelectedApps() {
        String[] selectedArray = selectedPackages.toArray(new String[0]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selectedArray.length; i++) {
            sb.append(selectedArray[i]);
            if (i < selectedArray.length - 1) {
                sb.append(",");
            }
        }
        String result = sb.toString();
        listener.saveQuickStartApps(result);
        QuickStartAppPreference preference = (QuickStartAppPreference) getPreference();
        if (preference != null) {
            preference.saveValue(result);
        }
    }
}

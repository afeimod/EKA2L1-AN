/*
 * Copyright (c) 2024 EKA2L1 Team
 *
 * This file is part of EKA2L1 project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.eka2l1.j2me;

import android.app.Dialog;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.github.eka2l1.R;
import com.github.eka2l1.emu.Emulator;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Modal list of every installed J2ME / MIDlet on the current device.
 *
 * Each row shows the title, version, vendor and (if available) the icon
 * that the native install code extracted into the emulator data dir.
 *
 * Clicking a row launches the MIDlet via
 * {@link Emulator#launchJ2meApp(long)}; long-pressing uninstalls it.
 */
public class J2meAppListDialog extends DialogFragment {

    /** Caller-supplied entry point that lets the host fragment decide how to launch. */
    public interface Launcher {
        void launch(long appId);
    }

    private J2meAppRepository repo;
    private ListView listView;
    private J2meAppAdapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public static J2meAppListDialog newInstance() {
        return new J2meAppListDialog();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context ctx = requireContext();
        repo = new J2meAppRepository(com.github.eka2l1.emu.Emulator.getEmulatorDir());

        final View root = LayoutInflater.from(ctx).inflate(R.layout.dialog_j2me_app_list, null, false);
        listView = root.findViewById(R.id.lv_j2me_apps);
        final TextView empty = root.findViewById(R.id.tv_j2me_empty);

        final List<J2meAppItem> items = repo.getInstalledApps();
        adapter = new J2meAppAdapter(ctx, items);
        listView.setAdapter(adapter);
        listView.setEmptyView(empty);

        // Lazy icon resolution: load icons off the UI thread to keep
        // the dialog snappy when the user opens it the first time after
        // installing many games.
        io.execute(() -> {
            for (int i = 0; i < items.size(); i++) {
                final J2meAppItem item = items.get(i);
                final File iconFile = repo.resolveIconFile(item);
                if (iconFile != null) {
                    try {
                        final android.graphics.Bitmap bmp = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
                        if (bmp != null) {
                            item.setIconBitmap(bmp);
                        }
                    } catch (Throwable t) {
                        // Bad icon — leave it null, the adapter shows a placeholder.
                    }
                }
            }
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            final J2meAppItem item = items.get(position);
            launchViaHost(item.getAppId());
            dismissAllowingStateLoss();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            final J2meAppItem item = items.get(position);
            confirmUninstall(item);
            return true;
        });

        return new AlertDialog.Builder(ctx)
                .setTitle(R.string.j2me_dialog_title)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    @Override
    public void onDestroyView() {
        io.shutdownNow();
        super.onDestroyView();
    }

    private void launchViaHost(long appId) {
        // The dialog is hosted inside a Fragment; ask the activity chain
        // for a launcher hook so the actual native launchApp can reuse
        // the existing EmulatorActivity pipeline.
        Fragment parent = getParentFragment();
        while (parent != null) {
            if (parent instanceof Launcher) {
                ((Launcher) parent).launch(appId);
                return;
            }
            parent = parent.getParentFragment();
        }
        // Fall back to a direct native call — this is enough to spawn
        // KMID even if the host hasn't wired up the hook.
        try {
            Emulator.launchJ2meApp(appId);
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmUninstall(J2meAppItem item) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.j2me_uninstall_title)
                .setMessage(getString(R.string.j2me_uninstall_confirm, item.getTitle()))
                .setPositiveButton(android.R.string.ok, (d, w) -> doUninstall(item))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doUninstall(J2meAppItem item) {
        // Capture into effectively-final locals before handing off to
        // the background executor + main-thread lambda. The compile rule
        // forbids reassigning a captured variable inside a lambda (and
        // also forbids assigning a 'final' local twice across try/catch
        // branches), so we route the success flag through a one-element
        // holder. itemTitle is captured by value once.
        final String itemTitle = item.getTitle();
        final boolean[] okHolder = new boolean[1];
        io.execute(() -> {
            try {
                okHolder[0] = Emulator.uninstallJ2meApp(item.getAppId());
            } catch (Throwable t) {
                okHolder[0] = false;
            }
            if (getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (okHolder[0]) {
                    Toast.makeText(requireContext(),
                            getString(R.string.j2me_uninstall_done, itemTitle),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show();
                }
                // Refresh the adapter in place.
                final List<J2meAppItem> refreshed = repo.getInstalledApps();
                if (adapter != null) {
                    adapter.clear();
                    adapter.addAll(refreshed);
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }

    private static class J2meAppAdapter extends ArrayAdapter<J2meAppItem> {
        J2meAppAdapter(Context ctx, List<J2meAppItem> items) {
            super(ctx, 0, items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            final J2meAppItem item = getItem(position);
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_j2me_app, parent, false);
            }
            final TextView title = convertView.findViewById(R.id.tv_j2me_title);
            final TextView subtitle = convertView.findViewById(R.id.tv_j2me_subtitle);
            final ImageView icon = convertView.findViewById(R.id.iv_j2me_icon);

            title.setText(item.getTitle());
            StringBuilder sub = new StringBuilder();
            if (!item.getVersion().isEmpty()) {
                sub.append(getContext().getString(R.string.j2me_version, item.getVersion()));
            }
            if (!item.getAuthor().isEmpty()) {
                if (sub.length() > 0) sub.append(" \u2022 ");
                sub.append(getContext().getString(R.string.j2me_author, item.getAuthor()));
            }
            subtitle.setText(sub.toString());

            if (item.getIconBitmap() != null) {
                icon.setImageBitmap(item.getIconBitmap());
            } else {
                // Fallback to the bundled J2ME icon so empty rows don't look broken.
                icon.setImageResource(R.mipmap.ic_ducky);
            }
            return convertView;
        }
    }
}
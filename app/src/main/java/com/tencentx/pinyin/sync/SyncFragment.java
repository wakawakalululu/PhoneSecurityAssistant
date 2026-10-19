package com.tencentx.pinyin.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.databinding.DialogSyncTaskBinding;
import com.tencentx.pinyin.databinding.FragmentSyncBinding;
import com.tencentx.pinyin.databinding.ItemSyncTaskBinding;
import com.tencentx.pinyin.remote.RemoteAccessFragment;

/**
 * Screen managing file synchronization tasks.
 * <p>
 * Each task runs a one-way {@code rclone copy} between a configured remote
 * (SFTP or WebDAV) and a local directory. Tasks can be created, edited,
 * started/stopped and inspected (live log).
 */
public class SyncFragment
        extends Fragment {

    public static final String TAG = "SyncFragment";

    private static final long REFRESH_INTERVAL_MS = 2_000;
    private static final int LOG_LINES = 200;
    private static final int LOG_PREVIEW_LINES = 40;
    private static final int LOG_PREVIEW_BYTES = 12 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            vm.refresh();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(@NonNull final Context context,
                              @NonNull final Intent intent) {
            vm.refresh();
        }
    };

    private FragmentSyncBinding vb;
    private SyncViewModel vm;

    private ActivityResultLauncher<String[]> importLauncher;

    private final OnBackPressedCallback backPressedCallback =
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    getParentFragmentManager().popBackStack();
                }
            };

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        SyncInstallHelper.importFromUri(requireActivity(), uri);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        vb = FragmentSyncBinding.inflate(inflater, container, false);
        return vb.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        final Toolbar toolbar = requireActivity().findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitle(R.string.sync_screen_title);
            toolbar.setNavigationIcon(R.drawable.arrow_back_24px);
        }

        requireActivity().getOnBackPressedDispatcher()
                         .addCallback(getViewLifecycleOwner(), backPressedCallback);

        //noinspection DataFlowIssue
        vm = new ViewModelProvider(getActivity()).get(SyncViewModel.class);

        vb.btnInstall.setOnClickListener(v -> showInstallDialog());
        vb.btnImport.setOnClickListener(v ->
                importLauncher.launch(new String[]{"*/*"}));
        vb.btnNewTask.setOnClickListener(v -> showEditDialog(null));

        vm.getTasks().observe(getViewLifecycleOwner(), this::renderTasks);

        vm.refresh();
        refreshBinarySummary();
    }

    private void refreshBinarySummary() {
        if (vb == null) {
            return;
        }
        vb.binarySummary.setText(SyncManager.getSummary(requireContext()));
    }

    /**
     * Render the task list as a stack of cards.
     *
     * @param tasks to render
     */
    private void renderTasks(@NonNull final List<SyncTask> tasks) {
        if (vb == null) {
            return;
        }
        vb.tasksContainer.removeAllViews();
        for (final SyncTask task : tasks) {
            final ItemSyncTaskBinding card = ItemSyncTaskBinding.inflate(
                    getLayoutInflater(), vb.tasksContainer, false);
            card.getRoot().setTag(task.getId());
            bindTaskCard(card, task);
            vb.tasksContainer.addView(card.getRoot());
        }
        refreshBinarySummary();
    }

    private void bindTaskCard(@NonNull final ItemSyncTaskBinding card,
                              @NonNull final SyncTask task) {
        card.taskName.setText(task.getName());

        final boolean running = SyncService.isRunning(task.getId());
        card.taskStatus.setText(statusLabel(task.getStatus()));
        setChipColor(card.taskStatus, statusColor(task.getStatus(), running));

        card.taskSummary.setText(summary(task));

        final String scheduleText = scheduleSummary(task);
        if (scheduleText.isEmpty()) {
            card.taskSchedule.setVisibility(View.GONE);
        } else {
            card.taskSchedule.setVisibility(View.VISIBLE);
            card.taskSchedule.setText(scheduleText);
        }

        if (running && task.getTotalBytes() > 0) {
            card.taskProgress.setVisibility(View.VISIBLE);
            card.taskProgressText.setVisibility(View.VISIBLE);
            final int percent = (int) Math.min(100,
                    task.getTransferredBytes() * 100 / task.getTotalBytes());
            card.taskProgress.setProgress(percent);
            card.taskProgressText.setText(getString(R.string.sync_progress_fmt,
                    percent,
                    humanSize(task.getTransferredBytes()),
                    humanSize(task.getTotalBytes())));
        } else if (running) {
            card.taskProgress.setVisibility(View.VISIBLE);
            card.taskProgressText.setVisibility(View.VISIBLE);
            card.taskProgress.setProgress(0);
            card.taskProgressText.setText(R.string.sync_progress_running);
        } else {
            card.taskProgress.setVisibility(View.GONE);
            card.taskProgressText.setVisibility(View.GONE);
        }

        card.taskLog.setTypeface(Typeface.MONOSPACE);
        card.taskLog.setText(readLogTail(SyncTaskStore.getLogFile(requireContext(), task),
                                         LOG_PREVIEW_LINES, LOG_PREVIEW_BYTES));

        card.btnToggle.setText(running ? R.string.lbl_stop : R.string.lbl_start);
        card.btnToggle.setOnClickListener(v -> {
            if (running) {
                SyncService.stopTask(requireContext(), task.getId());
            } else {
                SyncService.startTask(requireContext(), task.getId());
            }
        });

        card.btnLog.setOnClickListener(v -> showLogDialog(task));
        card.btnEdit.setOnClickListener(v -> showEditDialog(task));
        card.btnDelete.setOnClickListener(v -> confirmDelete(task));
    }

    @NonNull
    private String summary(@NonNull final SyncTask task) {
        final String arrow = task.getDirection() == SyncTask.Direction.DOWNLOAD
                             ? getString(R.string.sync_dir_download)
                             : getString(R.string.sync_dir_upload);
        final String remote = task.getRemoteType().name().toLowerCase()
                              + "://" + task.getRemoteHost()
                              + (task.getRemotePort() > 0 ? ":" + task.getRemotePort() : "")
                              + task.getRemotePath();
        return getString(R.string.sync_summary_fmt, arrow, remote, task.getLocalPath());
    }

    @NonNull
    private String scheduleSummary(@NonNull final SyncTask task) {
        if (!task.isScheduleEnabled() || task.getScheduleIntervalMinutes() <= 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(
                getString(R.string.sync_schedule_interval_fmt,
                          task.getScheduleIntervalMinutes()));
        if (task.isScheduleRequireWifi()) {
            sb.append(" · ").append(getString(R.string.sync_schedule_wifi));
        }
        if (task.isScheduleRequireCharging()) {
            sb.append(" · ").append(getString(R.string.sync_schedule_charging));
        }
        return sb.toString();
    }

    @NonNull
    private String statusLabel(@NonNull final SyncTask.Status status) {
        switch (status) {
            case RUNNING:
                return getString(R.string.sync_state_running);
            case SUCCEEDED:
                return getString(R.string.sync_state_succeeded);
            case FAILED:
                return getString(R.string.sync_state_failed);
            case CANCELLED:
                return getString(R.string.sync_state_cancelled);
            case IDLE:
            default:
                return getString(R.string.sync_state_idle);
        }
    }

    @ColorInt
    private int statusColor(@NonNull final SyncTask.Status status,
                            final boolean running) {
        if (running) {
            return RemoteAccessFragment.COLOR_STATE_RUNNING;
        }
        switch (status) {
            case SUCCEEDED:
                return RemoteAccessFragment.COLOR_STATE_RUNNING;
            case FAILED:
                return RemoteAccessFragment.COLOR_STATE_NOT_CONFIGURED;
            case CANCELLED:
            case IDLE:
            default:
                return RemoteAccessFragment.COLOR_STATE_STOPPED;
        }
    }

    private void setChipColor(@NonNull final View chip,
                              @ColorInt final int color) {
        final Drawable background = chip.getBackground();
        if (background != null) {
            background.mutate().setTint(color);
        }
    }

    @NonNull
    private String humanSize(final long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        final String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        double size = bytes;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return unit == 0
               ? (long) size + " " + units[unit]
               : String.format(java.util.Locale.ROOT, "%.1f %s", size, units[unit]);
    }

    /**
     * Show the create/edit task dialog.
     *
     * @param task to edit, or {@code null} to create a new one
     */
    private void showEditDialog(@Nullable final SyncTask task) {
        final DialogSyncTaskBinding d = DialogSyncTaskBinding.inflate(getLayoutInflater());
        final SyncTask edit = task != null ? copyOf(task) : new SyncTask();

        d.editName.setText(edit.getName());
        d.spinnerRemoteType.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item,
                SyncTask.RemoteType.values()));
        d.spinnerRemoteType.setSelection(edit.getRemoteType().ordinal());
        d.editHost.setText(edit.getRemoteHost());
        d.editPort.setText(edit.getRemotePort() > 0
                           ? String.valueOf(edit.getRemotePort()) : "");
        d.editUser.setText(edit.getRemoteUser());
        d.editPassword.setText(edit.getRemotePassword());
        d.editRemotePath.setText(edit.getRemotePath());
        d.editLocalPath.setText(edit.getLocalPath());
        d.spinnerDirection.setAdapter(new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_dropdown_item,
                SyncTask.Direction.values()));
        d.spinnerDirection.setSelection(edit.getDirection().ordinal());
        d.checkTls.setChecked(edit.isUseTls());
        d.checkScheduleEnabled.setChecked(edit.isScheduleEnabled());
        d.editInterval.setText(edit.getScheduleIntervalMinutes() > 0
                               ? String.valueOf(edit.getScheduleIntervalMinutes()) : "");
        d.checkRequireWifi.setChecked(edit.isScheduleRequireWifi());
        d.checkRequireCharging.setChecked(edit.isScheduleRequireCharging());

        final AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(task != null ? R.string.sync_btn_edit : R.string.sync_new_task)
                .setView(d.getRoot())
                .setNegativeButton(R.string.cancel, (dlg, w) -> dlg.dismiss())
                .setPositiveButton(R.string.ok, (dlg, w) -> applyEdit(d, edit))
                .create();
        dialog.show();
    }

    @NonNull
    private static SyncTask copyOf(@NonNull final SyncTask src) {
        final SyncTask copy = new SyncTask();
        copy.setId(src.getId());
        copy.setName(src.getName());
        copy.setRemoteType(src.getRemoteType());
        copy.setRemoteHost(src.getRemoteHost());
        copy.setRemotePort(src.getRemotePort());
        copy.setRemoteUser(src.getRemoteUser());
        copy.setRemotePassword(src.getRemotePassword());
        copy.setRemotePath(src.getRemotePath());
        copy.setLocalPath(src.getLocalPath());
        copy.setDirection(src.getDirection());
        copy.setUseTls(src.isUseTls());
        copy.setStatus(src.getStatus());
        copy.setLastRunEpochMs(src.getLastRunEpochMs());
        copy.setLastError(src.getLastError());
        copy.setScheduleEnabled(src.isScheduleEnabled());
        copy.setScheduleIntervalMinutes(src.getScheduleIntervalMinutes());
        copy.setScheduleRequireWifi(src.isScheduleRequireWifi());
        copy.setScheduleRequireCharging(src.isScheduleRequireCharging());
        return copy;
    }

    private void applyEdit(@NonNull final DialogSyncTaskBinding d,
                           @NonNull final SyncTask task) {
        task.setName(d.editName.getText().toString().trim());
        task.setRemoteType((SyncTask.RemoteType) d.spinnerRemoteType.getSelectedItem());
        task.setRemoteHost(d.editHost.getText().toString().trim());
        final String portText = d.editPort.getText().toString().trim();
        int port = 0;
        if (!portText.isEmpty()) {
            //noinspection OverlyBroadCatchBlock
            try {
                port = Integer.parseInt(portText);
            } catch (@NonNull final NumberFormatException ignore) {
                port = 0;
            }
        }
        task.setRemotePort(port);
        task.setRemoteUser(d.editUser.getText().toString().trim());
        task.setRemotePassword(d.editPassword.getText().toString());
        task.setRemotePath(d.editRemotePath.getText().toString().trim());
        task.setLocalPath(d.editLocalPath.getText().toString().trim());
        task.setDirection((SyncTask.Direction) d.spinnerDirection.getSelectedItem());
        task.setUseTls(d.checkTls.isChecked());
        task.setScheduleEnabled(d.checkScheduleEnabled.isChecked());
        final String intervalText = d.editInterval.getText().toString().trim();
        int interval = 0;
        if (!intervalText.isEmpty()) {
            //noinspection OverlyBroadCatchBlock
            try {
                interval = Integer.parseInt(intervalText);
            } catch (@NonNull final NumberFormatException ignore) {
                interval = 0;
            }
        }
        task.setScheduleIntervalMinutes(interval);
        task.setScheduleRequireWifi(d.checkRequireWifi.isChecked());
        task.setScheduleRequireCharging(d.checkRequireCharging.isChecked());

        if (!task.isConfigured()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.sync_not_configured)
                    .setPositiveButton(R.string.ok, (dlg, w) -> dlg.dismiss())
                    .create()
                    .show();
            return;
        }
        task.setStatus(SyncTask.Status.IDLE);
        task.resetProgress();
        vm.save(task);
    }

    private void confirmDelete(@NonNull final SyncTask task) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sync_btn_delete)
                .setMessage(getString(R.string.sync_confirm_delete, task.getName()))
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.ok, (d, w) -> {
                    if (SyncService.isRunning(task.getId())) {
                        SyncService.stopTask(requireContext(), task.getId());
                    }
                    vm.delete(task.getId());
                })
                .create()
                .show();
    }

    private void showLogDialog(@NonNull final SyncTask task) {
        final Context context = requireContext();
        final TextView textView = new TextView(context);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextSize(11);
        textView.setTextIsSelectable(true);

        final ScrollView scroller = new ScrollView(context);
        scroller.addView(textView);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.remote_log_title_fmt, task.getName()))
                .setView(scroller)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create();

        final Handler logHandler = new Handler(Looper.getMainLooper());
        final Runnable logRunnable = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    return;
                }
                final List<String> lines = readAllLines(
                        SyncTaskStore.getLogFile(context, task), LOG_LINES);
                textView.setText(lines.isEmpty()
                                 ? getString(R.string.remote_log_empty)
                                 : String.join("\n", lines));
                scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
                logHandler.postDelayed(this, 1_000);
            }
        };
        dialog.setOnDismissListener(d -> logHandler.removeCallbacks(logRunnable));
        dialog.show();
        logHandler.post(logRunnable);
    }

    private void showInstallDialog() {
        final String[] items = new String[]{
                getString(R.string.remote_btn_import),
                getString(R.string.remote_btn_download)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.remote_btn_install) + ": rclone")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        importLauncher.launch(new String[]{"*/*"});
                    } else {
                        SyncInstallHelper.download(requireActivity());
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .create()
                .show();
    }

    @NonNull
    private static String readLogTail(@NonNull final File file,
                                      final int maxLines,
                                      final int maxBytes) {
        if (!file.isFile() || file.length() == 0) {
            return "";
        }
        //noinspection OverlyBroadCatchBlock
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            final long length = raf.length();
            final int bytesToRead = (int) Math.min(length, maxBytes);
            final byte[] buffer = new byte[bytesToRead];
            raf.seek(length - bytesToRead);
            raf.readFully(buffer);

            String text = new String(buffer, StandardCharsets.UTF_8);
            if (bytesToRead < length) {
                final int firstNewline = text.indexOf('\n');
                text = firstNewline >= 0 ? text.substring(firstNewline + 1) : "";
            }

            final String[] lines = text.split("\n", -1);
            final int start = Math.max(0, lines.length - maxLines);
            final List<String> tail = new ArrayList<>(lines.length - start);
            for (int i = start; i < lines.length; i++) {
                tail.add(lines[i]);
            }
            if (!tail.isEmpty() && tail.get(tail.size() - 1).isEmpty()) {
                tail.remove(tail.size() - 1);
            }
            return String.join("\n", tail);
        } catch (@NonNull final IOException e) {
            return "";
        }
    }

    @NonNull
    private static List<String> readAllLines(@NonNull final File file,
                                              final int maxLines) {
        final List<String> lines = new ArrayList<>();
        if (!file.exists()) {
            return lines;
        }
        //noinspection OverlyBroadCatchBlock
        try {
            lines.addAll(java.nio.file.Files.readAllLines(file.toPath(),
                                                          StandardCharsets.UTF_8));
        } catch (@NonNull final IOException ignore) {
            // ignore
        }
        final int size = lines.size();
        if (size > maxLines) {
            return new ArrayList<>(lines.subList(size - maxLines, size));
        }
        return lines;
    }

    @Override
    public void onResume() {
        super.onResume();
        //noinspection deprecation
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                stateReceiver, new IntentFilter(SyncService.STATE_CHANGED));
        handler.post(refreshRunnable);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refreshRunnable);
        //noinspection deprecation
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(stateReceiver);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        vb = null;
    }
}

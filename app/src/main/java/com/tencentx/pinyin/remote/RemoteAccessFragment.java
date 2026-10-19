package com.tencentx.pinyin.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.databinding.FragmentRemoteAccessBinding;

/**
 * Screen managing the long-running helper processes:
 * frpc (reverse proxy), tailscaled (Tailscale) and nezha-agent (monitoring).
 * <p>
 * Each tool can be started/stopped, its configuration summary is shown,
 * and its live log can be inspected (the "reporting panel" for the Nezha agent).
 */
public class RemoteAccessFragment
        extends Fragment {

    public static final String TAG = "RemoteAccessFragment";

    private static final long REFRESH_INTERVAL_MS = 2_000;
    private static final int LOG_LINES = 200;
    /** Lines shown in the inline log preview of a tool card. */
    private static final int LOG_PREVIEW_LINES = 60;
    /** Maximum bytes read from the end of a log file for the inline preview. */
    private static final int LOG_PREVIEW_BYTES = 16 * 1024;

    /*
     * State colors of the status chips and dots. The main screen reuses the
     * running/stopped colors for its compact status area.
     */
    /** The tool is running (green). */
    @ColorInt
    public static final int COLOR_STATE_RUNNING = 0xFF2E7D32;
    /** The tool is configured, but not running (gray). */
    @ColorInt
    public static final int COLOR_STATE_STOPPED = 0xFF757575;
    /** The tool is not configured yet (amber). */
    @ColorInt
    public static final int COLOR_STATE_NOT_CONFIGURED = 0xFFF57C00;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshAll();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    /** React on state changes of the remote access service. */
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(@NonNull final Context context,
                              @NonNull final Intent intent) {
            refreshAll();
        }
    };

    private FragmentRemoteAccessBinding vb;

    @Nullable
    private RemoteTool importTool;
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
                    if (uri != null && importTool != null) {
                        RemoteInstallHelper.importFromUri(requireActivity(), importTool, uri);
                    }
                    importTool = null;
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                            @Nullable final ViewGroup container,
                            @Nullable final Bundle savedInstanceState) {
        vb = FragmentRemoteAccessBinding.inflate(inflater, container, false);
        return vb.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        final Toolbar toolbar = requireActivity().findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitle(R.string.remote_screen_title);
            toolbar.setNavigationIcon(R.drawable.arrow_back_24px);
        }

        requireActivity().getOnBackPressedDispatcher()
                         .addCallback(getViewLifecycleOwner(), backPressedCallback);

        vb.btnStopAll.setOnClickListener(v -> {
            RemoteAccessService.stopService(requireContext());
            refreshAll();
        });

        bindTool(RemoteTool.FRP, vb.frpSummary, vb.frpStatus, vb.frpToggle,
                 vb.frpLogBtn, vb.frpInstall, null, null, null);
        bindTool(RemoteTool.TAILSCALE, vb.tsSummary, vb.tsStatus, vb.tsToggle,
                 vb.tsLogBtn, vb.tsInstall, vb.tsUp, vb.tsCliStatus, vb.tsDown);
        bindTool(RemoteTool.NEZHA, vb.nezhaSummary, vb.nezhaStatus, vb.nezhaToggle,
                 vb.nezhaLogBtn, vb.nezhaInstall, null, null, null);

        prepareLogView(vb.frpLog);
        prepareLogView(vb.tsLog);
        prepareLogView(vb.nezhaLog);

        refreshAll();
    }

    private void bindTool(@NonNull final RemoteTool tool,
                          @NonNull final TextView summaryView,
                          @NonNull final TextView statusView,
                          @NonNull final View toggleButton,
                          @NonNull final View logButton,
                          @NonNull final View installButton,
                          @Nullable final View upButton,
                          @Nullable final View cliStatusButton,
                          @Nullable final View downButton) {
        toggleButton.setOnClickListener(v -> {
            if (RemoteAccessService.isRunning(tool)) {
                RemoteAccessService.stopTool(requireContext(), tool);
            } else {
                RemoteAccessService.startTool(requireContext(), tool);
            }
            refreshAll();
        });

        logButton.setOnClickListener(v -> showLogDialog(tool));
        installButton.setOnClickListener(v -> showInstallDialog(tool));

        if (upButton != null && cliStatusButton != null && downButton != null) {
            upButton.setOnClickListener(v -> TailscaleManager.up(requireContext(),
                                                                 output -> showOutputDialog(tool, output)));
            cliStatusButton.setOnClickListener(v -> TailscaleManager.status(requireContext(),
                                                                            output -> showOutputDialog(tool, output)));
            downButton.setOnClickListener(v -> TailscaleManager.down(requireContext(),
                                                                     output -> showOutputDialog(tool, output)));
        }
    }

    /**
     * Style an inline log preview: monospace, and explicitly outside the
     * D-pad focus order (selecting text is a touch-only nicety).
     *
     * @param logView preview to prepare
     */
    private static void prepareLogView(@NonNull final TextView logView) {
        logView.setTypeface(Typeface.MONOSPACE);
        // setTextIsSelectable() makes the view focusable by itself; keep the
        // focus order limited to the buttons.
        logView.setFocusable(false);
        logView.setFocusableInTouchMode(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        //noinspection deprecation
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                stateReceiver, new IntentFilter(RemoteAccessService.STATE_CHANGED));
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

    /**
     * Update all texts with the current state/configuration.
     */
    private void refreshAll() {
        final FragmentRemoteAccessBinding binding = vb;
        if (binding == null) {
            return;
        }

        final Context context = requireContext();

        final int count = runningCount();
        if (count == 0) {
            binding.globalStatus.setText(R.string.remote_status_stopped);
        } else {
            binding.globalStatus.setText(getString(R.string.remote_running_count_fmt, count));
        }

        // Nothing to stop: grey the "stop all" button out.
        binding.btnStopAll.setEnabled(count > 0);
        setDotColor(binding.globalStatusDot,
                    count > 0 ? COLOR_STATE_RUNNING : COLOR_STATE_STOPPED);

        updateTool(binding.frpSummary, binding.frpStatus, binding.frpToggle,
                   binding.frpLog, RemoteTool.FRP,
                   FrpManager.getSummary(context), FrpManager.isConfigured(context));
        updateTool(binding.tsSummary, binding.tsStatus, binding.tsToggle,
                   binding.tsLog, RemoteTool.TAILSCALE,
                   TailscaleManager.getSummary(context), TailscaleManager.isConfigured(context));
        updateTool(binding.nezhaSummary, binding.nezhaStatus, binding.nezhaToggle,
                   binding.nezhaLog, RemoteTool.NEZHA,
                   NezhaManager.getSummary(context), NezhaManager.isConfigured(context));
    }

    private void updateTool(@NonNull final TextView summaryView,
                            @NonNull final TextView statusView,
                            @NonNull final TextView toggleButton,
                            @NonNull final TextView logView,
                            @NonNull final RemoteTool tool,
                            @NonNull final String summary,
                            final boolean configured) {
        summaryView.setText(summary);

        final boolean running = RemoteAccessService.isRunning(tool);
        final String label = getString(tool.getLabelRes());
        if (running) {
            statusView.setText(getString(R.string.ui_tool_running_fmt, label));
            setChipColor(statusView, COLOR_STATE_RUNNING);
        } else if (configured) {
            statusView.setText(getString(R.string.ui_tool_stopped_fmt, label));
            setChipColor(statusView, COLOR_STATE_STOPPED);
        } else {
            statusView.setText(R.string.rc_state_not_configured);
            setChipColor(statusView, COLOR_STATE_NOT_CONFIGURED);
        }

        toggleButton.setText(running ? R.string.lbl_stop : R.string.lbl_start);

        updateLogPreview(logView, tool);
    }

    /**
     * Replace the inline log preview with the current tail of the tool its log
     * file; the preview follows the output, like a console.
     *
     * @param view preview to update
     * @param tool its log is shown
     */
    private void updateLogPreview(@NonNull final TextView view,
                                  @NonNull final RemoteTool tool) {
        final String tail = readLogTail(tool.getLogFile(requireContext()),
                                        LOG_PREVIEW_LINES, LOG_PREVIEW_BYTES);
        final String text = tail.isEmpty() ? getString(R.string.remote_log_empty) : tail;
        if (text.contentEquals(view.getText())) {
            return;
        }
        view.setText(text);
        if (view.getParent() instanceof ScrollView) {
            final ScrollView scroller = (ScrollView) view.getParent();
            view.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
        }
    }

    /**
     * Read the tail of a (possibly large) log file without loading all of it.
     *
     * @param file     to read
     * @param maxLines maximum number of lines to return
     * @param maxBytes maximum number of bytes to read from the end of the file
     *
     * @return the last lines joined by {@code '\n'}; empty when there is no log
     */
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
                // The first line is likely cut in the middle: drop it.
                final int firstNewline = text.indexOf('\n');
                text = firstNewline >= 0 ? text.substring(firstNewline + 1) : "";
            }

            final String[] lines = text.split("\n", -1);
            final int start = Math.max(0, lines.length - maxLines);
            final List<String> tail = new ArrayList<>(lines.length - start);
            for (int i = start; i < lines.length; i++) {
                tail.add(lines[i]);
            }
            // A trailing newline produces an empty last element.
            if (!tail.isEmpty() && tail.get(tail.size() - 1).isEmpty()) {
                tail.remove(tail.size() - 1);
            }
            return String.join("\n", tail);

        } catch (@NonNull final IOException e) {
            return "";
        }
    }

    /**
     * Tint the rounded background of a tool state chip.
     *
     * @param chip  to color
     * @param color to apply
     */
    private static void setChipColor(@NonNull final View chip,
                                     @ColorInt final int color) {
        final Drawable background = chip.getBackground();
        if (background != null) {
            // mutate(): the three chips share the same drawable resource.
            background.mutate().setTint(color);
        }
    }

    /**
     * Tint a small status dot.
     *
     * @param dot   to color
     * @param color to apply
     */
    private static void setDotColor(@NonNull final ImageView dot,
                                    @ColorInt final int color) {
        dot.setImageTintList(ColorStateList.valueOf(color));
    }

    private int runningCount() {
        int count = 0;
        for (final RemoteTool tool : RemoteTool.values()) {
            if (RemoteAccessService.isRunning(tool)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Show a live-updating view of a tool its log file.
     *
     * @param tool to show
     */
    private void showLogDialog(@NonNull final RemoteTool tool) {
        final Context context = requireContext();

        final TextView textView = new TextView(context);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextSize(11);
        textView.setTextIsSelectable(true);

        final int padding = getResources()
                .getDimensionPixelSize(R.dimen.container_margin);
        textView.setPadding(padding, padding, padding, padding);

        final ScrollView scroller = new ScrollView(context);
        scroller.addView(textView);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.remote_log_title_fmt,
                                    getString(tool.getLabelRes())))
                .setView(scroller)
                .setNeutralButton(R.string.remote_btn_clear_log,
                                  (d, w) -> new ManagedProcess(tool.getName(),
                                                               tool.getLogFile(context)).clearLog())
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create();

        final Handler logHandler = new Handler(Looper.getMainLooper());
        final Runnable logRunnable = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) {
                    return;
                }
                final List<String> lines = ManagedProcess.readTail(tool.getLogFile(context),
                                                                   LOG_LINES);
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

    /**
     * Show the output of a CLI action (tailscale up/status/down).
     *
     * @param tool   involved
     * @param output to show
     */
    private void showOutputDialog(@NonNull final RemoteTool tool,
                                  @NonNull final String output) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(tool.getLabelRes()))
                .setMessage(output)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create()
                .show();
    }

    /**
     * Offer to import a local file or to download the official binary.
     *
     * @param tool to install
     */
    private void showInstallDialog(@NonNull final RemoteTool tool) {
        final String[] items = new String[]{
                getString(R.string.remote_btn_import),
                getString(R.string.remote_btn_download)
        };

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.remote_btn_install) + ": "
                          + getString(tool.getLabelRes()))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        importTool = tool;
                        importLauncher.launch(new String[]{"*/*"});
                    } else {
                        RemoteInstallHelper.download(requireActivity(), tool);
                    }
                })
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .create()
                .show();
    }
}

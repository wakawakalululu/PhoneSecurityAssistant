package com.tencentx.pinyin.terminal;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.List;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.databinding.FragmentTerminalBinding;
import com.tencentx.pinyin.privilege.PrivilegeMode;
import com.tencentx.pinyin.remote.RemoteAccessService;
import com.tencentx.pinyin.remote.RemoteTool;

/**
 * The built-in terminal: run commands with the selected privilege mode
 * and show the combined stdout+stderr output in a console view.
 * <p>
 * The session and its output are kept in the {@link TerminalViewModel},
 * so a long-running command and its output survive screen rotation.
 * <p>
 * The screen also offers quick actions to start/stop the remote helper tools
 * (frpc, tailscaled, nezha-agent) without leaving the terminal.
 */
public class TerminalFragment
        extends Fragment {

    /** Fragment tag. */
    public static final String TAG = "TerminalFragment";

    @Nullable
    private FragmentTerminalBinding vb;
    @Nullable
    private TerminalViewModel vm;

    /** {@code true} on Android TV devices; used to give the D-pad an initial focus. */
    private boolean isTelevision;

    /** Back press: restore the title and leave the terminal screen. */
    private final OnBackPressedCallback backPressedCallback =
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    restoreToolbarTitle();
                    getParentFragmentManager().popBackStack();
                }
            };

    /**
     * The remote tools can be started/stopped right here (quick actions),
     * so refresh them whenever the {@link RemoteAccessService} reports a change.
     */
    private final BroadcastReceiver remoteStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(@NonNull final Context context,
                              @NonNull final Intent intent) {
            updateQuickActions();
        }
    };

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //noinspection DataFlowIssue
        isTelevision = requireContext().getPackageManager()
                                       .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        vb = FragmentTerminalBinding.inflate(inflater, container, false);
        return vb.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        vm = new ViewModelProvider(this).get(TerminalViewModel.class);

        final Toolbar toolbar = requireActivity().findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setTitle(R.string.term_title);
            toolbar.setNavigationIcon(R.drawable.arrow_back_24px);
        }

        //noinspection ConstantConditions
        getActivity().getOnBackPressedDispatcher()
                     .addCallback(getViewLifecycleOwner(), backPressedCallback);

        vm.getOutput().observe(getViewLifecycleOwner(), this::onOutput);
        vm.getRunning().observe(getViewLifecycleOwner(), this::onRunningChanged);
        vm.getMode().observe(getViewLifecycleOwner(), this::onModeChanged);

        // monospace in XML (fontFamily) and in code, as a safety net
        vb.console.setTypeface(Typeface.MONOSPACE);
        vb.editCommand.setTypeface(Typeface.MONOSPACE);
        // textIsSelectable() makes the console focusable; keep the D-pad path free
        vb.console.setFocusable(false);
        vb.consoleScroller.setFocusable(false);

        vb.btnMode.setOnClickListener(v -> vm.cycleMode());
        vb.btnRun.setOnClickListener(v -> send());
        vb.btnStop.setOnClickListener(v -> vm.stop());
        vb.btnClear.setOnClickListener(v -> vm.clearOutput());
        vb.btnHistoryUp.setOnClickListener(v -> recall(true));
        vb.btnHistoryDown.setOnClickListener(v -> recall(false));

        vb.btnQuickFrp.setOnClickListener(v -> toggleTool(RemoteTool.FRP));
        vb.btnQuickTailscale.setOnClickListener(v -> toggleTool(RemoteTool.TAILSCALE));
        vb.btnQuickNezha.setOnClickListener(v -> toggleTool(RemoteTool.NEZHA));

        vb.editCommand.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });
        // Hardware keyboards: Enter sends the command.
        // The D-pad keys are left alone so a TV remote can still move the focus
        // (the dedicated ↑/↓ buttons recall the history).
        vb.editCommand.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    send();
                }
                return true;
            }
            return false;
        });

        updateQuickActions();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (vm != null) {
            // in case the mode was changed on another screen
            vm.refreshMode();
        }

        //noinspection deprecation
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                remoteStateReceiver,
                new IntentFilter(RemoteAccessService.STATE_CHANGED));
        updateQuickActions();

        if (isTelevision && vb != null) {
            vb.btnQuickFrp.requestFocus();
        }
    }

    @Override
    public void onPause() {
        //noinspection deprecation
        LocalBroadcastManager.getInstance(requireContext())
                             .unregisterReceiver(remoteStateReceiver);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        restoreToolbarTitle();
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        if (vm != null) {
            // the session belongs to this screen: stop it when we are destroyed
            vm.stop();
        }
        super.onDestroy();
    }

    /**
     * Echo the entered command and start it.
     */
    private void send() {
        if (vb == null) {
            return;
        }
        final String command = vb.editCommand.getText() != null
                               ? vb.editCommand.getText().toString().strip()
                               : "";
        if (command.isEmpty() || !vb.btnRun.isEnabled()) {
            return;
        }

        // echo the command into the console
        vm.appendLine(getString(R.string.term_started_fmt, command));
        vb.editCommand.setText("");
        vm.run(command);
    }

    /**
     * Recall a command from the history into the input field.
     *
     * @param up {@code true}: previous command; {@code false}: next command
     */
    private void recall(final boolean up) {
        if (vb == null) {
            return;
        }
        final String command = up ? vm.historyUp() : vm.historyDown();
        vb.editCommand.setText(command);
        vb.editCommand.setSelection(command.length());
    }

    /**
     * Show the console output and keep it pinned to the bottom.
     *
     * @param lines the console output
     */
    private void onOutput(@Nullable final List<String> lines) {
        if (vb == null) {
            return;
        }
        final boolean empty = lines == null || lines.isEmpty();
        vb.consoleEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        vb.console.setText(empty ? "" : String.join("\n", lines));

        final ScrollView scroller = vb.consoleScroller;
        scroller.post(() -> scroller.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void onRunningChanged(@Nullable final Boolean running) {
        if (vb == null) {
            return;
        }
        final boolean isRunning = Boolean.TRUE.equals(running);
        vb.btnRun.setEnabled(!isRunning);
        vb.btnStop.setEnabled(isRunning);
        vb.btnMode.setEnabled(!isRunning);
        vb.txtStatus.setText(isRunning
                             ? R.string.term_status_running
                             : R.string.term_status_idle);
    }

    private void onModeChanged(@Nullable final PrivilegeMode mode) {
        if (vb == null) {
            return;
        }
        final PrivilegeMode current = mode != null ? mode : PrivilegeMode.APP;
        vb.btnMode.setText(getString(R.string.term_mode_fmt,
                                     getString(current.getLabelRes())));
    }

    /**
     * Show for every quick action button whether it would start or stop
     * the matching remote tool.
     */
    private void updateQuickActions() {
        if (vb == null) {
            return;
        }
        bindQuickAction(vb.btnQuickFrp, RemoteTool.FRP);
        bindQuickAction(vb.btnQuickTailscale, RemoteTool.TAILSCALE);
        bindQuickAction(vb.btnQuickNezha, RemoteTool.NEZHA);
    }

    /**
     * Update the text of one quick action button: "Start &lt;tool&gt;"
     * while it is stopped, "Stop &lt;tool&gt;" while it is running.
     *
     * @param button to update
     * @param tool   the tool the button controls
     */
    private void bindQuickAction(@NonNull final Button button,
                                 @NonNull final RemoteTool tool) {
        final String label = getString(tool.getLabelRes());
        final boolean running = RemoteAccessService.isRunning(tool);
        button.setText(getString(running
                                 ? R.string.term_tool_stop_fmt
                                 : R.string.term_tool_start_fmt,
                                 label));
    }

    /**
     * Start or stop a remote tool (frpc / tailscaled / nezha-agent).
     * The button state itself is refreshed by the {@link RemoteAccessService#STATE_CHANGED}
     * broadcast, so whether the tool really starts is up to the service
     * (e.g. a missing binary is reported in the tool its log).
     *
     * @param tool to toggle
     */
    private void toggleTool(@NonNull final RemoteTool tool) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final String label = getString(tool.getLabelRes());
        if (RemoteAccessService.isRunning(tool)) {
            Toast.makeText(context,
                           getString(R.string.term_tool_stopping_toast_fmt, label),
                           Toast.LENGTH_SHORT).show();
            RemoteAccessService.stopTool(context, tool);
        } else {
            Toast.makeText(context,
                           getString(R.string.term_tool_starting_toast_fmt, label),
                           Toast.LENGTH_SHORT).show();
            RemoteAccessService.startTool(context, tool);
        }
    }

    /**
     * Restore the activity toolbar title after leaving this screen.
     */
    private void restoreToolbarTitle() {
        final Activity activity = getActivity();
        if (activity != null) {
            final Toolbar toolbar = activity.findViewById(R.id.toolbar);
            if (toolbar != null) {
                toolbar.setTitle(R.string.app_name);
            }
        }
    }
}

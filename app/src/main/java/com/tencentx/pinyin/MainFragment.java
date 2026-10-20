package com.tencentx.pinyin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

import com.tencentx.pinyin.databinding.DialogAboutBinding;
import com.tencentx.pinyin.databinding.FragmentMainBinding;
import com.tencentx.pinyin.privilege.PrivilegeMode;
import com.tencentx.pinyin.remote.RemoteAccessFragment;
import com.tencentx.pinyin.remote.RemoteAccessService;
import com.tencentx.pinyin.remote.RemoteTool;
import com.tencentx.pinyin.remotedesktop.RemoteDesktopFragment;
import com.tencentx.pinyin.settings.Prefs;
import com.tencentx.pinyin.settings.SettingsFragment;
import com.tencentx.pinyin.sync.SyncFragment;
import com.tencentx.pinyin.terminal.TerminalFragment;

public class MainFragment
        extends Fragment {

    static final String TAG = "MainFragment";
    /** boolean. */
    private static final String PK_UI_NOTIFICATION_ASK_PERMISSION =
            "ui.notification.ask_permission";
    /** boolean. */
    private static final String PK_UI_ASK_TO_ADD_TILE = "ui.tile.ask";

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), isGranted -> {
                        if (!isGranted) {
                            //noinspection DataFlowIssue
                            Prefs.getSharedPreferences(getContext())
                                .edit()
                                .putBoolean(PK_UI_NOTIFICATION_ASK_PERMISSION, false)
                                .apply();
                        }
                    });

    private ServiceViewModel vm;

    /**
     * Listen for broadcasts from the service informing us about any changes.
     * We simply react by updating the UI.
     */
    private final BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(@NonNull final Context context,
                              @NonNull final Intent intent) {
            vm.updateUI();
        }
    };

    /**
     * Listen for broadcasts from the {@link RemoteAccessService}
     * informing us that the state of one of the tools changed.
     * We simply react by updating the UI.
     */
    private final BroadcastReceiver remoteStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(@NonNull final Context context,
                              @NonNull final Intent intent) {
            onUpdateUi();
        }
    };
    private FragmentMainBinding vb;

    @Nullable
    private ToolbarMenuProvider toolbarMenuProvider;
    private boolean isTelevision;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //noinspection DataFlowIssue
        isTelevision = getContext().getPackageManager()
                                   .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        vb = FragmentMainBinding.inflate(inflater, container, false);
        return vb.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {

        final Toolbar toolbar = requireActivity().findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(null);

        if (isTelevision) {
            vb.tvButtons.setVisibility(View.VISIBLE);

            vb.tvBtnStartAction.setOnClickListener(v -> onMenu(R.id.start_action));
            vb.tvBtnSettings.setOnClickListener(v -> onMenu(R.id.settings));
            vb.tvBtnTerminal.setOnClickListener(v -> onMenu(R.id.terminal));
            vb.tvBtnRemote.setOnClickListener(v -> onMenu(R.id.remote_access));
            vb.tvBtnSync.setOnClickListener(v -> onMenu(R.id.sync));
            vb.tvBtnRemoteDesktop.setOnClickListener(v -> onMenu(R.id.remote_desktop));
            vb.tvBtnHelp.setOnClickListener(v -> onMenu(R.id.menu_help));
            vb.tvBtnAbout.setOnClickListener(v -> onMenu(R.id.about));

            vb.log.setSelectAllOnFocus(true);
            vb.logScroller.setFocusable(false);

            // up/down loops through the log view and all buttons.
            // left/right not defined
            vb.log.setNextFocusDownId(vb.tvBtnStartAction.getId());
            vb.log.setNextFocusUpId(vb.tvBtnAbout.getId());
            vb.tvBtnStartAction.setNextFocusUpId(vb.log.getId());
            vb.tvBtnStartAction.setNextFocusDownId(vb.tvBtnSettings.getId());
            vb.tvBtnSettings.setNextFocusUpId(vb.tvBtnStartAction.getId());
            vb.tvBtnSettings.setNextFocusDownId(vb.tvBtnTerminal.getId());
            vb.tvBtnTerminal.setNextFocusUpId(vb.tvBtnSettings.getId());
            vb.tvBtnTerminal.setNextFocusDownId(vb.tvBtnRemote.getId());
            vb.tvBtnRemote.setNextFocusUpId(vb.tvBtnTerminal.getId());
            vb.tvBtnRemote.setNextFocusDownId(vb.tvBtnSync.getId());
            vb.tvBtnSync.setNextFocusUpId(vb.tvBtnRemote.getId());
            vb.tvBtnSync.setNextFocusDownId(vb.tvBtnRemoteDesktop.getId());
            vb.tvBtnRemoteDesktop.setNextFocusUpId(vb.tvBtnSync.getId());
            vb.tvBtnRemoteDesktop.setNextFocusDownId(vb.tvBtnHelp.getId());
            vb.tvBtnHelp.setNextFocusUpId(vb.tvBtnRemoteDesktop.getId());
            vb.tvBtnHelp.setNextFocusDownId(vb.tvBtnAbout.getId());
            vb.tvBtnAbout.setNextFocusUpId(vb.tvBtnHelp.getId());
            vb.tvBtnAbout.setNextFocusDownId(vb.log.getId());

        } else {
            vb.tvButtons.setVisibility(View.GONE);
            toolbarMenuProvider = new ToolbarMenuProvider();
            toolbar.addMenuProvider(toolbarMenuProvider, getViewLifecycleOwner());
        }

        //noinspection DataFlowIssue
        vm = new ViewModelProvider(getActivity()).get(ServiceViewModel.class);
        vm.forceLogUpdate();

        vm.onLogUpdate().observe(getViewLifecycleOwner(), output -> {
            // We always replace the WHOLE content.
            vb.log.setText(String.join("\n", output));
            vb.logScroller.post(() -> vb.logScroller.fullScroll(ScrollView.FOCUS_DOWN));
        });
        vm.onUpdateUi().observe(getViewLifecycleOwner(), aVoid -> onUpdateUi());

        //noinspection DataFlowIssue
        final SharedPreferences prefs = Prefs.getSharedPreferences(getContext());
        if (prefs.getBoolean(PK_UI_ASK_TO_ADD_TILE, true)) {
            askToAddTile();
            // We only ask once
            prefs.edit().putBoolean(PK_UI_ASK_TO_ADD_TILE, false).apply();
        }
    }

    private void askToAddTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askV33();
        } else {
            askV30();
        }
    }

    private void askV30() {
        //noinspection DataFlowIssue
        new MaterialAlertDialogBuilder(getContext())
                .setTitle(R.string.app_name)
                .setIcon(R.drawable.app_tile)
                .setMessage(R.string.tile_info_adding)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create()
                .show();
    }

    @RequiresApi(api = 33)
    private void askV33() {
        final Context context = requireContext();
        final StatusBarManager sbm = context.getSystemService(StatusBarManager.class);
        if (sbm == null) {
            // Should never get here... flw
            Log.e(TAG, "No StatusBarManager service found");
            return;
        }

        final ComponentName tileService = new ComponentName(context, SshdTileService.class);
        sbm.requestAddTileService(
                tileService,
                getString(R.string.app_name),
                Icon.createWithResource(context, R.drawable.app_tile),
                Runnable::run,
                result -> {
                    switch (result) {
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED:
                            //noinspection DataFlowIssue
                            Snackbar.make(getView(), R.string.tile_added,
                                          Snackbar.LENGTH_SHORT).show();
                            // Not sure if this is really needed?
                            TileService.requestListeningState(context, tileService);
                            break;
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED:
                            //noinspection DataFlowIssue
                            Snackbar.make(getView(), R.string.tile_not_added,
                                          Snackbar.LENGTH_SHORT).show();
                            break;
                        case StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED:
                            //noinspection DataFlowIssue
                            Snackbar.make(getView(), R.string.tile_already_added,
                                          Snackbar.LENGTH_SHORT).show();
                            break;
                    }
                });
    }

    private void requestListeningState(@NonNull final Context context) {
        final ComponentName tileService = new ComponentName(context, SshdTileService.class);
        TileService.requestListeningState(context, tileService);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Child screens (settings/terminal/remote access) set their own title
        // and navigation icon. So restore ours every time we resume.
        final Toolbar toolbar = requireActivity().findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        toolbar.setNavigationIcon(null);

        // This is quite essential, without this permission the user cannot really use rsync/sftp
        if (!hasFileAccessPermission()
            && !vm.isSpecialPermDialogShowing()) {
            requestFileAccessPermission();
        }

        // This is optional and only needed from Android 13 up
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission();
        }

        //noinspection ConstantConditions,deprecation
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(
                messageReceiver, new IntentFilter(SshdService.SERVICE_UI_REQUEST));

        //noinspection ConstantConditions,deprecation
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(
                remoteStateReceiver,
                new IntentFilter(RemoteAccessService.STATE_CHANGED));

        // Race situation if the service does not stop fast enough....
        // 1. device in portrait; start-on-app-start
        // 2. running==false
        // 3. starts
        // 4. ROTATE, the service is triggered to stop running
        // 5. onResume... service still running
        // 6. so we don't start it.
        // 7. the service is finally stopped, and we have NOT (re)started it
        // 2025-04-12: won't fix... unless some user complains very hard...
        // ... and that might result in locking the rotation of the device for this app.
        if (SshdService.isRunning()) {
            vm.startUpdateThread(getContext());

        } else if (Prefs.isRunOnAppStart(getContext())) {
            vm.startService(getContext(), StartMode.ByUser);
        }

        onUpdateUi();
        populateNetworkAddressList();

        if (isTelevision) {
            vb.tvBtnStartAction.requestFocus();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void requestNotificationPermission() {
        //noinspection ConstantConditions
        if (ContextCompat.checkSelfPermission(
                getContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {

            if (Prefs.getSharedPreferences(getContext())
                     .getBoolean(PK_UI_NOTIFICATION_ASK_PERMISSION, true)) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    /**
     * GitHub #12:
     * Android/Google TV 11 & 12:
     * The standard Google image does not provide the needed Dialog.
     * Some vendors <strong>might</strong> add them.
     * <p>
     * Android/Google TV 13 & 14: the dialog is always available.
     */
    private void requestFileAccessPermission() {

        final Context context = requireContext();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestManageAllFilesAccessPermission(context);
        } else {
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void requestManageAllFilesAccessPermission(@NonNull final Context context) {

        final Intent intent = new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.getPackageName(), null));

        @SuppressLint("QueryPermissionsNeeded")
        final List<ResolveInfo> resInfoList = context
                .getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_ALL);

        if (resInfoList.isEmpty()) {
            potentiallyUnsupportedDevice();
            return;
        }

        //noinspection ConstantConditions
        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.security_24px)
                .setTitle(R.string.dialog_title_attention)
                .setMessage(R.string.msg_request_files_management)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    d.dismiss();
                    vm.setSpecialPermDialogShowing(false);
                })
                .setPositiveButton(R.string.ok, (d, w) -> {
                    d.dismiss();
                    vm.setSpecialPermDialogShowing(false);
                    try {
                        startActivity(intent);
                    } catch (@NonNull final ActivityNotFoundException e) {
                        unsupportedDevice();
                    }
                })
                .create()
                .show();

        vm.setSpecialPermDialogShowing(true);
    }

    private boolean hasFileAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The intent to start {@link Settings#ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION} failed.
     * The user is likely on an Android-TV 11 or 12,
     * or on an Android-Wear device.
     * <p>
     * It <strong>might</strong> be possible to manually grant permissions:
     * show a message explaining how to do so.
     */
    private void potentiallyUnsupportedDevice() {
        //noinspection DataFlowIssue
        new MaterialAlertDialogBuilder(getContext())
                .setIcon(R.drawable.warning_24px)
                .setMessage(R.string.err_device_maybe_not_supported)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create()
                .show();
    }

    /**
     * Fatal. The device claimed to support
     * {@link Settings#ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION}
     * but failed to start the Activity.
     * We should never get here... flw
     */
    private void unsupportedDevice() {
        //noinspection DataFlowIssue
        new MaterialAlertDialogBuilder(getContext())
                .setIcon(R.drawable.error_24px)
                .setMessage(R.string.err_unexpected_error)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create()
                .show();
    }

    @Override
    public void onPause() {
        vm.cancelUpdateThread();
        //noinspection ConstantConditions,deprecation
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(messageReceiver);
        //noinspection ConstantConditions,deprecation
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(remoteStateReceiver);
        super.onPause();
    }

    /**
     * When started {@link StartMode#ByUser} stop it.
     * If we were started at app-start, then onResume will restart us.
     * <p>
     * <strong>NEVER STOP</strong> the service if started
     * <ul>
     *     <li>{@link StartMode#ByIntent}</li>
     *     <li>{@link StartMode#OnBoot}</li>
     * </ul>
     */
    @Override
    public void onDestroy() {
        if (vm != null) {
            final Context context = requireContext();
            if (SshdService.getStartMode() == StartMode.ByUser
                && !Prefs.isKeepRunningOnAppExit(context)) {
                vm.stopService(context);
            }
        }
        super.onDestroy();
    }

    private void onUpdateUi() {
        if (toolbarMenuProvider != null) {
            updateStartButton(toolbarMenuProvider.startButton);
        } else if (isTelevision) {
            updateStartButton(vb.tvBtnStartAction);
        }

        // We're setting this regardless of StartMode.
        // If we have a UI, then we always want this flag set while running
        // to prevent the device going to sleep and slowing down transfers.
        if (SshdService.isRunning()) {
            //noinspection DataFlowIssue
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            //noinspection DataFlowIssue
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        populateNetworkAddressList();
        updateStatusViews();
    }

    private void populateNetworkAddressList() {
        // Show all interfaces, limited to 6...
        final List<String> iList = SshdSettings.getHostAddresses(6);
        if (iList.isEmpty()) {
            // should never happen... flw
            vb.allInterfaces.setText(R.string.err_no_ip);
        } else {
            vb.allInterfaces.setText(String.join("\n", iList));
        }

        // The configured interface listener(s)
        //noinspection ConstantConditions
        final SharedPreferences prefs = Prefs.getSharedPreferences(getContext());
        final List<String> bindings = Prefs.getBindings(prefs);
        if (bindings.isEmpty()) {
            vb.listeners.setText(R.string.err_no_ip);
        } else {
            vb.listeners.setText(String.join("\n", bindings));
        }
    }

    /**
     * Update the compact status area with the current privilege mode
     * and the state of the remote tools.
     */
    private void updateStatusViews() {
        //noinspection ConstantConditions
        final SharedPreferences prefs = Prefs.getSharedPreferences(getContext());
        final PrivilegeMode mode = Prefs.getShellMode(prefs);
        vb.statusMode.setText(getString(R.string.ui_privilege_fmt,
                                       getString(mode.getLabelRes())));
        // Green = escalated (root/ADB) privileges are in use; gray = plain app mode.
        setStatusDot(vb.statusModeDot,
                     mode == PrivilegeMode.APP
                     ? RemoteAccessFragment.COLOR_STATE_STOPPED
                     : RemoteAccessFragment.COLOR_STATE_RUNNING);

        final boolean remoteRunning = RemoteAccessService.isAnyRunning();
        setStatusDot(vb.statusRemoteDot,
                     remoteRunning
                     ? RemoteAccessFragment.COLOR_STATE_RUNNING
                     : RemoteAccessFragment.COLOR_STATE_STOPPED);

        if (!remoteRunning) {
            vb.statusRemote.setText(R.string.ui_remote_stopped);
            return;
        }

        final List<String> tools = new ArrayList<>();
        for (final RemoteTool tool : RemoteTool.values()) {
            tools.add(getString(RemoteAccessService.isRunning(tool)
                                ? R.string.ui_tool_running_fmt
                                : R.string.ui_tool_stopped_fmt,
                                getString(tool.getLabelRes())));
        }
        vb.statusRemote.setText(getString(R.string.ui_remote_running_fmt,
                                         String.join(" · ", tools)));
    }

    /**
     * Tint one of the small decorative status dots next to
     * {@code status_mode} / {@code status_remote}.
     *
     * @param dot   to color
     * @param color to apply
     */
    private void setStatusDot(@NonNull final ImageView dot,
                              @ColorInt final int color) {
        dot.setImageTintList(ColorStateList.valueOf(color));
    }

    private void showAbout() {
        final Context context = getContext();
        String version;
        //noinspection OverlyBroadCatchBlock
        try {
            //noinspection ConstantConditions
            version = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0).versionName;

        } catch (@NonNull final Exception e) {
            version = getString(R.string.err_no_version);
        }

        final DialogAboutBinding dvb = DialogAboutBinding.inflate(getLayoutInflater());
        dvb.version.setText(getString(R.string.about_versions,
                                      SshdService.getDropbearVersion(),
                                      SshdService.getRsyncVersion(),
                                      SshdService.getOpensshVersion()));

        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.info_24px)
                .setTitle(getString(R.string.about_title, version))
                .setView(dvb.getRoot())
                .setCancelable(true)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create()
                .show();
    }

    /**
     * Show the in-app help.
     */
    private void showHelpDialog() {
        //noinspection DataFlowIssue
        new MaterialAlertDialogBuilder(getContext())
                .setIcon(R.drawable.info_24px)
                .setTitle(R.string.ui_help_title)
                .setMessage(R.string.ui_help_message)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create()
                .show();
    }

    private void showAdbBridgeHint() {
        //noinspection DataFlowIssue
        new MaterialAlertDialogBuilder(getContext())
                .setIcon(R.drawable.info_24px)
                .setTitle(R.string.ui_adb_bridge)
                .setMessage(R.string.adb_bridge_menu_hint)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create()
                .show();
    }

    @SuppressWarnings("SameParameterValue")
    private void replaceFragment(@NonNull final Class<? extends Fragment> fragmentClass,
                                 @NonNull final String tag) {
        final FragmentManager fm = getParentFragmentManager();
        if (fm.findFragmentByTag(tag) == null) {
            fm.beginTransaction()
              .addToBackStack(tag)
              .setReorderingAllowed(true)
              .replace(R.id.fragment_container, fragmentClass, null, tag)
              .commit();
        }
    }

    /**
     * React to both the options-menu and television button-menu.
     *
     * @param itemId selected
     *
     * @return {@code true} if handled
     */
    private boolean onMenu(final int itemId) {
        if (itemId == R.id.start_action) {
            if (SshdService.isRunning()) {
                //noinspection ConstantConditions
                vm.stopService(getContext());
            } else {
                //noinspection ConstantConditions
                if (!vm.startService(getContext(), StartMode.ByUser)) {
                    //noinspection ConstantConditions
                    Snackbar.make(getView(), R.string.err_service_failed_to_start,
                                  Snackbar.LENGTH_LONG).show();
                }
            }
        } else if (itemId == R.id.settings) {
            replaceFragment(SettingsFragment.class, SettingsFragment.TAG);
            return true;
        } else if (itemId == R.id.terminal) {
            replaceFragment(TerminalFragment.class, TerminalFragment.TAG);
            return true;
        } else if (itemId == R.id.remote_access) {
            replaceFragment(RemoteAccessFragment.class, RemoteAccessFragment.TAG);
            return true;
        } else if (itemId == R.id.sync) {
            replaceFragment(SyncFragment.class, SyncFragment.TAG);
            return true;
        } else if (itemId == R.id.adb_bridge) {
            showAdbBridgeHint();
            return true;
        } else if (itemId == R.id.remote_desktop) {
            replaceFragment(RemoteDesktopFragment.class, RemoteDesktopFragment.TAG);
            return true;
        } else if (itemId == R.id.menu_help) {
            showHelpDialog();
            return true;
        } else if (itemId == R.id.about) {
            showAbout();
            return true;
        }
        return false;
    }

    /**
     * Update text/color of the "start" button.
     *
     * @param startButton either the toolbar or the TV button.
     */
    private void updateStartButton(@NonNull final Button startButton) {
        if (SshdService.isRunning()) {
            startButton.setText(R.string.lbl_stop);
            startButton.setTextColor(getColor(R.attr.stopButtonTextColor));
        } else {
            startButton.setText(R.string.lbl_start);
            startButton.setTextColor(getColor(R.attr.startButtonTextColor));
        }
    }

    @ColorInt
    private int getColor(@AttrRes final int colorAttrId) {
        //noinspection DataFlowIssue
        final TypedArray a = getContext()
                .obtainStyledAttributes(new int[]{colorAttrId});
        try {
            return a.getColor(0, 0);
        } finally {
            a.recycle();
        }
    }

    private class ToolbarMenuProvider
            implements MenuProvider {

        private Button startButton;

        @Override
        public void onCreateMenu(@NonNull final Menu menu,
                                 @NonNull final MenuInflater menuInflater) {
            menuInflater.inflate(R.menu.main_menu, menu);

            final MenuItem menuItem = menu.findItem(R.id.start_action);
            //noinspection DataFlowIssue
            startButton = menuItem.getActionView().findViewById(R.id.btn_start);
            startButton.setOnClickListener(v -> onMenu(R.id.start_action));
        }

        @Override
        public boolean onMenuItemSelected(@NonNull final MenuItem menuItem) {
            return onMenu(menuItem.getItemId());
        }
    }
}

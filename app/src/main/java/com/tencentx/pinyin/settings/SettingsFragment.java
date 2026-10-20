package com.tencentx.pinyin.settings;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import com.hardbacknutter.prefslib.BooleanSetting;
import com.hardbacknutter.prefslib.Setting;
import com.hardbacknutter.prefslib.SettingsDataStore;
import com.hardbacknutter.prefslib.SettingsManager;
import com.hardbacknutter.prefslib.SharedPreferencesDataStore;
import com.hardbacknutter.prefslib.StringSetting;
import com.tencentx.pinyin.R;
import com.tencentx.pinyin.SshdSettings;
import com.tencentx.pinyin.adb.AdbUi;
import com.tencentx.pinyin.remote.RemoteInstallHelper;
import com.tencentx.pinyin.remote.RemoteTool;
import com.tencentx.pinyin.remote.WebDavManager;
import com.tencentx.pinyin.sync.SyncInstallHelper;
import com.tencentx.pinyin.util.theme.NightMode;

public class SettingsFragment
        extends Fragment {

    public static final String TAG = "SettingsFragment";

    private static final int PORT_MIN = 1024;
    private static final int PORT_MAX = 32768;

    private boolean isTelevision;
    private SettingsViewModel vm;
    private final OnBackPressedCallback backPressedCallback =
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    try {
                        //noinspection DataFlowIssue
                        vm.getUserPassDataStore().storeCredentials(getContext());

                    } catch (@NonNull final IOException | NoSuchAlgorithmException ignore) {
                        // we should never get here... flw
                        //noinspection DataFlowIssue
                        Snackbar.make(getView(), R.string.err_failed_to_save,
                                      Snackbar.LENGTH_LONG).show();
                        getView().postDelayed(() -> getParentFragmentManager().popBackStack(),
                                              2_000);
                    }

                    if (vm.hasAtLeastOneAuthMethod(getContext())) {
                        getParentFragmentManager().popBackStack();
                    } else {
                        new MaterialAlertDialogBuilder(getContext())
                                .setIcon(R.drawable.warning_24px)
                                .setTitle(R.string.dialog_title_warning)
                                .setMessage(R.string.warning_no_auth_methods)
                                .setCancelable(true)
                                .setNegativeButton(R.string.cancel, (d, which) ->
                                        d.dismiss())
                                .setPositiveButton(R.string.ok, (d, which) ->
                                        getParentFragmentManager().popBackStack())
                                .create()
                                .show();
                    }
                }
            };
    private SettingsManager settingsManager;

    private ActivityResultLauncher<String> authKeysImportLauncher;

    private View progressOverlay;
    private CircularProgressIndicator progressIndicator;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //noinspection DataFlowIssue
        isTelevision = getContext().getPackageManager()
                                   .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    @Override
    @Nullable
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    private SettingsManager.Builder onCreateSettings() {
        final Context context = getContext();

        //noinspection DataFlowIssue
        final SettingsDataStore store = new SharedPreferencesDataStore(
                Prefs.getSharedPreferences(context));

        final SettingsManager.Builder factory = new SettingsManager.Builder(context, store);

        factory.header(R.string.pc_startup);
        factory.bool(Prefs.RUN_ON_BOOT,
                     R.string.pt_start_service_on_device_boot,
                     this::onRunModeChanged, null);
        factory.bool(Prefs.RUN_ON_APP_START,
                     R.string.pt_start_service_on_app_opening,
                     null, null);
        factory.bool(Prefs.RUN_ON_INTENT_ALLOWED,
                     R.string.pt_start_service_by_intent_in_fg_allowed,
                     null, null);
        factory.bool(Prefs.RUN_IN_FOREGROUND,
                     R.string.pt_foreground,
                     R.string.ps_foreground_off,
                     R.string.ps_foreground_on,
                     this::onRunModeChanged, p -> {
                    p.setChecked(true);
                });
        factory.bool(Prefs.ON_APP_EXIT_KEEP_RUNNING,
                     R.string.pt_keep_running_after_app_exit,
                     R.string.disabled, R.string.enabled,
                     null, null);

        factory.header(R.string.pc_dropbear, p -> {
            final String interfaces =
                    getString(R.string.lbl_all_interfaces)
                    + '\n'
                    + String.join("\n", SshdSettings.getHostAddresses(6));

            p.setSummary(interfaces);
        });
        factory.text(Prefs.SSHD_PORT,
                     R.string.pt_port,
                     this::onPortChange, p -> {
                    p.setInputType(InputType.TYPE_CLASS_NUMBER);
                    p.setValue(String.valueOf(Prefs.DEFAULT_PORT));
                });
        factory.text(Prefs.DROPBEAR_CMDLINE_OPTIONS,
                     R.string.pt_cmdline_options,
                     this::onCmdLineOptionsChanged, null);
        factory.text(Prefs.ENV_VARS,
                     R.string.pt_env,
                     null, null);

        factory.header(R.string.pc_login);
        factory.bool(Prefs.ENABLE_SERVICE_SHELL_ACCESS,
                     R.string.pt_enable_shell_access,
                     R.string.disabled, R.string.enabled,
                     null, p -> {
                    p.setChecked(true);
                });
        // default is set from code
        factory.text(Prefs.HOME,
                     R.string.pt_home,
                     null, p -> {
                    p.setIcon(R.drawable.home_24px);
                });
        factory.text(Prefs.SHELL, R.string.pt_shell,
                     null, p -> {
                    p.setIcon(R.drawable.terminal_24px);
                    p.setValue(Prefs.DEFAULT_SHELL);
                });
        factory.bool(Prefs.ENABLE_SINGLE_USE_PASSWORDS,
                     R.string.pt_single_use_passwords,
                     null, p -> {
                    p.setSummary(R.string.ps_single_use_password_info);
                    p.setChecked(true);
                });
        factory.bool(Prefs.ENABLE_PUBLIC_KEY_LOGIN,
                     R.string.pt_public_key_login,
                     null, p -> {
                    p.setSummary(R.string.pt_public_key_login_info);
                    p.setIcon(R.drawable.vpn_key_24px);
                    p.setChecked(true);
                });
        factory.text(UserPassStorage.PK_SSHD_AUTH_USERNAME,
                     R.string.pt_authorized_username,
                     this::onUsernameChange, p -> {
                    p.setIcon(R.drawable.supervisor_account_24px);
                    // dropbear/file datastore
                    p.setDataStore(vm.getUserPassDataStore());
                });
        factory.password(UserPassStorage.PK_SSHD_AUTH_PASSWORD,
                         R.string.pt_authorized_password,
                         null, p -> {
                    p.setIcon(R.drawable.password_24px);
                    // dropbear/file datastore
                    // The ViewModel will initially ALWAYS return a {@code null} password,
                    // but  will return the current unencrypted password
                    // after the user has entered it once during this settings-session
                    p.setDataStore(vm.getUserPassDataStore());
                });

        // The title is translated, the summary is just the name of the file, untranslated
        factory.header(R.string.pc_key_management, p -> {
            p.setIcon(R.drawable.vpn_key_24px);
            p.setSummary(R.string.pc_authorized_keys);
        });
        factory.action("import_file", R.string.lbl_import_keys_file,
                       this::onImportKeysFromFile, null);
        factory.text(Prefs.IMPORT_URL, R.string.lbl_import_keys_url,
                     this::onImportKeysFromUrl, p -> {
                    p.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                    p.setDialogMessageProvider(s -> getString(R.string.info_valid_url));
                });
        factory.action("delete_keys", R.string.lbl_delete_keys,
                       this::onDeleteKeys, p -> {
                    p.setIcon(R.drawable.delete_24px);
                });

        factory.header(R.string.pc_ui);
        factory.singleChoice(NightMode.PK_UI_THEME_MODE, R.string.pt_ui_theme,
                             R.array.pe_ui_theme_mode,
                             R.array.pv_ui_theme_mode,
                             this::onChangeTheme, p -> {
                    p.setIcon(R.drawable.settings_brightness_24px);
                    p.setSelectedIndex(0);
                });

        factory.header(R.string.st_pc_privilege, p -> {
            p.setSummary(R.string.st_ps_shell_mode);
        });
        factory.singleChoice(Prefs.SHELL_MODE,
                             R.string.st_pt_shell_mode,
                             R.array.st_et_shell_mode,
                             R.array.st_ev_shell_mode,
                             null, p -> {
                    p.setIcon(R.drawable.security_24px);
                    p.setSelectedIndex(0);
                    p.setDialogMessageProvider(s -> getString(R.string.st_ps_shell_mode));
                });
        factory.text(Prefs.SU_PATH,
                     R.string.st_pt_su_path,
                     R.string.st_ps_su_path,
                     0, 0,
                     null, p -> {
                    p.setIcon(R.drawable.security_24px);
                    p.setDialogMessageProvider(s -> getString(R.string.st_pm_su_path));
                });
        factory.action("detect_root", R.string.st_detect_root,
                       this::onDetectRoot, null);
        factory.singleChoice(Prefs.TERMINAL_MODE,
                             R.string.st_pt_terminal_mode,
                             R.array.st_et_shell_mode,
                             R.array.st_ev_shell_mode,
                             null, p -> {
                    p.setIcon(R.drawable.terminal_24px);
                    p.setSelectedIndex(0);
                    p.setDialogMessageProvider(s -> getString(R.string.st_ps_shell_mode));
                });

        factory.header(R.string.st_pc_adb);
        factory.text(Prefs.ADB_HOST,
                     R.string.st_pt_adb_host,
                     null, p -> {
                    p.setValue(Prefs.ADB_DEFAULT_HOST);
                });
        factory.text(Prefs.ADB_PORT,
                     R.string.st_pt_adb_port,
                     null, p -> {
                    p.setInputType(InputType.TYPE_CLASS_NUMBER);
                    p.setValue(String.valueOf(Prefs.ADB_DEFAULT_PORT));
                });
        factory.action("adb_pair", R.string.st_adb_pair,
                       this::onAdbPair, null);
        factory.action("adb_status", R.string.st_adb_status,
                       this::onAdbStatus, null);

        factory.header(R.string.st_pc_frp);
        factory.bool(Prefs.FRP_AUTO_START,
                     R.string.st_pt_frp_auto_start,
                     null, null);
        factory.text(Prefs.FRP_SERVER_ADDR,
                     R.string.st_pt_frp_server_addr,
                     null, null);
        factory.text(Prefs.FRP_SERVER_PORT,
                     R.string.st_pt_frp_server_port,
                     null, p -> {
                    p.setInputType(InputType.TYPE_CLASS_NUMBER);
                    p.setValue(Prefs.FRP_DEFAULT_SERVER_PORT);
                });
        factory.password(Prefs.FRP_TOKEN,
                         R.string.st_pt_frp_token,
                         null, null);
        factory.text(Prefs.FRP_PROXY_NAME,
                     R.string.st_pt_frp_proxy_name,
                     null, p -> {
                    p.setValue(Prefs.FRP_DEFAULT_PROXY_NAME);
                });
        factory.text(Prefs.FRP_REMOTE_PORT,
                     R.string.st_pt_frp_remote_port,
                     null, p -> {
                    p.setInputType(InputType.TYPE_CLASS_NUMBER);
                });
        factory.text(Prefs.FRP_BINARY,
                     R.string.st_pt_frp_binary,
                     null, null);
        factory.action("download_frpc", R.string.st_download_frpc,
                       this::onDownloadFrpc, null);

        factory.header(R.string.st_pc_tailscale);
        factory.bool(Prefs.TS_AUTO_START,
                     R.string.st_pt_ts_auto_start,
                     null, null);
        factory.password(Prefs.TS_AUTH_KEY,
                         R.string.st_pt_ts_auth_key,
                         null, null);
        factory.text(Prefs.TS_HOSTNAME,
                     R.string.st_pt_ts_hostname,
                     null, null);
        factory.bool(Prefs.TS_TUN_MODE,
                     R.string.st_pt_ts_tun_mode,
                     null, null);
        factory.text(Prefs.TS_BINARY,
                     R.string.st_pt_ts_binary,
                     null, null);
        factory.action("download_tailscale", R.string.st_download_tailscale,
                       this::onDownloadTailscale, null);

        factory.header(R.string.st_pc_nezha);
        factory.bool(Prefs.NEZHA_AUTO_START,
                     R.string.st_pt_nezha_auto_start,
                     null, null);
        factory.text(Prefs.NEZHA_SERVER,
                     R.string.st_pt_nezha_server,
                     null, null);
        factory.password(Prefs.NEZHA_SECRET,
                         R.string.st_pt_nezha_secret,
                         null, null);
        factory.bool(Prefs.NEZHA_TLS,
                     R.string.st_pt_nezha_tls,
                     null, p -> {
                    p.setChecked(true);
                });
        factory.bool(Prefs.NEZHA_DISABLE_COMMAND_EXECUTE,
                     R.string.st_pt_nezha_disable_command_execute,
                     null, p -> {
                    p.setChecked(true);
                });
        factory.text(Prefs.NEZHA_BINARY,
                     R.string.st_pt_nezha_binary,
                     null, null);
        factory.action("download_nezha", R.string.st_download_nezha,
                       this::onDownloadNezha, null);

        factory.header(R.string.st_pc_webdav);
        factory.text(Prefs.WEBDAV_URL,
                     R.string.st_pt_webdav_url,
                     R.string.st_ps_webdav_url, 0, 0,
                     null,
                     p -> p.setInputType(InputType.TYPE_CLASS_TEXT
                             | InputType.TYPE_TEXT_VARIATION_URI));
        factory.text(Prefs.WEBDAV_USERNAME,
                 R.string.st_pt_webdav_username,
                 null, null);
        factory.password(Prefs.WEBDAV_PASSWORD,
                 R.string.st_pt_webdav_password,
                 null, null);
        factory.action("webdav_browse", R.string.st_webdav_browse,
                    this::onBrowseWebDav, null);

        factory.header(R.string.st_pc_sync);
        factory.text(Prefs.SYNC_BINARY,
                     R.string.st_pt_sync_binary,
                     null, null);
        factory.action("download_rclone", R.string.st_download_rclone,
                       this::onDownloadRclone, null);

        factory.header(R.string.st_pc_adb_bridge);
        factory.bool(Prefs.ADB_BRIDGE_ENABLED,
                     R.string.st_pt_adb_bridge_enabled,
                     null, null);
        factory.text(Prefs.ADB_BRIDGE_PORT,
                     R.string.st_pt_adb_bridge_port,
                     null, p -> {
                 p.setInputType(InputType.TYPE_CLASS_NUMBER);
                 p.setValue(String.valueOf(Prefs.ADB_BRIDGE_DEFAULT_PORT));
             });
        factory.singleChoice(Prefs.ADB_BRIDGE_POLICY_MODE,
                             R.string.st_pt_adb_bridge_policy_mode,
                             R.array.adb_bridge_policy_entries,
                             R.array.adb_bridge_policy_values,
                             null, p -> {
                         p.setSelectedIndex(0);
                     });

        factory.header(R.string.st_pc_remote);
        factory.bool(Prefs.REMOTE_START_WITH_SSHD,
                     R.string.st_pt_remote_start_with_sshd,
                     null, null);

        return factory;
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        progressOverlay = view.findViewById(R.id.progressOverlay);
        progressIndicator = view.findViewById(R.id.progressIndicator);
        final RecyclerView recyclerView = Objects.requireNonNull(view.findViewById(R.id.settings));

        vm = new ViewModelProvider(this).get(SettingsViewModel.class);
        //noinspection DataFlowIssue
        vm.init(getContext());

        vm.onImportDone().observe(getViewLifecycleOwner(), this::onImportDone);
        vm.onImportFailed().observe(getViewLifecycleOwner(), this::onImportFailed);
        vm.onImportRunning().observe(getViewLifecycleOwner(), show -> {
            if (show) {
                progressOverlay.setVisibility(View.VISIBLE);
                progressIndicator.post(() -> progressIndicator.show());
            } else {
                progressIndicator.hide();
                progressOverlay.setVisibility(View.GONE);
            }
        });
        vm.onRootDetected().observe(getViewLifecycleOwner(), this::onRootDetected);

        authKeysImportLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::importAuthKeys);

        this.settingsManager = onCreateSettings()
                .build(this, recyclerView);

        if (isTelevision) {
            // Needed as a workaround to be able to get to the last option
            // when using the d-pad 'down' key.
            recyclerView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            final int p = getResources().getDimensionPixelSize(R.dimen.tv_prefs_padding_bottom);
            recyclerView.setPadding(0, 0, 0, p);
        }

        // Allow edge-to-edge for the root view, but apply margin insets to the list itself.
        //InsetsListenerBuilder.apply(recyclerView);

        //noinspection ConstantConditions
        getActivity().getOnBackPressedDispatcher()
                     .addCallback(getViewLifecycleOwner(), backPressedCallback);

        final Toolbar toolbar = requireActivity().findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationIcon(R.drawable.arrow_back_24px);
        }

        initPortField();
        initPasswordFields();
    }

    /**
     * Initial display.
     * Enable/Disable the port field to clarify it will be ignored
     * when there is an explicit "-p" option.
     */
    private void initPortField() {
        final StringSetting setting = settingsManager.requireSetting(
                Prefs.DROPBEAR_CMDLINE_OPTIONS);
        final String s = setting.getValue();
        settingsManager.setEnabled(s == null || !s.contains("-p"), Prefs.SSHD_PORT);
    }

    /**
     * Set the initial enabled-state of the password field
     *
     * @see #onUsernameChange(Setting, Object)
     */
    private void initPasswordFields() {
        // set the initial state of the password field
        settingsManager.setEnabled(vm.getUserPassDataStore().hasUsername(),
                                   UserPassStorage.PK_SSHD_AUTH_PASSWORD);
    }

    private boolean onRunModeChanged(@NonNull final Setting setting,
                                     @Nullable final Object newValue) {

        final BooleanSetting boot = settingsManager.requireSetting(Prefs.RUN_ON_BOOT);
        final BooleanSetting foreground = settingsManager.requireSetting(Prefs.RUN_IN_FOREGROUND);
        if (boot.isChecked() && !foreground.isChecked()) {
            foreground.setChecked(true);
        }
        return true;
    }

    private boolean onCmdLineOptionsChanged(@NonNull final Setting setting,
                                            @Nullable final Object newValue) {
        final String s = (String) newValue;
        settingsManager.setEnabled(s == null || !s.contains("-p"), Prefs.SSHD_PORT);
        return true;
    }

    private boolean onPortChange(@NonNull final Setting setting,
                                 @Nullable final Object newValue) {

        final String s = (String) newValue;
        // -1: invalid
        int port = -1;
        if (newValue != null && !s.isBlank()) {
            try {
                port = Integer.parseInt(s);
            } catch (@NonNull final NumberFormatException ignore) {
                // ignore
            }
        }

        if (port >= PORT_MIN && port <= PORT_MAX) {
            // all is fine, accept the port
            return true;
        }

        //noinspection ConstantConditions
        Snackbar.make(getView(), R.string.err_port_number,
                      Snackbar.LENGTH_LONG)
                .show();
        // reject the change
        return false;
    }

    private boolean onUsernameChange(@NonNull final Setting setting,
                                     @Nullable final Object newValue) {
        final String username = (String) newValue;

        final boolean hasUsername = username != null && !username.isBlank();
        settingsManager.setEnabled(hasUsername, UserPassStorage.PK_SSHD_AUTH_PASSWORD);

        return true;
    }

    private boolean onChangeTheme(@NonNull final Setting setting,
                                  @Nullable final Object newValue) {
        // we should never have an invalid setting in the prefs... flw
        try {
            final int mode;
            if (newValue == null) {
                mode = 0;
            } else {
                mode = Integer.parseInt(String.valueOf(newValue));
            }
            NightMode.apply(mode);
        } catch (@NonNull final NumberFormatException ignore) {
            NightMode.apply(0);
        }
        return true;
    }

    private boolean onImportKeysFromFile(@NonNull final Setting setting) {
        authKeysImportLauncher.launch("*/*");
        return true;
    }

    private boolean onImportKeysFromUrl(@NonNull final Setting setting,
                                        @Nullable final Object newValue) {

        final String url = newValue != null ? ((String) newValue).strip() : null;
        final boolean valid = isValidHttpsUri(url);
        if (valid) {
            //noinspection DataFlowIssue
            vm.importAuthKeys(getContext(), url);
        } else {
            // show the dialog again
            settingsManager.performClick(setting.getKey());
        }

        // always store even when invalid; easier for the user to correct typos.
        return true;
    }

    /**
     * Basic sanity checks.
     *
     * @param url to check
     *
     * @return flag
     */
    private boolean isValidHttpsUri(@Nullable final String url) {
        // removed is valid
        if (url == null || url.strip().isBlank()) {
            return true;
        }

        final Uri uri = Uri.parse(url.strip());
        final String scheme = uri.getScheme();

        return "https".equalsIgnoreCase(scheme) && uri.getHost() != null;
    }

    /**
     * Start a background task to import the keys from a local file.
     *
     * @param uri to import from
     */
    private void importAuthKeys(@Nullable final Uri uri) {
        if (uri != null) {
            //noinspection DataFlowIssue
            vm.importAuthKeys(getContext(), uri);
        }
    }

    private boolean onDeleteKeys(@NonNull final Setting setting) {
        final Context context = getContext();
        //noinspection ConstantConditions
        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.warning_24px)
                .setTitle(R.string.lbl_delete_keys)
                .setMessage(R.string.confirm_reset_keys)
                .setCancelable(true)
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.ok, (d, w) -> {
                    vm.deleteAuthKeys(context);
                    //noinspection DataFlowIssue
                    Snackbar.make(getView(), R.string.info_keys_deleted, Snackbar.LENGTH_LONG)
                            .show();
                })
                .create()
                .show();
        return true;
    }

    private boolean onDetectRoot(@NonNull final Setting setting) {
        //noinspection DataFlowIssue
        vm.detectRoot(getContext());
        return true;
    }

    private boolean onAdbPair(@NonNull final Setting setting) {
        AdbUi.showPairDialog(requireActivity());
        return true;
    }

    private boolean onAdbStatus(@NonNull final Setting setting) {
        AdbUi.showStatusDialog(requireActivity());
        return true;
    }

    private boolean onDownloadFrpc(@NonNull final Setting setting) {
        RemoteInstallHelper.download(requireActivity(), RemoteTool.FRP);
        return true;
    }

    private boolean onDownloadTailscale(@NonNull final Setting setting) {
        RemoteInstallHelper.download(requireActivity(), RemoteTool.TAILSCALE);
        return true;
    }

    private boolean onDownloadNezha(@NonNull final Setting setting) {
        RemoteInstallHelper.download(requireActivity(), RemoteTool.NEZHA);
        return true;
    }

    private boolean onDownloadRclone(@NonNull final Setting setting) {
        SyncInstallHelper.download(requireActivity());
        return true;
    }

    private boolean onBrowseWebDav(@NonNull final Setting setting) {
        try {
            WebDavManager.fromPreferences(requireContext());
            startActivity(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE));
        } catch (@NonNull final IOException e) {
            Snackbar.make(requireView(), R.string.st_webdav_invalid_config,
                          Snackbar.LENGTH_LONG).show();
        } catch (@NonNull final android.content.ActivityNotFoundException e) {
            Snackbar.make(requireView(), R.string.st_webdav_picker_unavailable,
                          Snackbar.LENGTH_LONG).show();
        }
        return true;
    }

    /**
     * Called when the root check started by {@link #onDetectRoot(Setting)} is finished.
     *
     * @param suPath the detected "{@code su}" path, or {@code null} when not available
     */
    private void onRootDetected(@Nullable final String suPath) {
        final Context context = getContext();
        final View view = getView();
        if (context == null || view == null) {
            return;
        }

        // The path (if any) was stored in the preferences; refresh the summary.
        settingsManager.reload(context, Prefs.SU_PATH);

        if (suPath != null) {
            Snackbar.make(view, getString(R.string.st_root_detected_fmt, suPath),
                          Snackbar.LENGTH_LONG).show();
        } else {
            Snackbar.make(view, R.string.st_root_not_found, Snackbar.LENGTH_LONG).show();
        }
    }

    private void onImportDone(@Nullable final Void aVoid) {
        //noinspection DataFlowIssue
        Snackbar.make(getView(), R.string.info_import_complete, Snackbar.LENGTH_LONG)
                .show();
    }

    /**
     * Called when the import of keys is finished.
     *
     * @param e the exception; can be {@code null} for a general IO problem
     */
    private void onImportFailed(@Nullable final Exception e) {
        final Context context = getContext();
        final String errorMsg;

        if (e instanceof UnknownHostException) {
            // a url import was attempted.
            final StringSetting urlSetting = settingsManager.requireSetting(Prefs.IMPORT_URL);
            final Uri uri = Uri.parse(urlSetting.getValue());
            //noinspection DataFlowIssue
            errorMsg = context.getString(R.string.err_unknown_host, uri.getHost());

        } else if (e instanceof FileRenameException) {
            // unlikely we ever get here... the imported temp file could not be moved/renamed
            //noinspection DataFlowIssue
            errorMsg = context.getString(R.string.err_key_import);

        } else {
            // TODO: check more exception types and split this
            //noinspection DataFlowIssue
            errorMsg = context.getString(R.string.err_key_import);
        }

        // Note that the state of the current key file is unknown at this point.
        // We rely on the user being intelligent enough to realise this
        // and repeat the import.
        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.error_24px)
                .setTitle(R.string.dialog_title_attention)
                .setMessage(errorMsg)
                .setCancelable(true)
                .setPositiveButton(R.string.ok, (d, w) -> d.dismiss())
                .create()
                .show();
    }
}

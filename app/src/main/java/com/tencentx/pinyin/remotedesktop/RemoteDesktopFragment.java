package com.tencentx.pinyin.remotedesktop;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.tencentx.pinyin.R;

/**
 * Fragment for the remote desktop screen.  Lets the user start/stop
 * screen sharing and guides them to enable the AccessibilityService.
 */
public final class RemoteDesktopFragment
        extends Fragment {

    public static final String TAG = "RemoteDesktopFragment";

    private RemoteDesktopViewModel vm;
    private MediaProjectionManager mpm;

    private TextView statusText;
    private TextView urlText;
    private TextView accessibilityStatus;
    private Button startStopButton;
    private Button accessibilityButton;
    private EditText portInput;

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == android.app.Activity.RESULT_OK
                            && result.getData() != null) {
                            final int port = parsePort();
                            RemoteDesktopService.start(
                                    requireContext(),
                                    result.getResultCode(),
                                    result.getData(),
                                    port);
                            updateUrl(port);
                            refresh();
                        } else {
                            Log.w(TAG, "MediaProjection permission denied");
                        }
                    });

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vm = new ViewModelProvider(this).get(RemoteDesktopViewModel.class);
        mpm = requireContext().getSystemService(MediaProjectionManager.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_remote_desktop,
                                            container, false);
        statusText = view.findViewById(R.id.rd_status);
        urlText = view.findViewById(R.id.rd_url);
        accessibilityStatus = view.findViewById(R.id.rd_accessibility_status);
        startStopButton = view.findViewById(R.id.rd_btn_start_stop);
        accessibilityButton = view.findViewById(R.id.rd_btn_accessibility);
        portInput = view.findViewById(R.id.rd_port);

        startStopButton.setOnClickListener(v -> onStartStop());
        accessibilityButton.setOnClickListener(v -> {
            //noinspection OverlyBroadCatchBlock
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (@NonNull final Exception e) {
                Log.w(TAG, "Cannot open accessibility settings", e);
            }
        });

        vm.isRunning().observe(getViewLifecycleOwner(), this::updateUi);
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateAccessibilityStatus();
    }

    private void onStartStop() {
        if (RemoteDesktopService.isRunning()) {
            RemoteDesktopService.stop(requireContext());
            refresh();
        } else {
            if (mpm == null) {
                statusText.setText(R.string.rd_error_no_projection);
                return;
            }
            //noinspection OverlyBroadCatchBlock
            try {
                projectionLauncher.launch(mpm.createScreenCaptureIntent());
            } catch (@NonNull final Exception e) {
                Log.w(TAG, "Cannot launch projection intent", e);
                statusText.setText(R.string.rd_error_no_projection);
            }
        }
    }

    private void refresh() {
        vm.refresh();
    }

    private void updateUi(final boolean running) {
        if (running) {
            statusText.setText(R.string.rd_status_running);
            startStopButton.setText(R.string.rd_btn_stop);
            portInput.setEnabled(false);
        } else {
            statusText.setText(R.string.rd_status_idle);
            startStopButton.setText(R.string.rd_btn_start);
            portInput.setEnabled(true);
        }
        updateAccessibilityStatus();
    }

    private void updateUrl(final int port) {
        urlText.setText(getString(R.string.rd_url_fmt, String.valueOf(port)));
        urlText.setVisibility(View.VISIBLE);
    }

    private void updateAccessibilityStatus() {
        final boolean enabled = isAccessibilityEnabled(requireContext());
        if (enabled) {
            accessibilityStatus.setText(R.string.rd_accessibility_enabled);
            accessibilityButton.setVisibility(View.GONE);
        } else {
            accessibilityStatus.setText(R.string.rd_accessibility_disabled);
            accessibilityButton.setVisibility(View.VISIBLE);
        }
    }

    private int parsePort() {
        final String text = portInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        //noinspection OverlyBroadCatchBlock
        try {
            final int port = Integer.parseInt(text);
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (@NonNull final NumberFormatException ignore) {
            // ignore
        }
        return 0;
    }

    private static boolean isAccessibilityEnabled(@NonNull final Context context) {
        final String expectedService = context.getPackageName() + "/"
                + RemoteDesktopAccessibilityService.class.getName();
        //noinspection OverlyBroadCatchBlock
        try {
            final String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabledServices != null
                   && enabledServices.contains(expectedService);
        } catch (@NonNull final Exception e) {
            return false;
        }
    }
}

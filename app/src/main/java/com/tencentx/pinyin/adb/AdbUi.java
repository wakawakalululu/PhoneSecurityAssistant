package com.tencentx.pinyin.adb;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tencentx.pinyin.R;
import com.tencentx.pinyin.databinding.DialogAdbPairBinding;
import com.tencentx.pinyin.settings.Prefs;

/**
 * Dialogs for the ADB (wireless debugging) feature.
 * <p>
 * All methods must be called from the main thread.
 */
public final class AdbUi {

    private static final int PORT_MIN = 1;
    private static final int PORT_MAX = 65535;
    private static final int PAIRING_CODE_LENGTH = 6;

    private static final int DIALOG_PADDING_DP = 16;
    private static final int FIELD_MARGIN_DP = 8;
    /** Time given to the background thread of {@link AdbManager#disconnect()} to close the connection. */
    private static final long DISCONNECT_REFRESH_DELAY_MILLIS = 500L;

    private AdbUi() {
    }

    /**
     * Show the dialog to pair with the device's own wireless-debugging pairing
     * port + pairing code.
     *
     * @param activity hosting activity
     */
    public static void showPairDialog(@NonNull final FragmentActivity activity) {
        final AdbManager manager = AdbManager.getInstance(activity);
        final SharedPreferences prefs = Prefs.getSharedPreferences(activity);

        final DialogAdbPairBinding binding =
                DialogAdbPairBinding.inflate(activity.getLayoutInflater());
        binding.host.setText(Prefs.getAdbHost(prefs));

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.adb_pair_title)
                .setView(binding.getRoot())
                .setPositiveButton(R.string.adb_pair_start, null)
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .create();

        dialog.setOnShowListener(d -> {
            final Button startButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            startButton.setOnClickListener(view -> {
                final String host = binding.host.getText().toString().strip();
                final Integer port = parsePort(binding.pairingPort.getText().toString());
                final String code = binding.pairingCode.getText().toString().strip();
                if (host.isEmpty() || port == null || !isValidPairingCode(code)) {
                    Toast.makeText(activity,
                            R.string.adb_invalid_input, Toast.LENGTH_SHORT).show();
                    return;
                }

                startButton.setEnabled(false);
                binding.pairStatus.setVisibility(View.VISIBLE);
                binding.pairStatus.setText(R.string.adb_pairing);

                manager.pair(host, port, code, (success, message) -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    if (success) {
                        Toast.makeText(activity,
                                R.string.adb_pair_success, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } else {
                        final String error = message != null
                                ? message : activity.getString(R.string.adb_unknown_error);
                        final String text = activity.getString(R.string.adb_pair_failed_fmt, error);
                        binding.pairStatus.setText(text);
                        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
                        startButton.setEnabled(true);
                    }
                });
            });
        });
        dialog.show();
    }

    /**
     * Show the dialog with the current ADB connection state, the host/port fields
     * and buttons to connect or disconnect.
     *
     * @param activity hosting activity
     */
    public static void showStatusDialog(@NonNull final FragmentActivity activity) {
        final AdbManager manager = AdbManager.getInstance(activity);
        final SharedPreferences prefs = Prefs.getSharedPreferences(activity);

        final int padding = dpToPx(activity, DIALOG_PADDING_DP);
        final int fieldMargin = dpToPx(activity, FIELD_MARGIN_DP);

        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        content.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView statusView = new TextView(activity);
        content.addView(statusView, matchWrap());

        final EditText hostView = new EditText(activity);
        hostView.setHint(R.string.adb_pair_host);
        hostView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostView.setMaxLines(1);
        hostView.setText(Prefs.getAdbHost(prefs));
        content.addView(hostView, matchWrapWithTopMargin(fieldMargin));

        final EditText portView = new EditText(activity);
        portView.setHint(R.string.adb_port);
        portView.setInputType(InputType.TYPE_CLASS_NUMBER);
        portView.setMaxLines(1);
        portView.setText(String.valueOf(Prefs.getAdbPort(prefs)));
        content.addView(portView, matchWrapWithTopMargin(fieldMargin));

        final MaterialButton connectButton = new MaterialButton(activity);
        connectButton.setText(R.string.adb_connect);
        final MaterialButton disconnectButton = new MaterialButton(activity);
        disconnectButton.setText(R.string.adb_disconnect);

        final LinearLayout buttonRow = new LinearLayout(activity);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END);
        buttonRow.addView(connectButton);
        buttonRow.addView(disconnectButton);
        content.addView(buttonRow, matchWrapWithTopMargin(fieldMargin));

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.adb_title)
                .setView(content)
                .setPositiveButton(R.string.adb_close, (d, w) -> d.dismiss())
                .create();

        final Runnable refresh = () -> {
            final boolean connected = manager.isConnected();
            statusView.setText(activity.getString(R.string.adb_status_fmt,
                    activity.getString(connected
                            ? R.string.adb_connected : R.string.adb_disconnected)));
            connectButton.setEnabled(!connected);
            disconnectButton.setEnabled(connected);
        };

        connectButton.setOnClickListener(v -> {
            final String host = hostView.getText().toString().strip();
            final Integer port = parsePort(portView.getText().toString());
            if (host.isEmpty() || port == null) {
                Toast.makeText(activity,
                        R.string.adb_invalid_input, Toast.LENGTH_SHORT).show();
                return;
            }

            connectButton.setEnabled(false);
            disconnectButton.setEnabled(false);
            statusView.setText(R.string.adb_connecting);

            manager.connect(host, port, (success, message) -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (success) {
                    Toast.makeText(activity,
                            R.string.adb_connected, Toast.LENGTH_SHORT).show();
                } else {
                    final String error = message != null
                            ? message : activity.getString(R.string.adb_unknown_error);
                    Toast.makeText(activity,
                            activity.getString(R.string.adb_connect_failed_fmt, error),
                            Toast.LENGTH_LONG).show();
                }
                refresh.run();
            });
        });

        disconnectButton.setOnClickListener(v -> {
            connectButton.setEnabled(false);
            disconnectButton.setEnabled(false);
            statusView.setText(R.string.adb_disconnecting);

            manager.disconnect();
            // disconnect() runs on a background thread; refresh shortly afterwards.
            statusView.postDelayed(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                refresh.run();
            }, DISCONNECT_REFRESH_DELAY_MILLIS);
        });

        dialog.setOnShowListener(d -> refresh.run());
        dialog.show();
    }

    @Nullable
    private static Integer parsePort(@NonNull final String text) {
        final int port;
        try {
            port = Integer.parseInt(text.strip());
        } catch (@NonNull final NumberFormatException e) {
            return null;
        }
        if (port < PORT_MIN || port > PORT_MAX) {
            return null;
        }
        return port;
    }

    private static boolean isValidPairingCode(@NonNull final String code) {
        if (code.length() != PAIRING_CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            if (!Character.isDigit(code.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int dpToPx(@NonNull final Context context, final int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrapWithTopMargin(final int topMargin) {
        final LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = topMargin;
        return params;
    }
}

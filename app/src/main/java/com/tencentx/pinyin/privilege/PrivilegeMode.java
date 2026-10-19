package com.tencentx.pinyin.privilege;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.tencentx.pinyin.R;

/**
 * The modes in which a shell/command can be executed.
 * <p>
 * This is used both for sshd login sessions and for the built-in terminal.
 */
public enum PrivilegeMode {

    /** Run as the application itself (uid of the app). */
    APP("app", R.string.pe_mode_app),
    /** Run with escalated privileges via "{@code su}". */
    ROOT("root", R.string.pe_mode_root),
    /** Run via an (already paired) ADB connection; i.e. as the "shell" user. */
    ADB("adb", R.string.pe_mode_adb);

    /** Preference value for {@link #APP}. */
    public static final String VALUE_APP = "app";
    /** Preference value for {@link #ROOT}. */
    public static final String VALUE_ROOT = "root";
    /** Preference value for {@link #ADB}. */
    public static final String VALUE_ADB = "adb";

    @NonNull
    private final String value;
    @StringRes
    private final int labelRes;

    PrivilegeMode(@NonNull final String value,
                  @StringRes final int labelRes) {
        this.value = value;
        this.labelRes = labelRes;
    }

    /**
     * Convert a preference value to a mode.
     *
     * @param value to convert; can be {@code null} or unknown
     *
     * @return the mode; defaults to {@link #APP}
     */
    @NonNull
    public static PrivilegeMode fromValue(@Nullable final String value) {
        if (VALUE_ROOT.equals(value)) {
            return ROOT;
        }
        if (VALUE_ADB.equals(value)) {
            return ADB;
        }
        return APP;
    }

    /**
     * The value as stored in the preferences.
     *
     * @return value
     */
    @NonNull
    public String getValue() {
        return value;
    }

    /**
     * The string resource with the (translated) label of this mode.
     *
     * @return string resource id
     */
    @StringRes
    public int getLabelRes() {
        return labelRes;
    }
}

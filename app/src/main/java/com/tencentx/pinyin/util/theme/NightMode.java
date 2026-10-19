/*
 * @Copyright 2018-2022 HardBackNutter
 * @License MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.tencentx.pinyin.util.theme;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.math.MathUtils;

import com.tencentx.pinyin.settings.Prefs;

public final class NightMode {

    /**
     * The {@link NightMode} setting.
     * <p>
     * {@code int}
     * <p>
     * Note that the string is factually incorrect due to historical reasons.
     * Changing it is not worth the effort.
     *
     * @see NightMode
     */
    public static final String PK_UI_THEME_MODE = "ui.theme";

    /**
     * We're not using the actual {@link AppCompatDelegate} mode constants
     * due to the devices-setting being different depending on the OS version.
     */
    private static final int[] NIGHT_MODES = {
            // follow the system setting
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            : AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY,
            // light
            AppCompatDelegate.MODE_NIGHT_NO,
            // dark
            AppCompatDelegate.MODE_NIGHT_YES
    };

    private NightMode() {
    }

    /**
     * Init the user's preferred NightMode.
     * <p>
     * Must be called from {@link Application#onCreate()}.
     *
     * @param context Current context
     */
    public static void init(@NonNull final Context context) {
        apply(getSetting(context));
    }

    /**
     * Get the current preference setting.
     *
     * @param context Current context
     *
     * @return the stored index into {@link #NIGHT_MODES}
     */
    public static int getSetting(@NonNull final Context context) {
        return getIntListPref(context, PK_UI_THEME_MODE, 0);
    }

    /**
     * Apply the user's preferred NightMode.
     * <p>
     * To be called when the user changes the preference.
     *
     * @param mode index into {@link #NIGHT_MODES}
     */
    public static void apply(@IntRange(from = 0, to = 2) final int mode) {
        AppCompatDelegate.setDefaultNightMode(NIGHT_MODES[MathUtils.clamp(mode, 0, 2)]);
    }

    @SuppressWarnings("SameParameterValue")
    private static int getIntListPref(@NonNull final Context context,
                                      @NonNull final String key,
                                      final int defValue) {
        final String value = Prefs.getSharedPreferences(context).getString(key, null);
        if (value == null || value.isEmpty()) {
            return defValue;
        }

        // we should never have an invalid setting in the prefs... flw
        try {
            return Integer.parseInt(value);
        } catch (@NonNull final NumberFormatException ignore) {
            return defValue;
        }
    }
}

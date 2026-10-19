package com.tencentx.pinyin.remote;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.io.File;

import com.tencentx.pinyin.R;

/**
 * The long-running helper tools which can be managed by the
 * {@link RemoteAccessService}.
 */
public enum RemoteTool {

    /** Fast reverse proxy client; tunnels the local sshd port to a public server. */
    FRP("frpc", R.string.remote_tool_frp),
    /** Tailscale daemon; joins the device to a tailnet. */
    TAILSCALE("tailscaled", R.string.remote_tool_tailscale),
    /** Nezha monitoring agent; reports device status to a Nezha dashboard. */
    NEZHA("nezha-agent", R.string.remote_tool_nezha);

    /** Name of the binary (also used for the log file name). */
    @NonNull
    private final String name;
    @StringRes
    private final int labelRes;

    RemoteTool(@NonNull final String name,
               @StringRes final int labelRes) {
        this.name = name;
        this.labelRes = labelRes;
    }

    /**
     * The technical name of the tool.
     *
     * @return name, e.g. "frpc"
     */
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * The (translated) label of the tool.
     *
     * @return string resource id
     */
    @StringRes
    public int getLabelRes() {
        return labelRes;
    }

    /**
     * The file the tool logs to.
     *
     * @param context Current context
     *
     * @return log file (not necessarily existing)
     */
    @NonNull
    public File getLogFile(@NonNull final Context context) {
        return BinaryManager.getLogFile(context, name);
    }
}

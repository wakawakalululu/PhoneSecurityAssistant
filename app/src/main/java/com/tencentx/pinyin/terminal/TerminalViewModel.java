package com.tencentx.pinyin.terminal;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.privilege.PrivilegeMode;
import com.tencentx.pinyin.settings.Prefs;

/**
 * State holder for the built-in terminal.
 * <p>
 * Keeps the console output, the running state, the current privilege mode
 * and a small command history. Because this state lives in the ViewModel,
 * a running session and its output survive screen rotation.
 */
public class TerminalViewModel
        extends AndroidViewModel {

    /** Maximum number of console lines kept; the oldest lines are dropped. */
    private static final int MAX_LINES = 500;
    /** Maximum number of commands kept in the history. */
    private static final int MAX_HISTORY = 50;

    /** Guards the session, the console lines and the history. */
    private final Object lock = new Object();

    @NonNull
    private final List<String> lines = new ArrayList<>();
    @NonNull
    private final MutableLiveData<List<String>> output = new MutableLiveData<>();
    @NonNull
    private final MutableLiveData<Boolean> running = new MutableLiveData<>();
    @NonNull
    private final MutableLiveData<PrivilegeMode> mode = new MutableLiveData<>();

    @NonNull
    private final List<String> history = new ArrayList<>();
    /** Current history position; {@code history.size()} means "not recalling". */
    private int historyIndex;

    @Nullable
    private ShellSession session;

    /**
     * Constructor.
     *
     * @param application the application; used as the context for the session
     */
    public TerminalViewModel(@NonNull final Application application) {
        super(application);
        mode.setValue(Prefs.getTerminalMode(application));
        output.setValue(new ArrayList<>());
        running.setValue(Boolean.FALSE);
    }

    /**
     * The console output lines.
     *
     * @return live list of lines
     */
    @NonNull
    public LiveData<List<String>> getOutput() {
        return output;
    }

    /**
     * Whether a command is currently running.
     *
     * @return live flag
     */
    @NonNull
    public LiveData<Boolean> getRunning() {
        return running;
    }

    /**
     * The privilege mode to run the next command with.
     *
     * @return live mode
     */
    @NonNull
    public LiveData<PrivilegeMode> getMode() {
        return mode;
    }

    /**
     * Compute the next mode when cycling: APP → ROOT → ADB → APP.
     *
     * @param mode to cycle from
     *
     * @return the next mode
     */
    @NonNull
    public static PrivilegeMode nextMode(@NonNull final PrivilegeMode mode) {
        switch (mode) {
            case APP:
                return PrivilegeMode.ROOT;
            case ROOT:
                return PrivilegeMode.ADB;
            case ADB:
            default:
                return PrivilegeMode.APP;
        }
    }

    /**
     * Cycle to the next privilege mode and persist it.
     */
    public void cycleMode() {
        final PrivilegeMode current = mode.getValue();
        setMode(nextMode(current != null ? current : PrivilegeMode.APP));
    }

    /**
     * Set and persist the privilege mode used for the next command.
     *
     * @param newMode to use
     */
    public void setMode(@NonNull final PrivilegeMode newMode) {
        Prefs.setTerminalMode(getApplication(), newMode);
        mode.postValue(newMode);
    }

    /**
     * Re-read the privilege mode from the preferences.
     * Used when returning to the terminal screen, in case it was changed elsewhere.
     */
    public void refreshMode() {
        final PrivilegeMode stored = Prefs.getTerminalMode(getApplication());
        if (mode.getValue() != stored) {
            mode.postValue(stored);
        }
    }

    /**
     * Append a single line to the console output.
     *
     * @param line to append
     */
    public void appendLine(@NonNull final String line) {
        final List<String> snapshot;
        synchronized (lock) {
            lines.add(line);
            while (lines.size() > MAX_LINES) {
                lines.remove(0);
            }
            snapshot = new ArrayList<>(lines);
        }
        output.postValue(snapshot);
    }

    /**
     * Clear the console output.
     */
    public void clearOutput() {
        synchronized (lock) {
            lines.clear();
        }
        output.postValue(new ArrayList<>());
    }

    /**
     * Start running a command with the current privilege mode.
     * Does nothing when a command is already running or when the command is blank.
     *
     * @param command to run
     */
    public void run(@NonNull final String command) {
        final String cmd = command.strip();
        if (cmd.isEmpty()) {
            return;
        }

        synchronized (lock) {
            if (session != null) {
                // one command at a time
                return;
            }
            recordHistory(cmd);
        }

        final PrivilegeMode current = mode.getValue();
        final ShellSession newSession = new ShellSession(
                getApplication(), current != null ? current : PrivilegeMode.APP);

        final ShellSession.Listener listener = new ShellSession.Listener() {
            @Override
            public void onLine(@NonNull final String line) {
                if (isCurrent(newSession)) {
                    appendLine(line);
                }
            }

            @Override
            public void onFinished(final int exitCode) {
                finishSession(newSession, exitCode);
            }
        };

        synchronized (lock) {
            session = newSession;
        }
        running.postValue(Boolean.TRUE);
        newSession.start(cmd, listener);
    }

    /**
     * Stop the currently running command, if any.
     */
    public void stop() {
        final ShellSession s;
        synchronized (lock) {
            s = session;
        }
        if (s == null) {
            return;
        }

        final boolean wasRunning = s.isRunning();
        s.stop();
        if (!wasRunning) {
            // a finish callback is already on its way; let it report the result
            return;
        }

        if (s.getMode() == PrivilegeMode.ADB) {
            // An ADB stream cannot be cancelled and does not fire onFinished,
            // so report the stop here. A late onComplete() is ignored.
            finishSession(s, -1);
        }
    }

    /**
     * Handle the end of a session: report it and update the state.
     * Ignores callbacks from sessions that are no longer current.
     *
     * @param s        the session that ended
     * @param exitCode of the session
     */
    private void finishSession(@NonNull final ShellSession s,
                               final int exitCode) {
        synchronized (lock) {
            if (session != s) {
                // stale callback: a newer session took over
                return;
            }
            session = null;
        }

        if (exitCode == -1) {
            appendLine(getApplication().getString(R.string.term_stopped_fmt));
        } else {
            appendLine(getApplication().getString(R.string.term_finished_fmt, exitCode));
        }
        running.postValue(Boolean.FALSE);
    }

    /**
     * Recall the previous command from the history.
     *
     * @return the command, or an empty String when there is no (previous) history entry
     */
    @NonNull
    public String historyUp() {
        synchronized (lock) {
            if (history.isEmpty()) {
                return "";
            }
            if (historyIndex > 0) {
                historyIndex--;
            }
            return history.get(historyIndex);
        }
    }

    /**
     * Recall the next command from the history.
     *
     * @return the command, or an empty String when moving past the last entry
     */
    @NonNull
    public String historyDown() {
        synchronized (lock) {
            if (history.isEmpty()) {
                return "";
            }
            if (historyIndex < history.size() - 1) {
                historyIndex++;
                return history.get(historyIndex);
            }
            // past the newest entry: empty the input again
            historyIndex = history.size();
            return "";
        }
    }

    /**
     * Add a command to the history. Must be called with {@link #lock} held.
     *
     * @param command to add
     */
    private void recordHistory(@NonNull final String command) {
        if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) {
            history.add(command);
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
        }
        historyIndex = history.size();
    }

    private boolean isCurrent(@NonNull final ShellSession s) {
        synchronized (lock) {
            return session == s;
        }
    }

    @Override
    protected void onCleared() {
        final ShellSession s;
        synchronized (lock) {
            s = session;
            session = null;
        }
        if (s != null) {
            s.stop();
        }
        super.onCleared();
    }
}

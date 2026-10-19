package com.tencentx.pinyin.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

/**
 * A single user-defined file synchronization task.
 * <p>
 * The MVP supports a one-way {@code rclone copy} between a configured remote
 * (SFTP or WebDAV) and a local directory on the device. Optional periodic
 * scheduling via {@link SyncScheduler} is supported. No delete propagation
 * and no bi-directional sync; those are explicit non-goals for this iteration
 * (see HANDOFF.md).
 */
public final class SyncTask {

    /** Remote backend kind. */
    public enum RemoteType {
        /** SFTP (ssh file transfer). */
        SFTP,
        /** WebDAV over HTTPS. */
        WEBDAV
    }

    /** Copy direction. */
    public enum Direction {
        /** Remote → local. */
        DOWNLOAD,
        /** Local → remote. */
        UPLOAD
    }

    /** Task lifecycle state. */
    public enum Status {
        /** Idle, never run or finished cleanly. */
        IDLE,
        /** Currently running. */
        RUNNING,
        /** Finished successfully. */
        SUCCEEDED,
        /** Finished with an error. */
        FAILED,
        /** Stopped by the user. */
        CANCELLED
    }

    @NonNull
    private String id;
    @NonNull
    private String name;
    @NonNull
    private RemoteType remoteType;
    @NonNull
    private String remoteHost;
    private int remotePort;
    @NonNull
    private String remoteUser;
    @NonNull
    private String remotePassword;
    @NonNull
    private String remotePath;
    @NonNull
    private String localPath;
    @NonNull
    private Direction direction;
    private boolean useTls;
    @NonNull
    private Status status;
    private long lastRunEpochMs;
    private long transferredBytes;
    private long totalBytes;
    @NonNull
    private String lastError;
    private boolean scheduleEnabled;
    private int scheduleIntervalMinutes;
    private boolean scheduleRequireWifi;
    private boolean scheduleRequireCharging;

    public SyncTask() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.remoteType = RemoteType.SFTP;
        this.remoteHost = "";
        this.remotePort = 0;
        this.remoteUser = "";
        this.remotePassword = "";
        this.remotePath = "";
        this.localPath = "";
        this.direction = Direction.DOWNLOAD;
        this.useTls = true;
        this.status = Status.IDLE;
        this.lastError = "";
        this.scheduleEnabled = false;
        this.scheduleIntervalMinutes = 0;
        this.scheduleRequireWifi = false;
        this.scheduleRequireCharging = false;
    }

    @NonNull
    public String getId() {
        return id;
    }

    public void setId(@NonNull final String id) {
        this.id = id;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public void setName(@NonNull final String name) {
        this.name = name;
    }

    @NonNull
    public RemoteType getRemoteType() {
        return remoteType;
    }

    public void setRemoteType(@NonNull final RemoteType remoteType) {
        this.remoteType = remoteType;
    }

    @NonNull
    public String getRemoteHost() {
        return remoteHost;
    }

    public void setRemoteHost(@NonNull final String remoteHost) {
        this.remoteHost = remoteHost;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public void setRemotePort(final int remotePort) {
        this.remotePort = remotePort;
    }

    @NonNull
    public String getRemoteUser() {
        return remoteUser;
    }

    public void setRemoteUser(@NonNull final String remoteUser) {
        this.remoteUser = remoteUser;
    }

    @NonNull
    public String getRemotePassword() {
        return remotePassword;
    }

    public void setRemotePassword(@NonNull final String remotePassword) {
        this.remotePassword = remotePassword;
    }

    @NonNull
    public String getRemotePath() {
        return remotePath;
    }

    public void setRemotePath(@NonNull final String remotePath) {
        this.remotePath = remotePath;
    }

    @NonNull
    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(@NonNull final String localPath) {
        this.localPath = localPath;
    }

    @NonNull
    public Direction getDirection() {
        return direction;
    }

    public void setDirection(@NonNull final Direction direction) {
        this.direction = direction;
    }

    public boolean isUseTls() {
        return useTls;
    }

    public void setUseTls(final boolean useTls) {
        this.useTls = useTls;
    }

    @NonNull
    public Status getStatus() {
        return status;
    }

    public void setStatus(@NonNull final Status status) {
        this.status = status;
    }

    public long getLastRunEpochMs() {
        return lastRunEpochMs;
    }

    public void setLastRunEpochMs(final long lastRunEpochMs) {
        this.lastRunEpochMs = lastRunEpochMs;
    }

    public long getTransferredBytes() {
        return transferredBytes;
    }

    public void setTransferredBytes(final long transferredBytes) {
        this.transferredBytes = transferredBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(final long totalBytes) {
        this.totalBytes = totalBytes;
    }

    @NonNull
    public String getLastError() {
        return lastError;
    }

    public void setLastError(@NonNull final String lastError) {
        this.lastError = lastError;
    }

    public boolean isScheduleEnabled() {
        return scheduleEnabled;
    }

    public void setScheduleEnabled(final boolean scheduleEnabled) {
        this.scheduleEnabled = scheduleEnabled;
    }

    public int getScheduleIntervalMinutes() {
        return scheduleIntervalMinutes;
    }

    public void setScheduleIntervalMinutes(final int scheduleIntervalMinutes) {
        this.scheduleIntervalMinutes = scheduleIntervalMinutes;
    }

    public boolean isScheduleRequireWifi() {
        return scheduleRequireWifi;
    }

    public void setScheduleRequireWifi(final boolean scheduleRequireWifi) {
        this.scheduleRequireWifi = scheduleRequireWifi;
    }

    public boolean isScheduleRequireCharging() {
        return scheduleRequireCharging;
    }

    public void setScheduleRequireCharging(final boolean scheduleRequireCharging) {
        this.scheduleRequireCharging = scheduleRequireCharging;
    }

    /**
     * Whether the task has enough configuration to be run.
     *
     * @return flag
     */
    public boolean isConfigured() {
        return !name.isBlank()
               && !remoteHost.isBlank()
               && !remotePath.isBlank()
               && !localPath.isBlank();
    }

    /**
     * Reset transient progress fields to a fresh idle state.
     */
    public void resetProgress() {
        transferredBytes = 0;
        totalBytes = 0;
        lastError = "";
    }

    @NonNull
    @Override
    public String toString() {
        return "SyncTask{name=" + name
               + ", direction=" + direction
               + ", remote=" + remoteType + ':' + remoteHost
               + ", status=" + status + '}';
    }

    @Override
    public boolean equals(@Nullable final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SyncTask)) {
            return false;
        }
        return id.equals(((SyncTask) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

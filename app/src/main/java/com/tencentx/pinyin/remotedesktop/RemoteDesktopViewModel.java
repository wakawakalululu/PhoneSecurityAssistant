package com.tencentx.pinyin.remotedesktop;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/**
 * ViewModel for the remote desktop screen.
 */
public final class RemoteDesktopViewModel
        extends AndroidViewModel {

    private final MutableLiveData<Boolean> running =
            new MutableLiveData<>(false);

    public RemoteDesktopViewModel(@NonNull final Application application) {
        super(application);
    }

    @NonNull
    LiveData<Boolean> isRunning() {
        return running;
    }

    void refresh() {
        running.postValue(RemoteDesktopService.isRunning());
    }
}

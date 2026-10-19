package com.tencentx.pinyin.sync;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the list of {@link SyncTask}s as observable state for {@link SyncFragment}.
 */
public class SyncViewModel
        extends AndroidViewModel {

    private final MutableLiveData<List<SyncTask>> tasks = new MutableLiveData<>();

    public SyncViewModel(@NonNull final Application application) {
        super(application);
    }

    @NonNull
    public MutableLiveData<List<SyncTask>> getTasks() {
        return tasks;
    }

    /**
     * Reload the tasks from the store and publish them.
     */
    public void refresh() {
        tasks.postValue(new ArrayList<>(SyncTaskStore.load(getApplication())));
    }

    /**
     * Upsert a task and refresh.
     *
     * @param task to save
     */
    public void save(@NonNull final SyncTask task) {
        SyncTaskStore.upsert(getApplication(), task);
        refresh();
    }

    /**
     * Delete a task by id and refresh.
     *
     * @param id of the task
     */
    public void delete(@NonNull final String id) {
        SyncTaskStore.delete(getApplication(), id);
        refresh();
    }
}

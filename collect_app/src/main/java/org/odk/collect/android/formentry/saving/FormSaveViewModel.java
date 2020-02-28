package org.odk.collect.android.formentry.saving;

import android.net.Uri;
import android.os.AsyncTask;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.javarosa.core.model.FormIndex;
import org.javarosa.core.model.data.IAnswerData;
import org.javarosa.form.api.FormEntryController;
import org.odk.collect.android.exception.JavaRosaException;
import org.odk.collect.android.formentry.audit.AuditEvent;
import org.odk.collect.android.formentry.audit.AuditUtils;
import org.odk.collect.android.formentry.javarosawrapper.FormController;
import org.odk.collect.android.fragments.dialogs.ProgressDialogFragment;
import org.odk.collect.android.tasks.SaveFormToDisk;
import org.odk.collect.android.tasks.SaveToDiskResult;
import org.odk.collect.utilities.Clock;

import java.util.HashMap;

import static org.odk.collect.android.tasks.SaveFormToDisk.SAVED;
import static org.odk.collect.android.tasks.SaveFormToDisk.SAVED_AND_EXIT;
import static org.odk.collect.android.utilities.StringUtils.isBlank;

public class FormSaveViewModel extends ViewModel implements ProgressDialogFragment.Cancellable {

    private final Clock clock;
    private final FormSaver formSaver;
    private FormController formController;

    private String reason = "";
    private MutableLiveData<SaveResult> saveResult = new MutableLiveData<>(null);

    @Nullable
    private AsyncTask saveTask;

    public FormSaveViewModel(Clock clock, FormSaver formSaver) {
        this.clock = clock;
        this.formSaver = formSaver;
    }

    public void formLoaded(FormController formController) {
        this.formController = formController;
    }

    public void editingForm() {
        if (formController.getAuditEventLogger() != null) {
            formController.getAuditEventLogger().setEditing(true);
        }
    }

    public void saveAnswersForScreen(HashMap<FormIndex, IAnswerData> answers) {
        try {
            formController.saveAllScreenAnswers(answers, false);
        } catch (JavaRosaException ignored) {
            // ignored
        }
    }

    public LiveData<SaveResult> saveForm(Uri instanceContentURI, boolean shouldFinalize, String updatedSaveName, boolean viewExiting) {
        if (isSaving()) {
            return new MutableLiveData<>(new SaveResult(SaveResult.State.ALREADY_SAVING, null));
        }

        if (formController.getAuditEventLogger() != null) {
            formController.getAuditEventLogger().flush();
        }

        SaveRequest saveRequest = new SaveRequest(instanceContentURI, viewExiting, updatedSaveName, shouldFinalize);
        this.saveResult = new MutableLiveData<>(null);

        if (!requiresReasonToSave()) {
            this.saveResult.setValue(new SaveResult(SaveResult.State.SAVING, saveRequest));
            saveToDisk(saveRequest);
        } else {
            this.saveResult.setValue(new SaveResult(SaveResult.State.CHANGE_REASON_REQUIRED, saveRequest));
        }

        return this.saveResult;
    }

    public boolean isSaving() {
        return saveResult.getValue() != null && saveResult.getValue().getState().equals(SaveResult.State.SAVING);
    }

    @Override
    public boolean cancel() {
        return saveTask.cancel(true);
    }

    public void setReason(@NonNull String reason) {
        this.reason = reason;
    }

    public boolean saveReason() {
        if (reason == null || isBlank(reason)) {
            return false;
        }

        formController.getAuditEventLogger().logEvent(AuditEvent.AuditEventType.CHANGE_REASON, null, true, null, clock.getCurrentTime(), reason);

        if (saveResult.getValue() != null) {
            SaveRequest request = saveResult.getValue().request;
            saveResult.setValue(new SaveResult(SaveResult.State.SAVING, request));
            saveToDisk(request);
        }

        return true;
    }

    public String getReason() {
        return reason;
    }

    private void saveToDisk(SaveRequest saveRequest) {
        saveTask = new SaveTask(saveRequest, formSaver, formController, new SaveTask.Listener() {
            @Override
            public void onProgressPublished(String progress) {
                saveResult.setValue(new SaveResult(SaveResult.State.SAVING, saveRequest, progress));
            }

            @Override
            public void onComplete(SaveToDiskResult saveToDiskResult) {
                handleTaskResult(saveToDiskResult, saveRequest);
            }
        }).execute();
    }

    private void handleTaskResult(SaveToDiskResult taskResult, SaveRequest saveRequest) {
        switch (taskResult.getSaveResult()) {
            case SAVED:
            case SAVED_AND_EXIT: {
                formController.getAuditEventLogger().logEvent(AuditEvent.AuditEventType.FORM_SAVE, false, clock.getCurrentTime());

                if (saveRequest.viewExiting) {
                    if (saveRequest.shouldFinalize) {
                        formController.getAuditEventLogger().logEvent(AuditEvent.AuditEventType.FORM_EXIT, false, clock.getCurrentTime());
                        formController.getAuditEventLogger().logEvent(AuditEvent.AuditEventType.FORM_FINALIZE, true, clock.getCurrentTime());
                    } else {
                        formController.getAuditEventLogger().logEvent(AuditEvent.AuditEventType.FORM_EXIT, true, clock.getCurrentTime());
                    }
                } else {
                    AuditUtils.logCurrentScreen(formController, formController.getAuditEventLogger(), clock.getCurrentTime());
                }

                saveResult.setValue(new SaveResult(SaveResult.State.SAVED, saveRequest, taskResult.getSaveErrorMessage()));
                break;
            }

            case SaveFormToDisk.SAVE_ERROR: {
                formController.getAuditEventLogger().logEvent(AuditEvent.AuditEventType.SAVE_ERROR, true, clock.getCurrentTime());
                saveResult.setValue(new SaveResult(SaveResult.State.SAVE_ERROR, saveRequest, taskResult.getSaveErrorMessage()));
                break;
            }

            case SaveFormToDisk.ENCRYPTION_ERROR: {
                formController.getAuditEventLogger().logEvent(AuditEvent.AuditEventType.FINALIZE_ERROR, true, clock.getCurrentTime());
                saveResult.setValue(new SaveResult(SaveResult.State.FINALIZE_ERROR, saveRequest, taskResult.getSaveErrorMessage()));
                break;
            }

            case FormEntryController.ANSWER_CONSTRAINT_VIOLATED:
            case FormEntryController.ANSWER_REQUIRED_BUT_EMPTY: {
                formController.getAuditEventLogger().logEvent(AuditEvent.AuditEventType.CONSTRAINT_ERROR, true, clock.getCurrentTime());
                saveResult.setValue(new SaveResult(SaveResult.State.CONSTRAINT_ERROR, saveRequest, taskResult.getSaveErrorMessage()));
                break;
            }
        }
    }

    private boolean requiresReasonToSave() {
        return formController.getAuditEventLogger().isEditing()
                && formController.getAuditEventLogger().isChangeReasonRequired()
                && formController.getAuditEventLogger().isChangesMade();
    }

    public static class SaveResult {

        private final State state;
        private final String message;
        private final SaveRequest request;

        SaveResult(State state, SaveRequest request) {
            this(state, request, null);
        }

        SaveResult(State state, SaveRequest request, String message) {
            this.state = state;
            this.message = message;
            this.request = request;
        }

        public State getState() {
            return state;
        }

        public String getMessage() {
            return message;
        }

        public enum State {
            CHANGE_REASON_REQUIRED,
            SAVING,
            SAVED,
            SAVE_ERROR,
            FINALIZE_ERROR,
            CONSTRAINT_ERROR,
            ALREADY_SAVING
        }

        public SaveRequest getRequest() {
            return request;
        }
    }

    private static class SaveRequest {

        private final boolean shouldFinalize;
        private final boolean viewExiting;
        private final String updatedSaveName;
        private final Uri uri;

        SaveRequest(Uri instanceContentURI, boolean viewExiting, String updatedSaveName, boolean shouldFinalize) {
            this.shouldFinalize = shouldFinalize;
            this.viewExiting = viewExiting;
            this.updatedSaveName = updatedSaveName;
            this.uri = instanceContentURI;
        }
    }

    private static class SaveTask extends AsyncTask<Void, String, SaveToDiskResult> {

        private final SaveRequest saveRequest;
        private final FormSaver formSaver;

        private final Listener listener;
        private final FormController formController;

        SaveTask(SaveRequest saveRequest, FormSaver formSaver, FormController formController, Listener listener) {
            this.saveRequest = saveRequest;
            this.formSaver = formSaver;
            this.listener = listener;
            this.formController = formController;
        }

        @Override
        protected SaveToDiskResult doInBackground(Void... voids) {
            return formSaver.save(saveRequest.uri, formController,
                    saveRequest.shouldFinalize,
                    saveRequest.viewExiting, saveRequest.updatedSaveName,
                    this::publishProgress
            );
        }

        @Override
        protected void onProgressUpdate(String... values) {
            listener.onProgressPublished(values[0]);
        }

        @Override
        protected void onPostExecute(SaveToDiskResult saveToDiskResult) {
            listener.onComplete(saveToDiskResult);
        }

        interface Listener {
            void onProgressPublished(String progress);

            void onComplete(SaveToDiskResult saveToDiskResult);
        }
    }

    public static class Factory implements ViewModelProvider.Factory {

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new FormSaveViewModel(System::currentTimeMillis, new DiskFormSaver());
        }
    }
}

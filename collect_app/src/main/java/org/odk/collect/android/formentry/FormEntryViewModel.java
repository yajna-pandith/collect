package org.odk.collect.android.formentry;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import org.javarosa.core.model.FormIndex;
import org.odk.collect.android.analytics.Analytics;
import org.odk.collect.android.exception.JavaRosaException;
import org.odk.collect.android.formentry.javarosawrapper.FormController;

public class FormEntryViewModel extends ViewModel {

    private FormController formController;
    private final Analytics analytics;

    @Nullable
    private FormIndex jumpBackIndex;

    public FormEntryViewModel(Analytics analytics) {
        this.analytics = analytics;
    }

    public void formLoaded(FormController formController) {
        this.formController = formController;
    }

    public void promptForNewRepeat() {
        FormIndex index = getFormController().getFormIndex();
        jumpBackIndex = index;

        getFormController().jumpToNewRepeatPrompt();
    }

    public void addRepeat(boolean fromPrompt) {
        if (jumpBackIndex != null) {
            jumpBackIndex = null;
            analytics.logEvent("AddRepeat", "Inline");
        } else if (fromPrompt) {
            analytics.logEvent("AddRepeat", "Prompt");
        } else {
            analytics.logEvent("AddRepeat", "Hierarchy");
        }

        getFormController().newRepeat();

        if (!getFormController().indexIsInFieldList()) {
            try {
                getFormController().stepToNextScreenEvent();
            } catch (JavaRosaException ignored) {
                // ignored
            }
        }
    }

    /**
     * Returns true if moving forward or false if moving backwards after cancelling
     */
    public boolean cancelRepeatPrompt() {
        analytics.logEvent("AddRepeat", "InlineDecline");

        FormController formController = getFormController();

        if (jumpBackIndex != null) {
            formController.jumpToIndex(jumpBackIndex);
            jumpBackIndex = null;
            return false;
        } else {
            try {
                getFormController().stepToNextScreenEvent();
            } catch (JavaRosaException ignored) {
                // ignored
            }

            return true;
        }
    }

    private FormController getFormController() {
        return formController;
    }

    public static class Factory implements ViewModelProvider.Factory {

        private final Analytics analytics;

        public Factory(Analytics analytics) {
            this.analytics = analytics;
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            return (T) new FormEntryViewModel(analytics);
        }
    }
}

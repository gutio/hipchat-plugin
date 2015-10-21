package jenkins.plugins.hipchat;

import static jenkins.plugins.hipchat.NotificationType.STARTED;

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.matrix.MatrixAggregatable;
import hudson.matrix.MatrixAggregator;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixProject;
import hudson.matrix.MatrixRun;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Job;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import jenkins.plugins.hipchat.exceptions.NotificationException;
import jenkins.plugins.hipchat.impl.HipChatV1Service;
import jenkins.plugins.hipchat.impl.HipChatV2Service;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import java.io.IOException;
import java.util.logging.Logger;

@SuppressWarnings({"unchecked"})
public class HipChatNotifier extends Notifier implements MatrixAggregatable {

    private static final Logger logger = Logger.getLogger(HipChatNotifier.class.getName());
    private String token;
    private String room;
    private boolean startNotification;
    private boolean notifySuccess;
    private boolean notifyAborted;
    private boolean notifyNotBuilt;
    private boolean notifyUnstable;
    private boolean notifyFailure;
    private boolean notifyBackToNormal;
    private MatrixTriggerMode matrixTriggerMode;

    private String startJobMessage;
    private String completeJobMessage;

    private boolean startNotifyPurple;

    @DataBoundConstructor
    public HipChatNotifier(String token, String room, boolean startNotification, boolean notifySuccess,
            boolean notifyAborted, boolean notifyNotBuilt, boolean notifyUnstable, boolean notifyFailure,
            boolean notifyBackToNormal, MatrixTriggerMode matrixTriggerMode, String startJobMessage,
            String completeJobMessage, boolean startNotifyPurple) {
        this.token = token;
        this.room = room;
        this.startNotification = startNotification;
        this.notifySuccess = notifySuccess;
        this.notifyAborted = notifyAborted;
        this.notifyNotBuilt = notifyNotBuilt;
        this.notifyUnstable = notifyUnstable;
        this.notifyFailure = notifyFailure;
        this.notifyBackToNormal = notifyBackToNormal;
        this.matrixTriggerMode = matrixTriggerMode;

        this.startJobMessage = startJobMessage;
        this.completeJobMessage = completeJobMessage;

        this.startNotifyPurple = startNotifyPurple;
    }

    /* notification enabled disabled setters/getters */

    public boolean isStartNotification() {
        return startNotification;
    }

    public void setStartNotification(boolean startNotification) {
        this.startNotification = startNotification;
    }

    public boolean isNotifySuccess() {
        return notifySuccess;
    }

    public void setNotifySuccess(boolean notifySuccess) {
        this.notifySuccess = notifySuccess;
    }

    public boolean isNotifyAborted() {
        return notifyAborted;
    }

    public void setNotifyAborted(boolean notifyAborted) {
        this.notifyAborted = notifyAborted;
    }

    public boolean isNotifyNotBuilt() {
        return notifyNotBuilt;
    }

    public void setNotifyNotBuilt(boolean notifyNotBuilt) {
        this.notifyNotBuilt = notifyNotBuilt;
    }

    public boolean isNotifyUnstable() {
        return notifyUnstable;
    }

    public void setNotifyUnstable(boolean notifyUnstable) {
        this.notifyUnstable = notifyUnstable;
    }

    public boolean isNotifyFailure() {
        return notifyFailure;
    }

    public void setNotifyFailure(boolean notifyFailure) {
        this.notifyFailure = notifyFailure;
    }

    public boolean isNotifyBackToNormal() {
        return notifyBackToNormal;
    }

    public void setNotifyBackToNormal(boolean notifyBackToNormal) {
        this.notifyBackToNormal = notifyBackToNormal;
    }

    public MatrixTriggerMode getMatrixTriggerMode() {
        return matrixTriggerMode == null ? MatrixTriggerMode.BOTH : matrixTriggerMode;
    }

    public void setMatrixTriggerMode(MatrixTriggerMode matrixTriggerMode) {
        this.matrixTriggerMode = matrixTriggerMode;
    }

    /* notification message configurations */

    public String getStartJobMessage() {
        return startJobMessage;
    }

    public void setStartJobMessage(String startJobMessage) {
        this.startJobMessage = startJobMessage;
    }

    public String getCompleteJobMessage() {
        return completeJobMessage;
    }

    public void setCompleteJobMessage(String completeJobMessage) {
        this.completeJobMessage = completeJobMessage;
    }

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    /**
     * Return the room name defined in the job configuration, or if that's empty return the room name from the global
     * configuration.
     * If the room name is parameterized, this will also try to resolve those parameters.
     *
     * @param build The current build for which we need to get the room.
     * @return The room name tied to the current build.
     */
    public String getResolvedRoom(AbstractBuild<?, ?> build) {
        return Util.replaceMacro(StringUtils.isBlank(room) ? getDescriptor().getRoom() : room,
                build.getBuildVariableResolver());
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return Jenkins.getInstance().getDescriptorByType(DescriptorImpl.class);
    }

    @Override
    public boolean needsToRunAfterFinalized() {
        //This is here to ensure that the reported build status is actually correct. If we were to return false here,
        //other build plugins could still modify the build result, making the sent out HipChat notification incorrect.
        return true;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
        logger.fine("Creating build start notification");
        if (!(build instanceof MatrixRun) || getMatrixTriggerMode().forChild) {
            publishNotificationIfEnabled(STARTED, build, listener);
        }

        return true;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        if (!(build instanceof MatrixRun) || getMatrixTriggerMode().forChild) {
            notifyOnBuildComplete(build, listener);
        }

        return true;
    }

    private void notifyOnBuildComplete(AbstractBuild<?, ?> build, BuildListener listener) {
        logger.fine("Creating build completed notification");
        Result result = build.getResult();
        Result previousResult = findPreviousBuildResult(build);

        NotificationType notificationType = NotificationType.fromResults(previousResult, result);
        publishNotificationIfEnabled(notificationType, build, listener);
    }

    private Result findPreviousBuildResult(AbstractBuild<?,?> build) {
        do {
            build = build.getPreviousBuild();
            if (build == null || build.isBuilding()) {
                return null;
            }
        } while (build.getResult() == Result.ABORTED || build.getResult() == Result.NOT_BUILT);
        return build.getResult();
    }

    private void publishNotificationIfEnabled(NotificationType notificationType, AbstractBuild<?, ?> build,
            BuildListener listener) {
        if (isNotificationEnabled(notificationType)) {
            try {
                if(notificationType == NotificationType.STARTED && startNotifyPurple)
                    getHipChatService(build).publish(notificationType.getMessage(build, this), "purple");
                else
                    getHipChatService(build).publish(notificationType.getMessage(build, this), notificationType.getColor());
                listener.getLogger().println(Messages.NotificationSuccessful(getResolvedRoom(build)));
            } catch (NotificationException ne) {
                listener.getLogger().println(Messages.NotificationFailed(ne.getMessage()));
            }
        }
    }

    private boolean isNotificationEnabled(NotificationType type) {
        switch (type) {
            case ABORTED:
                return notifyAborted;
            case BACK_TO_NORMAL:
                return notifyBackToNormal;
            case FAILURE:
                return notifyFailure;
            case NOT_BUILT:
                return notifyNotBuilt;
            case STARTED:
                return startNotification;
            case SUCCESS:
                return notifySuccess;
            case UNSTABLE:
                return notifyUnstable;
            default:
                return false;
        }
    }

    private HipChatService getHipChatService(AbstractBuild<?, ?> build) {
        DescriptorImpl desc = getDescriptor();
        String authToken = Util.fixEmpty(token) != null ? token : desc.getToken();
        return getHipChatService(desc.getServer(), authToken, desc.isV2Enabled(), getResolvedRoom(build),
                desc.getSendAs());
    }

    private static HipChatService getHipChatService(String server, String token, boolean v2Enabled, String room,
            String sendAs) {
        if (v2Enabled) {
            return new HipChatV2Service(server, token, room);
        } else {
            return new HipChatV1Service(server, token, room, sendAs);
        }
    }

    @Override
    public MatrixAggregator createAggregator(MatrixBuild build, Launcher launcher, BuildListener listener) {
        return new MatrixAggregator(build, launcher, listener) {

            @Override
            public boolean startBuild() throws InterruptedException, IOException {
                if (getMatrixTriggerMode().forParent) {
                    publishNotificationIfEnabled(STARTED, build, listener);
                }
                return true;
            }

            @Override
            public boolean endBuild() throws InterruptedException, IOException {
                if (getMatrixTriggerMode().forParent) {
                    notifyOnBuildComplete(build, listener);
                }
                return true;
            }
        };
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String server = "api.hipchat.com";
        private String token;
        private boolean v2Enabled = false;
        private String room;
        private String sendAs = "Jenkins";
        private static int testNotificationCount = 0;

        public DescriptorImpl() {
            load();
        }

        public String getServer() {
            return server;
        }

        public void setServer(String server) {
            this.server = server;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public boolean isV2Enabled() {
            return v2Enabled;
        }

        public void setV2Enabled(boolean v2Enabled) {
            this.v2Enabled = v2Enabled;
        }

        public String getRoom() {
            return room;
        }

        public void setRoom(String room) {
            this.room = room;
        }

        public String getSendAs() {
            return sendAs;
        }

        public void setSendAs(String sendAs) {
            this.sendAs = sendAs;
        }

        /* Default notification messages for UI */
        public String getStartJobMessageDefault() {
            return Messages.JobStarted();
        }

        public String getCompleteJobMessageDefault() {
            return Messages.JobCompleted();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public boolean isMatrixProject(Object project) {
            return project instanceof MatrixProject;
        }

        @Override
        public boolean configure(StaplerRequest request, JSONObject formData) throws FormException {
            request.bindJSON(this, formData);

            save();
            return super.configure(request, formData);
        }

        public FormValidation doCheckSendAs(@QueryParameter boolean v2Enabled, @QueryParameter String sendAs) {
            sendAs = Util.fixEmpty(sendAs);
            if (!v2Enabled) {
                if (sendAs == null || sendAs.length() > 15) {
                    return FormValidation.error(Messages.InvalidSendAs());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doSendTestNotification(@QueryParameter String server, @QueryParameter String token,
                @QueryParameter boolean v2Enabled, @QueryParameter String room, @QueryParameter String sendAs) {
            HipChatService service = getHipChatService(server, token, v2Enabled, room, sendAs);
            try {
                service.publish(Messages.TestNotification(++testNotificationCount), "yellow");
                return FormValidation.ok(Messages.TestNotificationSent());
            } catch (NotificationException ne) {
                return FormValidation.error(Messages.TestNotificationFailed(ne.getMessage()));
            }
        }

        @Override
        public String getDisplayName() {
            return Messages.DisplayName();
        }
    }

    /**
     * The settings defined here have been moved to the {@link HipChatNotifier} configuration (shows up under the Post
     * Build task view).
     *
     * @deprecated The plugin configuration should be stored in {@link HipChatNotifier}. This class only exists, so
     * configurations can be migrated for the build jobs.
     */
    @Deprecated
    public static class HipChatJobProperty extends hudson.model.JobProperty<AbstractProject<?, ?>> {
        private final String room;
        private final boolean startNotification;
        private final boolean notifySuccess;
        private final boolean notifyAborted;
        private final boolean notifyNotBuilt;
        private final boolean notifyUnstable;
        private final boolean notifyFailure;
        private final boolean notifyBackToNormal;


        @DataBoundConstructor
        public HipChatJobProperty(String room,
                                  boolean startNotification,
                                  boolean notifyAborted,
                                  boolean notifyFailure,
                                  boolean notifyNotBuilt,
                                  boolean notifySuccess,
                                  boolean notifyUnstable,
                                  boolean notifyBackToNormal) {
            this.room = room;
            this.startNotification = startNotification;
            this.notifyAborted = notifyAborted;
            this.notifyFailure = notifyFailure;
            this.notifyNotBuilt = notifyNotBuilt;
            this.notifySuccess = notifySuccess;
            this.notifyUnstable = notifyUnstable;
            this.notifyBackToNormal = notifyBackToNormal;
        }

        @Exported
        public String getRoom() {
            return room;
        }

        @Exported
        public boolean getStartNotification() {
            return startNotification;
        }

        @Exported
        public boolean getNotifySuccess() {
            return notifySuccess;
        }

        @Exported
        public boolean getNotifyAborted() {
            return notifyAborted;
        }

        @Exported
        public boolean getNotifyFailure() {
            return notifyFailure;
        }

        @Exported
        public boolean getNotifyNotBuilt() {
            return notifyNotBuilt;
        }

        @Exported
        public boolean getNotifyUnstable() {
            return notifyUnstable;
        }

        @Exported
        public boolean getNotifyBackToNormal() {
            return notifyBackToNormal;
        }

        @Extension
        public static final class DescriptorImpl extends JobPropertyDescriptor {
            public String getDisplayName() {
                return "HipChat Notifications";
            }

            @Override
            public boolean isApplicable(Class<? extends Job> jobType) {
                return true;
            }

            @Override
            public HipChatJobProperty newInstance(StaplerRequest sr, JSONObject formData) throws hudson.model.Descriptor.FormException {
                return new HipChatJobProperty(sr.getParameter("hipChatProjectRoom"),
                        sr.getParameter("hipChatStartNotification") != null,
                        sr.getParameter("hipChatNotifyAborted") != null,
                        sr.getParameter("hipChatNotifyFailure") != null,
                        sr.getParameter("hipChatNotifyNotBuilt") != null,
                        sr.getParameter("hipChatNotifySuccess") != null,
                        sr.getParameter("hipChatNotifyUnstable") != null,
                        sr.getParameter("hipChatNotifyBackToNormal") != null);
            }
        }
    }
}

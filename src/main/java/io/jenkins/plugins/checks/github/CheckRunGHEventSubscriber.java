package io.jenkins.plugins.checks.github;

import edu.hm.hafner.util.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.*;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.util.JenkinsFacade;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.plugins.github.extension.GHEventsSubscriber;
import org.jenkinsci.plugins.github.extension.GHSubscriberEvent;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.kohsuke.github.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This subscriber manages {@link GHEvent#CHECK_RUN} event and handles the re-run action request.
 */
@Extension
public class CheckRunGHEventSubscriber extends GHEventsSubscriber {
    private static final Logger LOGGER = Logger.getLogger(CheckRunGHEventSubscriber.class.getName());
    private static final String RERUN_ACTION = "rerequested";

    private final JenkinsFacade jenkinsFacade;
    private final SCMFacade scmFacade;

    /**
     * Construct the subscriber.
     */
    public CheckRunGHEventSubscriber() {
        this(new JenkinsFacade(), new SCMFacade());
    }

    @VisibleForTesting
    CheckRunGHEventSubscriber(final JenkinsFacade jenkinsFacade, final SCMFacade scmFacade) {
        super();

        this.jenkinsFacade = jenkinsFacade;
        this.scmFacade = scmFacade;
    }

    @Override
    protected boolean isApplicable(@CheckForNull final Item item) {
        if (item instanceof Job<?, ?>) {
            return scmFacade.findGitHubSCMSource((Job<?, ?>)item).isPresent();
        }

        return false;
    }

    @Override
    protected Set<GHEvent> events() {
        return Collections.unmodifiableSet(new HashSet<>(Collections.singletonList(GHEvent.CHECK_RUN)));
    }

    @Override
    protected void onEvent(final GHSubscriberEvent event) {
        final String payload = event.getPayload();
        GHEventPayload.CheckRun checkRun;
        try {
            checkRun = GitHub.offline().parseEventPayload(new StringReader(payload), GHEventPayload.CheckRun.class);
        }
        catch (IOException e) {
            throw new IllegalStateException("Could not parse check run event: " + payload.replaceAll("[\r\n]", ""), e);
        }

        if (!checkRun.getAction().equals(RERUN_ACTION)) {
            LOGGER.log(Level.FINE, "Unsupported check run action: " + checkRun.getAction().replaceAll("[\r\n]", ""));
            return;
        }

        LOGGER.log(Level.INFO, "Received rerun request through GitHub checks API.");
        try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
            scheduleRerun(checkRun);
        }
    }

    private void scheduleRerun(final GHEventPayload.CheckRun checkRun) {
        final GHRepository repository = checkRun.getRepository();

        Optional<Job<?, ?>> optionalJob = jenkinsFacade.getJob(checkRun.getCheckRun().getExternalId());
        if (optionalJob.isPresent()) {
            Job<?, ?> job = optionalJob.get();
            Cause cause = new GitHubChecksRerunActionCause(checkRun.getSender().getLogin());
            ParameterizedJobMixIn.scheduleBuild2(job, 0, new CauseAction(cause));

            LOGGER.log(Level.INFO, String.format("Scheduled rerun (build #%d) for job %s, requested by %s",
                    job.getNextBuildNumber(), jenkinsFacade.getFullNameOf(job),
                    checkRun.getSender().getLogin()).replaceAll("[\r\n]", ""));
        }
        else {
            LOGGER.log(Level.WARNING, String.format("No job found for rerun request from repository: %s and job: %s",
                    repository.getFullName(), checkRun.getCheckRun().getExternalId()).replaceAll("[\r\n]", ""));
        }
    }

    /**
     * Declares that a build was started due to a user's rerun request through GitHub checks API.
     */
    public static class GitHubChecksRerunActionCause extends Cause {
        private final String user;

        /**
         * Construct the cause with user who requested the rerun.
         *
         * @param user
         *         name of the user who made the request
         */
        public GitHubChecksRerunActionCause(final String user) {
            super();

            this.user = user;
        }

        @Override
        public String getShortDescription() {
            return String.format("Rerun request by %s through GitHub checks API", user);
        }
    }
}

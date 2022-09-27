/*
 * Copyright 2013 Netherlands eScience Center
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.esciencecenter.xenon.adaptors.schedulers.slurm;

import static nl.esciencecenter.xenon.adaptors.schedulers.slurm.SlurmSchedulerAdaptor.ADAPTOR_NAME;

import java.util.ArrayList;
import java.util.Formatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.esciencecenter.xenon.XenonException;
import nl.esciencecenter.xenon.adaptors.schedulers.JobCanceledException;
import nl.esciencecenter.xenon.adaptors.schedulers.JobStatusImplementation;
import nl.esciencecenter.xenon.adaptors.schedulers.QueueStatusImplementation;
import nl.esciencecenter.xenon.adaptors.schedulers.ScriptingUtils;
import nl.esciencecenter.xenon.filesystems.Path;
import nl.esciencecenter.xenon.schedulers.InvalidJobDescriptionException;
import nl.esciencecenter.xenon.schedulers.JobDescription;
import nl.esciencecenter.xenon.schedulers.JobStatus;
import nl.esciencecenter.xenon.schedulers.QueueStatus;
import nl.esciencecenter.xenon.schedulers.Scheduler;

public final class SlurmUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(SlurmUtils.class);

    /**
     * These are the states a job can be in when it has failed: FAILED: the job terminated with non-zero exit code or other failure condition. CANCELLED: the
     * job was explicitly cancelled by the user or system administrator. NODE_FAIL: the job terminated due to failure of one or more allocated nodes. TIMEOUT:
     * the job terminated upon reaching its time limit. PREEMPTED: the job terminated due to preemption (a more important job took its place). BOOT_FAIL: the
     * job terminated due to a launch failure (typically a hardware failure).
     */
    private static final String[] FAILED_STATES = new String[] { "FAILED", "CANCELLED", "NODE_FAIL", "TIMEOUT", "PREEMPTED", "BOOT_FAIL" };

    /**
     * These are the states a job can be in when it is running:
     *
     * CONFIGURING: the resources are available and being preparing to run the job (for example by booting). RUNNING: the resources are running the job.
     * COMPLETING: the job is in process of completing. Some processes may have completed, others may still be running.
     */
    private static final String[] RUNNING_STATES = new String[] { "CONFIGURING", "RUNNING", "COMPLETING" };

    /**
     * These are the states a job can be in when it is pending:
     *
     * PENDING: the job is awaiting resource allocation. STOPPED: the job has an allocation, but execution has been stopped with SIGSTOP signal (allocation is
     * retained). SUSPENDED: the job has an allocation, but execution has been suspended (resources have been released for other jobs). SPECIAL_EXIT: The job
     * was requeued in a special state.
     */
    private static final String[] PENDING_STATES = new String[] { "PENDING", "STOPPED", "SUSPENDED", "SPECIAL_EXIT" };

    /** In completed state, the job has terminated and all processes have returned exit code 0. */
    private static final String DONE_STATE = "COMPLETED";

    protected static String identifiersAsCSList(String[] jobs) {
        String result = null;
        for (String job : jobs) {
            if (job != null) {
                if (result == null) {
                    result = job;
                } else {
                    result = ScriptingUtils.concat(result, ",", job);
                }
            }
        }
        return result;
    }

    private SlurmUtils() {
        throw new IllegalStateException("Utility class");
    }

    // Retrieve an exit code from the "ExitCode" output field of scontrol
    protected static Integer exitcodeFromString(String value) throws XenonException {
        if (value == null) {
            return null;
        }

        // the exit status may contain a ":" followed by the signal send to stop the job. Ignore
        String exitCodeString = value.split(":")[0];

        try {
            return Integer.parseInt(exitCodeString);
        } catch (NumberFormatException e) {
            throw new XenonException(ADAPTOR_NAME, "job exit code \"" + exitCodeString + "\" is not a number", e);
        }

    }

    protected static JobStatus getJobStatusFromSacctInfo(Map<String, Map<String, String>> info, String jobIdentifier) throws XenonException {

        if (info == null) {
            LOGGER.debug("No sacct output provided");
            return null;
        }

        Map<String, String> jobInfo = info.get(jobIdentifier);

        if (jobInfo == null) {
            LOGGER.debug("job {} not found in sacct output", jobIdentifier);
            return null;
        }

        // also checks if the job id is correct
        ScriptingUtils.verifyJobInfo(jobInfo, jobIdentifier, ADAPTOR_NAME, "JobID", "JobName", "State", "ExitCode");

        String name = jobInfo.get("JobName");
        String state = jobInfo.get("State");

        Integer exitcode = exitcodeFromString(jobInfo.get("ExitCode"));

        XenonException exception;
        if (!isFailedState(state) || (state.equals("FAILED") && (exitcode != null && exitcode != 0))) {
            // Not a failed state (non zero exit code does not count either), no error.
            exception = null;
        } else if (state.startsWith("CANCELLED")) {
            exception = new JobCanceledException(ADAPTOR_NAME, "Job " + state.toLowerCase(Locale.getDefault()));
        } else {
            exception = new XenonException(ADAPTOR_NAME, "Job failed for unknown reason");
        }

        JobStatus result = new JobStatusImplementation(jobIdentifier, name, state, exitcode, exception, isRunningState(state), isDoneOrFailedState(state),
                jobInfo);

        LOGGER.debug("Got job status from sacct output {}", result);

        return result;
    }

    protected static JobStatus getJobStatusFromScontrolInfo(Map<String, String> jobInfo, String jobIdentifier) throws XenonException {

        if (jobInfo == null || jobInfo.isEmpty()) {
            LOGGER.debug("No scontrol output provided");
            return null;
        }

        // Checks if the job id is correct and mandatory fields are present.
        // Note that slurm 14, 15 and 16 seem to return the last job if scontrol fails to parse jobIdentifier string. We assume this
        // is the case if the verification fails.
        try {
            ScriptingUtils.verifyJobInfo(jobInfo, jobIdentifier, ADAPTOR_NAME, "JobId", "JobName", "JobState", "ExitCode", "Reason");
        } catch (XenonException e) {
            LOGGER.debug("Scontrol output does not contain expected job info");
            return null;
        }

        String name = jobInfo.get("JobName");
        String state = jobInfo.get("JobState");

        Integer exitcode = exitcodeFromString(jobInfo.get("ExitCode"));
        String reason = jobInfo.get("Reason");

        XenonException exception;
        if (!isFailedState(state) || state.equals("FAILED") && reason.equals("NonZeroExitCode")) {
            // Not a failed state (non zero exit code does not count either), no error.
            exception = null;
        } else if (state.startsWith("CANCELLED")) {
            exception = new JobCanceledException(ADAPTOR_NAME, "Job " + state.toLowerCase(Locale.getDefault()));
        } else if (!reason.equals("None")) {
            exception = new XenonException(ADAPTOR_NAME, "Job failed with state \"" + state + "\" and reason: " + reason);
        } else {
            exception = new XenonException(ADAPTOR_NAME, "Job failed with state \"" + state + "\" for unknown reason");
        }

        JobStatus result = new JobStatusImplementation(jobIdentifier, name, state, exitcode, exception, isRunningState(state), isDoneOrFailedState(state),
                jobInfo);

        LOGGER.debug("Got job status from scontrol output {}", result);

        return result;
    }

    protected static JobStatus getJobStatusFromSqueueInfo(Map<String, Map<String, String>> info, String jobIdentifier) throws XenonException {

        if (info == null) {
            LOGGER.debug("No info provided");
            return null;
        }

        Map<String, String> jobInfo = info.get(jobIdentifier);

        if (jobInfo == null) {
            LOGGER.debug("job {} not found in queue", jobIdentifier);
            return null;
        }

        // also checks if the job id is correct
        ScriptingUtils.verifyJobInfo(jobInfo, jobIdentifier, ADAPTOR_NAME, "JOBID", "NAME", "STATE");

        String name = jobInfo.get("NAME");
        String state = jobInfo.get("STATE");

        return new JobStatusImplementation(jobIdentifier, name, state, null, null, isRunningState(state), false, jobInfo);
    }

    protected static QueueStatus getQueueStatusFromSInfo(Map<String, Map<String, String>> info, String queueName, Scheduler scheduler) {

        if (info == null) {
            LOGGER.debug("No info provided");
            return null;
        }

        Map<String, String> queueInfo = info.get(queueName);

        if (queueInfo == null) {
            LOGGER.debug("queue {} not found", queueName);
            return null;
        }

        return new QueueStatusImplementation(scheduler, queueName, null, queueInfo);
    }

    /**
     * Is the given state a running state ?
     *
     * @param state
     *            the state to check
     * @return if the state is a running state.
     */
    protected static boolean isRunningState(String state) {
        for (String validState : RUNNING_STATES) {
            if (state.startsWith(validState)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Is the given state a pending state ?
     *
     * @param state
     *            the state to check
     * @return if the state is a pending state.
     */
    protected static boolean isPendingState(String state) {
        for (String validState : PENDING_STATES) {
            if (state.startsWith(validState)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Is the given state a done or failed state ?
     *
     * @param state
     *            the state to check
     * @return if the state is a done or failed state.
     */
    protected static boolean isDoneOrFailedState(String state) {
        return isDoneState(state) || isFailedState(state);
    }

    /**
     * Is the given state a done state ?
     *
     * @param state
     *            the state to check
     * @return if the state is a done state.
     */
    protected static boolean isDoneState(String state) {
        return state.equals(DONE_STATE);
    }

    /**
     * Is the given state a failed state ?
     *
     * @param state
     *            the state to check
     * @return if the state is failed state.
     */
    protected static boolean isFailedState(String state) {
        for (String validState : FAILED_STATES) {
            if (state.startsWith(validState)) {
                return true;
            }
        }
        return false;
    }

    protected static void verifyJobDescription(JobDescription description, String[] queueNames, boolean interactive) throws XenonException {
        if (interactive) {
            if (description.getStdin() != null) {
                throw new InvalidJobDescriptionException(ADAPTOR_NAME, "Stdin redirect not supported in interactive mode");
            }

            if (description.getStdout() != null && !description.getStdout().equals("stdout.txt")) {
                throw new InvalidJobDescriptionException(ADAPTOR_NAME, "Stdout redirect not supported in interactive mode");
            }

            if (description.getStderr() != null && !description.getStderr().equals("stderr.txt")) {
                throw new InvalidJobDescriptionException(ADAPTOR_NAME, "Stderr redirect not supported in interactive mode");
            }

            if (description.getEnvironment().size() != 0) {
                throw new InvalidJobDescriptionException(ADAPTOR_NAME, "Environment variables not supported in interactive mode");
            }
        }

        // Perform standard checks.
        ScriptingUtils.verifyJobDescription(description, queueNames, ADAPTOR_NAME);

        // Check is the maxTime is set
        if (description.getMaxRuntime() == 0) {
            throw new InvalidJobDescriptionException(ADAPTOR_NAME, "Illegal maximum runtime: 0");
        }
    }

    public static String[] generateInteractiveArguments(JobDescription description, Path fsEntryPath, UUID tag, int defaultRuntime) {
        ArrayList<String> arguments = new ArrayList<>();

        // suppress printing of status messages
        arguments.add("--quiet");

        // add a tag so we can find the job back in the queue later
        arguments.add("--job-name=" + tag.toString());

        // set working directory
        if (description.getWorkingDirectory() != null) {
            String path = ScriptingUtils.getWorkingDirPath(description, fsEntryPath);
            arguments.add("--chdir=" + path);
        }

        if (description.getQueueName() != null) {
            arguments.add("--partition=" + description.getQueueName());
        }

        // number of nodes
        arguments.add("--ntasks=" + description.getTasks());

        // number of processor per node
        if (description.getTasksPerNode() > 0) {
            arguments.add("--ntasks-per-node=" + description.getTasksPerNode());
        }

        // number of thread per process
        arguments.add("--cpus-per-task=" + description.getCoresPerTask());

        // the max amount of memory per node.
        if (description.getMaxMemory() > 0) {
            arguments.add("--mem=" + description.getMaxMemory() + "M");
        }

        if (description.getTempSpace() > 0) {
            arguments.add("--tmp=" + description.getTempSpace() + "M");
        }

        // add maximum runtime in hour:minute:second format (converted from minutes in description)
        int runtime = description.getMaxRuntime();

        if (runtime == -1) {
            runtime = defaultRuntime;
        }

        // add maximum runtime
        arguments.add("--time=" + runtime);

        // add scheduler arguments
        arguments.addAll(description.getSchedulerArguments());

        arguments.add(description.getExecutable());
        arguments.addAll(description.getArguments());

        return arguments.toArray(new String[arguments.size()]);
    }

    public static String generate(JobDescription description, Path fsEntryPath, int defaultRuntime) {
        StringBuilder stringBuilder = new StringBuilder();
        Formatter script = new Formatter(stringBuilder, Locale.US);

        script.format("%s\n", "#!/bin/sh");

        String name = description.getName();

        if (name == null || name.trim().isEmpty()) {
            name = "xenon";
        }

        // set name of job to xenon
        script.format("#SBATCH --job-name='%s'\n", name);

        // set working directory. Note: we use the short argument '-D' instead of the long one, as it changed in slurm 17.11.0 from "--workdir" to "--chdir"
        if (description.getWorkingDirectory() != null) {
            String path = ScriptingUtils.getWorkingDirPath(description, fsEntryPath);
            script.format("#SBATCH -D '%s'\n", path);
        }

        if (description.getQueueName() != null) {
            script.format("#SBATCH --partition=%s\n", description.getQueueName());
        }

        // number of nodes
        script.format("#SBATCH --ntasks=%d\n", description.getTasks());

        // number of thread per process
        script.format("#SBATCH --cpus-per-task=%d\n", description.getCoresPerTask());

        // number of processer per node
        if (description.getTasksPerNode() > 0) {
            script.format("#SBATCH --ntasks-per-node=%d\n", description.getTasksPerNode());
        }

        // add maximum runtime in hour:minute:second format (converted from minutes in description)
        int runtime = description.getMaxRuntime();

        if (runtime == -1) {
            runtime = defaultRuntime;
        }

        // add maximum runtime
        script.format("#SBATCH --time=%d\n", runtime);

        // the max amount of memory per node.
        if (description.getMaxMemory() > 0) {
            script.format("#SBATCH --mem=%dM\n", description.getMaxMemory());
        }

        if (description.getTempSpace() > 0) {
            script.format("#SBATCH --tmp=%dM\n", description.getTempSpace());
        }

        if (description.getStdin() != null) {
            script.format("#SBATCH --input='%s'\n", description.getStdin());
        }

        if (description.getStdout() == null) {
            script.format("#SBATCH --output=/dev/null\n");
        } else {
            // NOTE: SLURM directly accepts the %j when the JOB_ID needs to be inserted.
            script.format("#SBATCH --output='%s'\n", description.getStdout());
        }

        if (description.getStderr() == null) {
            script.format("%s\n", "#SBATCH --error=/dev/null");
        } else {
            // NOTE: SLURM directly accepts the %j when the JOB_ID needs to be inserted.
            script.format("#SBATCH --error='%s'\n", description.getStderr());
        }

        for (String argument : description.getSchedulerArguments()) {
            script.format("#SBATCH %s\n", argument);
        }

        for (Map.Entry<String, String> entry : description.getEnvironment().entrySet()) {
            script.format("export %s=\"%s\"\n", entry.getKey(), entry.getValue());
        }

        script.format("\n");

        if (description.isStartPerTask()) {
            // run commands through srun
            script.format("%s ", "srun");
        }

        script.format("%s", description.getExecutable());

        for (String argument : description.getArguments()) {
            script.format(" %s", ScriptingUtils.protectAgainstShellMetas(argument));
        }
        script.format("\n");

        script.close();

        LOGGER.debug("Created job script:%n{} from description {}", stringBuilder, description);

        return stringBuilder.toString();
    }

}

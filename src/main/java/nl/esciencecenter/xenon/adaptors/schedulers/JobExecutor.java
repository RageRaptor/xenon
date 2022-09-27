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
package nl.esciencecenter.xenon.adaptors.schedulers;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.esciencecenter.xenon.XenonException;
import nl.esciencecenter.xenon.filesystems.FileSystem;
import nl.esciencecenter.xenon.filesystems.Path;
import nl.esciencecenter.xenon.schedulers.JobDescription;
import nl.esciencecenter.xenon.schedulers.JobStatus;
import nl.esciencecenter.xenon.schedulers.Streams;
import nl.esciencecenter.xenon.utils.LocalFileSystemUtils;

/**
 *
 */
public class JobExecutor implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobExecutor.class);

    private static final String PENDING_STATE = "PENDING";
    private static final String RUNNING_STATE = "RUNNING";
    private static final String DONE_STATE = "DONE";
    private static final String ERROR_STATE = "ERROR";
    private static final String KILLED_STATE = "KILLED";

    /** Polling delay in ms. */
    private static final long POLLING_DELAY = 1000L;

    /** Number of ms. per min. */
    private static final long MILLISECONDS_PER_MINUTE = 60L * 1000L;

    private final JobDescription description;
    private final String jobIdentifier;
    private final boolean interactive;

    private final InteractiveProcessFactory factory;

    private final long pollingDelay;
    private final long startupTimeout;

    private final String adaptorName;

    private final FileSystem filesystem;
    private final Path workingDirectory;

    private Streams streams;

    private Integer exitStatus;

    private boolean updateSignal = false;
    private boolean isRunning = false;
    private boolean killed = false;
    private boolean done = false;
    private boolean hasRun = false;

    private String state = PENDING_STATE;

    private XenonException error;

    public JobExecutor(String adaptorName, FileSystem filesystem, Path workingDirectory, InteractiveProcessFactory factory, JobDescription description,
            String jobIdentifier, boolean interactive, long pollingDelay, long startupTimeout) {

        this.adaptorName = adaptorName;
        this.filesystem = filesystem;
        this.workingDirectory = workingDirectory;
        this.description = description;
        this.jobIdentifier = jobIdentifier;
        this.interactive = interactive;
        this.factory = factory;
        this.pollingDelay = pollingDelay;
        this.startupTimeout = startupTimeout;

        LOGGER.debug("{}/{} new JobExecutor interactive={} pollingDelay={} startupTimeout={} Jobdescription={}", adaptorName, jobIdentifier, interactive,
                pollingDelay, startupTimeout, description);
    }

    public synchronized boolean hasRun() {
        return hasRun;
    }

    public synchronized boolean kill() {
        if (done) {
            return true;
        }

        killed = true;

        if (!isRunning) {
            updateState(KILLED_STATE, -1, new JobCanceledException(adaptorName, "Process cancelled by user."));
            return true;
        }

        return false;
    }

    public synchronized boolean isDone() {
        return done;
    }

    public String getJobIdentifier() {
        return jobIdentifier;
    }

    public JobDescription getJobDescription() {
        return description;
    }

    public synchronized JobStatus getStatus() {
        if (!done && RUNNING_STATE.equals(state)) {
            triggerStatusUpdate();
            waitForStatusUpdate(pollingDelay);
        }

        return new JobStatusImplementation(jobIdentifier, description.getName(), state, exitStatus, error, RUNNING_STATE.equals(state), done, null);
    }

    public synchronized String getState() {
        return state;
    }

    public synchronized Exception getError() {
        return error;
    }

    private synchronized void updateState(String state, int exitStatus, XenonException e) {

        if (ERROR_STATE.equals(state) || KILLED_STATE.equals(state)) {
            error = e;
            done = true;
        } else if (DONE_STATE.equals(state)) {
            this.exitStatus = exitStatus;
            done = true;
        } else if (RUNNING_STATE.equals(state)) {
            hasRun = true;
        } else {
            throw new InternalError("Illegal state: " + state);
        }

        this.state = state;
        clearUpdateRequest();
    }

    private synchronized boolean getKilled() {
        isRunning = true;
        return killed;
    }

    private synchronized void setStreams(Streams streams) {
        this.streams = streams;
    }

    public synchronized Streams getStreams() throws XenonException {

        if (streams == null) {
            throw new XenonException(adaptorName, "Streams not available");
        }

        return streams;
    }

    public synchronized JobStatus waitUntilRunning(long timeout) {

        long deadline = Deadline.getDeadline(timeout);

        triggerStatusUpdate();

        long leftover = deadline - System.currentTimeMillis();

        LOGGER.debug("{}/{} waiting for job to START timeout={} leftover={} state={}", adaptorName, jobIdentifier, timeout, leftover, state);

        while (leftover > 0 && PENDING_STATE.equals(state)) {
            try {
                wait(leftover);
            } catch (InterruptedException e) {
                // We were interrupted
                Thread.currentThread().interrupt();
                break;
            }

            leftover = deadline - System.currentTimeMillis();
        }

        LOGGER.debug("{}/{} done waiting for job to START {}", adaptorName, jobIdentifier, getStatus());

        return getStatus();
    }

    public synchronized JobStatus waitUntilDone(long timeout) {

        long deadline = Deadline.getDeadline(timeout);

        triggerStatusUpdate();

        long leftover = deadline - System.currentTimeMillis();

        LOGGER.debug("{}/{} waiting for job to FINISH timeout={} leftover={} state={}", adaptorName, jobIdentifier, timeout, leftover, state);

        while (leftover > 0 && !done) {

            try {
                wait(leftover);
            } catch (InterruptedException e) {
                // We were interrupted
                Thread.currentThread().interrupt();
                break;
            }

            leftover = deadline - System.currentTimeMillis();

        }

        LOGGER.debug("{}/{} done waiting for job to FINISH {}", adaptorName, jobIdentifier, getStatus());

        return getStatus();
    }

    /**
     * Signal the polling thread to produce a status update.
     */
    private synchronized void triggerStatusUpdate() {
        if (done) {
            return;
        }

        updateSignal = true;
        notifyAll();
    }

    /**
     * Wait for a certain amount of time for an update.
     *
     * @param maxDelay
     *            the maximum time to wait
     */
    private synchronized void waitForStatusUpdate(long maxDelay) {

        if (done || !updateSignal) {
            return;
        }

        long deadline = Deadline.getDeadline(maxDelay);

        long left = maxDelay > 0 ? maxDelay : POLLING_DELAY;

        while (!done && updateSignal && left > 0) {
            try {
                wait(left);
            } catch (InterruptedException e) {
                // We were interrupted
                Thread.currentThread().interrupt();
                break;
            }

            if (maxDelay > 0) {
                left = deadline - System.currentTimeMillis();
            }
        }
    }

    /**
     * Clear the update signal and wake up any waiting threads
     */
    private synchronized void clearUpdateRequest() {
        updateSignal = false;
        notifyAll();
    }

    /**
     * Sleep for a certain amount of time, provide the job is not done, and no one requested an update.
     *
     * @param maxDelay
     *            the maximum amount of time to wait
     */
    private synchronized void sleep(long maxDelay) {

        if (done || updateSignal || maxDelay <= 0) {
            return;
        }

        long deadline = Deadline.getDeadline(maxDelay);

        long left = deadline - System.currentTimeMillis();

        while (!done && !updateSignal && left > 0) {
            try {
                wait(left);
            } catch (InterruptedException e) {
                // We were interrupted
                Thread.currentThread().interrupt();
                break;
            }

            left = deadline - System.currentTimeMillis();
        }
    }

    private Path processPath(Path root, String path) {
        Path result;

        if (path == null) {
            result = root;
        } else if (LocalFileSystemUtils.startWithRoot(path)) {
            result = new Path(path);
        } else {
            result = root.resolve(path);
        }

        return result;
    }

    @Override
    public void run() {
        Process process;

        LOGGER.debug("{}/{} starting job", adaptorName, jobIdentifier);

        if (getKilled()) {
            updateState(KILLED_STATE, -1, new JobCanceledException(adaptorName, "Process cancelled by user."));
            LOGGER.debug("{}/{} KILLED before start", adaptorName, jobIdentifier);
            return;
        }

        long endTime = 0;
        int maxTime = description.getMaxRuntime();

        if (maxTime > 0) {
            endTime = System.currentTimeMillis() + maxTime * MILLISECONDS_PER_MINUTE;
        }

        LOGGER.debug("{}/{} endTime set to {}", adaptorName, jobIdentifier, endTime);

        try {
            // Retrieve the filesystem that goes with the scheduler and resolve the workdir if needed.
            Path workdir = processPath(workingDirectory, description.getWorkingDirectory());

            if (!filesystem.exists(workdir)) {
                throw new IOException("Working directory " + workdir + " does not exist!");
            }

            if (interactive) {
                LOGGER.debug("{}/{} starting interactive process", adaptorName, jobIdentifier);
                InteractiveProcess p = factory.createInteractiveProcess(description, workdir.toString(), jobIdentifier, startupTimeout);
                setStreams(p.getStreams());
                process = p;
            } else {
                LOGGER.debug("{}/{} starting batch process", adaptorName, jobIdentifier);
                process = new BatchProcess(filesystem, workdir, description, jobIdentifier, factory, startupTimeout);
            }
        } catch (XenonException e) {
            LOGGER.debug("{}/{} ERROR {}", adaptorName, jobIdentifier, e);
            updateState(ERROR_STATE, -1, e);
            return;
        } catch (IOException e) {
            LOGGER.debug("{}/{} ERROR {}", adaptorName, jobIdentifier, e);
            updateState(ERROR_STATE, -1, new XenonException(adaptorName, "Error starting job.", e));
            return;
        }

        updateState(RUNNING_STATE, -1, null);

        LOGGER.debug("{}/{} RUNNING");

        while (true) {

            if (process.isDone()) {
                LOGGER.debug("{}/{} DONE with exit {}", process.getExitStatus());
                updateState(DONE_STATE, process.getExitStatus(), null);
                return;
            }

            if (getKilled()) {
                // Destroy first, update state last, otherwise we have a race condition!
                LOGGER.debug("{}/{} KILLED (after start)");
                process.destroy();
                updateState(KILLED_STATE, -1, new JobCanceledException(adaptorName, "Process cancelled by user."));
                return;
            }

            if (maxTime > 0 && System.currentTimeMillis() > endTime) {
                // Destroy first, update state last, otherwise we have a race condition!
                LOGGER.debug("{}/{} KILLED (ran out of time)");
                process.destroy();
                updateState(KILLED_STATE, -1, new JobCanceledException(adaptorName, "Process timed out."));
                return;
            }

            clearUpdateRequest();

            sleep(pollingDelay);

            LOGGER.trace("{}/{} polling");
        }
    }

}

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.esciencecenter.xenon.XenonException;
import nl.esciencecenter.xenon.schedulers.JobDescription;
import nl.esciencecenter.xenon.schedulers.JobStatus;
import nl.esciencecenter.xenon.schedulers.Scheduler;
import nl.esciencecenter.xenon.schedulers.Streams;
import nl.esciencecenter.xenon.utils.InputWriter;
import nl.esciencecenter.xenon.utils.OutputReader;

/**
 * Runs a command. Constructor waits for command to finish.
 *
 */
public class RemoteCommandRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteCommandRunner.class);

    private final int exitCode;

    private final String output;

    private final String error;

    /**
     * Run a command remotely, and save stdout, stderr, and exit code for later processing.
     *
     * @param scheduler
     *            the scheduler to submit the job to
     * @param stdin
     *            input to feed to the command
     * @param executable
     *            command to run
     * @param arguments
     *            arguments for the command
     * @throws XenonException
     *             if the job could not be run successfully.
     */
    public RemoteCommandRunner(Scheduler scheduler, String stdin, String executable, String... arguments) throws XenonException {
        long start = System.currentTimeMillis();

        JobDescription description = new JobDescription();
        description.setExecutable(executable);
        description.setArguments(arguments);
        description.setQueueName("unlimited");

        Streams streams = scheduler.submitInteractiveJob(description);

        InputWriter in = new InputWriter(stdin, streams.getStdin());

        // we must always read the output and error streams to avoid deadlocks
        OutputReader out = new OutputReader(streams.getStdout());
        OutputReader err = new OutputReader(streams.getStderr());

        in.waitUntilFinished();
        out.waitUntilFinished();
        err.waitUntilFinished();

        JobStatus status = scheduler.getJobStatus(streams.getJobIdentifier());

        if (!status.isDone()) {
            status = scheduler.waitUntilDone(streams.getJobIdentifier(), 0);
        }

        if (status.hasException()) {
            throw new XenonException(scheduler.getAdaptorName(), "Could not run command remotely", status.getException());
        }

        this.exitCode = status.getExitCode();
        this.output = out.getResultAsString();
        this.error = err.getResultAsString();

        long runtime = System.currentTimeMillis() - start;

        LOGGER.debug("Remote command took {} ms, executable = {}, arguments = {}, exitcode = {}", runtime, executable, arguments, exitCode);
        LOGGER.debug("Remote command stdout:\n--output below this line--\n{}--end of output--", output);
        LOGGER.debug("Remote command stderr:\n--output below this line--\n{}--end of output--", error);
    }

    public String getStdout() {
        return output;
    }

    public String getStderr() {
        return error;
    }

    public int getExitCode() {
        return exitCode;
    }

    public boolean success() {
        return exitCode == 0 && error.isEmpty();
    }

    public boolean successIgnoreError() {
        return exitCode == 0;
    }

    public String toString() {
        return "CommandRunner[exitCode=" + exitCode + ",output=" + output + ",error=" + error + "]";
    }
}

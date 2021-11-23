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
package nl.esciencecenter.xenon.adaptors.schedulers.local;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.esciencecenter.xenon.XenonException;
import nl.esciencecenter.xenon.utils.InputWriter;
import nl.esciencecenter.xenon.utils.OutputReader;

/**
 * Runs a command. Constructor waits for command to finish.
 *
 */
public class CommandRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRunner.class);

    private final int exitCode;

    private final OutputReader out;

    private final OutputReader err;

    // determine location of exe file using path, will return given location if
    // not found in path
    private static String getExeFile(String exe) {
        String path = System.getenv("PATH");

        if (path != null) {
            for (String pathElement : path.split(File.pathSeparator)) {
                if (!pathElement.isEmpty()) {
                    String candidateLocation = pathElement + File.separator + exe;
                    File file = new File(candidateLocation);
                    if (file.canExecute()) {
                        return candidateLocation;
                    }
                } else {
                    // special case empty path element
                    File f = new File(exe);
                    if (f.canExecute()) {
                        return exe;
                    }
                }
            }
        }
        return exe;
    }

    public CommandRunner(String... command) throws XenonException {
        this(null, null, command);
    }

    public CommandRunner(String stdin, File workingDir, String... command) throws CommandNotFoundException {

        if (command.length == 0) {
            throw new IllegalArgumentException("runCommand: command array has length 0");
        }

        // expand command using path
        command[0] = getExeFile(command[0]);

        LOGGER.debug("CommandRunner running {}", Arrays.toString(command));

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDir != null) {
            builder.directory(workingDir);
        }
        java.lang.Process p;
        try {
            p = builder.start();
        } catch (IOException e) {
            throw new CommandNotFoundException(getClass().getName(), "CommandRunner cannot run command " + Arrays.toString(command), e);
        }

        // write given content to stdin of process
        new InputWriter((stdin == null ? "" : stdin), p.getOutputStream());

        // we must always read the output and error streams to avoid deadlocks
        out = new OutputReader(p.getInputStream());
        err = new OutputReader(p.getErrorStream());

        int exit = 0;

        try {
            exit = p.waitFor();

            out.waitUntilFinished();
            err.waitUntilFinished();

            LOGGER.debug("exit:{}", exit);
            LOGGER.debug("stdout:\n```\n{}```", out.getResultAsString());
            LOGGER.debug("stderr:\n```\n{}```", err.getResultAsString());

        } catch (InterruptedException e) {
            LOGGER.warn("CommandRunner was interrupted before termination!");
            Thread.currentThread().interrupt();
            exit = -1;
        }

        exitCode = exit;
    }

    public String getStdout() {
        return out.getResultAsString();
    }

    public String getStderr() {
        return err.getResultAsString();
    }

    public int getExitCode() {
        return exitCode;
    }

}

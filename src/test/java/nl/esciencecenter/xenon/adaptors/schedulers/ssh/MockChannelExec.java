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
package nl.esciencecenter.xenon.adaptors.schedulers.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.channel.PtyChannelConfigurationHolder;
import org.apache.sshd.common.future.SshFutureListener;

public class MockChannelExec extends ChannelExec {

    public boolean closed = false;

    public String command;

    public boolean closeThrows = false;

    public HashMap<String, Object> env = new HashMap<>();

    public MockChannelExec(String command, PtyChannelConfigurationHolder configHolder, Map<String, ?> env) {
        super(command, configHolder, env);
        this.command = command;
    }

    @Override
    public Integer getExitStatus() {
        return 42;
    }

    @Override
    public Object setEnv(String key, Object value) {
        return env.put(key, value);
    }

    @Override
    public InputStream getInvertedOut() {
        return null;
    }

    @Override
    public OutputStream getInvertedIn() {
        return null;
    }

    @Override
    public InputStream getInvertedErr() {
        return null;
    }

    @Override
    public OpenFuture open() throws IOException {

        return new OpenFuture() {

            @Override
            public OpenFuture verify(long timeoutMillis) throws IOException {
                return null;
            }

            @Override
            public boolean isDone() {
                return closed;
            }

            @Override
            public boolean awaitUninterruptibly(long timeoutMillis) {
                return true;
            }

            @Override
            public Object getId() {
                return null;
            }

            @Override
            public boolean await(long timeoutMillis) throws IOException {
                return true;
            }

            @Override
            public OpenFuture removeListener(SshFutureListener<OpenFuture> listener) {
                return null;
            }

            @Override
            public OpenFuture addListener(SshFutureListener<OpenFuture> listener) {
                return null;
            }

            @Override
            public void setOpened() {
            }

            @Override
            public void setException(Throwable exception) {
            }

            @Override
            public boolean isOpened() {
                return true;
            }

            @Override
            public boolean isCanceled() {
                return false;
            }

            @Override
            public Throwable getException() {
                return null;
            }

            @Override
            public void cancel() {
            }
        };

    }

}

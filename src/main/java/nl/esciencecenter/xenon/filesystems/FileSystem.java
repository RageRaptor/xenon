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
package nl.esciencecenter.xenon.filesystems;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import nl.esciencecenter.xenon.InvalidCredentialException;
import nl.esciencecenter.xenon.InvalidLocationException;
import nl.esciencecenter.xenon.InvalidPropertyException;
import nl.esciencecenter.xenon.UnknownAdaptorException;
import nl.esciencecenter.xenon.UnknownPropertyException;
import nl.esciencecenter.xenon.UnsupportedOperationException;
import nl.esciencecenter.xenon.XenonException;
import nl.esciencecenter.xenon.adaptors.AdaptorLoader;
import nl.esciencecenter.xenon.adaptors.NotConnectedException;
import nl.esciencecenter.xenon.adaptors.XenonProperties;
import nl.esciencecenter.xenon.adaptors.filesystems.FileAdaptor;
import nl.esciencecenter.xenon.credentials.Credential;
import nl.esciencecenter.xenon.credentials.DefaultCredential;
import nl.esciencecenter.xenon.utils.DaemonThreadFactory;

/**
 * FileSystem represent a (possibly remote) file system that can be used to access data.
 */
public abstract class FileSystem implements AutoCloseable {

    private static FileAdaptor getAdaptorByName(String adaptorName) throws UnknownAdaptorException {
        return AdaptorLoader.getFileAdaptor(adaptorName);
    }

    /**
     * Gives a list names of the available adaptors.
     *
     * @return the list
     */
    public static String[] getAdaptorNames() {
        return AdaptorLoader.getFileAdaptorNames();
    }

    /**
     * Gives the description of the adaptor with the given name.
     *
     * @param adaptorName
     *            the type of file system to connect to (e.g. "sftp" or "webdav")
     * @return the description
     * @throws UnknownAdaptorException
     *             If the adaptor name is absent in {@link #getAdaptorNames()}.
     */
    public static FileSystemAdaptorDescription getAdaptorDescription(String adaptorName) throws UnknownAdaptorException {
        return getAdaptorByName(adaptorName);
    }

    /**
     * Gives a list of the descriptions of the available adaptors.
     *
     * @return the list
     */
    public static FileSystemAdaptorDescription[] getAdaptorDescriptions() {
        return AdaptorLoader.getFileAdaptorDescriptions();
    }

    /**
     * CopyStatus contains status information for a specific copy operation.
     */
    static class CopyStatusImplementation implements CopyStatus {

        private final String copyIdentifier;
        private final String state;
        private final XenonException exception;

        private final long bytesToCopy;
        private final long bytesCopied;

        public CopyStatusImplementation(String copyIdentifier, String state, long bytesToCopy, long bytesCopied, XenonException exception) {
            super();
            this.copyIdentifier = copyIdentifier;
            this.state = state;
            this.bytesToCopy = bytesToCopy;
            this.bytesCopied = bytesCopied;
            this.exception = exception;
        }

        @Override
        public String getCopyIdentifier() {
            return copyIdentifier;
        }

        @Override
        public String getState() {
            return state;
        }

        @Override
        public XenonException getException() {
            return exception;
        }

        @Override
        public void maybeThrowException() throws XenonException {
            if (hasException()) {
                throw getException();
            }
        }

        @Override
        public boolean isRunning() {
            return "RUNNING".equals(state);
        }

        @Override
        public boolean isDone() {
            return "DONE".equals(state) || "FAILED".equals(state);
        }

        @Override
        public boolean hasException() {
            return exception != null;
        }

        @Override
        public long bytesToCopy() {
            return bytesToCopy;
        }

        @Override
        public long bytesCopied() {
            return bytesCopied;
        }

        @Override
        public String toString() {
            return "CopyStatus [copyIdentifier=" + copyIdentifier + ", state=" + state + ", exception=" + exception + ", bytesToCopy=" + bytesToCopy
                    + ", bytesCopied=" + bytesCopied + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            CopyStatusImplementation that = (CopyStatusImplementation) o;
            return bytesToCopy == that.bytesToCopy && bytesCopied == that.bytesCopied && Objects.equals(copyIdentifier, that.copyIdentifier)
                    && Objects.equals(state, that.state) && Objects.equals(exception, that.exception);
        }

        @Override
        public int hashCode() {
            return Objects.hash(copyIdentifier, state, exception, bytesToCopy, bytesCopied);
        }
    }

    /**
     * Create a new FileSystem using the <code>adaptor</code> that connects to a data store at <code>location</code> using the <code>credentials</code> to get
     * access. Use <code>properties</code> to (optionally) configure the FileSystem when it is created.
     *
     * Make sure to always close {@code FileSystem} instances by calling {@code close(FileSystem)} when you no longer need them, otherwise their associated
     * resources remain allocated.
     *
     * @see <a href="../../../../overview-summary.html#filesystems">Documentation on the supported adaptors and locations.</a>
     *
     * @param adaptor
     *            the type of file system to connect to (e.g. "sftp" or "webdav")
     * @param location
     *            the location of the FileSystem.
     * @param credential
     *            the Credentials to use to get access to the FileSystem.
     * @param properties
     *            optional properties to use when creating the FileSystem.
     *
     * @return the new FileSystem.
     *
     * @throws UnknownPropertyException
     *             If a unknown property was provided.
     * @throws InvalidPropertyException
     *             If a known property was provided with an invalid value.
     * @throws UnknownAdaptorException
     *             If the adaptor was invalid.
     * @throws InvalidLocationException
     *             If the location was invalid.
     * @throws InvalidCredentialException
     *             If the credential is invalid to access the location.
     *
     * @throws XenonException
     *             If the creation of the FileSystem failed.
     * @throws IllegalArgumentException
     *             If adaptor is null.
     */
    public static FileSystem create(String adaptor, String location, Credential credential, Map<String, String> properties) throws XenonException {
        return getAdaptorByName(adaptor).createFileSystem(location, credential, properties);
    }

    /**
     * Create a new FileSystem using the <code>adaptor</code> that connects to a data store at <code>location</code> using the <code>credentials</code> to get
     * access.
     *
     * Make sure to always close {@code FileSystem} instances by calling {@code close(FileSystem)} when you no longer need them, otherwise their associated
     * resources remain allocated.
     *
     * @see <a href="../../../../overview-summary.html#filesystems">Documentation on the supported adaptors and locations.</a>
     *
     * @param adaptor
     *            the type of file system to connect to (e.g. "sftp" or "webdav")
     * @param location
     *            the location of the FileSystem.
     * @param credential
     *            the Credentials to use to get access to the FileSystem.
     *
     * @return the new FileSystem.
     *
     * @throws UnknownPropertyException
     *             If a unknown property was provided.
     * @throws InvalidPropertyException
     *             If a known property was provided with an invalid value.
     * @throws UnknownAdaptorException
     *             If the adaptor was invalid.
     * @throws InvalidLocationException
     *             If the location was invalid.
     * @throws InvalidCredentialException
     *             If the credential is invalid to access the location.
     * @throws XenonException
     *             If the creation of the FileSystem failed.
     * @throws IllegalArgumentException
     *             If adaptor is null.
     */
    public static FileSystem create(String adaptor, String location, Credential credential) throws XenonException {
        return create(adaptor, location, credential, new HashMap<>(0));
    }

    /**
     * Create a new FileSystem using the <code>adaptor</code> that connects to a data store at <code>location</code> using the default credentials to get
     * access.
     *
     * Make sure to always close {@code FileSystem} instances by calling {@code close(FileSystem)} when you no longer need them, otherwise their associated
     * resources remain allocated.
     *
     * @see <a href="../../../../overview-summary.html#filesystems">Documentation on the supported adaptors and locations.</a>
     *
     * @param adaptor
     *            the type of file system to connect to (e.g. "sftp" or "webdav")
     * @param location
     *            the location of the FileSystem.
     *
     * @return the new FileSystem.
     *
     * @throws UnknownPropertyException
     *             If a unknown property was provided.
     * @throws InvalidPropertyException
     *             If a known property was provided with an invalid value.
     * @throws UnknownAdaptorException
     *             If the adaptor was invalid.
     * @throws InvalidLocationException
     *             If the location was invalid.
     * @throws InvalidCredentialException
     *             If the credential is invalid to access the location.
     *
     * @throws XenonException
     *             If the creation of the FileSystem failed.
     * @throws IllegalArgumentException
     *             If adaptor is null.
     */
    public static FileSystem create(String adaptor, String location) throws XenonException {
        return create(adaptor, location, new DefaultCredential());
    }

    /**
     * Create a new FileSystem using the <code>adaptor</code> that connects to a data store at the default location using the default credentials to get access.
     *
     * Note that there are very few filesystem adaptors that support a default location. The local filesystem adaptor is the prime example.
     *
     * Make sure to always close {@code FileSystem} instances by calling {@code close(FileSystem)} when you no longer need them, otherwise their associated
     * resources remain allocated.
     *
     * @see <a href="overview-summary.html#filesystems">Documentation on the supported adaptors and locations.</a>
     *
     * @param adaptor
     *            the type of file system to connect to (e.g. "sftp" or "webdav")
     *
     * @return the new FileSystem.
     *
     * @throws UnknownPropertyException
     *             If a unknown property was provided.
     * @throws InvalidPropertyException
     *             If a known property was provided with an invalid value.
     * @throws UnknownAdaptorException
     *             If the adaptor was invalid.
     * @throws InvalidLocationException
     *             If the location was invalid.
     * @throws InvalidCredentialException
     *             If the credential is invalid to access the location.
     *
     * @throws XenonException
     *             If the creation of the FileSystem failed.
     * @throws IllegalArgumentException
     *             If adaptor is null.
     */
    public static FileSystem create(String adaptor) throws XenonException {
        return create(adaptor, null);
    }

    class CopyCallback {

        private long bytesToCopy = 0;
        private long bytesCopied = 0;

        private boolean started = false;
        private boolean cancelled = false;

        synchronized void start(long bytesToCopy) {
            if (!started) {
                started = true;
                this.bytesToCopy = bytesToCopy;
            }
        }

        synchronized boolean isStarted() {
            return started;
        }

        synchronized long getBytesCopied() {
            return bytesCopied;
        }

        synchronized long getBytesToCopy() {
            return bytesToCopy;
        }

        synchronized void addBytesCopied(long bytes) {
            this.bytesCopied += bytes;
        }

        synchronized void cancel() {
            cancelled = true;
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }
    }

    private class PendingCopy {

        Future<Void> future;
        CopyCallback callback;

        public PendingCopy(Future<Void> future, CopyCallback callback) {
            super();
            this.future = future;
            this.callback = callback;
        }
    }

    private final String uniqueID;
    private final String adaptor;
    private final String location;
    private final Credential credential;
    private final XenonProperties properties;
    private final ExecutorService pool;

    private Path workingDirectory;

    private long nextCopyID = 0;

    private int bufferSize;

    private final HashMap<String, PendingCopy> pendingCopies = new HashMap<>();

    protected FileSystem(String uniqueID, String adaptor, String location, Credential credential, Path workDirectory, int bufferSize,
            XenonProperties properties) {

        if (uniqueID == null) {
            throw new IllegalArgumentException("Identifier may not be null!");
        }

        if (adaptor == null) {
            throw new IllegalArgumentException("Adaptor may not be null!");
        }

        if (location == null) {
            throw new IllegalArgumentException("Location may not be null!");
        }

        if (credential == null) {
            throw new IllegalArgumentException("Credential may not be null!");
        }

        if (workDirectory == null) {
            throw new IllegalArgumentException("EntryPath may not be null!");
        }

        if (bufferSize <= 0) {
            throw new IllegalArgumentException("Buffer size may not be 0 or smaller!");
        }

        this.uniqueID = uniqueID;
        this.adaptor = adaptor;
        this.location = location;
        this.credential = credential;
        this.workingDirectory = workDirectory;
        this.properties = properties;
        this.bufferSize = bufferSize;
        this.pool = Executors.newFixedThreadPool(1, new DaemonThreadFactory("CopyThread." + uniqueID));
    }

    protected int getBufferSize() {
        return bufferSize;
    }

    private synchronized String getNextCopyID() {
        return "COPY-" + getAdaptorName() + "-" + nextCopyID++;
    }

    /**
     * Get the name of the adaptor that created this FileSystem.
     *
     * @return the name of the adaptor.
     */
    public String getAdaptorName() {
        return adaptor;
    }

    /**
     * Get the location of the FileSystem.
     *
     * @return the location of the FileSystem.
     */
    public String getLocation() {
        return location;
    }

    /**
     * Get the credential that this FileSystem is using.
     *
     * @return the credential this FileSystem is using.
     */
    public Credential getCredential() {
        return credential;
    }

    /**
     * Get the properties used to create this FileSystem.
     *
     * @return the properties used to create this FileSystem.
     */
    public Map<String, String> getProperties() {
        return properties.toMap();
    }

    /**
     * Get the current working directory of this file system.
     *
     * All relative paths provided to FileSystem methods are resolved against this current working directory.
     *
     * The current working directory is set when a FileSystem is created using the path specified in the location. If no path is specified in the location, an
     * adaptor specific default path is used, for example <code>"/home/username"</code>.
     *
     * @return the current working directory of this file system.
     */
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Get the path separator used by this file system.
     *
     * The path separator is set when a FileSystem is created.
     *
     * @return the path separator used by this file system.
     */
    public String getPathSeparator() {
        return "" + workingDirectory.getSeparator();
    }

    /**
     * Set the current working directory of this file system to <code>directory</code>.
     *
     * The provided <code>directory</code> must exist and be a directory. Both an absolute or relative path may be provided. In the latter case, the path will
     * be resolved against the current working directory.
     *
     * @param directory
     *            a path to which the current working directory must be set.
     * @throws NoSuchPathException
     *             if the <code>directory</code> does not exist
     * @throws InvalidPathException
     *             if <code>directory</code> is not a directory
     * @throws NotConnectedException
     *             if file system is closed.
     * @throws IllegalArgumentException
     *             if the argument is null.
     * @throws XenonException
     *             if an I/O error occurred
     */
    public void setWorkingDirectory(Path directory) throws XenonException {

        Path wd = toAbsolutePath(directory);

        assertDirectoryExists(wd);

        workingDirectory = wd;
    }

    /**
     * Close this FileSystem. If the adaptor does not support closing this is a no-op.
     *
     * @throws XenonException
     *             If the FileSystem failed to close or if an I/O error occurred.
     */
    public void close() throws XenonException {
        try {
            pool.shutdownNow();
        } catch (Exception e) {
            throw new XenonException(getAdaptorName(), "Failed to cleanly shutdown copy thread pool");
        }
    }

    /**
     * Return if the connection to the FileSystem is open. An adaptor which does not support closing is always open.
     *
     * @throws XenonException
     *             if the test failed or an I/O error occurred.
     * @return if the connection to the FileSystem is open.
     */
    public abstract boolean isOpen() throws XenonException;

    /**
     * Rename an existing source path to a non-existing target path (optional operation).
     * <p>
     *
     * This method only implements a <em>rename</em> operation, not a <em>move</em> operation. Hence, this method will not copy files and should return (almost)
     * instantaneously.
     *
     * The parent of the target path (e.g. <code>target.getParent</code>) must exist.
     *
     * If the target is equal to the source this method has no effect.
     *
     * If the source is a link, the link itself will be renamed, not the path to which it refers.
     *
     * If the source is a directory, it will be renamed to the target. This implies that a moving a directory between physical locations may fail.
     * </p>
     *
     * @param source
     *            the existing source path.
     * @param target
     *            the non existing target path.
     *
     * @throws UnsupportedOperationException
     *             If the adapter does not support renaming.
     * @throws NoSuchPathException
     *             If the source file does not exist or the target parent directory does not exist.
     * @throws PathAlreadyExistsException
     *             If the target file already exists.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             If the move failed.
     * @throws IllegalArgumentException
     *             If one or both of the arguments are null.
     */
    public abstract void rename(Path source, Path target) throws XenonException;

    /**
     * Creates a new directory, failing if the directory already exists. All nonexistent parent directories are also created.
     *
     * @param dir
     *            the directory to create.
     *
     * @throws PathAlreadyExistsException
     *             If the directory already exists or if a parent directory could not be created because a file with the same name already exists.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             If an I/O error occurred.
     * @throws IllegalArgumentException
     *             If one or both of the arguments are null.
     */
    public void createDirectories(Path dir) throws XenonException {

        Path absolute = toAbsolutePath(dir);

        Path parent = absolute.getParent();

        if (parent != null && !exists(parent)) {
            // Recursive call
            createDirectories(parent);
        }

        createDirectory(absolute);
    }

    /**
     * Creates a new directory, failing if the directory already exists.
     *
     * The parent directory of the file must already exists.
     *
     * @param dir
     *            the directory to create.
     *
     * @throws PathAlreadyExistsException
     *             If the directory already exists.
     * @throws NoSuchPathException
     *             If the parent directory does not exist.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             If an I/O error occurred.
     * @throws IllegalArgumentException
     *             If the argument is null.
     *
     */
    public abstract void createDirectory(Path dir) throws XenonException;

    /**
     * Creates a new empty file, failing if the file already exists.
     *
     * The parent directory of the file must already exists.
     *
     * @param file
     *            a path referring to the file to create.
     *
     * @throws PathAlreadyExistsException
     *             If the file already exists.
     * @throws NoSuchPathException
     *             If the parent directory does not exist.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             If an I/O error occurred.
     * @throws IllegalArgumentException
     *             If one or both of the arguments are null.
     */
    public abstract void createFile(Path file) throws XenonException;

    /**
     * Creates a new symbolic link, failing if the link already exists (optional operation).
     *
     * The target is taken as is. It may be absolute, relative path and/or non-normalized path and may or may not exist.
     *
     * @param link
     *            the symbolic link to create.
     * @param target
     *            the target the symbolic link should refer to.
     *
     * @throws PathAlreadyExistsException
     *             If the link already exists.
     * @throws NoSuchPathException
     *             If the target or parent directory of link does not exist
     * @throws InvalidPathException
     *             If parent of link is not a directory
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             If an I/O error occurred.
     * @throws IllegalArgumentException
     *             If one or both of the arguments are null.
     */
    public abstract void createSymbolicLink(Path link, Path target) throws XenonException;

    /**
     * Deletes an existing path.
     *
     * If path is a symbolic link the symbolic link is removed and the symbolic link's target is not deleted.
     *
     * If the path is a directory and <code>recursive</code> is set to true, the contents of the directory will also be deleted. If <code>recursive</code> is
     * set to <code>false</code>, a directory will only be removed if it is empty.
     *
     * @param path
     *            the path to delete.
     * @param recursive
     *            if the delete must be done recursively
     * @throws DirectoryNotEmptyException
     *             if the directory was not empty (and the delete was not recursive).
     * @throws NoSuchPathException
     *             if the provided path does not exist.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If path is null.
     */
    public void delete(Path path, boolean recursive) throws XenonException {

        Path absPath = toAbsolutePath(path);

        assertPathExists(absPath);

        if (getAttributes(absPath).isDirectory()) {

            Iterable<PathAttributes> itt = list(absPath, false);

            if (recursive) {
                for (PathAttributes p : itt) {
                    delete(p.getPath(), true);
                }
            } else {
                if (itt.iterator().hasNext()) {
                    throw new DirectoryNotEmptyException(getAdaptorName(), "Directory not empty: " + absPath.toString());
                }
            }

            deleteDirectory(absPath);
        } else {
            deleteFile(absPath);
        }
    }

    /**
     * Tests if a path exists.
     *
     * @param path
     *            the path to test.
     *
     * @return If the path exists.
     *
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If path is null.
     */
    public abstract boolean exists(Path path) throws XenonException;

    /**
     * List all entries in the directory <code>dir</code>.
     *
     * All entries in the directory are returned, but subdirectories will not be traversed by default. Set <code>recursive</code> to <code>true</code>, include
     * the listing of all subdirectories.
     *
     * Symbolic links are not followed.
     *
     * @param dir
     *            the target directory.
     * @param recursive
     *            should the list recursively traverse the subdirectories ?
     *
     * @return a {@link List} of {@link PathAttributes} that iterates over all entries in the directory <code>dir</code>.
     *
     * @throws NoSuchPathException
     *             If a directory does not exists.
     * @throws InvalidPathException
     *             If <code>dir</code> is not a directory.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If path is null.
     */
    public Iterable<PathAttributes> list(Path dir, boolean recursive) throws XenonException {

        Path absolute = toAbsolutePath(dir);

        assertDirectoryExists(dir);

        ArrayList<PathAttributes> result = new ArrayList<>();
        list(absolute, result, recursive);
        return result;
    }

    /**
     * Open an existing file and return an {@link InputStream} to read from this file.
     *
     * @param file
     *            the to read.
     *
     * @return the {@link InputStream} to read from the file.
     *
     * @throws NoSuchPathException
     *             If the file does not exists.
     * @throws InvalidPathException
     *             If the file is not regular file.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If path is null.
     */
    public abstract InputStream readFromFile(Path file) throws XenonException;

    /**
     * Open a file and return an {@link OutputStream} to write to this file.
     * <p>
     *
     * The size of the file (once all data has been written) must be specified using the <code>size</code> parameter. This is required by some implementations
     * (typically blob-stores).
     *
     * </p>
     *
     * @param path
     *            the target file for the OutputStream.
     * @param size
     *            the size of the file once fully written.
     *
     * @return the {@link OutputStream} to write to the file.
     *
     * @throws PathAlreadyExistsException
     *             If the target existed.
     * @throws NoSuchPathException
     *             if a parent directory does not exist.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If path is null.
     */
    public abstract OutputStream writeToFile(Path path, long size) throws XenonException;

    /**
     * Open a file and return an {@link OutputStream} to write to this file. (optional operation)
     * <p>
     * If the file already exists it will be replaced and its data will be lost.
     *
     * The amount of data that will be written to the file is not specified in advance. This operation may not be supported by all implementations.
     *
     * </p>
     *
     * @param file
     *            the target file for the OutputStream.
     *
     * @return the {@link OutputStream} to write to the file.
     *
     *
     * @throws PathAlreadyExistsException
     *             If the target existed.
     * @throws NoSuchPathException
     *             if a parent directory does not exist.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If path is null.
     */
    public abstract OutputStream writeToFile(Path file) throws XenonException;

    /**
     * Open an existing file and return an {@link OutputStream} to append data to this file. (optional operation)
     * <p>
     * If the file does not exist, an exception will be thrown.
     *
     * This operation may not be supported by all implementations.
     *
     * </p>
     *
     * @param file
     *            the target file for the OutputStream.
     *
     * @return the {@link OutputStream} to write to the file.
     *
     * @throws PathAlreadyExistsException
     *             If the target existed.
     * @throws NoSuchPathException
     *             if a parent directory does not exist.
     * @throws InvalidPathException
     *             if not a regular file
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If path is null.
     * @throws UnsupportedOperationException
     *             if the adaptor does not support appending
     */
    public abstract OutputStream appendToFile(Path file) throws XenonException;

    /**
     * Get the {@link PathAttributes} of an existing path.
     *
     * @param path
     *            the existing path.
     *
     * @return the FileAttributes of the path.
     *
     * @throws NoSuchPathException
     *             If the file does not exists.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If path is null.
     */
    public abstract PathAttributes getAttributes(Path path) throws XenonException;

    /**
     * Reads the target of a symbolic link (optional operation).
     *
     * @param link
     *            the link to read.
     *
     * @return a Path representing the target of the link.
     *
     * @throws NoSuchPathException
     *             If the link does not exists.
     * @throws InvalidPathException
     *             If the source is not a link.
     * @throws UnsupportedOperationException
     *             If this FileSystem does not support symbolic links.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If path is null.
     */
    public abstract Path readSymbolicLink(Path link) throws XenonException;

    /**
     * Sets the POSIX permissions of a path (optional operation).
     *
     * @param path
     *            the target path.
     * @param permissions
     *            the permissions to set.
     *
     * @throws NoSuchPathException
     *             If the target path does not exists.
     * @throws UnsupportedOperationException
     *             If this FileSystem does not support symbolic links.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If path is null.
     */
    public abstract void setPosixFilePermissions(Path path, Set<PosixFilePermission> permissions) throws XenonException;

    /**
     * Convert the provided path to an absolute path by (if necessary) resolving a relative path against the working directory of this FileSystem. The resulting
     * path is also normalized.
     *
     * @param path
     *            the path to convert
     * @throws IllegalArgumentException
     *             if path is null.
     * @return an absolute path
     */
    protected Path toAbsolutePath(Path path) {

        assertNotNull(path);

        if (path.isAbsolute()) {
            return path.normalize();
        }

        return workingDirectory.resolve(path).normalize();
    }

    /**
     * Copy data from <code>in</code> to <code>out</code> using a buffer size of <code>buffersize</code>.
     *
     * After each <code>buffersize</code> block of data, <code>callback.addBytesCopied</code> will be invoked to report the number of bytes copied and
     * <code>callback.isCancelled</code> will be invoked to determine if the copy should continue.
     *
     * @param in
     *            the stream to copy the data from.
     * @param out
     *            the stream to copy the data to.
     * @param buffersize
     *            the buffer size to use for copying.
     * @param callback
     *            the callback to report bytes copied to and check cancellation from.
     * @throws IOException
     *             if an I/O exception occurred.
     * @throws CopyCancelledException
     *             if the copy was cancelled by the user.
     */
    protected void streamCopy(InputStream in, OutputStream out, int buffersize, CopyCallback callback) throws IOException, CopyCancelledException {

        byte[] buffer = new byte[buffersize];

        int size = in.read(buffer);

        while (size > 0) {
            out.write(buffer, 0, size);

            callback.addBytesCopied(size);

            if (callback.isCancelled()) {
                throw new CopyCancelledException(getAdaptorName(), "Copy cancelled by user");
            }

            size = in.read(buffer);
        }

        // Flush the output to ensure all data is written when this method returns.
        out.flush();
    }

    /**
     * Copy a symbolic link to another file system (optional operation).
     *
     * This is a blocking copy operation. It only returns once the link has been copied or the copy has failed.
     *
     * This operation may be re-implemented by the various implementations of FileSystem.
     *
     * This default implementation is based on a creating a new link on the destination filesystem. Note that the file the link is referring to is not copied.
     * Only the link itself is copied.
     *
     * @param source
     *            the link to copy.
     * @param destinationFS
     *            the destination {@link FileSystem} to copy to.
     * @param destination
     *            the destination link on the destination file system.
     * @param mode
     *            selects what should happen if the destination link already exists
     * @param callback
     *            a {@link CopyCallback} used to update the status of the copy, or cancel it while in progress.
     *
     * @throws InvalidPathException
     *             if the provide source is not a link.
     * @throws NoSuchPathException
     *             if the source link does not exist or the destination parent directory does not exist.
     * @throws PathAlreadyExistsException
     *             if the destination link already exists.
     * @throws UnsupportedOperationException
     *             if the destination FileSystem does not support symbolic links.
     * @throws XenonException
     *             if the link could not be copied.
     */
    protected void copySymbolicLink(Path source, FileSystem destinationFS, Path destination, CopyMode mode, CopyCallback callback) throws XenonException {

        PathAttributes attributes = getAttributes(source);

        if (!attributes.isSymbolicLink()) {
            throw new InvalidPathException(getAdaptorName(), "Source is not a regular file: " + source);
        }

        destinationFS.assertParentDirectoryExists(destination);

        if (destinationFS.exists(destination)) {
            switch (mode) {
            case CREATE:
                throw new PathAlreadyExistsException(getAdaptorName(), "Destination path already exists: " + destination);
            case IGNORE:
                return;
            case REPLACE:
                // continue
                break;
            }
        }

        Path target = readSymbolicLink(source);
        destinationFS.createSymbolicLink(destination, target);
    }

    /**
     * Copy a single file to another file system.
     *
     * This is a blocking copy operation. It only returns once the file has been copied or the copy has failed.
     *
     * This operation may be re-implemented by the various implementations of FileSystem. This default implementation is based on a simple stream based copy.
     *
     * @param source
     *            the file to copy.
     * @param destinationFS
     *            the destination {@link FileSystem} to copy to.
     * @param destination
     *            the destination file on the destination file system.
     * @param mode
     *            selects what should happen if the destination file already exists
     * @param callback
     *            a {@link CopyCallback} used to update the status of the copy, or cancel it while in progress.
     *
     * @throws InvalidPathException
     *             if the provide source is not a regular file.
     * @throws NoSuchPathException
     *             if the source file does not exist or the destination parent directory does not exist.
     * @throws PathAlreadyExistsException
     *             if the destination file already exists.
     * @throws XenonException
     *             If the file could not be copied.
     */
    protected void copyFile(Path source, FileSystem destinationFS, Path destination, CopyMode mode, CopyCallback callback) throws XenonException {

        PathAttributes attributes = getAttributes(source);

        if (!attributes.isRegular()) {
            throw new InvalidPathException(getAdaptorName(), "Source is not a regular file: " + source);
        }

        destinationFS.assertParentDirectoryExists(destination);

        if (destinationFS.exists(destination)) {
            switch (mode) {
            case CREATE:
                throw new PathAlreadyExistsException(getAdaptorName(), "Destination path already exists: " + destination);
            case IGNORE:
                return;
            case REPLACE:
                destinationFS.delete(destination, true);
                // continue
                break;
            }
        }

        if (callback.isCancelled()) {
            throw new CopyCancelledException(getAdaptorName(), "Copy cancelled by user");
        }

        try (InputStream in = readFromFile(source); OutputStream out = destinationFS.writeToFile(destination, attributes.getSize())) {
            streamCopy(in, out, bufferSize, callback);
        } catch (Exception e) {
            throw new XenonException(getAdaptorName(), "Stream copy failed", e);
        }

    }

    /**
     * Perform a (possibly) recursive copy from a path on this filesystem to a path on <code>destinationFS</code>.
     *
     * @param source
     *            the source path on this FileSystem.
     * @param destinationFS
     *            the destination FileSystem.
     * @param destination
     *            the destination path.
     * @param mode
     *            the copy mode that determines how to react if the destination already exists.
     * @param recursive
     *            should the copy be performed recursively ?
     * @param callback
     *            a {@link CopyCallback} used to return status information on the copy.
     * @throws XenonException
     *             if an error occurred.
     */
    protected void performCopy(Path source, FileSystem destinationFS, Path destination, CopyMode mode, boolean recursive, CopyCallback callback)
            throws XenonException {

        if (!exists(source)) {
            throw new NoSuchPathException(getAdaptorName(), "No such file " + source.toString());
        }

        PathAttributes attributes = getAttributes(source);

        // if (attributes.isRegular() || attributes.isSymbolicLink()) {
        if (attributes.isRegular()) {
            copyFile(source, destinationFS, destination, mode, callback);
            return;
        }

        if (attributes.isSymbolicLink()) {
            copySymbolicLink(source, destinationFS, destination, mode, callback);
            return;
        }

        if (!attributes.isDirectory()) {
            throw new InvalidPathException(getAdaptorName(), "Source path is not a file, link or directory: " + source);
        }

        if (!recursive) {
            throw new InvalidPathException(getAdaptorName(), "Source path is a directory: " + source);
        }

        // From here on we know the source is a directory. We should also check the destination type.
        if (destinationFS.exists(destination)) {

            switch (mode) {
            case CREATE:
                throw new PathAlreadyExistsException(getAdaptorName(), "Destination path already exists: " + destination);
            case IGNORE:
                return;
            case REPLACE:
                // continue
                break;
            }

            attributes = destinationFS.getAttributes(destination);

            if (attributes.isRegular() || attributes.isSymbolicLink()) {
                destinationFS.delete(destination, false);
                destinationFS.createDirectory(destination);
            } else if (!attributes.isDirectory()) {
                throw new InvalidPathException(getAdaptorName(), "Existing destination is not a file, link or directory: " + source);
            }
        } else {
            destinationFS.createDirectory(destination);
        }

        // We are now sure the target directory exists.
        copyRecursive(source, destinationFS, destination, mode, callback);
    }

    private void copyRecursive(Path source, FileSystem destinationFS, Path destination, CopyMode mode, CopyCallback callback) throws XenonException {
        long bytesToCopy = 0;
        Iterable<PathAttributes> listing = list(source, true);

        for (PathAttributes p : listing) {

            if (callback.isCancelled()) {
                throw new CopyCancelledException(getAdaptorName(), "Copy cancelled by user");
            }

            if (p.isDirectory() && !isDotDot(p.getPath())) {

                Path rel = source.relativize(p.getPath());
                Path dst = destination.resolve(rel);
                if (destinationFS.exists(dst)) {
                    if (destinationFS.getAttributes(dst).isDirectory()) {
                        switch (mode) {
                        case CREATE:
                            throw new PathAlreadyExistsException(getAdaptorName(), "Directory already exists: " + dst);
                        case REPLACE:
                            break; // leave directory
                        case IGNORE:
                            return; // ignore subdir
                        }
                    } else {
                        destinationFS.delete(dst, true);
                    }
                } else {
                    destinationFS.createDirectories(dst);
                }
            } else if (p.isRegular()) {
                bytesToCopy += p.getSize();
            }
        }

        callback.start(bytesToCopy);

        for (PathAttributes p : listing) {

            if (callback.isCancelled()) {
                throw new CopyCancelledException(getAdaptorName(), "Copy cancelled by user");
            }

            if (p.isRegular()) {

                Path rel = source.relativize(p.getPath());
                Path dst = destination.resolve(rel);

                copyFile(p.getPath(), destinationFS, dst, mode, callback);
            }
        }
    }

    /**
     * Delete a file. Is only called on existing files
     *
     * This operation must be implemented by the various implementations of FileSystem.
     *
     * @param file
     *            the file to remove
     * @throws InvalidPathException
     *             if the provided path is not a file.
     * @throws NoSuchPathException
     *             if the provided file does not exist.
     * @throws XenonException
     *             If the file could not be removed.
     */
    protected abstract void deleteFile(Path file) throws XenonException;

    /**
     * Delete an empty directory. Is only called on empty directories
     *
     * This operation can only delete empty directories (analogous to <code>rmdir</code> in Linux).
     *
     * This operation must be implemented by the various implementations of FileSystem.
     *
     * @param path
     *            the directory to remove
     * @throws InvalidPathException
     *             if the provided path is not a directory.
     * @throws NoSuchPathException
     *             if the provided path does not exist.
     * @throws XenonException
     *             If the directory could not be removed.
     */
    protected abstract void deleteDirectory(Path path) throws XenonException;

    /**
     * Return the list of entries in a directory.
     *
     * This operation is non-recursive; any subdirectories in <code>dir</code> will be returned as part of the list, but they will not be listed themselves.
     *
     * This operation must be implemented by the various implementations of FileSystem.
     *
     * @param dir
     *            the directory to list
     * @return a {@link Iterable} that iterates over all entries in <code>dir</code>
     * @throws XenonException
     *             If the list could not be retrieved.
     */
    protected abstract Iterable<PathAttributes> listDirectory(Path dir) throws XenonException;

    /**
     * Returns an (optionally recursive) listing of the entries in a directory <code>dir</code>.
     *
     * This is a generic implementation which relies on <code>listDirectory</code> to provide listings of individual directories.
     *
     * @param dir
     *            the directory to list.
     * @param list
     *            the list to which the directory entries will be added.
     * @param recursive
     *            if the listing should be done recursively.
     * @throws XenonException
     *             If the list could not be retrieved.
     */
    protected void list(Path dir, ArrayList<PathAttributes> list, boolean recursive) throws XenonException {

        Iterable<PathAttributes> tmp = listDirectory(dir);

        for (PathAttributes p : tmp) {
            if (!isDotDot(p.getPath())) {
                list.add(p);
            }
        }

        if (recursive) {
            for (PathAttributes current : tmp) {
                // traverse subdirs provided they are not "." or "..".
                if (current.isDirectory() && !isDotDot(current.getPath())) {
                    list(dir.resolve(current.getPath().getFileNameAsString()), list, true);
                }
            }
        }
    }

    /**
     * Asynchronously Copy an existing source path to a target path on a different file system.
     *
     * If the source path is a file, it will be copied to the destination file on the target file system.
     *
     * If the source path is a directory, it will only be copied if <code>recursive</code> is set to <code>true</code>. Otherwise, an exception will be thrown.
     * When copying recursively, the directory and its content (both files and subdirectories with content), will be copied to <code>destination</code>.
     *
     * Exceptions that occur during copying will not be thrown by this function, but instead are contained in a {@link CopyStatus} object which can be obtained
     * with {@link FileSystem#getStatus(String)}
     *
     * @param source
     *            the source path (on this filesystem) to copy from.
     * @param destinationFS
     *            the destination filesystem to copy to.
     * @param destination
     *            the destination path (on the destination filesystem) to copy to.
     * @param mode
     *            how to react if the destination already exists.
     * @param recursive
     *            if the copy should be recursive.
     *
     * @return a {@link String} that identifies this copy and be used to inspect its progress.
     *
     * @throws IllegalArgumentException
     *             If source, destinationFS, destination or mode is null.
     */
    public synchronized String copy(final Path source, final FileSystem destinationFS, final Path destination, final CopyMode mode, final boolean recursive) {

        if (source == null) {
            throw new IllegalArgumentException("Source path is null");
        }

        if (destinationFS == null) {
            throw new IllegalArgumentException("Destination filesystem is null");
        }

        if (destination == null) {
            throw new IllegalArgumentException("Destination path is null");
        }

        if (mode == null) {
            throw new IllegalArgumentException("Copy mode is null!");
        }

        String copyID = getNextCopyID();

        final CopyCallback callback = new CopyCallback();

        Future<Void> future = pool.submit(() -> {

            if (Thread.currentThread().isInterrupted()) {
                throw new CopyCancelledException(getAdaptorName(), "Copy cancelled by user");
            }

            performCopy(toAbsolutePath(source), destinationFS, toAbsolutePath(destination), mode, recursive, callback);
            return null;
        });

        pendingCopies.put(copyID, new PendingCopy(future, callback));
        return copyID;
    }

    /**
     * Cancel a copy operation. Afterwards, the copy is forgotten and subsequent queries with this copy string will lead to {@link NoSuchCopyException}
     *
     * @param copyIdentifier
     *            the identifier of the copy operation which to cancel.
     *
     * @return a {@link CopyStatus} containing the status of the copy.
     *
     * @throws NoSuchCopyException
     *             If the copy is not known.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If the copyIdentifier is null.
     */
    public synchronized CopyStatus cancel(String copyIdentifier) throws XenonException {

        if (copyIdentifier == null) {
            throw new IllegalArgumentException("Copy identifier may not be null");
        }
        PendingCopy copy = pendingCopies.remove(copyIdentifier);

        if (copy == null) {
            throw new NoSuchCopyException(getAdaptorName(), "Copy not found: " + copyIdentifier);
        }

        copy.callback.cancel();
        copy.future.cancel(true);

        XenonException ex = null;
        String state = "DONE";

        try {
            copy.future.get();
        } catch (ExecutionException ee) {
            ex = new XenonException(getAdaptorName(), ee.getMessage(), ee);
            state = "FAILED";
        } catch (CancellationException ce) {
            ex = new CopyCancelledException(getAdaptorName(), "Copy cancelled by user");
            state = "FAILED";
        } catch (InterruptedException e) {
            ex = new CopyCancelledException(getAdaptorName(), "Copy interrupted by user");
            state = "FAILED";
            Thread.currentThread().interrupt();
        }
        return new CopyStatusImplementation(copyIdentifier, state, copy.callback.getBytesToCopy(), copy.callback.getBytesCopied(), ex);
    }

    /**
     * Wait until a copy operation is done or until a timeout expires.
     * <p>
     * This method will wait until a copy operation is done (either gracefully or by producing an error), or until the timeout expires, whichever comes first.
     * If the timeout expires, the copy operation will continue to run.
     * </p>
     * <p>
     * The timeout is in milliseconds and must be &gt;= 0. When timeout is 0, it will be ignored and this method will wait until the copy operation is done.
     * </p>
     * After this operation, the copy is forgotten and subsequent queries with this copy string will lead to {@link NoSuchCopyException}
     * <p>
     * A {@link CopyStatus} is returned that can be used to determine why the call returned.
     * </p>
     *
     * @param copyIdentifier
     *            the identifier of the copy operation to wait for.
     * @param timeout
     *            the maximum time to wait for the copy operation in milliseconds.
     *
     * @return a {@link CopyStatus} containing the status of the copy.
     *
     * @throws IllegalArgumentException
     *             If argument is illegal.
     * @throws NoSuchCopyException
     *             If the copy handle is not known.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If the copyIdentifier is null or if the value of timeout is negative.
     */
    public CopyStatus waitUntilDone(String copyIdentifier, long timeout) throws XenonException {

        if (copyIdentifier == null) {
            throw new IllegalArgumentException("Copy identifier may not be null");
        }

        PendingCopy copy = pendingCopies.get(copyIdentifier);

        if (copy == null) {
            throw new NoSuchCopyException(getAdaptorName(), "Copy not found: " + copyIdentifier);
        }

        XenonException ex = null;
        String state = "DONE";

        try {
            copy.future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            state = "RUNNING";
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof XenonException) {
                ex = (XenonException) cause;
            } else {
                ex = new XenonException(getAdaptorName(), cause.getMessage(), cause);
            }
            state = "FAILED";
        } catch (CancellationException ce) {
            ex = new CopyCancelledException(getAdaptorName(), "Copy cancelled by user");
            state = "FAILED";
        } catch (InterruptedException ie) {
            ex = new CopyCancelledException(getAdaptorName(), "Copy interrupted by user");
            state = "FAILED";
            Thread.currentThread().interrupt();
        }

        if (copy.future.isDone()) {
            pendingCopies.remove(copyIdentifier);
        }

        return new CopyStatusImplementation(copyIdentifier, state, copy.callback.getBytesToCopy(), copy.callback.getBytesCopied(), ex);
    }

    /**
     * Retrieve the status of an copy. After obtaining the status of a completed copy, the copy is forgotten and subsequent queries with this copy string will
     * lead to {@link NoSuchCopyException}.
     *
     * @param copyIdentifier
     *            the identifier of the copy for which to retrieve the status.
     *
     * @return a {@link CopyStatus} containing the status of the asynchronous copy.
     *
     * @throws NoSuchCopyException
     *             If the copy is not known.
     * @throws NotConnectedException
     *             If file system is closed.
     * @throws XenonException
     *             if an I/O error occurred.
     * @throws IllegalArgumentException
     *             If the copyIdentifier is null.
     */
    public CopyStatus getStatus(String copyIdentifier) throws XenonException {

        if (copyIdentifier == null) {
            throw new IllegalArgumentException("Copy identifier may not be null");
        }

        PendingCopy copy = pendingCopies.get(copyIdentifier);

        if (copy == null) {
            throw new NoSuchCopyException(getAdaptorName(), "Copy not found: " + copyIdentifier);
        }

        XenonException ex = null;
        String state = "PENDING";

        if (copy.future.isDone()) {
            pendingCopies.remove(copyIdentifier);

            // We have either finished, crashed, or cancelled
            try {
                copy.future.get();
                state = "DONE";
            } catch (ExecutionException ee) {
                ex = new XenonException(getAdaptorName(), ee.getMessage(), ee);
                state = "FAILED";
            } catch (CancellationException ce) {
                ex = new CopyCancelledException(getAdaptorName(), "Copy cancelled by user");
                state = "FAILED";
            } catch (InterruptedException ie) {
                ex = new CopyCancelledException(getAdaptorName(), "Copy interrupted by user");
                state = "FAILED";
                Thread.currentThread().interrupt();
            }
        } else if (copy.callback.isStarted()) {
            state = "RUNNING";
        }

        return new CopyStatusImplementation(copyIdentifier, state, copy.callback.getBytesToCopy(), copy.callback.getBytesCopied(), ex);
    }

    protected void assertNotNull(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Path is null");
        }
    }

    protected void assertPathExists(Path path) throws XenonException {

        assertNotNull(path);

        if (!exists(path)) {
            throw new NoSuchPathException(getAdaptorName(), "Path does not exist: " + path);
        }
    }

    protected void assertPathNotExists(Path path) throws XenonException {

        assertNotNull(path);

        if (exists(path)) {
            throw new PathAlreadyExistsException(getAdaptorName(), "Path already exists: " + path);
        }
    }

    protected void assertPathIsNotDirectory(Path path) throws XenonException {

        assertNotNull(path);

        if (exists(path)) {

            PathAttributes a = getAttributes(path);
            if (a.isDirectory()) {
                throw new InvalidPathException(getAdaptorName(), "Was expecting a regular file, but got a directory: " + path.toString());
            }
        }
    }

    protected void assertPathIsFile(Path path) throws XenonException {

        assertNotNull(path);

        if (!getAttributes(path).isRegular()) {
            throw new InvalidPathException(getAdaptorName(), "Path is not a file: " + path);
        }
    }

    protected void assertPathIsDirectory(Path path) throws XenonException {

        assertNotNull(path);

        PathAttributes a = getAttributes(path);

        if (a == null) {
            throw new InvalidPathException(getAdaptorName(), "Path failed to produce attributes: " + path);
        }

        if (!a.isDirectory()) {
            throw new InvalidPathException(getAdaptorName(), "Path is not a directory: " + path);
        }
    }

    protected void assertFileExists(Path file) throws XenonException {
        assertPathExists(file);
        assertPathIsFile(file);
    }

    protected void assertDirectoryExists(Path dir) throws XenonException {
        assertPathExists(dir);
        assertPathIsDirectory(dir);
    }

    protected void assertParentDirectoryExists(Path path) throws XenonException {

        assertNotNull(path);
        Path parent = path.getParent();

        if (parent != null) {
            assertDirectoryExists(parent);
        }
    }

    protected void assertFileIsSymbolicLink(Path link) throws XenonException {
        assertNotNull(link);
        assertPathExists(link);
        if (!getAttributes(link).isSymbolicLink()) {
            throw new InvalidPathException(getAdaptorName(), "Not a symbolic link: " + link);
        }
    }

    protected void assertIsOpen() throws XenonException {
        if (!isOpen()) {
            throw new NotConnectedException(getAdaptorName(), "Connection is closed");
        }
    }

    // Expects two non-null, normalized absolute paths
    protected boolean areSamePaths(Path source, Path target) {
        return source.equals(target);
    }

    protected boolean isDotDot(Path path) {

        assertNotNull(path);

        String filename = path.getFileNameAsString();
        return ".".equals(filename) || "..".equals(filename);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        FileSystem that = (FileSystem) o;
        return Objects.equals(uniqueID, that.uniqueID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uniqueID);
    }

}

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
package nl.esciencecenter.xenon.adaptors.filesystems.sftp;

import static nl.esciencecenter.xenon.adaptors.filesystems.sftp.SftpFileAdaptor.ADAPTOR_NAME;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.sshd.client.subsystem.sftp.SftpClient;
import org.apache.sshd.common.subsystem.sftp.SftpConstants;
import org.apache.sshd.common.subsystem.sftp.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.esciencecenter.xenon.XenonException;
import nl.esciencecenter.xenon.adaptors.NotConnectedException;
import nl.esciencecenter.xenon.adaptors.XenonProperties;
import nl.esciencecenter.xenon.adaptors.filesystems.EndOfFileException;
import nl.esciencecenter.xenon.adaptors.filesystems.NoSpaceException;
import nl.esciencecenter.xenon.adaptors.filesystems.PathAttributesImplementation;
import nl.esciencecenter.xenon.adaptors.filesystems.PermissionDeniedException;
import nl.esciencecenter.xenon.adaptors.filesystems.PosixFileUtils;
import nl.esciencecenter.xenon.adaptors.shared.ssh.SSHConnection;
import nl.esciencecenter.xenon.credentials.Credential;
import nl.esciencecenter.xenon.filesystems.DirectoryNotEmptyException;
import nl.esciencecenter.xenon.filesystems.FileSystem;
import nl.esciencecenter.xenon.filesystems.InvalidPathException;
import nl.esciencecenter.xenon.filesystems.NoSuchPathException;
import nl.esciencecenter.xenon.filesystems.Path;
import nl.esciencecenter.xenon.filesystems.PathAlreadyExistsException;
import nl.esciencecenter.xenon.filesystems.PathAttributes;
import nl.esciencecenter.xenon.filesystems.PosixFilePermission;

public class SftpFileSystem extends FileSystem {

    private static final Logger LOGGER = LoggerFactory.getLogger(SftpFileSystem.class);

    private final SftpClient client;
    private final SSHConnection connection;

    protected SftpFileSystem(String uniqueID, String name, String location, Credential credential, Path entryPath, int bufferSize, SSHConnection connection,
            SftpClient client, XenonProperties properties) {
        super(uniqueID, name, location, credential, entryPath, bufferSize, properties);
        this.client = client;
        this.connection = connection;
    }

    @Override
    public void close() throws XenonException {

        LOGGER.debug("close fileSystem = {}", this);

        IOException ex = null;

        try {
            client.close();
        } catch (IOException e) {
            ex = e;
        }

        connection.close();
        super.close();

        if (ex != null) {
            throw sftpExceptionToXenonException(ex, "Failed to close sftp client");
        }

        LOGGER.debug("close OK");
    }

    @Override
    public boolean isOpen() throws XenonException {
        return client.isOpen();
    }

    @Override
    public void rename(Path source, Path target) throws XenonException {

        LOGGER.debug("move source = {} target = {}", source, target);

        Path absSource = toAbsolutePath(source);
        Path absTarget = toAbsolutePath(target);

        assertPathExists(absSource);

        if (areSamePaths(absSource, absTarget)) {
            return;
        }

        assertPathNotExists(absTarget);
        assertParentDirectoryExists(absTarget);

        try {
            client.rename(absSource.toString(), absTarget.toString());
        } catch (IOException e) {
            throw sftpExceptionToXenonException(e, "Failed to rename path");
        }

        LOGGER.debug("move OK");
    }

    @Override
    public void createDirectory(Path dir) throws XenonException {

        LOGGER.debug("createDirectory dir = {}", dir);

        Path absDir = toAbsolutePath(dir);
        assertPathNotExists(absDir);
        assertParentDirectoryExists(absDir);

        try {
            client.mkdir(absDir.toString());
        } catch (IOException e) {
            throw sftpExceptionToXenonException(e, "Failed to mkdir");
        }

        LOGGER.debug("createDirectory OK");
    }

    @Override
    public void createFile(Path file) throws XenonException {

        Path absFile = toAbsolutePath(file);
        assertPathNotExists(absFile);

        LOGGER.debug("createFile path = {}", absFile);

        OutputStream out = null;

        try {
            out = writeToFile(absFile);
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                // ignore
            }
        }

        LOGGER.debug("createFile OK");
    }

    @Override
    public void createSymbolicLink(Path link, Path path) throws XenonException {

        Path absLink = toAbsolutePath(link);
        assertPathNotExists(absLink);
        assertParentDirectoryExists(absLink);

        try {
            client.symLink(absLink.toString(), path.toString());
        } catch (IOException e) {
            throw sftpExceptionToXenonException(e, "Cannot create link: " + absLink + " -> " + path);
        }
    }

    @Override
    protected void deleteFile(Path file) throws XenonException {
        try {
            client.remove(file.toString());
        } catch (IOException e) {
            throw sftpExceptionToXenonException(e, "Cannot delete file: " + file);
        }
    }

    @Override
    protected void deleteDirectory(Path dir) throws XenonException {
        try {
            client.rmdir(dir.toString());
        } catch (IOException e) {
            throw sftpExceptionToXenonException(e, "Cannot delete directory: " + dir);
        }
    }

    private SftpClient.Attributes stat(Path path) throws XenonException {

        LOGGER.debug("* stat path = {}", path);

        SftpClient.Attributes result;

        try {
            result = client.lstat(path.toString());
        } catch (IOException e) {
            throw sftpExceptionToXenonException(e, "Failed to retrieve attributes from: " + path);
        }

        LOGGER.debug("* stat OK result = {}", result);

        return result;
    }

    @Override
    public boolean exists(Path path) throws XenonException {

        LOGGER.debug("exists path = {}", path);
        try {
            stat(toAbsolutePath(path));
            return true;
        } catch (NoSuchPathException e) {
            return false;
        }
    }

    @Override
    protected List<PathAttributes> listDirectory(Path path) throws XenonException {

        try {
            assertDirectoryExists(path);

            ArrayList<PathAttributes> result = new ArrayList<>();

            for (SftpClient.DirEntry f : client.readDir(path.toString())) {
                result.add(convertAttributes(path.resolve(f.getFilename()), f.getAttributes()));
            }

            return result;
        } catch (IOException e) {
            throw sftpExceptionToXenonException(e, "Failed to list directory " + path);
        }
    }

    @Override
    public InputStream readFromFile(Path path) throws XenonException {
        LOGGER.debug("newInputStream path = {}", path);

        Path absPath = toAbsolutePath(path);

        assertFileExists(absPath);

        InputStream in;

        try {
            in = client.read(absPath.toString());
        } catch (IOException e) {
            throw new XenonException(ADAPTOR_NAME, "Failed to open stream to read from " + absPath, e);
        }

        LOGGER.debug("newInputStream OK");

        return in;
    }

    @Override
    public OutputStream writeToFile(Path path, long size) throws XenonException {

        Path absPath = toAbsolutePath(path);
        assertPathNotExists(absPath);
        assertParentDirectoryExists(absPath);

        try {
            return client.write(absPath.toString(), SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate);
        } catch (IOException e) {
            throw new XenonException(ADAPTOR_NAME, "Failed open stream to write to: " + absPath, e);
        }
    }

    @Override
    public OutputStream writeToFile(Path path) throws XenonException {
        return writeToFile(path, -1);
    }

    @Override
    public OutputStream appendToFile(Path path) throws XenonException {

        Path absPath = toAbsolutePath(path);
        assertFileExists(absPath);

        try {
            return client.write(absPath.toString(), SftpClient.OpenMode.Write, SftpClient.OpenMode.Append);
        } catch (IOException e) {
            throw new XenonException(ADAPTOR_NAME, "Failed open stream to write to: " + absPath, e);
        }
    }

    @Override
    public PathAttributes getAttributes(Path path) throws XenonException {
        Path absPath = toAbsolutePath(path);
        return convertAttributes(absPath, stat(absPath));
    }

    @Override
    public Path readSymbolicLink(Path link) throws XenonException {
        LOGGER.debug("readSymbolicLink path = {}", link);

        Path absLink = toAbsolutePath(link);

        Path result;
        assertFileIsSymbolicLink(absLink);
        try {
            String target = client.readLink(absLink.toString());

            if (!target.startsWith(File.separator)) {
                Path parent = absLink.getParent();
                result = parent.resolve(target);
            } else {
                result = new Path(target);
            }
        } catch (IOException e) {
            throw sftpExceptionToXenonException(e, "Failed to read link: " + absLink);
        }

        LOGGER.debug("readSymbolicLink OK result = {}", result);

        return result;
    }

    @Override
    public void setPosixFilePermissions(Path path, Set<PosixFilePermission> permissions) throws XenonException {
        LOGGER.debug("setPosixFilePermissions path = {} permissions = {}", path, permissions);

        if (permissions == null) {
            throw new IllegalArgumentException("Permissions is null");
        }

        Path absPath = toAbsolutePath(path);
        assertPathExists(absPath);

        try {
            // We need to create a new Attributes object here. SFTP will only
            // forward the fields that are actually set
            // when we call setStat. If we retrieve the existing attributes,
            // change permissions and send the lot back
            // we'll receive an error since some of the other attributes cannot
            // be changed (learned this the hard way).
            SftpClient.Attributes a = new SftpClient.Attributes();
            a.setPermissions(PosixFileUtils.permissionsToBits(permissions));
            client.setStat(absPath.toString(), a);
        } catch (IOException e) {
            throw sftpExceptionToXenonException(e, "Failed to set permissions on: " + absPath);
        }
        LOGGER.debug("setPosixFilePermissions OK");
    }

    private static long convertTime(FileTime time) {
        return time.toMillis();
    }

    private static PathAttributes convertAttributes(Path path, SftpClient.Attributes attributes) {

        PathAttributesImplementation result = new PathAttributesImplementation();

        result.setPath(path);
        result.setDirectory(attributes.isDirectory());
        result.setRegular(attributes.isRegularFile());
        result.setOther(attributes.isOther());
        result.setSymbolicLink(attributes.isSymbolicLink());
        if (attributes.getModifyTime() == null) {
            result.setLastModifiedTime(0);
        } else {
            result.setLastModifiedTime(convertTime(attributes.getModifyTime()));
        }
        if (attributes.getCreateTime() == null) {
            result.setCreationTime(result.getLastModifiedTime());
        } else {
            result.setCreationTime(convertTime(attributes.getCreateTime()));
        }
        if (attributes.getAccessTime() == null) {
            result.setCreationTime(result.getLastModifiedTime());
        } else {
            result.setLastAccessTime(convertTime(attributes.getAccessTime()));
        }

        result.setSize(attributes.getSize());

        Set<PosixFilePermission> permission = PosixFileUtils.bitsToPermissions(attributes.getPermissions());
        result.setPermissions(permission);

        result.setExecutable(permission.contains(PosixFilePermission.OWNER_EXECUTE));
        result.setReadable(permission.contains(PosixFilePermission.OWNER_READ));
        result.setWritable(permission.contains(PosixFilePermission.OWNER_WRITE));

        result.setGroup(attributes.getGroup());
        result.setOwner(attributes.getOwner());

        // assume UNIX-like filesystem
        if (!path.isEmpty()) {
            result.setHidden(path.getFileNameAsString().startsWith("."));
        }

        return result;
    }

    static XenonException sftpExceptionToXenonException(IOException e, String message) {

        if (e instanceof SftpException) {
            SftpException x = (SftpException) e;
            switch (x.getStatus()) {

            case SftpConstants.SSH_FX_EOF:
                return new EndOfFileException(ADAPTOR_NAME, "Unexpected EOF", e);

            case SftpConstants.SSH_FX_NO_SUCH_FILE:
            case SftpConstants.SSH_FX_NO_SUCH_PATH:
                return new NoSuchPathException(ADAPTOR_NAME, "Path does not exists", e);

            case SftpConstants.SSH_FX_PERMISSION_DENIED:
                return new PermissionDeniedException(ADAPTOR_NAME, "Permission denied", e);

            case SftpConstants.SSH_FX_NO_CONNECTION:
                return new NotConnectedException(ADAPTOR_NAME, "Not connected", e);

            case SftpConstants.SSH_FX_CONNECTION_LOST:
                return new NotConnectedException(ADAPTOR_NAME, "Connection lost", e);

            case SftpConstants.SSH_FX_OP_UNSUPPORTED:
                return new XenonException(ADAPTOR_NAME, "Unsupported operation", e);

            case SftpConstants.SSH_FX_FILE_ALREADY_EXISTS:
                return new PathAlreadyExistsException(ADAPTOR_NAME, "Already exists", e);

            case SftpConstants.SSH_FX_WRITE_PROTECT:
                return new PermissionDeniedException(ADAPTOR_NAME, "Write protected", e);

            case SftpConstants.SSH_FX_CANNOT_DELETE:
                return new PermissionDeniedException(ADAPTOR_NAME, "Cannot delete", e);

            case SftpConstants.SSH_FX_DELETE_PENDING:
                return new PermissionDeniedException(ADAPTOR_NAME, "Delete pending", e);

            case SftpConstants.SSH_FX_NO_MEDIA:
            case SftpConstants.SSH_FX_NO_SPACE_ON_FILESYSTEM:
                return new NoSpaceException(ADAPTOR_NAME, "No space on filesystem", e);

            case SftpConstants.SSH_FX_QUOTA_EXCEEDED:
                return new NoSpaceException(ADAPTOR_NAME, "Quota exceeded", e);

            case SftpConstants.SSH_FX_FILE_CORRUPT:
                return new InvalidPathException(ADAPTOR_NAME, "File corrupt", e);

            case SftpConstants.SSH_FX_DIR_NOT_EMPTY:
                return new DirectoryNotEmptyException(ADAPTOR_NAME, "Directory not empty", e);

            case SftpConstants.SSH_FX_NOT_A_DIRECTORY:
                return new InvalidPathException(ADAPTOR_NAME, "Not a directory", e);

            case SftpConstants.SSH_FX_INVALID_FILENAME:
                return new InvalidPathException(ADAPTOR_NAME, "Invalid file name", e);

            case SftpConstants.SSH_FX_LINK_LOOP:
                return new InvalidPathException(ADAPTOR_NAME, "Link loop", e);

            case SftpConstants.SSH_FX_FILE_IS_A_DIRECTORY:
                return new InvalidPathException(ADAPTOR_NAME, "File is a directory", e);

            case SftpConstants.SSH_FX_OWNER_INVALID:
                return new XenonException(ADAPTOR_NAME, "Invalid owner", e);

            case SftpConstants.SSH_FX_GROUP_INVALID:
                return new XenonException(ADAPTOR_NAME, "Invalid group", e);

            case SftpConstants.SSH_FX_INVALID_HANDLE:
                return new XenonException(ADAPTOR_NAME, "Invalid handle", e);

            case SftpConstants.SSH_FX_INVALID_PARAMETER:
                return new XenonException(ADAPTOR_NAME, "Invalid parameter", e);

            case SftpConstants.SSH_FX_LOCK_CONFLICT:
            case SftpConstants.SSH_FX_BYTE_RANGE_LOCK_CONFLICT:
            case SftpConstants.SSH_FX_BYTE_RANGE_LOCK_REFUSED:
            case SftpConstants.SSH_FX_NO_MATCHING_BYTE_RANGE_LOCK:
                return new XenonException(ADAPTOR_NAME, "Locking failed", e);

            case SftpConstants.SSH_FX_UNKNOWN_PRINCIPAL:
                return new XenonException(ADAPTOR_NAME, "Unknown principal", e);

            case SftpConstants.SSH_FX_BAD_MESSAGE:
                return new XenonException(ADAPTOR_NAME, "Malformed message", e);

            }
        }

        if (e.getMessage().contains("client is close")) {
            return new NotConnectedException(ADAPTOR_NAME, e.getMessage());
        }

        // Fall through if we do not know the error
        return new XenonException(ADAPTOR_NAME, message, e);
    }
}

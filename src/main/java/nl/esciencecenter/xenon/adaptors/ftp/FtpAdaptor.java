/**
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
package nl.esciencecenter.xenon.adaptors.ftp;

import java.util.HashMap;
import java.util.Map;

import nl.esciencecenter.xenon.XenonException;
import nl.esciencecenter.xenon.XenonPropertyDescription;
import nl.esciencecenter.xenon.XenonPropertyDescription.Component;
import nl.esciencecenter.xenon.credentials.Credentials;
import nl.esciencecenter.xenon.engine.Adaptor;
import nl.esciencecenter.xenon.engine.XenonEngine;
import nl.esciencecenter.xenon.engine.XenonProperties;
import nl.esciencecenter.xenon.engine.util.ImmutableArray;
import nl.esciencecenter.xenon.files.Files;
import nl.esciencecenter.xenon.jobs.Jobs;

public class FtpAdaptor extends Adaptor {

    /** The name of this adaptor */
    public static final String ADAPTOR_NAME = "ftp";

    /** The default SSH port */
    protected static final int DEFAULT_PORT = 21;

    /** A description of this adaptor */
    private static final String ADAPTOR_DESCRIPTION = "The FTP adaptor implements all functionality with remote ftp servers.";

    /** The schemes supported by this adaptor */
    protected static final ImmutableArray<String> ADAPTOR_SCHEME = new ImmutableArray<>("ftp");

    /** The locations supported by this adaptor */
    private static final ImmutableArray<String> ADAPTOR_LOCATIONS = new ImmutableArray<>("[user@]host[:port]");

    /** All our own properties start with this prefix. */
    public static final String PREFIX = XenonEngine.ADAPTORS_PREFIX + "ftp.";

    /** List of properties supported by this FTP adaptor */
    protected static final ImmutableArray<XenonPropertyDescription> VALID_PROPERTIES = 
            new ImmutableArray<XenonPropertyDescription>();
    
    private final FtpFiles filesAdaptor;
    private final FtpCredentials credentialsAdaptor;

    public FtpAdaptor(XenonEngine xenonEngine, Map<String, String> properties) throws XenonException {
        super(xenonEngine, ADAPTOR_NAME, ADAPTOR_DESCRIPTION, ADAPTOR_SCHEME, ADAPTOR_LOCATIONS, VALID_PROPERTIES,
                new XenonProperties(VALID_PROPERTIES, Component.XENON, properties));

        filesAdaptor = new FtpFiles(this, xenonEngine);
        credentialsAdaptor = new FtpCredentials(getProperties(), this);
    }

    @Override
    public XenonPropertyDescription[] getSupportedProperties() {
        return VALID_PROPERTIES.asArray();
    }

    @Override
    public Files filesAdaptor() {
        return filesAdaptor;
    }

    @Override
    public Jobs jobsAdaptor() throws XenonException {
        throw new XenonException(getName(), "jobsAdaptor(): Not implemented");
    }

    @Override
    public Credentials credentialsAdaptor() {
        return credentialsAdaptor;
    }

    @Override
    public void end() {
        filesAdaptor.end();
    }

    @Override
    public Map<String, String> getAdaptorSpecificInformation() {
        return new HashMap<>();
    }
}

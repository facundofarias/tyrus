/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.tyrus.server;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import javax.websocket.ClientEndpointConfiguration;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfiguration;
import javax.websocket.Extension;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpointConfiguration;

import org.glassfish.tyrus.AnnotatedEndpoint;
import org.glassfish.tyrus.ComponentProviderService;
import org.glassfish.tyrus.EndpointWrapper;
import org.glassfish.tyrus.ErrorCollector;
import org.glassfish.tyrus.WithProperties;
import org.glassfish.tyrus.spi.SPIRegisteredEndpoint;
import org.glassfish.tyrus.spi.TyrusServer;

/**
 * Server Container Implementation.
 *
 * @author Martin Matula (martin.matula at oracle.com)
 */
public class TyrusServerContainer extends WithProperties implements WebSocketContainer {
    private final TyrusServer server;
    private final String contextPath;
    private final ServerConfiguration configuration;
    private final Set<SPIRegisteredEndpoint> endpoints = new HashSet<SPIRegisteredEndpoint>();
    private final ErrorCollector collector;

    public TyrusServerContainer(final TyrusServer server, final String contextPath,
                                final ServerConfiguration configuration) {
        this.collector = new ErrorCollector();
        this.server = server;
        this.contextPath = contextPath;
        // make a read-only copy of the configuration
        this.configuration = new ServerConfiguration() {
            private final Set<Class<?>> endpointClasses =
                    Collections.unmodifiableSet(new HashSet<Class<?>>(configuration.getEndpointClasses()));
            private final Set<Endpoint> endpointInstances =
                    Collections.unmodifiableSet(new HashSet<Endpoint>(configuration.getEndpointInstances()));
            private final long maxSessionIdleTimeout = configuration.getMaxSessionIdleTimeout();
            private final long maxBinaryMessageBufferSize = configuration.getMaxBinaryMessageBufferSize();
            private final long maxTextMessageBufferSize = configuration.getMaxTextMessageBufferSize();
            private final List<String> extensions =
                    Collections.unmodifiableList(new ArrayList<String>(configuration.getExtensions()));

            @Override
            public Set<Class<?>> getEndpointClasses() {
                return endpointClasses;
            }

            @Override
            public Set<Endpoint> getEndpointInstances() {
                return endpointInstances;
            }

            @Override
            public long getMaxSessionIdleTimeout() {
                return maxSessionIdleTimeout;
            }

            @Override
            public long getMaxBinaryMessageBufferSize() {
                return maxBinaryMessageBufferSize;
            }

            @Override
            public long getMaxTextMessageBufferSize() {
                return maxTextMessageBufferSize;
            }

            @Override
            public List<String> getExtensions() {
                return extensions;
            }
        };
    }

    public void start() throws IOException, DeploymentException {
        // start the underlying server
        server.start();

        for (Endpoint endpoint : configuration.getEndpointInstances()) {
            deploy(endpoint, null);
        }

        try {
            // deploy all the class-based endpoints
            for (Class<?> endpointClass : configuration.getEndpointClasses()) {
                deploy(endpointClass);
            }
        } catch (DeploymentException de) {
            collector.addException(de);
        }

        if (!collector.isEmpty()) {
            this.stop();
            throw collector.composeComprehensiveException();
        }
    }

    private void deploy(Endpoint endpoint, EndpointConfiguration endpointConfiguration) {
        EndpointWrapper ew = new EndpointWrapper(endpoint, endpointConfiguration, this, contextPath);
        SPIRegisteredEndpoint ge = server.register(ew);
        endpoints.add(ge);
    }

    private void deploy(Class<?> endpointClass) throws DeploymentException {
        Endpoint endpoint;
        EndpointConfiguration config;
        if (Endpoint.class.isAssignableFrom(endpointClass)) {
            endpoint = (Endpoint) ComponentProviderService.getInstance(endpointClass);
            config = null;
        } else {
            endpoint = AnnotatedEndpoint.fromClass(endpointClass, true, collector);
            config = ((AnnotatedEndpoint) endpoint).getEndpointConfiguration();
        }

        if (endpoint == null) {
            collector.addException(new DeploymentException("Endpoint class " + endpointClass.getName() + " does " +
                    "not extend Endpoint and is not " +
                    "annotated with @WebSocketEndpoint annotation."));
        }

        deploy(endpoint, config);
        Logger.getLogger(getClass().getName()).info("Registered a " + endpointClass);
    }

    public void stop() {
        for (SPIRegisteredEndpoint wsa : this.endpoints) {
            wsa.remove();
            this.server.unregister(wsa);
            Logger.getLogger(getClass().getName()).info("Closing down : " + wsa);
        }
        server.stop();
    }

    public void publishServer(Class<? extends ServerEndpointConfiguration> configuration) throws DeploymentException {
        deploy(configuration);
    }

    @Override
    public Session connectToServer(Class annotatedEndpointClass, URI path) throws DeploymentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfiguration cec, URI path) throws DeploymentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Session> getOpenSessions() {
        Set<Session> result = new HashSet<Session>();

        for (SPIRegisteredEndpoint endpoint : endpoints) {
            result.addAll(endpoint.getOpenSessions());
        }

        return Collections.unmodifiableSet(result);
    }

    @Override
    public long getMaxSessionIdleTimeout() {
        return configuration.getMaxSessionIdleTimeout();
    }

    @Override
    public void setMaxSessionIdleTimeout(long timeout) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getMaxBinaryMessageBufferSize() {
        return configuration.getMaxBinaryMessageBufferSize();
    }

    @Override
    public void setMaxBinaryMessageBufferSize(long max) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getMaxTextMessageBufferSize() {
        return configuration.getMaxTextMessageBufferSize();
    }

    @Override
    public void setMaxTextMessageBufferSize(long max) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Extension> getInstalledExtensions() {
        // TODO
        // return Collections.unmodifiableSet(new HashSet<String>(configuration.getExtensions()));

        return Collections.emptySet();
    }

    @Override
    public long getDefaultAsyncSendTimeout() {
        return 0;  // TODO: Implement.
    }

    @Override
    public void setAsyncSendTimeout(long timeoutmillis) {
        // TODO: Implement.
    }
}

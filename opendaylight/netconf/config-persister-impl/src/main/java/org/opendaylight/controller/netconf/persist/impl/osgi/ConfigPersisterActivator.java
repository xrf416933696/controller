/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.netconf.persist.impl.osgi;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.opendaylight.controller.netconf.persist.impl.ConfigPersisterNotificationHandler;
import org.opendaylight.controller.netconf.persist.impl.ConfigPusher;
import org.opendaylight.controller.netconf.persist.impl.PersisterAggregator;
import org.opendaylight.controller.netconf.util.osgi.NetconfConfigUtil;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;

public class ConfigPersisterActivator implements BundleActivator {

    private static final Logger logger = LoggerFactory.getLogger(ConfigPersisterActivator.class);

    private final static MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
    private static final String IGNORED_MISSING_CAPABILITY_REGEX_SUFFIX = "ignoredMissingCapabilityRegex";

    private static final String MAX_WAIT_FOR_CAPABILITIES_MILLIS = "maxWaitForCapabilitiesMillis";

    public static final String NETCONF_CONFIG_PERSISTER = "netconf.config.persister";

    public static final String STORAGE_ADAPTER_CLASS_PROP_SUFFIX = "storageAdapterClass";

    public static final String DEFAULT_IGNORED_REGEX = "^urn:ietf:params:xml:ns:netconf:base:1.0";


    private volatile ConfigPersisterNotificationHandler jmxNotificationHandler;
    private Thread initializationThread;
    private EventLoopGroup nettyThreadGroup;
    private PersisterAggregator persisterAggregator;

    @Override
    public void start(final BundleContext context) throws Exception {
        logger.debug("ConfigPersister starting");

        PropertiesProviderBaseImpl propertiesProvider = new PropertiesProviderBaseImpl(context);

        String regexProperty = propertiesProvider.getProperty(IGNORED_MISSING_CAPABILITY_REGEX_SUFFIX);
        String regex;
        if (regexProperty != null) {
            regex = regexProperty;
        } else {
            regex = DEFAULT_IGNORED_REGEX;
        }

        String timeoutProperty = propertiesProvider.getProperty(MAX_WAIT_FOR_CAPABILITIES_MILLIS);
        long maxWaitForCapabilitiesMillis;
        if (timeoutProperty == null) {
            maxWaitForCapabilitiesMillis = ConfigPusher.DEFAULT_MAX_WAIT_FOR_CAPABILITIES_MILLIS;
        } else {
            maxWaitForCapabilitiesMillis = Integer.valueOf(timeoutProperty);
        }

        final Pattern ignoredMissingCapabilityRegex = Pattern.compile(regex);
        nettyThreadGroup = new NioEventLoopGroup();

        persisterAggregator = PersisterAggregator.createFromProperties(propertiesProvider);
        final InetSocketAddress address = NetconfConfigUtil.extractTCPNetconfAddress(context,
                "Netconf is not configured, persister is not operational", true);
        final ConfigPusher configPusher = new ConfigPusher(address, nettyThreadGroup, maxWaitForCapabilitiesMillis,
                ConfigPusher.DEFAULT_CONNECTION_TIMEOUT_MILLIS);


        // offload initialization to another thread in order to stop blocking activator
        Runnable initializationRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    configPusher.pushConfigs(persisterAggregator.loadLastConfigs());
                    jmxNotificationHandler = new ConfigPersisterNotificationHandler(platformMBeanServer, persisterAggregator,
                            ignoredMissingCapabilityRegex);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Interrupted while waiting for netconf connection");
                    // uncaught exception handler will deal with this failure
                    throw new RuntimeException("Interrupted while waiting for netconf connection", e);
                }
                logger.info("Configuration Persister initialization completed.");
            }
        };
        initializationThread = new Thread(initializationRunnable, "ConfigPersister-registrator");
        initializationThread.start();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        initializationThread.interrupt();
        if (jmxNotificationHandler != null) {
            jmxNotificationHandler.close();
        }
        nettyThreadGroup.shutdownGracefully();
        persisterAggregator.close();
    }
}

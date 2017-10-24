/*******************************************************************************
 * Copyright (c) 2011, 2017 Eurotech and others
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Eurotech
 *     Red Hat Inc - allow opting-out of System.exit(), fix resource leak
 *******************************************************************************/
package org.eclipse.kura.core.system;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.AccessController;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

import org.eclipse.kura.core.util.IOUtil;
import org.eclipse.kura.core.util.NetUtil;
import org.eclipse.kura.core.util.ProcessUtil;
import org.eclipse.kura.core.util.SafeProcess;
import org.eclipse.kura.system.SystemService;
import org.osgi.framework.Bundle;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.ComponentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemServiceImpl extends SuperSystemService implements SystemService {

    private static final Logger logger = LoggerFactory.getLogger(SystemServiceImpl.class);

    private static final String CLOUDBEES_SECURITY_SETTINGS_PATH = "/private/eurotech/settings-security.xml";
    private static final String KURA_PATH = "/opt/eclipse/kura";

    private static final String SH_CMD = "/bin/sh";
    private static final String DMIDECODE_CMD = "dmidecode -t system";

    private static final String LINUX_WR42_VERSION = "2.6.34.9-WR4.2.0.0_standard";
    private static final String LINUX_WR43_VERSION = "2.6.34.12-WR4.3.0.0_standard";
    private static final String WINDOWS_STRING = "Windows";
    private static final String DPA_CONFIGURATION_STRING = "dpa.configuration";
    private static final String LOG4J_CONFIGURATION_STRING = "log4j.configuration";

    private static boolean onCloudbees = false;

    private Properties kuraProperties;
    private ComponentContext componentContext;

    // ----------------------------------------------------------------
    //
    // Activation APIs
    //
    // ----------------------------------------------------------------

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected void activate(ComponentContext componentContext) {
        this.componentContext = componentContext;

        AccessController.doPrivileged(new PrivilegedAction() {

            @Override
            public Object run() {
                try {
                    // privileged code goes here, for example:
                    onCloudbees = new File(CLOUDBEES_SECURITY_SETTINGS_PATH).exists();
                    return null; // nothing to return
                } catch (Exception e) {
                    logger.error("Unable to execute privileged in SystemService", e);
                    return null;
                }
            }
        });

        // load the defaults from the kura.properties files
        boolean updateTriggered = false;
        try {
            // special cases for older versions to fix relative path bug in v2.0.4 and earlier
            if (System.getProperty(KURA_CONFIG) != null
                    && "file:kura/kura.properties".equals(System.getProperty(KURA_CONFIG).trim())) {
                System.setProperty(KURA_CONFIG, "file:/opt/eclipse/kura/kura/kura.properties");
                updateTriggered = true;
                logger.warn("Overridding invalid kura.properties location");
            }
            if (System.getProperty(DPA_CONFIGURATION_STRING) != null
                    && "kura/dpa.properties".equals(System.getProperty(DPA_CONFIGURATION_STRING).trim())) {
                System.setProperty(DPA_CONFIGURATION_STRING, "/opt/eclipse/kura/kura/dpa.properties");
                updateTriggered = true;
                logger.warn("Overridding invalid dpa.properties location");
            }
            if (System.getProperty(LOG4J_CONFIGURATION_STRING) != null
                    && "file:kura/log4j.properties".equals(System.getProperty(LOG4J_CONFIGURATION_STRING).trim())) {
                System.setProperty(LOG4J_CONFIGURATION_STRING, "file:/opt/eclipse/kura/kura/log4j.properties");
                updateTriggered = true;
                logger.warn("Overridding invalid log4j.properties location");
            }

            // load the default kura.properties
            // look for kura.properties as resource in the classpath
            // if not found, look for such file in the kura.home directory
            String kuraHome = System.getProperty(KEY_KURA_HOME_DIR);
            Properties kuraDefaults = getKuraProperties(kuraHome, KURA_CONFIG, KURA_PROPS_FILE);

            // Try to reload kuraHome with the value set in kura.properties file.
            if (kuraHome == null) {
                kuraHome = kuraDefaults.getProperty(KEY_KURA_HOME_DIR);
            }

            // load custom kura properties
            // look for kura_custom.properties as resource in the classpath
            // if not found, look for such file in the kura.home directory
            Properties kuraCustomProps = getKuraProperties(kuraHome, KURA_CUSTOM_CONFIG, KURA_CUSTOM_PROPS_FILE);

            // Override defaults with values from kura_custom.properties
            kuraDefaults.putAll(kuraCustomProps);

            // more path overrides based on earlier Kura problem with relative paths
            if (kuraDefaults.getProperty(KEY_KURA_HOME_DIR) != null
                    && "kura".equals(kuraDefaults.getProperty(KEY_KURA_HOME_DIR).trim())) {
                kuraDefaults.setProperty(KEY_KURA_HOME_DIR, "/opt/eclipse/kura/kura");
                updateTriggered = true;
                logger.warn("Overridding invalid kura.home location");
            }
            if (kuraDefaults.getProperty(KEY_KURA_PLUGINS_DIR) != null
                    && "kura/plugins".equals(kuraDefaults.getProperty(KEY_KURA_PLUGINS_DIR).trim())) {
                kuraDefaults.setProperty(KEY_KURA_PLUGINS_DIR, "/opt/eclipse/kura/kura/plugins");
                updateTriggered = true;
                logger.warn("Overridding invalid kura.plugins location");
            }
            if (kuraDefaults.getProperty(KEY_KURA_PACKAGES_DIR) != null
                    && "kura/packages".equals(kuraDefaults.getProperty(KEY_KURA_PACKAGES_DIR).trim())) {
                kuraDefaults.setProperty(KEY_KURA_PACKAGES_DIR, "/opt/eclipse/kura/kura/packages");
                updateTriggered = true;
                logger.warn("Overridding invalid kura.packages location");
            }

            if (updateTriggered) {
                File directory;       // Desired current working directory

                directory = new File(KURA_PATH).getAbsoluteFile();
                if (directory.exists() || directory.mkdirs()) {
                    String oldDir = System.getProperty("user.dir");
                    if (System.setProperty("user.dir", directory.getAbsolutePath()) != null) {
                        logger.warn("Changed working directory to /opt/eclipse/kura from {}", oldDir);
                    }
                }
            }

            // build the this.kuraProperties instance with the defaults
            this.kuraProperties = new Properties(kuraDefaults);

            // take care of the CloudBees environment
            // that is run in the continuous integration.
            if (onCloudbees) {
                this.kuraProperties.put(KEY_OS_NAME, OS_CLOUDBEES);
            }

            // Put the Net Admin and Web interface availability property so that is available through a get.
            Boolean hasNetAdmin = Boolean.valueOf(this.kuraProperties.getProperty(KEY_KURA_HAVE_NET_ADMIN, "true"));
            this.kuraProperties.put(KEY_KURA_HAVE_NET_ADMIN, hasNetAdmin);
            logger.info("Kura has net admin? {}", hasNetAdmin);
            String webInterfaceEnabled = this.kuraProperties.getProperty(KEY_KURA_HAVE_WEB_INTER, "true");
            this.kuraProperties.put(KEY_KURA_HAVE_WEB_INTER, webInterfaceEnabled);
            logger.info("Is Kura web interface enabled? {}", webInterfaceEnabled);
            String kuraVersion = this.kuraProperties.getProperty(KEY_KURA_VERSION, "version-unknown");
            this.kuraProperties.put(KEY_KURA_VERSION, kuraVersion);
            logger.info("Kura version? {}", kuraVersion);

            if (System.getProperty(KEY_KURA_NAME) != null) {
                this.kuraProperties.put(KEY_KURA_NAME, System.getProperty(KEY_KURA_NAME));
            }
            if (System.getProperty(KEY_KURA_VERSION) != null) {
                this.kuraProperties.put(KEY_KURA_VERSION, System.getProperty(KEY_KURA_VERSION));
            }
            if (System.getProperty(KEY_KURA_NET_INTERFACE_BLACKLIST) != null) {
                this.kuraProperties.put(KEY_KURA_NET_INTERFACE_BLACKLIST,
                        System.getProperty(KEY_KURA_NET_INTERFACE_BLACKLIST));
            }
            if (System.getProperty(KEY_DEVICE_NAME) != null) {
                this.kuraProperties.put(KEY_DEVICE_NAME, System.getProperty(KEY_DEVICE_NAME));
            }
            if (System.getProperty(KEY_PLATFORM) != null) {
                this.kuraProperties.put(KEY_PLATFORM, System.getProperty(KEY_PLATFORM));
            }
            if (System.getProperty(KEY_MODEL_ID) != null) {
                this.kuraProperties.put(KEY_MODEL_ID, System.getProperty(KEY_MODEL_ID));
            }
            if (System.getProperty(KEY_MODEL_NAME) != null) {
                this.kuraProperties.put(KEY_MODEL_NAME, System.getProperty(KEY_MODEL_NAME));
            }
            if (System.getProperty(KEY_PART_NUMBER) != null) {
                this.kuraProperties.put(KEY_PART_NUMBER, System.getProperty(KEY_PART_NUMBER));
            }
            if (System.getProperty(KEY_SERIAL_NUM) != null) {
                this.kuraProperties.put(KEY_SERIAL_NUM, System.getProperty(KEY_SERIAL_NUM));
            }
            if (System.getProperty(KEY_BIOS_VERSION) != null) {
                this.kuraProperties.put(KEY_BIOS_VERSION, System.getProperty(KEY_BIOS_VERSION));
            }
            if (System.getProperty(KEY_FIRMWARE_VERSION) != null) {
                this.kuraProperties.put(KEY_FIRMWARE_VERSION, System.getProperty(KEY_FIRMWARE_VERSION));
            }
            if (System.getProperty(KEY_PRIMARY_NET_IFACE) != null) {
                this.kuraProperties.put(KEY_PRIMARY_NET_IFACE, System.getProperty(KEY_PRIMARY_NET_IFACE));
            }
            if (System.getProperty(KEY_KURA_HOME_DIR) != null) {
                this.kuraProperties.put(KEY_KURA_HOME_DIR, System.getProperty(KEY_KURA_HOME_DIR));
            }
            if (System.getProperty(KEY_KURA_PLUGINS_DIR) != null) {
                this.kuraProperties.put(KEY_KURA_PLUGINS_DIR, System.getProperty(KEY_KURA_PLUGINS_DIR));
            }
            if (System.getProperty(KEY_KURA_PACKAGES_DIR) != null) {
                this.kuraProperties.put(KEY_KURA_PACKAGES_DIR, System.getProperty(KEY_KURA_PACKAGES_DIR));
            }
            if (System.getProperty(KEY_KURA_DATA_DIR) != null) {
                this.kuraProperties.put(KEY_KURA_DATA_DIR, System.getProperty(KEY_KURA_DATA_DIR));
            }
            if (System.getProperty(KEY_KURA_TMP_DIR) != null) {
                this.kuraProperties.put(KEY_KURA_TMP_DIR, System.getProperty(KEY_KURA_TMP_DIR));
            }
            if (System.getProperty(KEY_KURA_SNAPSHOTS_DIR) != null) {
                this.kuraProperties.put(KEY_KURA_SNAPSHOTS_DIR, System.getProperty(KEY_KURA_SNAPSHOTS_DIR));
            }
            if (System.getProperty(KEY_KURA_SNAPSHOTS_COUNT) != null) {
                this.kuraProperties.put(KEY_KURA_SNAPSHOTS_COUNT, System.getProperty(KEY_KURA_SNAPSHOTS_COUNT));
            }
            if (System.getProperty(KEY_KURA_HAVE_NET_ADMIN) != null) {
                this.kuraProperties.put(KEY_KURA_HAVE_NET_ADMIN, System.getProperty(KEY_KURA_HAVE_NET_ADMIN));
            }
            if (System.getProperty(KEY_KURA_HAVE_WEB_INTER) != null) {
                this.kuraProperties.put(KEY_KURA_HAVE_WEB_INTER, System.getProperty(KEY_KURA_HAVE_WEB_INTER));
            }
            if (System.getProperty(KEY_KURA_STYLE_DIR) != null) {
                this.kuraProperties.put(KEY_KURA_STYLE_DIR, System.getProperty(KEY_KURA_STYLE_DIR));
            }
            if (System.getProperty(KEY_KURA_WIFI_TOP_CHANNEL) != null) {
                this.kuraProperties.put(KEY_KURA_WIFI_TOP_CHANNEL, System.getProperty(KEY_KURA_WIFI_TOP_CHANNEL));
            }
            if (System.getProperty(KEY_KURA_KEY_STORE_PWD) != null) {
                this.kuraProperties.put(KEY_KURA_KEY_STORE_PWD, System.getProperty(KEY_KURA_KEY_STORE_PWD));
            }
            if (System.getProperty(KEY_KURA_TRUST_STORE_PWD) != null) {
                this.kuraProperties.put(KEY_KURA_TRUST_STORE_PWD, System.getProperty(KEY_KURA_TRUST_STORE_PWD));
            }
            if (System.getProperty(KEY_FILE_COMMAND_ZIP_MAX_SIZE) != null) {
                this.kuraProperties.put(KEY_FILE_COMMAND_ZIP_MAX_SIZE,
                        System.getProperty(KEY_FILE_COMMAND_ZIP_MAX_SIZE));
            }
            if (System.getProperty(KEY_FILE_COMMAND_ZIP_MAX_NUMBER) != null) {
                this.kuraProperties.put(KEY_FILE_COMMAND_ZIP_MAX_NUMBER,
                        System.getProperty(KEY_FILE_COMMAND_ZIP_MAX_NUMBER));
            }
            if (System.getProperty(KEY_OS_ARCH) != null) {
                this.kuraProperties.put(KEY_OS_ARCH, System.getProperty(KEY_OS_ARCH));
            }
            if (System.getProperty(KEY_OS_NAME) != null) {
                this.kuraProperties.put(KEY_OS_NAME, System.getProperty(KEY_OS_NAME));
            }
            if (System.getProperty(KEY_OS_VER) != null) {
                this.kuraProperties.put(KEY_OS_VER, getOsVersion()); // System.getProperty(KEY_OS_VER)
            }
            if (System.getProperty(KEY_OS_DISTRO) != null) {
                this.kuraProperties.put(KEY_OS_DISTRO, System.getProperty(KEY_OS_DISTRO));
            }
            if (System.getProperty(KEY_OS_DISTRO_VER) != null) {
                this.kuraProperties.put(KEY_OS_DISTRO_VER, System.getProperty(KEY_OS_DISTRO_VER));
            }
            if (System.getProperty(KEY_JAVA_VERSION) != null) {
                this.kuraProperties.put(KEY_JAVA_VERSION, System.getProperty(KEY_JAVA_VERSION));
            }
            if (System.getProperty(KEY_JAVA_VENDOR) != null) {
                this.kuraProperties.put(KEY_JAVA_VENDOR, System.getProperty(KEY_JAVA_VENDOR));
            }
            if (System.getProperty(KEY_JAVA_VM_NAME) != null) {
                this.kuraProperties.put(KEY_JAVA_VM_NAME, System.getProperty(KEY_JAVA_VM_NAME));
            }
            if (System.getProperty(KEY_JAVA_VM_VERSION) != null) {
                this.kuraProperties.put(KEY_JAVA_VM_VERSION, System.getProperty(KEY_JAVA_VM_VERSION));
            }
            if (System.getProperty(KEY_JAVA_VM_INFO) != null) {
                this.kuraProperties.put(KEY_JAVA_VM_INFO, System.getProperty(KEY_JAVA_VM_INFO));
            }
            if (System.getProperty(KEY_OSGI_FW_NAME) != null) {
                this.kuraProperties.put(KEY_OSGI_FW_NAME, System.getProperty(KEY_OSGI_FW_NAME));
            }
            if (System.getProperty(KEY_OSGI_FW_VERSION) != null) {
                this.kuraProperties.put(KEY_OSGI_FW_VERSION, System.getProperty(KEY_OSGI_FW_VERSION));
            }
            if (System.getProperty(KEY_JAVA_HOME) != null) {
                this.kuraProperties.put(KEY_JAVA_HOME, System.getProperty(KEY_JAVA_HOME));
            }
            if (System.getProperty(KEY_FILE_SEP) != null) {
                this.kuraProperties.put(KEY_FILE_SEP, System.getProperty(KEY_FILE_SEP));
            }
            if (System.getProperty(CONFIG_CONSOLE_DEVICE_MANAGE_SERVICE_IGNORE) != null) {
                this.kuraProperties.put(CONFIG_CONSOLE_DEVICE_MANAGE_SERVICE_IGNORE,
                        System.getProperty(CONFIG_CONSOLE_DEVICE_MANAGE_SERVICE_IGNORE));
            }
            if (System.getProperty(DB_URL_PROPNAME) != null) {
                this.kuraProperties.put(DB_URL_PROPNAME, System.getProperty(DB_URL_PROPNAME));
            }
            if (System.getProperty(DB_CACHE_ROWS_PROPNAME) != null) {
                this.kuraProperties.put(DB_CACHE_ROWS_PROPNAME, System.getProperty(DB_CACHE_ROWS_PROPNAME));
            }
            if (System.getProperty(DB_LOB_FILE_PROPNAME) != null) {
                this.kuraProperties.put(DB_LOB_FILE_PROPNAME, System.getProperty(DB_LOB_FILE_PROPNAME));
            }
            if (System.getProperty(DB_DEFRAG_LIMIT_PROPNAME) != null) {
                this.kuraProperties.put(DB_DEFRAG_LIMIT_PROPNAME, System.getProperty(DB_DEFRAG_LIMIT_PROPNAME));
            }
            if (System.getProperty(DB_LOG_DATA_PROPNAME) != null) {
                this.kuraProperties.put(DB_LOG_DATA_PROPNAME, System.getProperty(DB_LOG_DATA_PROPNAME));
            }
            if (System.getProperty(DB_LOG_SIZE_PROPNAME) != null) {
                this.kuraProperties.put(DB_LOG_SIZE_PROPNAME, System.getProperty(DB_LOG_SIZE_PROPNAME));
            }
            if (System.getProperty(DB_NIO_PROPNAME) != null) {
                this.kuraProperties.put(DB_NIO_PROPNAME, System.getProperty(DB_NIO_PROPNAME));
            }
            if (System.getProperty(DB_WRITE_DELAY_MILLIES_PROPNAME) != null) {
                this.kuraProperties.put(DB_WRITE_DELAY_MILLIES_PROPNAME,
                        System.getProperty(DB_WRITE_DELAY_MILLIES_PROPNAME));
            }

            if (getKuraHome() == null) {
                logger.error("Did not initialize kura.home");
            } else {
                logger.info("Kura home directory is {}", getKuraHome());
                createDirIfNotExists(getKuraHome());
            }
            if (getKuraSnapshotsDirectory() == null) {
                logger.error("Did not initialize kura.snapshots");
            } else {
                logger.info("Kura snapshots directory is {}", getKuraSnapshotsDirectory());
                createDirIfNotExists(getKuraSnapshotsDirectory());
            }
            if (getKuraTemporaryConfigDirectory() == null) {
                logger.error("Did not initialize kura.tmp");
            } else {
                logger.info("Kura tmp directory is {}", getKuraTemporaryConfigDirectory());
                createDirIfNotExists(getKuraTemporaryConfigDirectory());
            }

            logger.info(new StringBuffer().append("Kura version ").append(getKuraVersion()).append(" is starting")
                    .toString());
        } catch (IOException e) {
            throw new ComponentException("Error loading default properties", e);
        }
    }

    protected String readResource(String resource) throws IOException {
        return IOUtil.readResource(resource);
    }

    protected void deactivate(ComponentContext componentContext) {
        this.componentContext = null;
        this.kuraProperties = null;
    }

    private Properties getKuraProperties(String kuraHome, String kuraConfigPropName, String kuraPropFile)
            throws IOException {
        String kuraConfig = System.getProperty(kuraConfigPropName);
        String kuraProp = readResource(kuraPropFile);
        Properties retKuraProperties = new Properties();
        if (kuraProp != null) {
            retKuraProperties.load(new StringReader(kuraProp));
            logger.info("Loaded Jar Resource kura.properties.");
        } else if (kuraConfig != null) {
            try {
                URL kuraConfigUrl = new URL(kuraConfig);
                try (InputStream in = kuraConfigUrl.openStream()) {
                    retKuraProperties.load(in);
                }
                logger.info("Loaded URL kura.properties: {}", kuraConfig);
            } catch (Exception e) {
                logger.warn("Could not open kuraConfig URL", e);
            }
        } else if (kuraHome != null) {
            File kuraPropsFile = new File(kuraHome + File.separator + kuraPropFile);
            if (kuraPropsFile.exists()) {
                try (FileReader fr = new FileReader(kuraPropsFile);) {
                    retKuraProperties.load(fr);
                }
                logger.info("Loaded File kura.properties: {}", kuraPropsFile);
            } else {
                logger.warn("File does not exist: {}", kuraPropsFile);
            }

        } else {
            logger.error("Did not locate kura.properties file in {}", kuraHome);
        }
        return retKuraProperties;
    }

    public void updated(Map<String, Object> properties) {
        // nothing to do
        // all properties of the System service are read-only
    }

    // ----------------------------------------------------------------
    //
    // Service APIs
    //
    // ----------------------------------------------------------------

    /**
     * Returns all KuraProperties for this system. The returned instances is
     * initialized by loading the kura.properties file. Properties defined at
     * the System level - for example using the java -D command line flag -
     * are used to overwrite the values loaded from the kura.properties file
     * in a hierarchical configuration fashion.
     */
    @Override
    public Properties getProperties() {
        return this.kuraProperties;
    }

    @Override
    public String getPrimaryMacAddress() {
        String primaryNetworkInterfaceName = getPrimaryNetworkInterfaceName();
        String macAddress = null;
        InetAddress ip;

        if (OS_MAC_OSX.equals(getOsName())) {
            SafeProcess proc = null;
            try {
                logger.info("executing: ifconfig and looking for {}", primaryNetworkInterfaceName);
                proc = ProcessUtil.exec("ifconfig");
                try (InputStreamReader isr = new InputStreamReader(proc.getInputStream());
                        BufferedReader br = new BufferedReader(isr)) {
                    proc.waitFor();
                    String line = null;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith(primaryNetworkInterfaceName)) {
                            // get the next line and save the MAC
                            line = br.readLine();
                            if (line == null) {
                                throw new IOException("Null imput!");
                            }
                            if (!line.trim().startsWith("ether")) {
                                line = br.readLine();
                            }
                            String[] splitLine = line.split(" ");
                            if (splitLine.length > 0) {
                                return splitLine[1].toUpperCase();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    logger.error("Exception while executing ifconfig!", e);
                }
            } catch (Exception e) {
                logger.error("Failed to get network interfaces", e);
            } finally {
                if (proc != null) {
                    ProcessUtil.destroy(proc);
                }
            }
        } else if (getOsName().contains(WINDOWS_STRING)) {
            try {
                logger.info("executing: InetAddress.getLocalHost {}", primaryNetworkInterfaceName);
                ip = InetAddress.getLocalHost();
                // Windows options are either ethX or wlanX, and eth0 may really not be the correct one
                InetAddress ip2 = getPrimaryIPWindows("eth");
                if (ip2 == null) {
                    ip2 = getPrimaryIPWindows("wlan");
                }
                if (ip2 != null) {
                    ip = ip2;
                }
                NetworkInterface network = NetworkInterface.getByInetAddress(ip);
                byte[] mac = network.getHardwareAddress();
                macAddress = NetUtil.hardwareAddressToString(mac);
                logger.info("macAddress {}", macAddress);
            } catch (UnknownHostException e) {
                logger.error("Failed to get an IP address of the local host", e);
            } catch (SocketException e) {
                logger.error("Failed to get primary IP address", e);
            }
        } else {
            macAddress = NetUtil.getPrimaryMacAddress();
            logger.info("macAddress {}", macAddress);
        }

        return macAddress;
    }

    /**
     * Returns ip of the first interface name of which begins with <code>prefix</code>.
     *
     * @param prefix
     *            network interface name prefix e.g. eth, wlan
     * @return ip of the first interface name of which begins with prefix; null if none found with ip
     * @throws SocketException
     */
    private InetAddress getPrimaryIPWindows(String prefix) throws SocketException {
        InetAddress ip = null;

        Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
        while (networks.hasMoreElements()) {
            NetworkInterface network = networks.nextElement();
            if (network.getName().startsWith(prefix)) {
                Enumeration<InetAddress> ips = network.getInetAddresses();
                if (ips.hasMoreElements()) {
                    ip = ips.nextElement();
                    break;
                }
            }
        }

        return ip;
    }

    @Override
    public String getPrimaryNetworkInterfaceName() {
        if (this.kuraProperties.getProperty(KEY_PRIMARY_NET_IFACE) != null) {
            return this.kuraProperties.getProperty(KEY_PRIMARY_NET_IFACE);
        } else {
            if (OS_MAC_OSX.equals(getOsName())) {
                return "en0";
            } else if (OS_LINUX.equals(getOsName())) {
                return "eth0";
            } else if (getOsName().contains(WINDOWS_STRING)) {
                return "windows";
            } else {
                logger.error("Unsupported platform");
                return null;
            }
        }
    }

    @Override
    public String getPlatform() {
        return this.kuraProperties.getProperty(KEY_PLATFORM);
    }

    @Override
    public String getOsArch() {
        String override = this.kuraProperties.getProperty(KEY_OS_ARCH);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_OS_ARCH);
    }

    @Override
    public String getOsName() {
        String override = this.kuraProperties.getProperty(KEY_OS_NAME);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_OS_NAME);
    }

    @Override    
    public String getOsVersion() {
        String override = this.kuraProperties.getProperty(KEY_OS_VER);
        if (override != null) {
            return override;
        }
        StringBuilder sbOsVersion = new StringBuilder();
        sbOsVersion.append(System.getProperty(KEY_OS_VER));
        if (!OS_LINUX.equals(getOsName())) {
            return sbOsVersion.toString();
        }
        File linuxKernelVersion = new File("/proc/sys/kernel/version");
        try (FileReader fr = new FileReader(linuxKernelVersion); BufferedReader in = new BufferedReader(fr)) {
            if (linuxKernelVersion.exists()) {
                StringBuilder kernelVersionData = new StringBuilder();
                String tempLine = null;
                while ((tempLine = in.readLine()) != null) {
                    kernelVersionData.append(" ");
                    kernelVersionData.append(tempLine);
                }
                sbOsVersion.append(kernelVersionData.toString());
            }
        } catch (Exception e) {
            logger.error("Failed to get OS version", e);
        }
        return sbOsVersion.toString();
    }
    

    @Override
    public String getOsDistro() {
        return this.kuraProperties.getProperty(KEY_OS_DISTRO);
    }

    @Override
    public String getOsDistroVersion() {
        return this.kuraProperties.getProperty(KEY_OS_DISTRO_VER);
    }

    @Override
    public String getJavaVendor() {
        String override = this.kuraProperties.getProperty(KEY_JAVA_VENDOR);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_JAVA_VENDOR);
    }

    @Override
    public String getJavaVersion() {
        String override = this.kuraProperties.getProperty(KEY_JAVA_VERSION);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_JAVA_VERSION);
    }

    @Override
    public String getJavaVmName() {
        String override = this.kuraProperties.getProperty(KEY_JAVA_VM_NAME);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_JAVA_VM_NAME);
    }

    @Override
    public String getJavaVmVersion() {
        String override = this.kuraProperties.getProperty(KEY_JAVA_VM_VERSION);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_JAVA_VM_VERSION);
    }

    @Override
    public String getJavaVmInfo() {
        String override = this.kuraProperties.getProperty(KEY_JAVA_VM_INFO);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_JAVA_VM_INFO);
    }

    @Override
    public String getOsgiFwName() {
        String override = this.kuraProperties.getProperty(KEY_OSGI_FW_NAME);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_OSGI_FW_NAME);
    }

    @Override
    public String getOsgiFwVersion() {
        String override = this.kuraProperties.getProperty(KEY_OSGI_FW_VERSION);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_OSGI_FW_VERSION);
    }

    @Override
    public int getNumberOfProcessors() {
        try {
            return Runtime.getRuntime().availableProcessors();
        } catch (Throwable t) {
            // NoSuchMethodError on pre-1.4 runtimes
        }
        return -1;
    }

    @Override
    public long getTotalMemory() {
        return Runtime.getRuntime().totalMemory() / 1024;
    }

    @Override
    public long getFreeMemory() {
        return Runtime.getRuntime().freeMemory() / 1024;
    }

    @Override
    public String getFileSeparator() {
        String override = this.kuraProperties.getProperty(KEY_FILE_SEP);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_FILE_SEP);
    }

    @Override
    public String getJavaHome() {
        String override = this.kuraProperties.getProperty(KEY_JAVA_HOME);
        if (override != null) {
            return override;
        }

        return System.getProperty(KEY_JAVA_HOME);
    }

    public String getKuraName() {
        return this.kuraProperties.getProperty(KEY_KURA_NAME);
    }

    @Override
    public String getKuraVersion() {
        return this.kuraProperties.getProperty(KEY_KURA_VERSION);
    }

    @Override
    public List<String> getBlacklistedNetworkInterfaces() {
        List<String> blacklistedNetworkInterfaces = new ArrayList<>();
        String prop = this.kuraProperties.getProperty(KEY_KURA_NET_INTERFACE_BLACKLIST);
        if (prop == null)
            return blacklistedNetworkInterfaces;
        String[] netIfaces = prop.split(",");
        for (String iface : netIfaces)
            blacklistedNetworkInterfaces.add(iface.trim());
        return blacklistedNetworkInterfaces;
    }

    @Override
    public String getKuraMarketplaceCompatibilityVersion() {
        String marketplaceCompatibilityVersion = (String) this.kuraProperties
                .getProperty(KEY_KURA_MARKETPLACE_COMPATIBILITY_VERSION);
        if (marketplaceCompatibilityVersion == null) {
            marketplaceCompatibilityVersion = getKuraVersion();
        }
        return marketplaceCompatibilityVersion.replaceAll("KURA[-_ ]", "").replaceAll("[-_]", ".");
    }

    @Override
    public String getKuraHome() {
        return this.kuraProperties.getProperty(KEY_KURA_HOME_DIR);
    }

    public String getKuraPluginsDirectory() {
        return this.kuraProperties.getProperty(KEY_KURA_PLUGINS_DIR);
    }

    @Override
    public String getKuraDataDirectory() {
        return this.kuraProperties.getProperty(KEY_KURA_DATA_DIR);
    }

    @Override
    public String getKuraTemporaryConfigDirectory() {
        return this.kuraProperties.getProperty(KEY_KURA_TMP_DIR);
    }

    @Override
    public String getKuraSnapshotsDirectory() {
        return this.kuraProperties.getProperty(KEY_KURA_SNAPSHOTS_DIR);
    }

    @Override
    public int getKuraSnapshotsCount() {
        int iMaxCount = 10;
        String maxCount = this.kuraProperties.getProperty(KEY_KURA_SNAPSHOTS_COUNT);
        if (maxCount != null && maxCount.trim().length() > 0) {
            try {
                iMaxCount = Integer.parseInt(maxCount);
            } catch (NumberFormatException nfe) {
                logger.error("Error - Invalid kura.snapshots.count setting. Using default.", nfe);
            }
        }
        return iMaxCount;
    }

    @Override
    public int getKuraWifiTopChannel() {
        String topWifiChannel = this.kuraProperties.getProperty(KEY_KURA_WIFI_TOP_CHANNEL);
        if (topWifiChannel != null && topWifiChannel.trim().length() > 0) {
            return Integer.parseInt(topWifiChannel);
        }

        logger.warn("The last wifi channel is not defined for this system - setting to lowest common value of 11");
        return 11;
    }

    @Override
    public String getKuraStyleDirectory() {
        return this.kuraProperties.getProperty(KEY_KURA_STYLE_DIR);
    }

    @Override
    public String getKuraWebEnabled() {
        return this.kuraProperties.getProperty(KEY_KURA_HAVE_WEB_INTER);
    }

    @Override
    public int getFileCommandZipMaxUploadSize() {
        String commandMaxUpload = this.kuraProperties.getProperty(KEY_FILE_COMMAND_ZIP_MAX_SIZE);
        if (commandMaxUpload != null && commandMaxUpload.trim().length() > 0) {
            return Integer.parseInt(commandMaxUpload);
        }
        logger.warn("Maximum command line upload size not available. Set default to 100 MB");
        return 100;
    }

    @Override
    public int getFileCommandZipMaxUploadNumber() {
        String commandMaxFilesUpload = this.kuraProperties.getProperty(KEY_FILE_COMMAND_ZIP_MAX_NUMBER);
        if (commandMaxFilesUpload != null && commandMaxFilesUpload.trim().length() > 0) {
            return Integer.parseInt(commandMaxFilesUpload);
        }
        logger.warn(
                "Missing the parameter that specifies the maximum number of files uploadable using the command servlet. Set default to 1024 files");
        return 1024;
    }

    @Override
    public String getBiosVersion() {
        String override = this.kuraProperties.getProperty(KEY_BIOS_VERSION);
        if (override != null) {
            return override;
        }

        String biosVersion = UNSUPPORTED;

        if (OS_LINUX.equals(getOsName())) {
            if (LINUX_WR42_VERSION.equals(getOsVersion()) || LINUX_WR43_VERSION.equals(getOsVersion())) {
                biosVersion = runSystemCommand("eth_vers_bios");
            } else {
                String biosTmp = runSystemCommand("dmidecode -s bios-version");
                if (biosTmp.length() > 0 && !biosTmp.contains("Permission denied")) {
                    biosVersion = biosTmp;
                }
            }
        } else if (OS_MAC_OSX.equals(getOsName())) {
            String[] cmds = { SH_CMD, "-c", "system_profiler SPHardwareDataType | grep 'Boot ROM'" };
            String biosTmp = runSystemCommand(cmds);
            if (biosTmp.contains(": ")) {
                biosVersion = biosTmp.split(":\\s+")[1];
            }
        } else if (getOsName().contains(WINDOWS_STRING)) {
            String[] cmds = { "wmic", "bios", "get", "smbiosbiosversion" };
            String biosTmp = runSystemCommand(cmds);
            if (biosTmp.contains("SMBIOSBIOSVersion")) {
                biosVersion = biosTmp.split("SMBIOSBIOSVersion\\s+")[1];
                biosVersion = biosVersion.trim();
            }
        }

        return biosVersion;
    }

    @Override
    public String getDeviceName() {
        String override = this.kuraProperties.getProperty(KEY_DEVICE_NAME);
        if (override != null) {
            return override;
        }

        String deviceName = UNKNOWN;
        if (OS_MAC_OSX.equals(getOsName())) {
            String displayTmp = runSystemCommand("scutil --get ComputerName");
            if (displayTmp.length() > 0) {
                deviceName = displayTmp;
            }
        } else if (OS_LINUX.equals(getOsName()) || OS_CLOUDBEES.equals(getOsName())) {
            String displayTmp = runSystemCommand("hostname");
            if (displayTmp.length() > 0) {
                deviceName = displayTmp;
            }
        }
        return deviceName;
    }

    @Override
    public String getFirmwareVersion() {
        String override = this.kuraProperties.getProperty(KEY_FIRMWARE_VERSION);
        if (override != null) {
            return override;
        }

        String fwVersion = UNSUPPORTED;

        if (OS_LINUX.equals(getOsName()) && getOsVersion() != null) {
            if (getOsVersion().startsWith(LINUX_WR42_VERSION) || getOsVersion().startsWith(LINUX_WR43_VERSION)) {
                fwVersion = runSystemCommand("eth_vers_cpld") + " " + runSystemCommand("eth_vers_uctl");
            } else if (getOsVersion().startsWith("3.0.35-12.09.01+yocto")) {
                fwVersion = runSystemCommand("eth_vers_avr");
            }
        }
        return fwVersion;
    }

    @Override
    public String getModelId() {
        String override = this.kuraProperties.getProperty(KEY_MODEL_ID);
        if (override != null) {
            return override;
        }

        String modelId = UNKNOWN;

        if (OS_MAC_OSX.equals(getOsName())) {
            String modelTmp = runSystemCommand("sysctl -b hw.model");
            if (modelTmp.length() > 0) {
                modelId = modelTmp;
            }
        } else if (OS_LINUX.equals(getOsName())) {
            String modelTmp = runSystemCommand(DMIDECODE_CMD);
            if (modelTmp.contains("Version: ")) {
                modelId = modelTmp.split("Version:\\s+")[1].split("\n")[0];
            }
        }

        return modelId;
    }

    @Override
    public String getModelName() {
        String override = this.kuraProperties.getProperty(KEY_MODEL_NAME);
        if (override != null) {
            return override;
        }

        String modelName = UNKNOWN;

        if (OS_MAC_OSX.equals(getOsName())) {
            String[] cmds = { SH_CMD, "-c", "system_profiler SPHardwareDataType | grep 'Model Name'" };
            String modelTmp = runSystemCommand(cmds);
            if (modelTmp.contains(": ")) {
                modelName = modelTmp.split(":\\s+")[1];
            }
        } else if (OS_LINUX.equals(getOsName())) {
            String modelTmp = runSystemCommand(DMIDECODE_CMD);
            if (modelTmp.contains("Product Name: ")) {
                modelName = modelTmp.split("Product Name:\\s+")[1].split("\n")[0];
            }
        }

        return modelName;
    }

    @Override
    public String getPartNumber() {
        String override = this.kuraProperties.getProperty(KEY_PART_NUMBER);
        if (override != null) {
            return override;
        }

        String partNumber = UNSUPPORTED;

        if (OS_LINUX.equals(getOsName())
                && (LINUX_WR42_VERSION.equals(getOsVersion()) || LINUX_WR43_VERSION.equals(getOsVersion()))) {
            partNumber = runSystemCommand("eth_partno_bsp") + " " + runSystemCommand("eth_partno_epr");

        }

        return partNumber;
    }

    @Override
    public String getSerialNumber() {
        String override = this.kuraProperties.getProperty(KEY_SERIAL_NUM);
        if (override != null) {
            return override;
        }

        String serialNum = UNKNOWN;

        if (OS_MAC_OSX.equals(getOsName())) {
            String[] cmds = { SH_CMD, "-c", "system_profiler SPHardwareDataType | grep 'Serial Number'" };
            String serialTmp = runSystemCommand(cmds);
            if (serialTmp.contains(": ")) {
                serialNum = serialTmp.split(":\\s+")[1];
            }
        } else if (OS_LINUX.equals(getOsName())) {
            String serialTmp = runSystemCommand(DMIDECODE_CMD);
            if (serialTmp.contains("Serial Number: ")) {
                serialNum = serialTmp.split("Serial Number:\\s+")[1].split("\n")[0];
            }
        }

        return serialNum;
    }

    @Override
    public char[] getJavaKeyStorePassword() throws InvalidKeyException, NoSuchAlgorithmException,
            NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, IOException {
        String keyStorePwd = this.kuraProperties.getProperty(KEY_KURA_KEY_STORE_PWD);
        if (keyStorePwd != null) {
            return keyStorePwd.toCharArray();
        }
        return new char[0];
    }

    @Override
    public char[] getJavaTrustStorePassword() throws InvalidKeyException, NoSuchAlgorithmException,
            NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, IOException {
        String trustStorePwd = this.kuraProperties.getProperty(KEY_KURA_TRUST_STORE_PWD);
        if (trustStorePwd != null) {
            return trustStorePwd.toCharArray();
        }
        return new char[0];
    }

    @Override
    public Bundle[] getBundles() {
        if (this.componentContext == null) {
            return new Bundle[0];
        }
        return this.componentContext.getBundleContext().getBundles();
    }

    @Override
    public List<String> getDeviceManagementServiceIgnore() {
        String servicesToIgnore = this.kuraProperties.getProperty(CONFIG_CONSOLE_DEVICE_MANAGE_SERVICE_IGNORE);
        if (servicesToIgnore != null && !servicesToIgnore.trim().isEmpty()) {
            String[] servicesArray = servicesToIgnore.split(",");
            if (servicesArray != null && servicesArray.length > 0) {
                List<String> services = new ArrayList<>();
                for (String service : servicesArray) {
                    services.add(service);
                }
                return services;
            }
        }

        return new ArrayList<>();
    }

    // ----------------------------------------------------------------
    //
    // Private Methods
    //
    // ----------------------------------------------------------------

    private static void createDirIfNotExists(String fileName) {
        // Make sure the configuration directory exists - create it if not
        File file = new File(fileName);
        if (!file.exists() && !file.mkdirs()) {
            logger.error("Failed to create the temporary configuration directory: {}", fileName);
            if (Boolean.getBoolean("org.eclipse.kura.core.dontExitOnFailure")) {
                throw new RuntimeException(
                        String.format("Failed to create the temporary configuration directory: %s", fileName));
            }
            System.exit(-1);
        }
    }
}

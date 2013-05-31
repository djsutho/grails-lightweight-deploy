package grails.plugin.lightweight;

import com.google.common.io.ByteStreams;
import com.yammer.metrics.jetty.InstrumentedSelectChannelConnector;
import com.yammer.metrics.jetty.InstrumentedSslSocketConnector;
import com.yammer.metrics.reporting.AdminServlet;
import grails.plugin.lightweight.logging.RequestLoggingFactory;
import grails.plugin.lightweight.logging.ServerLoggingFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.TagLibConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Based heavily on code from Burt Beckwith's standalone plugin and Codehale's Dropwizard.
 */
public class Launcher {
    private static final Logger logger = LoggerFactory.getLogger(Launcher.class);

    private static final String EXTERNAL_CONNECTOR_NAME = "external";
    private static final String INTERNAL_CONNECTOR_NAME = "internal";

	private Configuration configuration;

	/**
	 * Start the server.
	 */
	public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("Requires 1 argument, which is the path to the config.yml file");
        }

		final Launcher launcher = new Launcher(args[0]);
		launcher.start();
	}

	public Launcher(String configYmlPath) throws IOException {
        logger.info("Reading config from: " + configYmlPath);
		this.configuration = new Configuration(configYmlPath);
        logger.info("Using configuration: " + this.configuration);

        configureLogging();
	}

    protected void configureLogging() {
        if (this.configuration.isServerLoggingEnabled()) {
            ServerLoggingFactory loggingFactory = new ServerLoggingFactory(this.configuration);
            loggingFactory.configure();
        }
    }

	protected void start() throws IOException {
		final File exploded = extractWar();
		deleteExplodedOnShutdown(exploded);

		System.setProperty("org.eclipse.jetty.xml.XmlParser.NotValidating", "true");

		Server server = configureJetty(exploded);

		startJetty(server);
	}

	protected File extractWebdefaultXml() throws IOException {
		InputStream embeddedWebdefault = getClass().getClassLoader().getResourceAsStream("webdefault.xml");
		File temp = File.createTempFile("webdefault", ".war").getAbsoluteFile();
		temp.getParentFile().mkdirs();
		temp.deleteOnExit();
		ByteStreams.copy(embeddedWebdefault, new FileOutputStream(temp));
		return temp;
	}

	protected Server configureJetty(File exploded) throws IOException {
        Server server = new Server();

        HandlerCollection handlerCollection = new HandlerCollection();
        handlerCollection.addHandler(configureExternal(server, exploded));
        if (this.configuration.hasAdminPort()) {
            handlerCollection.addHandler(configureInternal(server));
        }
        if (this.configuration.isRequestLoggingEnabled()) {
            RequestLoggingFactory requestLoggingFactory = new RequestLoggingFactory(this.configuration);
            handlerCollection.addHandler(requestLoggingFactory.configure());
        }
        server.setHandler(handlerCollection);

        return server;
	}

    protected Handler configureExternal(Server server, File exploded) throws IOException {
		if (this.configuration.isSsl()) {
            logger.info("Creating https connector");
			addConnector(server, configureExternalHttpsConnector());
		} else {
            logger.info("Creating http connector");
		    addConnector(server, configureExternalHttpConnector());
        }

        return createApplicationContext(exploded.getPath());
    }

    protected Handler configureInternal(Server server) {
        logger.info("Configuring admin connector");

        addConnector(server, configureInternalConnector());

        return configureAdminContext();
    }

    protected AbstractConnector configureInternalConnector() {
        final SocketConnector connector = new SocketConnector();
        connector.setPort(this.configuration.getAdminPort());
        connector.setName("internal");
        connector.setThreadPool(new QueuedThreadPool(8));
        connector.setName(INTERNAL_CONNECTOR_NAME);
        return connector;
    }

	protected void startJetty(Server server) {
		try {
			server.start();
			logger.info("Startup complete. Server running on " + this.configuration.getPort());
		}
		catch (Exception e) {
			e.printStackTrace();
			System.err.println("Error loading Jetty: " + e.getMessage());
			System.exit(1);
		}
	}

    protected Handler configureAdminContext() {
        final ServletContextHandler handler = new ServletContextHandler();
        handler.addServlet(new ServletHolder(new AdminServlet()), "/*");
        handler.setConnectorNames(new String[]{INTERNAL_CONNECTOR_NAME});
        return handler;
    }

	protected Handler createApplicationContext(String webappRoot) throws IOException {
		// Jetty requires a 'defaults descriptor' on the filesystem
		File webDefaults = extractWebdefaultXml();

		WebAppContext context = new WebAppContext(webappRoot, "/");

		System.setProperty("java.naming.factory.url.pkgs", "org.eclipse.jetty.jndi");
		System.setProperty("java.naming.factory.initial", "org.eclipse.jetty.jndi.InitialContextFactory");

		context.setConfigurations(new org.eclipse.jetty.webapp.Configuration[] {new WebInfConfiguration(),
                                                                                new WebXmlConfiguration(),
                                                                                new MetaInfConfiguration(),
                                                                                new FragmentConfiguration(),
                                                                                new EnvConfiguration(),
                                                                                new PlusConfiguration(),
                                                                                new JettyWebXmlConfiguration(),
                                                                                new TagLibConfiguration()});
		context.setDefaultsDescriptor(webDefaults.getPath());

		System.setProperty("TomcatKillSwitch.active", "true"); // workaround to prevent server exiting

        context.setConnectorNames(new String[] {EXTERNAL_CONNECTOR_NAME});

		return context;
	}

	protected AbstractConnector configureExternalHttpConnector() {
        InstrumentedSelectChannelConnector connector = new InstrumentedSelectChannelConnector(this.configuration.getPort());
        connector.setName("external");
        connector.setUseDirectBuffers(true);
        return connector;
	}

	protected AbstractConnector configureExternalHttpsConnector() {
        InstrumentedSslSocketConnector connector = new InstrumentedSslSocketConnector(this.configuration.getPort());
        connector.setName("external");
        connector.getSslContextFactory().setCertAlias(this.configuration.getKeyStoreAlias());
        connector.getSslContextFactory().setKeyStorePath(this.configuration.getKeyStorePath());
        connector.getSslContextFactory().setKeyStorePassword(this.configuration.getKeyStorePassword());
        return connector;
	}

    protected void addConnector(Server server, AbstractConnector connector) {
        connector.setMaxIdleTime(200 * 1000);
        connector.setLowResourcesMaxIdleTime(0);
        connector.setRequestBufferSize(16 * 1024);
        connector.setRequestHeaderSize(6 * 1024);
        connector.setResponseBufferSize(32 * 1024);
        connector.setResponseHeaderSize(6 * 1024);

        server.addConnector(connector);
    }

	protected File getWorkDir() {
		return new File(System.getProperty("java.io.tmpdir", ""));
	}

	protected File extractWar() throws IOException {
		File dir = new File(getWorkDir(), "lightweight-war");
		Utils.deleteDir(dir);
		dir.mkdirs();
		return extractWar(dir);
	}

	protected File extractWar(File dir) throws IOException {
        String filePath = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        logger.info("Exploding jar at: " + filePath);
        FileInputStream fileInputStream = new FileInputStream(new File(filePath));
		return extractWar(fileInputStream, File.createTempFile("embedded", ".war", dir).getAbsoluteFile());
	}

	protected File extractWar(InputStream embeddedWarfile, File destinationWarfile) throws IOException {
		destinationWarfile.getParentFile().mkdirs();
		destinationWarfile.deleteOnExit();
		ByteStreams.copy(embeddedWarfile, new FileOutputStream(destinationWarfile));
		return explode(destinationWarfile);
	}

	protected File explode(File war) throws IOException {
		String basename = war.getName();
		int index = basename.lastIndexOf('.');
		if (index > -1) {
			basename = basename.substring(0, index);
		}
		File explodedDir = new File(war.getParentFile(), basename + "-exploded-" + System.currentTimeMillis());

		ZipFile zipfile = new ZipFile(war);
		for (Enumeration<? extends ZipEntry> e = zipfile.entries(); e.hasMoreElements(); ) {
			Utils.unzip(e.nextElement(), zipfile, explodedDir);
		}
		zipfile.close();

		return explodedDir;
	}

	protected void deleteExplodedOnShutdown(final File exploded) {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				Utils.deleteDir(exploded);
			}
		});
	}
}

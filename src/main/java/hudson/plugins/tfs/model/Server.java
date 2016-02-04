package hudson.plugins.tfs.model;

import com.microsoft.tfs.core.TFSConfigurationServer;
import com.microsoft.tfs.core.clients.versioncontrol.VersionControlClient;
import com.microsoft.tfs.core.clients.webservices.IIdentityManagementService;
import com.microsoft.tfs.core.clients.webservices.IdentityManagementException;
import com.microsoft.tfs.core.clients.webservices.IdentityManagementService;
import com.microsoft.tfs.core.httpclient.ProxyHost;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.TaskListener;
import hudson.plugins.tfs.commands.ServerConfigurationProvider;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.microsoft.tfs.core.TFSTeamProjectCollection;
import com.microsoft.tfs.core.httpclient.Credentials;
import com.microsoft.tfs.core.httpclient.DefaultNTCredentials;
import com.microsoft.tfs.core.httpclient.UsernamePasswordCredentials;
import com.microsoft.tfs.core.util.CredentialsUtils;
import com.microsoft.tfs.core.util.URIUtils;
import com.microsoft.tfs.util.Closable;
import hudson.remoting.Callable;
import hudson.remoting.VirtualChannel;
import jenkins.model.Jenkins;

public class Server implements ServerConfigurationProvider, Closable {
    
    private static final String nativeFolderPropertyName = "com.microsoft.tfs.jni.native.base-directory";
    private final String url;
    private final String userName;
    private final String userPassword;
    private Workspaces workspaces;
    private Map<String, Project> projects = new HashMap<String, Project>();
    private final Launcher launcher;
    private final TaskListener taskListener;
    private final TFSTeamProjectCollection tpc;
    private MockableVersionControlClient mockableVcc;

    public Server(final Launcher launcher, final TaskListener taskListener, final String url, final String username, final String password) throws IOException {
        this.launcher = launcher;
        this.taskListener = taskListener;
        this.url = url;
        this.userName = username;
        this.userPassword = password;
        final URI uri = URIUtils.newURI(url);

        NativeLibraryManager.initialize();

        Credentials credentials = null;
        // In case no user name is provided and the current platform supports
        // default credentials, use default credentials
        if ((username == null || username.length() == 0) && CredentialsUtils.supportsDefaultCredentials()) {
            credentials = new DefaultNTCredentials();
        }
        else if (username != null && password != null) {
            credentials = new UsernamePasswordCredentials(username, password);
        }

        if (credentials != null) {
            final ProxyHost proxyHost = determineProxyHost(uri);
            final ModernConnectionAdvisor advisor = new ModernConnectionAdvisor(proxyHost);
            this.tpc = new TFSTeamProjectCollection(uri, credentials, advisor);
        }
        else {
            this.tpc = null;
        }
    }

    static ProxyHost determineProxyHost(final URI uri) {
        final Jenkins jenkins = Jenkins.getInstance();
        final ProxyConfiguration proxyConfiguration = jenkins == null ? null : jenkins.proxy;
        final ProxyHost proxyHost;
        if (proxyConfiguration != null) {
            final String host = uri.getHost();
            boolean shouldProxy = shouldProxy(proxyConfiguration, host);
            if (shouldProxy) {
                // TODO: The version of httpclient used by the TFS SDK does not support proxy auth
                proxyHost = new ProxyHost(proxyConfiguration.name, proxyConfiguration.port);
            }
            else {
                proxyHost = null;
            }
        }
        else {
            proxyHost = null;
        }
        return proxyHost;
    }

    static boolean shouldProxy(final ProxyConfiguration proxyConfiguration, final String host) {
        // inspired by https://github.com/jenkinsci/git-client-plugin/commit/2fefeae06db79d09d6604994001f8f2bd21549e1
        boolean shouldProxy = true;
        for (final Pattern p : proxyConfiguration.getNoProxyHostPatterns()) {
            if (p.matcher(host).matches()) {
                shouldProxy = false;
                break;
            }
        }
        return shouldProxy;
    }

    Server(String url) throws IOException {
        this(null, null, url, null, null);
    }

    public Project getProject(String projectPath) {
        if (! projects.containsKey(projectPath)) {
            projects.put(projectPath, new Project(this, projectPath));
        }
        return projects.get(projectPath);
    }

    public Workspaces getWorkspaces() {
        if (workspaces == null) {
            workspaces = new Workspaces(this);
        }
        return workspaces;
    }

    public MockableVersionControlClient getVersionControlClient() {
        if (mockableVcc == null) {
            synchronized (this) {
                if (mockableVcc == null) {
                    final VersionControlClient vcc = tpc.getVersionControlClient();
                    mockableVcc = new MockableVersionControlClient(vcc);
                }
            }
        }
        return mockableVcc;
    }

    public <T, E extends Exception> T execute(final Callable<T, E> callable) {
        try {
            final VirtualChannel channel = launcher.getChannel();
            final T result = channel.call(callable);
            return result;
        } catch (final Exception e) {
            // convert from checked to unchecked exception
            throw new RuntimeException(e);
        }
    }

    public String getUrl() {
        return url;
    }

    public String getUserName() {
        return userName;
    }

    public String getUserPassword() {
        return userPassword;
    }

    public Launcher getLauncher() {
        return launcher;
    }

    public TaskListener getListener() {
        return taskListener;
    }

    public synchronized void close() {
        if (this.mockableVcc != null) {
            this.mockableVcc.close();
        }
        if (this.tpc != null) {
            // Close the configuration server connection that should be closed by
            // TFSTeamProjectCollection
            // The field is private, so use reflection
            // This should be removed when the TFS SDK is fixed
            // Post in MSDN forum: social.msdn.microsoft.com/Forums/vstudio/en-US/79985ef1-b35d-4fc5-af0b-b95e28402b83
            try {
                Field f = TFSTeamProjectCollection.class.getDeclaredField("configurationServer");
                f.setAccessible(true);
                TFSConfigurationServer configurationServer = (TFSConfigurationServer) f.get(this.tpc);
                if (configurationServer != null) {
                    configurationServer.close();
                }
                f.setAccessible(false);
            } catch (NoSuchFieldException ignore) {
            } catch (IllegalAccessException ignore) {
            }
            this.tpc.close();
        }
    }

    public IIdentityManagementService createIdentityManagementService() {
        IIdentityManagementService ims;
        try {
            ims = new IdentityManagementService(tpc);
        } catch (IdentityManagementException e) {
            ims = new LegacyIdentityManagementService();
        }
        return ims;
    }
}

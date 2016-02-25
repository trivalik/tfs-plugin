package hudson.plugins.tfs.browsers;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.Descriptor;
import hudson.plugins.tfs.TeamFoundationServerScm;
import hudson.plugins.tfs.model.ChangeSet;
import hudson.scm.EditType;
import hudson.scm.RepositoryBrowser;
import hudson.scm.SCM;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;

import org.kohsuke.stapler.DataBoundConstructor;

public class TeamSystemWebAccessBrowser extends TeamFoundationServerRepositoryBrowser {

    private static final long serialVersionUID = 1L;

    private final String url;

    @DataBoundConstructor
    public TeamSystemWebAccessBrowser(String urlExample) {
        this.url = Util.fixEmpty(urlExample);
    }

    public String getUrl() {
        return url;
    }

    private String getServerConfiguration(ChangeSet changeset) {
        AbstractProject<?, ?> project = changeset.getParent().build.getProject();
        SCM scm = project.getScm();
        if (scm instanceof TeamFoundationServerScm) {
            return ((TeamFoundationServerScm) scm).getServerUrl(changeset.getParent().build);
        } else {
            throw new IllegalStateException("TFS repository browser used on a non TFS SCM");
        }
    }

    private String getBaseUrlString(ChangeSet changeSet) throws MalformedURLException {
        String baseUrl;
        if (url != null) {
            baseUrl = DescriptorImpl.getBaseUrl(url);
        } else {
            String scmUrl = getServerConfiguration(changeSet);
            if (scmUrl.endsWith("/")) {
              baseUrl = scmUrl;
            } else {
              baseUrl = String.format("%s/", scmUrl);
            }
        }
        return baseUrl;
    }

    /*
     * Gets the link to a specific change set.
     * E.g. http://tswaserver:8090/_versionControl/changeset/99
     */
    @Override
    public URL getChangeSetLink(ChangeSet changeSet) throws IOException {
      return new URL(String.format("%s_versionControl/changeset/%s",
                                  getBaseUrlString(changeSet),
                                  changeSet.getVersion()));
    }

    /*
     * Gets the link for a specific file in a change set.
     */
    public URL getFileLink(ChangeSet.Item item) throws IOException {
      return new URL(String.format("%s_versionControl/changeset/%s#path=%s&_a=contents",
                                   getBaseUrlString(item.getParent()),
                                   item.getParent().getVersion(),
                                   URLEncoder.encode(item.getPath(),"UTF-8")));
    }

    /*
     * Gets the Compare to a specific file in a change set.
     */
    public URL getDiffLink(ChangeSet.Item item) throws IOException {
        ChangeSet parent = item.getParent();
        if (item.getEditType() != EditType.EDIT) {
            return null;
        } 
        return new URL(String.format("%s_versionControl/changeset/%s#path=%s&_a=compare",
                                  getBaseUrlString(item.getParent()),
                                  item.getParent().getVersion(),
                                  URLEncoder.encode(item.getPath(),"UTF-8")));
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<RepositoryBrowser<?>> {

        public DescriptorImpl() {
            super(TeamSystemWebAccessBrowser.class);
        }

        @Override
        public String getDisplayName() {
            return "Team System Web Access";
        }
        
        public static String getBaseUrl(String urlExample) throws MalformedURLException {

          URL url = new URL(urlExample);
          String path = url.getPath();
          // path needs to end with / and users sometimes forget so we add it
          if (!path.endsWith("/")) {
            path = path + "/";
          }
          return new URL(url.getProtocol(), url.getHost(), url.getPort(), path).toString();
        }
    }
}

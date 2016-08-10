package org.jenkinsci.plugins.codefresh;

import hudson.Launcher;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.BuildBadgeAction;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import hudson.util.Secret;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import java.io.IOException;
import static java.lang.Thread.sleep;
import java.net.MalformedURLException;
import java.util.List;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.stapler.QueryParameter;


public class CodefreshBuilder extends Builder {

    private boolean launch;
    private final String cfService;
    private final boolean selectService;
    private final String cfBranch;

    @DataBoundConstructor
    public CodefreshBuilder(Boolean launch, SelectService selectService) {
        this.launch = launch;

        if (selectService != null) {
            this.cfService = selectService.cfService;
            this.cfBranch = selectService.cfBranch;
            this.selectService = true;
        }
        else
        {
            this.selectService = false;
            this.cfService = null;
            this.cfBranch = "";
        }

    }

    public static class SelectService
    {
        private final String cfService;
        private final String cfBranch;

        @DataBoundConstructor
        public SelectService(String cfService, String cfBranch)
        {
            this.cfService = cfService;
            this.cfBranch = cfBranch;
        }
    }

    public boolean isLaunch() {
        return launch;
    }

    public String getCfService() {
        return cfService;
    }

    public boolean isSelectService(){
        return selectService;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {

      CFProfile profile  = new CFProfile(getDescriptor().getCfUser(), getDescriptor().getCfToken());
      String serviceId = "";
      String branch = "";
      
      if (cfService == null)
      {
        SCM scm = build.getProject().getScm();
        if (!(scm instanceof GitSCM)) {
            return false;
        }

        final GitSCM gitSCM = (GitSCM) scm;
        branch = gitSCM.getBranches().get(0).getName().replaceFirst("\\*\\/", "");
        RemoteConfig remote = gitSCM.getRepositories().get(0);
        URIish uri = remote.getURIs().get(0);
        String gitPath = uri.getPath();
        serviceId = profile.getServiceIdByPath(gitPath);
        if (serviceId != null)
        {
            listener.getLogger().println("\nTriggering Codefresh build. Service: "+gitPath+".\n");
        }
        else
        {
            listener.getLogger().println("\nUser "+getDescriptor().getCfUser()+"has no Codefresh service defined for url "+gitPath+".\n Exiting.");
            return false;
        }
      }
      else
      {

        serviceId = profile.getServiceIdByName(cfService);
        branch  = cfBranch;
        if (serviceId != null)
        {
            listener.getLogger().println("\nTriggering Codefresh build. Service: "+cfService+".\n");
        }
        else
        {
            listener.getLogger().println("\nService Id not found for "+cfService+".\n Exiting.");
            return false;
        }
        

      }
      CFApi api = new CFApi(getDescriptor().getCfToken());
      String buildId = api.startBuild(serviceId, branch);
      String progressId = api.getBuildProgress(buildId);
      String status = api.getProgressStatus(progressId);
      String progressUrl = api.getBuildUrl(progressId);
      while (status.equals("running"))
      {
          listener.getLogger().println("Codefresh build running - "+progressUrl+"\n Waiting 5 seconds...");
          Thread.sleep(5 * 1000);
          status = api.getProgressStatus(progressId);
      }
      //build.addAction(new CodefreshAction(progressUrl));
      build.addAction(new CodefreshBuildBadgeAction(progressUrl, status));
      switch (status) {
          case "success":
              listener.getLogger().println("Codefresh build successfull!");
              return true;
          case "error":
              listener.getLogger().println("Codefresh build failed!");
              return false;
          default:
              listener.getLogger().println("Codefresh status "+status+" unclassified.");
              return false;
      }

    }




    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }


    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        private String cfUser;
        private Secret cfToken;
        private CFApi api;


        public FormValidation doTestConnection(@QueryParameter("cfUser") final String cfUser, @QueryParameter("cfToken") final String cfToken) throws IOException
        {
            String userName = null;
            try {
                api = new CFApi(Secret.fromString(cfToken));
                userName = api.getUser();
            }
            catch (IOException e)
            {
                return FormValidation.error("Couldn't connect. Please check your token and internet connection.\n" + e.getMessage());
            }
                      
            if (  userName != null )
            {
                if ( userName.equals(cfUser) )
                {
                   return FormValidation.ok("Success");
                }
                else
                {
                    return FormValidation.error("Username and token don't match");
                }
            }
            return FormValidation.error("Couldn't connect. Please check your token and internet connection.");
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Define Codefresh Integration";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            cfUser = formData.getString("cfUser");
            cfToken = Secret.fromString(formData.getString("cfToken"));
            save();
            return super.configure(req,formData);
        }


        public String getCfUser() {
            return cfUser;
        }

        public Secret getCfToken() {
            return cfToken;
        }


        public ListBoxModel doFillCfServiceItems(@QueryParameter("cfService") String cfService) throws  IOException, MalformedURLException {
            ListBoxModel items = new ListBoxModel();
            if (cfToken == null){
                throw new IOException("No Codefresh Integration Defined!!! Please configure in System Settings.");
            }
            try {
                api = new CFApi(cfToken);
                for (CFService srv: api.getServices())
                {
                    String name = srv.getName();
                    items.add(new Option(name, name, cfService.equals(name)));

                }
            }
            catch (IOException e)
            {
                    throw e;
            }
            return items;
        }

    }

    public static class CodefreshBuildBadgeAction implements BuildBadgeAction {

        private final String buildUrl;
        private final String buildStatus;
        private final String iconFile;

        public CodefreshBuildBadgeAction(String buildUrl, String buildStatus) {
            super();
            this.buildUrl = buildUrl;
            this.buildStatus = buildStatus;
                switch (buildStatus) {
                    case "success":
                        this.iconFile = "/plugin/codefresh/images/16x16/leaves_green.png";
                        break;
                    case "unstable":
                        this.iconFile = "/plugin/codefresh/images/16x16/leaves_yellow.png";
                        break;
                    case "error":
                        this.iconFile = "/plugin/codefresh/images/16x16/leaves_red.png";
                        break;
                    default:
                        this.iconFile = "/plugin/codefresh/images/16x16/leaves_green.png";
                }
        }

        @Override
        public String getDisplayName() {
            return "Codefresh Build Page";
        }

        @Override
        public String getIconFileName() {
            return iconFile;
        }

        @Override
        public String getUrlName() {
            return buildUrl;
        }

    }

}

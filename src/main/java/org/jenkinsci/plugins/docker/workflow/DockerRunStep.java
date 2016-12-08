/*
 * The MIT License
 *
 * Copyright (c) 2015, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.docker.workflow;

import com.google.common.base.Optional;
import org.jenkinsci.plugins.docker.workflow.client.DockerClient;
import com.google.inject.Inject;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.WorkspaceList;
import java.util.Collection;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

import hudson.util.VersionNumber;
import org.jenkinsci.plugins.docker.commons.fingerprint.DockerFingerprints;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class DockerRunStep extends AbstractStepImpl {
    
    private static final Logger LOGGER = Logger.getLogger(DockerRunStep.class.getName());
    private final @Nonnull String image;
    private String entryPoint;
    private String command;
    private String user;
    private String workspace;
    private Boolean includeVolumes;
    private Boolean includeEnvironment;
    private String args;
    private String toolName;

    @DataBoundConstructor public DockerRunStep(@Nonnull String image) {
        this.image = image;
    }
    
    public String getImage() {
        return image;
    }

    @DataBoundSetter
    public void setEntryPoint(String entryPoint) {
        this.entryPoint = Util.fixEmpty(entryPoint);
    }

    public String getEntryPoint() {
        return entryPoint;
    }

    @DataBoundSetter
    public void setCommand(String command) {
        this.command = Util.fixEmpty(command);
    }

    public String getCommand() {
        return command;
    }

    @DataBoundSetter
    public void setUser(String user) {
        this.user = Util.fixEmpty(user);
    }

    public String getUser() {
        return user;
    }

    @DataBoundSetter
    public void setWorkspace(String workspace) {
        this.workspace = Util.fixEmpty(workspace);
    }

    public String getWorkspace() {
        return workspace;
    }

    @DataBoundSetter
    public void setIncludeVolumes(Boolean includeVolumes) {
        this.includeVolumes = includeVolumes;
    }

    public Boolean getIncludeVolumes() {
        return includeVolumes;
    }

    @DataBoundSetter
    public void setIncludeEnvironment(Boolean includeEnvironment) {
        this.includeEnvironment = includeEnvironment;
    }

    public Boolean getIncludeEnvironment() {
        return includeEnvironment;
    }

    @DataBoundSetter
    public void setArgs(String args) {
        this.args = Util.fixEmpty(args);
    }

    public String getArgs() {
        return args;
    }

    @DataBoundSetter public void setToolName(String toolName) {
        this.toolName = Util.fixEmpty(toolName);
    }

    public String getToolName() {
        return toolName;
    }

    public static class Execution extends AbstractSynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1;
        @Inject(optional=true) private transient DockerRunStep step;
        @StepContextParameter private transient Launcher launcher;
        @StepContextParameter private transient TaskListener listener;
        @StepContextParameter private transient FilePath workspace;
        @StepContextParameter private transient EnvVars env;
        @StepContextParameter private transient Computer computer;
        @StepContextParameter private transient Node node;
        @SuppressWarnings("rawtypes") // TODO not compiling on cloudbees.ci
        @StepContextParameter private transient Run run;
        private String container;
        private String toolName;

        @Override public String run() throws Exception {
            EnvVars envHost = computer.getEnvironment();

            EnvVars envReduced;
            if (step.includeEnvironment) {
                envReduced = new EnvVars(env);
                envReduced.entrySet().removeAll(envHost.entrySet());
                LOGGER.log(Level.FINE, "reduced environment: {0}", envReduced);
            } else {
                envReduced = new EnvVars();
            }

            workspace.mkdirs(); // otherwise it may be owned by root when created for -v
            String ws = workspace.getRemote();
            toolName = step.toolName;
            DockerClient dockerClient = new DockerClient(launcher, node, toolName);

            // Add a warning if the docker version is less than 1.4
            VersionNumber dockerVersion = dockerClient.version();
            if (dockerVersion != null) {
                if (dockerVersion.isOlderThan(new VersionNumber("1.4"))) {
                    throw new AbortException("The docker version is less than v1.4. Pipeline functions requiring 'docker exec' will not work e.g. 'docker.inside'.");
                }
            } else {
                listener.error("Failed to parse docker version. Please note there is a minimum docker version requirement of v1.4.");
            }

            FilePath tempDir = tempDir(workspace);
            tempDir.mkdirs();
            String tmp = tempDir.getRemote();

            Map<String, String> volumes = new LinkedHashMap<>();
            Collection<String> volumesFromContainers = new LinkedHashSet<>();
            if (step.includeVolumes) {
                Optional<String> containerId = dockerClient.getContainerIdIfContainerized();
                if (containerId.isPresent()) {
                    final Collection<String> mountedVolumes = dockerClient.getVolumes(envHost, containerId.get());
                    final String[] dirs = {ws, tmp};
                    for (String dir : dirs) {
                        // check if there is any volume which contains the directory
                        boolean found = false;
                        for (String vol : mountedVolumes) {
                            if (dir.startsWith(vol)) {
                                volumesFromContainers.add(containerId.get());
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            // there was no volume which contains the directory, fall back to --volume mount
                            volumes.put(dir, dir);
                        }
                    }
                } else {
                    // Jenkins is not running inside a container
                    volumes.put(ws, ws);
                    volumes.put(tmp, tmp);
                }
            }

            container = dockerClient.run(env, step.image, step.args, ws, volumes, volumesFromContainers, envReduced, step.user, step.entryPoint, step.command);
            DockerFingerprints.addRunFacet(dockerClient.getContainerRecord(env, container), run);
            ImageAction.add(step.image, run);
            
            return container;
        }

        // TODO use 1.652 use WorkspaceList.tempDir
        private static FilePath tempDir(FilePath ws) {
            return ws.sibling(ws.getName() + System.getProperty(WorkspaceList.class.getName(), "@") + "tmp");
        }
    }

    @Extension public static class DescriptorImpl extends AbstractStepDescriptorImpl {

        public DescriptorImpl() {
            super(Execution.class);
        }

        @Override public String getFunctionName() {
            return "dockerRun";
        }

        @Override public String getDisplayName() {
            return "Run a Docker container instantiated from an image";
        }

        @Override public boolean isAdvanced() {
            return true;
        }

    }

}

/**
 * Copyright 2011-2017 GatlingCorp (http://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.mojo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Mojo to verify Gatling simulation results.
 * <p>
 * Note: For this goal to function property, the results folder may
 * not contain gatling reports from earlier maven runs.
 * If your results folder resides inside the target directory
 * (which is the default), issuing 'mvn clean' will remove all
 * colliding reports.
 * </p>
 */
@Mojo(name = "verify", defaultPhase = LifecyclePhase.VERIFY)
public class VerifyMojo extends AbstractGatlingMojo {

    /**
     * Use this folder as the folder where results are stored.
     */
    @Parameter(property = "gatling.resultsFolder", defaultValue = "${project.build.directory}/gatling")
    private File resultsFolder;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        File[] runDirectories = resultsFolder.listFiles(File::isDirectory);

        if (runDirectories != null) {
            for (File runDirectory: runDirectories) {
                searchForAssertionFailures(runDirectory);
            }
        }
    }

    private void searchForAssertionFailures(File runDirectory) throws MojoExecutionException, MojoFailureException {
        File jsDir = new File(runDirectory, "js");
        if (jsDir.exists() && jsDir.isDirectory()) {
            File assertionFile = new File(jsDir, "assertions.xml");
            if (assertionFile.exists()) {
                analyzeFile(assertionFile);
            }
        }
    }

    private void analyzeFile(File assertionFile) throws MojoExecutionException, MojoFailureException {
        AssertionsSummary summary;
        try {
            summary = AssertionsSummary.fromAssertionsFile(assertionFile);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to parse " + assertionFile.toString(), e);
        }
        if (summary.hasFailures()) {
            throw new MojoFailureException("Gatling simulation assertions failed!");
        }
    }
}
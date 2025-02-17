
/*
 * Copyright 2011-2022 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.mojo;

import static io.gatling.mojo.MojoConstants.*;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Arrays.stream;
import static org.codehaus.plexus.util.StringUtils.isBlank;

import io.gatling.plugin.GatlingConstants;
import io.perfana.eventscheduler.EventScheduler;
import io.perfana.eventscheduler.EventSchedulerBuilder;
import io.perfana.eventscheduler.api.EventLogger;
import io.perfana.eventscheduler.api.SchedulerExceptionHandler;
import io.perfana.eventscheduler.api.config.EventSchedulerConfig;
import io.perfana.eventscheduler.api.message.EventMessage;
import io.perfana.eventscheduler.exception.EventCheckFailureException;
import io.perfana.eventscheduler.exception.handler.KillSwitchException;
import io.perfana.eventscheduler.util.JavaArgsParser;
import io.perfana.eventscheduler.util.TestRunConfigUtil;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.ExceptionUtils;
import org.codehaus.plexus.util.SelectorUtils;

/** Mojo to execute Gatling. */
@Execute(phase = LifecyclePhase.TEST_COMPILE)
@Mojo(
    name = "test",
    defaultPhase = LifecyclePhase.INTEGRATION_TEST,
    requiresDependencyResolution = ResolutionScope.TEST)
public final class GatlingMojo extends AbstractGatlingExecutionMojo {

  private final Object eventSchedulerLock = new Object();
  private EventScheduler eventScheduler;

  /** A name of a Simulation class to run. */
  @Parameter(property = "gatling.simulationClass")
  private String simulationClass;

  /**
   * Iterate over multiple simulations if more than one simulation file is found. By default false.
   * If multiple simulations are found but {@literal runMultipleSimulations} is false the execution
   * will fail.
   */
  @Parameter(property = "gatling.runMultipleSimulations", defaultValue = "false")
  private boolean runMultipleSimulations;

  /** List of include patterns to use for scanning. Includes all simulations by default. */
  @Parameter(property = "gatling.includes")
  private String[] includes;

  /** List of exclude patterns to use for scanning. Excludes none by default. */
  @Parameter(property = "gatling.excludes")
  private String[] excludes;

  /** Run simulation but does not generate reports. By default false. */
  @Parameter(property = "gatling.noReports", defaultValue = "false")
  private boolean noReports;

  /** Generate the reports for the simulation in this folder. */
  @Parameter(property = "gatling.reportsOnly")
  private String reportsOnly;

  /** A short description of the run to include in the report. */
  @Parameter(property = "gatling.runDescription")
  private String runDescription;

  /**
   * Will cause the project build to look successful, rather than fail, even if there are Gatling
   * test failures. This can be useful on a continuous integration server, if your only option to be
   * able to collect output files, is if the project builds successfully.
   */
  @Parameter(property = "gatling.failOnError", defaultValue = "true")
  private boolean failOnError;

  /**
   * Continue execution of simulations despite assertion failure. If you have some stack of
   * simulations and you want to get results from all simulations despite some assertion failures in
   * previous one.
   */
  @Parameter(property = "gatling.continueOnAssertionFailure", defaultValue = "false")
  private boolean continueOnAssertionFailure;

  @Parameter(property = "gatling.useOldJenkinsJUnitSupport", defaultValue = "false")
  private boolean useOldJenkinsJUnitSupport;

  /** Extra JVM arguments to pass when running Gatling. */
  @Parameter(property = "gatling.jvmArgs")
  private List<String> jvmArgs;

  /** Override Gatling's default JVM args, instead of replacing them. */
  @Parameter(property = "gatling.overrideJvmArgs", defaultValue = "false")
  private boolean overrideJvmArgs;

  /** Propagate System properties to forked processes. */
  @Parameter(property = "gatling.propagateSystemProperties", defaultValue = "true")
  private boolean propagateSystemProperties;

  /** Use this folder as the folder where feeders are stored. */
  @Parameter(
      property = "gatling.resourcesFolder",
      defaultValue = "${project.basedir}/src/test/resources")
  private File resourcesFolder;

  @Parameter(defaultValue = "${plugin.artifacts}", readonly = true)
  private List<Artifact> artifacts;

  /** Specify a different working directory. */
  @Parameter(property = "gatling.workingDirectory")
  private File workingDirectory;

  private Set<File> existingRunDirectories;

  @Parameter(defaultValue = "${project}", readonly = true)
  private MavenProject project;

  @Parameter EventSchedulerConfig eventSchedulerConfig;

  private boolean isEventSchedulerEnabled = false;

  /** This is the list of projects currently slated to be built by Maven. */
  @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
  private List<MavenProject> projects;

  /** Executes Gatling simulations. */
  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    checkPluginPreConditions();

    if (skip) {
      getLog().info("Skipping events-gatling-maven-plugin");
      return;
    }

    getLog().debug(">>> Start execute: " + eventSchedulerConfig);

    boolean abortEventScheduler = false;

    isEventSchedulerEnabled =
        eventSchedulerConfig != null && eventSchedulerConfig.isSchedulerEnabled();
    eventScheduler =
        isEventSchedulerEnabled ? createEventScheduler(eventSchedulerConfig, getLog()) : null;

    // Create results directories
    if (!resultsFolder.exists() && !resultsFolder.mkdirs()) {
      throw new MojoExecutionException(
          "Could not create resultsFolder " + resultsFolder.getAbsolutePath());
    }
    existingRunDirectories = runDirectories();
    Exception ex = null;

    try {
      List<String> testClasspath = buildTestClasspath();

      Toolchain toolchain = toolchainManager.getToolchainFromBuildContext("jdk", session);

      List<String> jvmArgs = gatlingJvmArgs();

      if (isEventSchedulerEnabled) {
        String newTestRunId =
            eventScheduler.getEventSchedulerContext().getTestContext().getTestRunId();
        replaceTestRunIdInJvmArgs(jvmArgs, newTestRunId);
      }

      if (reportsOnly != null) {
        executeGatling(jvmArgs, gatlingArgs(null), testClasspath, toolchain);

      } else {
        List<String> simulations = simulations();
        iterateBySimulations(toolchain, jvmArgs, testClasspath, simulations);
      }

    } catch (Exception e) {
      getLog().debug(">>> Inside catch exception: " + e);
      // AbortSchedulerException should just fall through and be handled like other exceptions
      // For KillSwitchException, go on with check results and assertions instead
      if (!(e instanceof KillSwitchException)) {
        if (failOnError) {
          getLog()
              .debug(">>> Fail on error is enabled (true), setting abortEventScheduler to true.");
          abortEventScheduler = true;
          if (e instanceof GatlingSimulationAssertionsFailedException) {
            throw new MojoFailureException(e.getMessage(), e);
          } else if (e instanceof MojoFailureException) {
            throw (MojoFailureException) e;
          } else if (e instanceof MojoExecutionException) {
            throw (MojoExecutionException) e;
          } else {
            throw new MojoExecutionException("Gatling failed.", e);
          }
        } else {
          getLog()
              .warn(
                  "There were some errors while running your simulation, but failOnError was set to false won't fail your build.");
        }
        ex = e instanceof GatlingSimulationAssertionsFailedException ? null : e;
      } else {
        getLog().debug(">>> KillSwitchException found.");
      }
    } finally {
      recordSimulationResults(ex);
      if (eventScheduler != null) {
        synchronized (eventSchedulerLock) {
          if (abortEventScheduler && !eventScheduler.isSessionStopped()) {
            getLog().debug(">>> Abort is called in finally: abortEventScheduler is true");
            // implicit stop session
            eventScheduler.abortSession();
          } else {
            getLog()
                .debug(
                    ">>> No abort called: "
                        + "abort event scheduler is "
                        + abortEventScheduler
                        + ", stop is already called is "
                        + eventScheduler.isSessionStopped());
          }
        }
      }
    }

    if (eventScheduler != null && !eventScheduler.isSessionStopped()) {
      getLog().debug(">>> Stop session (because not isSessionStopped())");
      eventScheduler.stopSession();
      try {
        getLog().debug(">>> Call check results");
        eventScheduler.checkResults();
      } catch (EventCheckFailureException e) {
        getLog().debug(">>> EventCheckFailureException: " + e.getMessage());
        if (!continueOnAssertionFailure) {
          throw new MojoFailureException(e.getMessage());
        } else {
          getLog()
              .warn(
                  "EventCheck failures found, but continue on assert failure is enabled:"
                      + e.getMessage());
        }
      }
    }
  }

  private void replaceTestRunIdInJvmArgs(List<String> jvmArgs, String newTestRunId) {
    getLog().debug(">>> testRunId from the eventScheduler: " + newTestRunId);
    String newTestRunIdJvmArg = "-DtestRunId=" + newTestRunId;

    Optional<String> existingTestRunId =
        jvmArgs.stream().filter(jvmArg -> jvmArg.startsWith("-DtestRunId")).findAny();

    if (existingTestRunId.isPresent()) {
      getLog().info(">>> testRunId is present in jvm args: " + existingTestRunId.get());
      if (!existingTestRunId.get().equals(newTestRunIdJvmArg)) {
        getLog().info(">>> replace testRunId in jvm args: " + newTestRunIdJvmArg);
        jvmArgs.set(jvmArgs.indexOf(existingTestRunId.get()), newTestRunIdJvmArg);
      }
    } else {
      getLog().info(">>> inject testRunId in jvm args:: " + newTestRunIdJvmArg);
      jvmArgs.add(newTestRunIdJvmArg);
    }
  }

  private void startScheduler(EventScheduler scheduler, SchedulerExceptionHandler handler) {
    scheduler.addKillSwitch(handler);
    scheduler.startSession();
    addShutdownHookForEventScheduler(scheduler);
  }

  private void addShutdownHookForEventScheduler(EventScheduler scheduler) {
    final Thread main = Thread.currentThread();
    Runnable shutdowner =
        () -> {
          synchronized (scheduler) {
            if (!scheduler.isSessionStopped()) {
              getLog().info("Shutdown Hook: abort event scheduler session!");
              // implicit stop session
              scheduler.abortSession();
            }
          }

          // try to hold on to main thread to let the abort event tasks finish properly
          try {
            main.join(4000);
          } catch (InterruptedException e) {
            getLog().warn("Interrupt while waiting for abort to finish.");
            Thread.currentThread().interrupt();
          }
        };
    Thread eventSchedulerShutdownThread = new Thread(shutdowner, "eventSchedulerShutdownThread");
    Runtime.getRuntime().addShutdownHook(eventSchedulerShutdownThread);
  }

  private Set<File> runDirectories() {
    File[] directories = resultsFolder.listFiles(File::isDirectory);
    return (directories == null)
        ? Collections.emptySet()
        : new HashSet<>(Arrays.asList(directories));
  }

  private void iterateBySimulations(
      Toolchain toolchain,
      List<String> jvmArgs,
      List<String> testClasspath,
      List<String> simulations)
      throws Exception {
    Exception exc = null;
    int simulationsCount = simulations.size();
    for (int i = 0; i < simulationsCount; i++) {
      try {
        executeGatling(jvmArgs, gatlingArgs(simulations.get(i)), testClasspath, toolchain);
      } catch (GatlingSimulationAssertionsFailedException e) {
        if (exc == null && i == simulationsCount - 1) {
          throw e;
        }

        if (continueOnAssertionFailure) {
          if (exc != null) {
            continue;
          }
          exc = e;
          continue;
        }
        throw e;
      }
    }

    if (exc != null) {
      getLog()
          .warn(
              "There were some errors while running your simulation, but continueOnAssertionFailure was set to true, so your simulations continue to perform.");
      throw exc;
    }
  }

  private void executeGatling(
      List<String> gatlingJvmArgs,
      List<String> gatlingArgs,
      List<String> testClasspath,
      Toolchain toolchain)
      throws Exception {
    Fork forkedGatling =
        newFork(
            GATLING_MAIN_CLASS,
            testClasspath,
            gatlingJvmArgs,
            gatlingArgs,
            toolchain,
            propagateSystemProperties,
            workingDirectory);

    if (isEventSchedulerEnabled) {
      SchedulerExceptionHandler exceptionHandler = forkedGatling.getSchedulerExceptionHandler();
      sendTestConfig(eventScheduler, gatlingJvmArgs);
      startScheduler(eventScheduler, exceptionHandler);
    } else {
      getLog()
          .warn("The Event Scheduler is disabled. Use 'eventSchedulerEnabled' property to enable.");
    }

    try {
      forkedGatling.run();
    } catch (Fork.ForkException e) {
      if (e.exitValue == 2) throw new GatlingSimulationAssertionsFailedException(e);
      else throw e; /* issue 1482 */
    }
  }

  private void sendTestConfig(EventScheduler scheduler, List<String> gatlingJvmArgs) {
    Map<String, String> keyValues = JavaArgsParser.createJvmArgsTestConfigLines(gatlingJvmArgs);

    List<String> activeProfiles = activeProfiles();
    Collections.sort(activeProfiles);
    keyValues.put(
        "activeProfiles", String.join(TestRunConfigUtil.VALUE_LIST_DELIMITER, activeProfiles));

    keyValues.put("overrideJvmArgs", String.valueOf(overrideJvmArgs));
    keyValues.put("propagateSystemProperties", String.valueOf(propagateSystemProperties));
    keyValues.put("simulationClass", simulationClass);

    EventMessage message =
        TestRunConfigUtil.createTestRunConfigMessageKeys(
            "events-gatling-maven-plugin", keyValues, "gatling");

    scheduler.sendMessage(message);
  }

  private List<String> activeProfiles() {
    List<String> activeProfiles = new ArrayList<>();
    for (MavenProject mavenProject : projects) {
      Map<String, List<String>> activeProfileIds = mavenProject.getInjectedProfileIds();
      for (Map.Entry<String, List<String>> entry : activeProfileIds.entrySet()) {
        activeProfiles.addAll(entry.getValue());
      }
    }
    return activeProfiles;
  }

  private EventMessage createTestConfigMessage(String key, String value) {
    return EventMessage.builder()
        .pluginName("events-gatling-maven-plugin")
        .variable("message-type", "test-run-config")
        .variable("output", "key")
        .variable("key", key)
        .variable("tags", "gatling")
        .message(value)
        .build();
  }

  private void recordSimulationResults(Exception exception) throws MojoExecutionException {
    try {
      saveSimulationResultToFile(exception);
      copyJUnitReports();
    } catch (IOException e) {
      throw new MojoExecutionException("Could not record simulation results.", e);
    }
  }

  private void saveSimulationResultToFile(Exception exception) throws IOException {
    Path resultsFile = resultsFolder.toPath().resolve(LAST_RUN_FILE);

    try (BufferedWriter writer = Files.newBufferedWriter(resultsFile)) {
      saveListOfNewRunDirectories(writer);
      writeExceptionIfExists(writer, exception);
    }
  }

  private void saveListOfNewRunDirectories(BufferedWriter writer) throws IOException {
    for (File directory : runDirectories()) {
      if (!existingRunDirectories.contains(directory)) {
        writer.write(directory.getName() + System.lineSeparator());
      }
    }
  }

  private void writeExceptionIfExists(BufferedWriter writer, Exception exception)
      throws IOException {
    if (exception != null) {
      writer.write(
          LAST_RUN_FILE_ERROR_LINE + getRecursiveCauses(exception) + System.lineSeparator());
    }
  }

  private String getRecursiveCauses(Throwable e) {
    return stream(ExceptionUtils.getThrowables(e))
        .map(ex -> joinNullable(ex.getClass().getName(), ex.getMessage()))
        .collect(Collectors.joining(" | "));
  }

  private String joinNullable(String s, String sNullable) {
    return isBlank(sNullable) ? s : s + ": " + sNullable;
  }

  private void copyJUnitReports() throws MojoExecutionException {

    try {
      if (useOldJenkinsJUnitSupport) {
        for (File directory : runDirectories()) {
          File jsDir = new File(directory, "js");
          if (jsDir.exists() && jsDir.isDirectory()) {
            File assertionFile = new File(jsDir, "assertions.xml");
            if (assertionFile.exists()) {
              File newAssertionFile =
                  new File(resultsFolder, "assertions-" + directory.getName() + ".xml");
              Files.copy(
                  assertionFile.toPath(),
                  newAssertionFile.toPath(),
                  COPY_ATTRIBUTES,
                  REPLACE_EXISTING);
              getLog()
                  .info(
                      "Copying assertion file "
                          + assertionFile.getCanonicalPath()
                          + " to "
                          + newAssertionFile.getCanonicalPath());
            }
          }
        }
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to copy JUnit reports", e);
    }
  }

  private List<String> gatlingJvmArgs() {
    return computeArgs(jvmArgs, GatlingConstants.DEFAULT_JVM_OPTIONS_BASE, overrideJvmArgs);
  }

  private List<String> computeArgs0(List<String> custom, List<String> defaults, boolean override) {
    if (custom.isEmpty()) {
      return defaults;
    }
    if (override) {
      List<String> merged = new ArrayList<>(custom);
      merged.addAll(defaults);
      return merged;
    }
    return custom;
  }

  private List<String> computeArgs(List<String> custom, List<String> defaults, boolean override) {
    List<String> result = new ArrayList<>(computeArgs0(custom, defaults, override));
    // force disable disableClassPathURLCheck because Debian messed up and takes
    // forever to fix, see https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=911925
    result.add("-Djdk.net.URLClassPath.disableClassPathURLCheck=true");
    return result;
  }

  private List<String> simulations() throws MojoFailureException {
    // Solves the simulations, if no simulation file is defined
    if (simulationClass != null) {
      return Collections.singletonList(simulationClass);

    } else {
      List<String> simulations =
          SimulationClassUtils.resolveSimulations(
              mavenProject, compiledClassesFolder, includes, excludes);

      if (simulations.isEmpty()) {
        getLog().error("No simulations to run");
        throw new MojoFailureException("No simulations to run");
      }

      if (simulations.size() > 1 && !runMultipleSimulations) {
        String message =
            "More than 1 simulation to run. Either specify one with -Dgatling.simulationClass=<className>, or enable runMultipleSimulations in your pom.xml";
        getLog().error(message);
        throw new MojoFailureException(message);
      }

      return simulations;
    }
  }

  private List<String> gatlingArgs(String simulationClass) throws Exception {
    // Arguments
    List<String> args = new ArrayList<>();
    addArg(args, "rsf", resourcesFolder.getCanonicalPath());
    addArg(args, "rf", resultsFolder.getCanonicalPath());

    addArg(args, "rd", runDescription);

    if (noReports) {
      args.add("-nr");
    }

    addArg(args, "s", simulationClass);
    addArg(args, "ro", reportsOnly);

    String[] gatlingVersion =
        MojoUtils.findByGroupIdAndArtifactId(
                mavenProject.getArtifacts(), GATLING_GROUP_ID, GATLING_MODULE_APP)
            .getVersion()
            .split("\\.");
    int gatlingMajorVersion = Integer.valueOf(gatlingVersion[0]);
    int gatlingMinorVersion = Integer.valueOf(gatlingVersion[1]);

    if ((gatlingMajorVersion == 3 && gatlingMinorVersion >= 8) || gatlingMajorVersion > 4) {
      addArg(args, "l", "maven");
      addArg(args, "btv", MavenProject.class.getPackage().getImplementationVersion());
    }

    return args;
  }

  private Optional<Class<?>> loadJavaSimulationClass(ClassLoader testClassLoader) {
    try {
      return Optional.of(testClassLoader.loadClass("io.gatling.javaapi.core.Simulation"));
    } catch (ClassNotFoundException e) {
      // ignore
      return Optional.empty();
    }
  }

  /**
   * Resolve simulation files to execute from the simulation folder.
   *
   * @return a comma separated String of simulation class names.
   */
  private List<String> resolveSimulations() {

    try {
      ClassLoader testClassLoader = new URLClassLoader(testClassPathUrls());

      Class<?> scalaSimulationClass =
          testClassLoader.loadClass("io.gatling.core.scenario.Simulation");
      Optional<Class<?>> javaSimulationClass = loadJavaSimulationClass(testClassLoader);

      List<String> includes = MojoUtils.arrayAsListEmptyIfNull(this.includes);
      List<String> excludes = MojoUtils.arrayAsListEmptyIfNull(this.excludes);

      List<String> simulationsClasses = new ArrayList<>();

      for (String classFile : compiledClassFiles()) {
        String className = pathToClassName(classFile);

        boolean isIncluded = includes.isEmpty() || match(includes, className);
        boolean isExcluded = !excludes.isEmpty() && match(excludes, className);

        if (isIncluded && !isExcluded) {
          // check if the class is a concrete Simulation
          Class<?> clazz = testClassLoader.loadClass(className);
          if (isConcreteClass(clazz)
              && (javaSimulationClass
                      .map(simClass -> simClass.isAssignableFrom(clazz))
                      .orElse(false)
                  || scalaSimulationClass.isAssignableFrom(clazz))) {
            simulationsClasses.add(className);
          }
        }
      }

      return simulationsClasses;

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean match(List<String> patterns, String string) {
    for (String pattern : patterns) {
      if (pattern != null && SelectorUtils.match(pattern, string)) {
        return true;
      }
    }
    return false;
  }

  private URL[] testClassPathUrls()
      throws DependencyResolutionRequiredException, MalformedURLException {

    List<String> testClasspathElements = mavenProject.getTestClasspathElements();

    URL[] urls = new URL[testClasspathElements.size()];
    for (int i = 0; i < testClasspathElements.size(); i++) {
      String testClasspathElement = testClasspathElements.get(i);
      URL url = Paths.get(testClasspathElement).toUri().toURL();
      urls[i] = url;
    }

    return urls;
  }

  private String[] compiledClassFiles() throws IOException {
    DirectoryScanner scanner = new DirectoryScanner();
    scanner.setBasedir(compiledClassesFolder.getCanonicalPath());
    scanner.setIncludes(new String[] {"**/*.class"});
    scanner.scan();
    String[] files = scanner.getIncludedFiles();
    Arrays.sort(files);
    return files;
  }

  private String pathToClassName(String path) {
    return path.substring(0, path.length() - ".class".length()).replace(File.separatorChar, '.');
  }

  private boolean isConcreteClass(Class<?> clazz) {
    return !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers());
  }

  private static EventScheduler createEventScheduler(
      EventSchedulerConfig eventSchedulerConfig, Log log) {

    EventLogger logger =
        new EventLogger() {
          @Override
          public void info(String message) {
            log.info(message);
          }

          @Override
          public void warn(String message) {
            log.warn(message);
          }

          @Override
          public void error(String message) {
            log.error(message);
          }

          @Override
          public void error(String message, Throwable throwable) {
            log.error(message, throwable);
          }

          @Override
          public void debug(final String message) {
            if (isDebugEnabled()) log.debug(message);
          }

          @Override
          public boolean isDebugEnabled() {
            return eventSchedulerConfig.isDebugEnabled();
          }
        };

    return EventSchedulerBuilder.of(eventSchedulerConfig, logger);
  }
}

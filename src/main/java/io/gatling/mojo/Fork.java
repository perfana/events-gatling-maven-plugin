
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

import io.gatling.plugin.io.PluginLogger;
import io.gatling.plugin.util.ForkMain;
import io.perfana.eventscheduler.api.SchedulerExceptionHandler;
import io.perfana.eventscheduler.api.SchedulerExceptionType;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.apache.commons.exec.ExecuteWatchdog;

public final class Fork {

  private static final String ARGS_RESOURCE = "META-INF/args.txt";

  private static final boolean IS_WINDOWS =
      System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

  public static final class ForkException extends Exception {
    public final int exitValue;

    public ForkException(int exitValue) {
      this.exitValue = exitValue;
    }
  }

  private static final String GATLING_MANIFEST_VALUE = "GATLING_ZINC";

  private final File javaExecutable;
  private final String mainClassName;
  private final List<String> classpath;
  private final boolean propagateSystemProperties;
  private final PluginLogger log;
  private final File workingDirectory;

  private final List<String> jvmArgs;
  private final List<String> args;

  // volatile because possibly multiple threads are involved
  private volatile SchedulerExceptionType schedulerExceptionType = SchedulerExceptionType.NONE;

  private final ExecuteWatchdog gatlingProcessWatchDog =
      new ExecuteWatchdog(ExecuteWatchdog.INFINITE_TIMEOUT);

  private final SchedulerExceptionHandler schedulerExceptionHandler =
      new SchedulerExceptionHandler() {
        @Override
        public void kill(String message) {
          log.info("Killing running process, message: " + message);
          schedulerExceptionType = SchedulerExceptionType.KILL;
          gatlingProcessWatchDog.destroyProcess();
        }

        @Override
        public void abort(String message) {
          log.info("Killing running process, message: " + message);
          schedulerExceptionType = SchedulerExceptionType.ABORT;
          gatlingProcessWatchDog.destroyProcess();
        }

        @Override
        public void stop(String message) {
          log.info("Stop running process, message: " + message);
          schedulerExceptionType = SchedulerExceptionType.STOP;
          gatlingProcessWatchDog.destroyProcess();
        }
      };

  public Fork(
      String mainClassName,
      List<String> classpath,
      List<String> jvmArgs,
      List<String> args,
      File javaExecutable,
      boolean propagateSystemProperties,
      PluginLogger log,
      File workingDirectory) {

    this.mainClassName = mainClassName;
    this.classpath = classpath;
    this.jvmArgs = Collections.unmodifiableList(jvmArgs);
    this.args = Collections.unmodifiableList(args);
    this.javaExecutable = javaExecutable;
    this.propagateSystemProperties = propagateSystemProperties;
    this.log = log;
    this.workingDirectory = workingDirectory;
  }

  SchedulerExceptionHandler getSchedulerExceptionHandler() {
    return schedulerExceptionHandler;
  }

  private static String toWindowsShortName(String value) {
    if (IS_WINDOWS) {
      int programFilesIndex = value.indexOf("Program Files");
      if (programFilesIndex >= 0) {
        // Could be "Program Files" or "Program Files (x86)"
        int firstSeparatorAfterProgramFiles =
            value.indexOf(File.separator, programFilesIndex + "Program Files".length());
        File longNameDir =
            firstSeparatorAfterProgramFiles < 0
                ? new File(value)
                : // C:\\Program Files with
                // trailing separator
                new File(value.substring(0, firstSeparatorAfterProgramFiles)); // chop child
        // Some other sibling dir could be PrograXXX and might shift short name index
        // so we can't be sure "Program Files" is "Progra~1" and "Program Files (x86)"
        // is "Progra~2"
        for (int i = 0; i < 10; i++) {
          File shortNameDir = new File(longNameDir.getParent(), "Progra~" + i);
          if (shortNameDir.equals(longNameDir)) {
            return shortNameDir.toString();
          }
        }
      }
    }

    return value;
  }

  private String safe(String value) {
    return value.contains(" ") ? '"' + value + '"' : value;
  }

  /**
   * Escapes any values it finds into their String form.
   *
   * <p>So a tab becomes the characters <code>'\\'</code> and <code>'t'</code>.
   *
   * @param str String to escape values in
   * @return String with escaped values
   * @throws NullPointerException if str is <code>null</code>
   */
  // forked from plexus-util
  private static String escape(String str) {
    // improved with code from cybertiger@cyberiantiger.org
    // unicode from him, and default for < 32's.
    StringBuilder buffer = new StringBuilder(2 * str.length());
    for (char ch : str.toCharArray()) {
      // handle unicode
      if (ch > 0xfff) {
        buffer.append("\\u").append(Integer.toHexString(ch));
      } else if (ch > 0xff) {
        buffer.append("\\u0").append(Integer.toHexString(ch));
      } else if (ch > 0x7f) {
        buffer.append("\\u00").append(Integer.toHexString(ch));
      } else if (ch < 32) {
        switch (ch) {
          case '\b':
            buffer.append('\\').append('b');
            break;
          case '\n':
            buffer.append('\\').append('n');
            break;
          case '\t':
            buffer.append('\\').append('t');
            break;
          case '\f':
            buffer.append('\\').append('f');
            break;
          case '\r':
            buffer.append('\\').append('r');
            break;
          default:
            if (ch > 0xf) {
              buffer.append("\\u00").append(Integer.toHexString(ch));
            } else {
              buffer.append("\\u000").append(Integer.toHexString(ch));
            }
            break;
        }
      } else {
        switch (ch) {
          case '\'':
            buffer.append('\\').append('\'');
            break;
          case '"':
            buffer.append('\\').append('"');
            break;
          case '\\':
            buffer.append('\\').append('\\');
            break;
          default:
            buffer.append(ch);
            break;
        }
      }
    }
    return buffer.toString();
  }

  public void run() throws Exception {
    List<String> command = new ArrayList<>(jvmArgs.size() + 5);
    command.add(toWindowsShortName(javaExecutable.getCanonicalPath()));
    command.addAll(jvmArgs);

    if (propagateSystemProperties) {
      for (Entry<Object, Object> systemProp : System.getProperties().entrySet()) {
        String name = systemProp.getKey().toString();
        String value = toWindowsShortName(systemProp.getValue().toString());
        if (isPropagatableProperty(name)) {
          if (name.contains(" ")) {
            log.error(
                "System property ("
                    + name
                    + ", "
                    + value
                    + "') has a name that contains a whitespace and can't be propagated");

          } else if (IS_WINDOWS && value.contains(" ")) {
            log.error(
                "System property ("
                    + name
                    + ", "
                    + value
                    + "') has a value that contains a whitespace and can't be propagated on Windows");

          } else {
            command.add("-D" + name + "=" + safe(escape(value)));
          }
        }
      }
    }

    command.add("-jar");
    command.add(createBooterJar(classpath, args).getCanonicalPath());
    command.add(mainClassName);

    Process process = new ProcessBuilder(command).directory(workingDirectory).inheritIO().start();
    process.getOutputStream().close();
    int exitValue = process.waitFor();
    if (exitValue != 0) {
      throw new io.gatling.plugin.util.Fork.ForkException(exitValue);
    }
  }

  /**
   * Create a jar with just a manifest containing a Main-Class entry for BooterConfiguration and a
   * Class-Path entry for all classpath elements.
   *
   * @param classPath List of all classpath elements.
   * @param args List of all parameter args
   * @return The file pointing to the jar
   * @throws java.io.IOException When a file operation fails.
   */
  private static File createBooterJar(List<String> classPath, List<String> args)
      throws IOException {
    File file = File.createTempFile("gatlingbooter", ".jar");
    file.deleteOnExit();

    String cp =
        classPath.stream()
            .map(element -> getURL(new File(element)).toExternalForm())
            .collect(Collectors.joining(" "));
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().putValue(Attributes.Name.MANIFEST_VERSION.toString(), "1.0");
    manifest.getMainAttributes().putValue(Attributes.Name.CLASS_PATH.toString(), cp);
    manifest
        .getMainAttributes()
        .putValue(Attributes.Name.MAIN_CLASS.toString(), ForkMain.class.getName());
    manifest.getMainAttributes().putValue(GATLING_MANIFEST_VALUE, "true");

    try (JarOutputStream jos =
        new JarOutputStream(new BufferedOutputStream(Files.newOutputStream(file.toPath())))) {
      jos.setLevel(JarOutputStream.STORED);
      JarEntry manifestJarEntry = new JarEntry("META-INF/MANIFEST.MF");
      jos.putNextEntry(manifestJarEntry);
      manifest.write(jos);
      jos.closeEntry();

      JarEntry argsFileEntry = new JarEntry(ARGS_RESOURCE);
      jos.putNextEntry(argsFileEntry);
      byte[] argsBytes =
          args.stream().collect(Collectors.joining("\n")).getBytes(StandardCharsets.UTF_8);
      jos.write(argsBytes);
      jos.closeEntry();
    }

    return file;
  }

  // encode any characters that do not comply with RFC 2396
  // this is primarily to handle Windows where the user's home directory contains
  // spaces
  private static URL getURL(File file) {
    try {
      return new URL(file.toURI().toASCIIString());
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean isPropagatableProperty(String name) {
    return !name.startsWith("java.") //
        && !name.startsWith("sun.") //
        && !name.startsWith("maven.") //
        && !name.startsWith("file.") //
        && !name.startsWith("awt.") //
        && !name.startsWith("os.") //
        && !name.startsWith("user.") //
        && !name.startsWith("idea.") //
        && !name.startsWith("guice.") //
        && !name.startsWith("hudson.") //
        && !name.equals("line.separator") //
        && !name.equals("path.separator") //
        && !name.equals("classworlds.conf") //
        && !name.equals("org.slf4j.simpleLogger.defaultLogLevel");
  }
}

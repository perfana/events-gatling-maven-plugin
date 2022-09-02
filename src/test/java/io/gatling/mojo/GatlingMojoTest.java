package io.gatling.mojo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatlingMojoTest {

    @Test
    void createJvmArgsTestConfigLines() {
        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-Xms1g");
        jvmArgs.add("-Xmx2g");
        jvmArgs.add("-Xss10k");
        jvmArgs.add("-XX:-UseSerialGC");
        jvmArgs.add("-XX:PreBlockSpin=10");
        jvmArgs.add("-XX:HeapDumpPath=./java_pid1234.hprof");
        jvmArgs.add("-javaagent:/full/path/to/agent.jar");
        jvmArgs.add("-Dthis.is.a.system.property=as-Defined-by-user");
        jvmArgs.add("-Xthr:minimizeUserCPU"); // ibm jvm option
        jvmArgs.add("-Xbootclasspath/a:/path/to/jar1.jar:/path/to/jar2.jar");
        jvmArgs.add("-Xbootclasspath/p:/path/to/jar1.jar:/path/to/jar2.jar");
        jvmArgs.add("-Xbootclasspath:/path/to/jar1.jar:/path/to/jar2.jar");
        jvmArgs.add("-Xnolinenumbers"); // ibm jvm option
        jvmArgs.add("-XX:StartFlightRecording=duration=60s,filename=c:\\temp\\myrecording.jfr");
        // duplicates are possible too: we expect these to be merged
        jvmArgs.add("-Xlog:gc*=debug:stdout");
        jvmArgs.add("-Xlog:gc*=debug:file=/tmp/gc.log");
        jvmArgs.add("-Xloggc:/home/user/log/gc.log");
        jvmArgs.add("-d32");
        jvmArgs.add("-server");
        jvmArgs.add("-XX:OnOutOfMemoryError=/bin/date; /bin/echo custom message;/bin/kill -9 %p");
        jvmArgs.add("-XX:SomeDoubleEqualsProp=/bin/date=123341");
        jvmArgs.add("option=test");
        jvmArgs.add("-DmyPassword=s3cr3t");
        jvmArgs.add("-DmyToken=s3cr3t");

        Map<String, String> jvmArgsTestConfigLines = new GatlingMojo().createJvmArgsTestConfigLines(jvmArgs);
        //System.out.println(jvmArgsTestConfigLines);
        // minus one because we expect one merged entry for Xlog, and two secrets filtered out
        assertEquals(jvmArgs.size() - 3, jvmArgsTestConfigLines.size());
        assertEquals("1g", jvmArgsTestConfigLines.get("jmvArg.Xms"));
        assertEquals("2g", jvmArgsTestConfigLines.get("jmvArg.Xmx"));
        assertEquals("10k", jvmArgsTestConfigLines.get("jmvArg.Xss"));
        assertEquals("-UseSerialGC", jvmArgsTestConfigLines.get("jmvArg.XXUseSerialGC"));
        assertEquals("10", jvmArgsTestConfigLines.get("jmvArg.XXPreBlockSpin"));
        assertEquals("./java_pid1234.hprof", jvmArgsTestConfigLines.get("jmvArg.XXHeapDumpPath"));
        assertEquals("/full/path/to/agent.jar", jvmArgsTestConfigLines.get("jmvArg.javaagent"));
        assertEquals("as-Defined-by-user", jvmArgsTestConfigLines.get("jmvArg.Dthis.is.a.system.property"));
        assertEquals("minimizeUserCPU", jvmArgsTestConfigLines.get("jmvArg.Xthr"));
        assertEquals("/path/to/jar1.jar:/path/to/jar2.jar", jvmArgsTestConfigLines.get("jmvArg.Xbootclasspatha"));
        assertEquals("/path/to/jar1.jar:/path/to/jar2.jar", jvmArgsTestConfigLines.get("jmvArg.Xbootclasspathp"));
        assertEquals("/path/to/jar1.jar:/path/to/jar2.jar", jvmArgsTestConfigLines.get("jmvArg.Xbootclasspath"));
        assertEquals("nolinenumbers", jvmArgsTestConfigLines.get("jmvArg.Xnolinenumbers"));
        assertEquals("duration=60s,filename=c:\\temp\\myrecording.jfr", jvmArgsTestConfigLines.get("jmvArg.XXStartFlightRecording"));
        // expect two -Xlog entries, concatenated by newline
        assertEquals("gc*=debug:stdout\ngc*=debug:file=/tmp/gc.log", jvmArgsTestConfigLines.get("jmvArg.Xlog"));
        assertEquals("/home/user/log/gc.log", jvmArgsTestConfigLines.get("jmvArg.Xloggc"));
        assertEquals("32", jvmArgsTestConfigLines.get("jmvArg.d"));
        assertEquals("/bin/date=123341", jvmArgsTestConfigLines.get("jmvArg.XXSomeDoubleEqualsProp"));
        // unexpected format
        assertEquals("test", jvmArgsTestConfigLines.get("jmvArg.option"));
        assertNull(jvmArgsTestConfigLines.get("jmvArg.DmyPassword"), "should not contain s3cr3t");
        assertNull(jvmArgsTestConfigLines.get("jmvArg.DmyToken"), "should not contain s3cr3t");
    }
}
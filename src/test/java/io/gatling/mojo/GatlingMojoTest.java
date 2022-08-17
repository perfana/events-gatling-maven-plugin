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
        jvmArgs.add("-Xbootclasspath/a"); // ibm jvm option
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
        assertEquals("-Xms1g", jvmArgsTestConfigLines.get("jmvArg.Xms"));
        assertEquals("-Xmx2g", jvmArgsTestConfigLines.get("jmvArg.Xmx"));
        assertEquals("-Xss10k", jvmArgsTestConfigLines.get("jmvArg.Xss"));
        assertEquals("-XX:-UseSerialGC", jvmArgsTestConfigLines.get("jmvArg.XXUseSerialGC"));
        assertEquals("-XX:PreBlockSpin=10", jvmArgsTestConfigLines.get("jmvArg.XXPreBlockSpin"));
        assertEquals("-XX:HeapDumpPath=./java_pid1234.hprof", jvmArgsTestConfigLines.get("jmvArg.XXHeapDumpPath"));
        assertEquals("-javaagent:/full/path/to/agent.jar", jvmArgsTestConfigLines.get("jmvArg.javaagent"));
        assertEquals("-Dthis.is.a.system.property=as-Defined-by-user", jvmArgsTestConfigLines.get("jmvArg.Dthis.is.a.system.property"));
        assertEquals("-Xthr:minimizeUserCPU", jvmArgsTestConfigLines.get("jmvArg.Xthr"));
        assertEquals("-Xbootclasspath/a", jvmArgsTestConfigLines.get("jmvArg.Xbootclasspath"));
        assertEquals("-Xnolinenumbers", jvmArgsTestConfigLines.get("jmvArg.Xnolinenumbers"));
        assertEquals("-XX:StartFlightRecording=duration=60s,filename=c:\\temp\\myrecording.jfr", jvmArgsTestConfigLines.get("jmvArg.XXStartFlightRecording"));
        assertEquals("-Xlog:gc*=debug:stdout -Xlog:gc*=debug:file=/tmp/gc.log", jvmArgsTestConfigLines.get("jmvArg.Xlog"));
        assertEquals("-Xloggc:/home/user/log/gc.log", jvmArgsTestConfigLines.get("jmvArg.Xloggc"));
        // can be d32 or d64, seems good enough to have as full flag (instead of parsing the 32/64 and have d flag)
        assertEquals("-d32", jvmArgsTestConfigLines.get("jmvArg.d32"));
        assertEquals("-XX:SomeDoubleEqualsProp=/bin/date=123341", jvmArgsTestConfigLines.get("jmvArg.XXSomeDoubleEqualsProp"));
        // unexpected format
        assertEquals("option=test", jvmArgsTestConfigLines.get("jmvArg.option=test"));
        assertNull(jvmArgsTestConfigLines.get("jmvArg.DmyPassword"), "should not contain s3cr3t");
        assertNull(jvmArgsTestConfigLines.get("jmvArg.DmyToken"), "should not contain s3cr3t");
    }
}
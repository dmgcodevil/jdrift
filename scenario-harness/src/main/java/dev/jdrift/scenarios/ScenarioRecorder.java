package dev.jdrift.scenarios;

import jdk.jfr.Configuration;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.lang.management.ManagementFactory;

public final class ScenarioRecorder {
    private static volatile long sink;
    private static int iterations = 100_000;

    @Name("dev.jdrift.ScenarioPhase")
    @Label("Controlled scenario phase")
    public static final class Phase extends Event {
        public String service = "synthetic-cpu";
        public String environment = "test";
        public String scenario;
        public String phase;
        public long operations;
        public String workload = "synthetic-batch-v1";
        public String loadProfile;
        public long threadCpuNanos = -1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !java.util.Set.of("healthy", "sustained", "burst", "improved", "regressed", "new-method", "lower-load").contains(args[0])) {
            throw new IllegalArgumentException("Usage: <healthy|sustained|burst|improved|regressed|new-method|lower-load> <output.jfr>");
        }
        iterations = args[0].equals("improved") ? 30_000 : 100_000;
        var threadBean = ManagementFactory.getThreadMXBean();
        boolean cpuAvailable = threadBean.isCurrentThreadCpuTimeSupported();
        if (cpuAvailable && !threadBean.isThreadCpuTimeEnabled()) threadBean.setThreadCpuTimeEnabled(true);
        Path output = Path.of(args[1]).toAbsolutePath();
        if (Files.exists(output) || Files.exists(Path.of(output + ".manifest.txt"))) {
            throw new IllegalArgumentException("Output already exists; choose a new dataset path: " + output);
        }
        Files.createDirectories(output.getParent());
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.enable(Phase.class).withThreshold(Duration.ZERO);
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.start();
            for (int window = 0; window < 10; window++) {
                boolean busy = !args[0].equals("healthy") && (!args[0].equals("burst") || window == 5);
                Phase phase = new Phase();
                phase.scenario = args[0];
                phase.loadProfile = args[0].equals("lower-load") ? "throttled" :
                    (args[0].equals("burst") ? "bursty" : (args[0].equals("healthy") ? "idle" : "saturated"));
                phase.phase = busy ? "busy" : "idle";
                phase.begin();
                long cpuStart = cpuAvailable ? threadBean.getCurrentThreadCpuTime() : -1;
                long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                while (System.nanoTime() < deadline) {
                    if (busy) {
                        if (args[0].equals("new-method")) alternativeHotLoop(); else hotLoop();
                        phase.operations++;
                        if (args[0].equals("lower-load")) Thread.sleep(5);
                    }
                    else Thread.sleep(10);
                }
                phase.end();
                if (cpuAvailable) phase.threadCpuNanos = threadBean.getCurrentThreadCpuTime() - cpuStart;
                phase.commit();
            }
            recording.stop();
            recording.dump(output);
        }
        Files.writeString(Path.of(output + ".manifest.txt"), "scenario=" + args[0]
            + "\njava=" + System.getProperty("java.version")
            + "\nconfiguration=profile\nphaseEvent=dev.jdrift.ScenarioPhase\n"
            + "durationSeconds=10\nexpectedBehavior=intended; verify recorded events before accepting fixture\n");
        System.out.println(output);
    }

    private static void hotLoop() {
        long value = sink;
        for (int i = 0; i < iterations; i++) value = value * 1664525 + 1013904223;
        sink = value;
    }

    private static void alternativeHotLoop() {
        long value = sink;
        for (int i = 0; i < iterations; i++) value = value * 22695477 + 1;
        sink = value;
    }
}

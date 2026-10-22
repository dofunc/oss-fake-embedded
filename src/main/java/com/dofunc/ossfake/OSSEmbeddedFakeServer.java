package com.dofunc.ossfake;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs oss-go-fake as a child process for tests.
 *
 * <p>The binary for the current platform is copied out of this jar into
 * {@code java.io.tmpdir/oss-fake-embedded/<version>/} once per version and reused afterwards.
 * {@link #start()} returns only after the process reports it is serving; {@link #stop()} is
 * idempotent and also runs from a JVM shutdown hook, so a test that dies mid-way does not
 * leave the process behind.
 *
 * <p>The process always listens on {@code 127.0.0.1}: it serves tests on this machine only, so
 * {@link #getEndpoint()} always matches where it listens.
 *
 * <p>Not thread-safe: one instance is started and stopped by one owner.
 */
public final class OSSEmbeddedFakeServer {

    private static final Logger log = LoggerFactory.getLogger(OSSEmbeddedFakeServer.class);

    private static final String READY_MARKER = "oss-go-fake ready on ";
    private static final String SUPPORTED_PLATFORMS = "darwin-arm64, darwin-amd64, linux-amd64, linux-arm64, windows-amd64";
    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final int OUTPUT_TAIL_LINES = 20;

    private final List<String> arguments;
    private final int port;
    private final Path dataDir;
    private final boolean ownsDataDir;
    private final Duration startTimeout;
    private final Path binary;

    private Process process;
    private Thread shutdownHook;

    public static Builder builder() {
        return new Builder();
    }

    /** Maps os.name and os.arch to a binary directory in this jar, such as {@code darwin-arm64}. */
    private static String platform() {
        final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        final String cpu = switch (arch) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64" -> "amd64";
            default -> throw unsupported(os, arch);
        };
        final String system = os.contains("mac") ? "darwin"
                : os.contains("linux") ? "linux"
                : os.contains("windows") ? "windows"
                : null;
        if (system == null || system.equals("windows") && !cpu.equals("amd64")) {
            throw unsupported(os, arch);
        }
        return system + "-" + cpu;
    }

    /**
     * Copies the bundled binary of a platform to the shared cache directory and returns its path.
     * A cached copy of the same size is reused. A fresh copy is written under a temporary name and
     * moved into place atomically, so JVMs extracting at the same time never run a half-written file.
     */
    private static Path extract(String platform) throws IOException {
        final String name = platform.startsWith("windows") ? "oss-go-fake.exe" : "oss-go-fake";
        final URL resource = OSSEmbeddedFakeServer.class.getResource("/oss-fake/" + platform + "/" + name);
        if (resource == null) {
            throw new IllegalStateException("this jar holds no oss-go-fake binary for " + platform);
        }
        final long size = resource.openConnection().getContentLengthLong();
        final Path dir = Path.of(System.getProperty("java.io.tmpdir"), "oss-fake-embedded", version(), platform);
        final Path target = dir.resolve(name);
        if (Files.isRegularFile(target) && Files.size(target) == size) {
            return target;
        }

        Files.createDirectories(dir);
        final Path temp = Files.createTempFile(dir, name, ".tmp");
        try (InputStream in = resource.openStream()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            if (!temp.toFile().setExecutable(true, false)) {
                throw new IOException("cannot make " + temp + " executable");
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            // Windows refuses to replace a binary another JVM is running; that copy is complete.
            if (Files.isRegularFile(target) && Files.size(target) == size) {
                return target;
            }
            throw e;
        }
        return target;
    }

    private static String version() throws IOException {
        try (InputStream in = OSSEmbeddedFakeServer.class.getResourceAsStream("/oss-fake/version.properties")) {
            if (in == null) {
                throw new IllegalStateException("oss-fake/version.properties is missing from the jar");
            }
            final Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        }
    }

    private static String joined(Deque<String> tail) {
        synchronized (tail) {
            return String.join(System.lineSeparator(), tail);
        }
    }

    private static IllegalStateException unsupported(String os, String arch) {
        return new IllegalStateException(String.format(
                "oss-go-fake has no binary for os.name=%s os.arch=%s; supported: %s", os, arch, SUPPORTED_PLATFORMS));
    }

    private OSSEmbeddedFakeServer(List<String> arguments, int port, Path dataDir, boolean ownsDataDir,
                                  Duration startTimeout, Path binary) {
        this.arguments = arguments;
        this.port = port;
        this.dataDir = dataDir;
        this.ownsDataDir = ownsDataDir;
        this.startTimeout = startTimeout;
        this.binary = binary;
    }

    /**
     * Start the process and block until it is serving.
     *
     * @throws IllegalStateException when already started, when the platform is unsupported, or when
     *         the process exits or stays silent past the start timeout; its output is in the message
     * @throws IOException when the binary cannot be extracted or launched
     */
    public void start() throws IOException {
        if (process != null) {
            throw new IllegalStateException("oss-go-fake is already started");
        }
        final Deque<String> tail = new ArrayDeque<>();
        final CompletableFuture<Boolean> ready = new CompletableFuture<>();
        try {
            final List<String> command = new ArrayList<>();
            command.add((binary != null ? binary : extract(platform())).toString());
            command.add("serve");
            command.addAll(arguments);
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final InputStream output = process.getInputStream();
            final Thread reader = new Thread(() -> pump(output, tail, ready), "oss-fake-output-" + port);
            reader.setDaemon(true);
            reader.start();

            if (!ready.get(startTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(String.format("oss-go-fake exited before it was ready, output:%n%s", joined(tail)));
            }
        } catch (TimeoutException e) {
            release();
            throw new IllegalStateException(String.format(
                    "oss-go-fake was not ready within %s, output:%n%s", startTimeout, joined(tail)), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release();
            throw new IllegalStateException("interrupted while waiting for oss-go-fake to start", e);
        } catch (ExecutionException e) {
            release();
            throw new IllegalStateException("reading oss-go-fake output failed", e.getCause());
        } catch (IOException | RuntimeException e) {
            release();
            throw e;
        }
        shutdownHook = new Thread(this::stop, "oss-fake-shutdown-" + port);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        log.info("oss-go-fake serving at {}, data in {}", getEndpoint(), dataDir);
    }


    /**
     * Stop the process and release what this instance owns. Safe to call twice, and safe to call
     * when never started or when start failed -- the data directory this instance created is
     * removed either way.
     */
    public void stop() {
        release();
        if (shutdownHook == null || Thread.currentThread() == shutdownHook) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            log.debug("JVM is shutting down, the hook stays registered", e);
        }
        shutdownHook = null;
    }

    public boolean isActive() {
        return process != null && process.isAlive();
    }

    public int getPort() {
        return port;
    }

    /** {@code http://127.0.0.1:<port>}, ready to hand to an OSS client as its endpoint. */
    public String getEndpoint() {
        return "http://127.0.0.1:" + port;
    }

    public Path getDataDir() {
        return dataDir;
    }

    /**
     * Logs every output line of the process and remembers the last few for error messages.
     * Completes ready with true on the ready line, or with false when the output ends first.
     */
    private void pump(InputStream output, Deque<String> tail, CompletableFuture<Boolean> ready) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(output, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[oss-fake] {}", line);
                synchronized (tail) {
                    tail.addLast(line);
                    if (tail.size() > OUTPUT_TAIL_LINES) {
                        tail.removeFirst();
                    }
                }
                if (line.startsWith(READY_MARKER)) {
                    ready.complete(true);
                }
            }
        } catch (IOException e) {
            log.debug("oss-go-fake output closed", e);
        }
        ready.complete(false);
    }

    /** Ends the process if there is one, then removes an owned data directory. Idempotent. */
    private void release() {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!waitForExit()) {
                process.destroyForcibly();
                if (!waitForExit()) {
                    log.error("oss-go-fake (pid {}) did not exit after being killed", process.pid());
                }
            }
        }
        process = null;
        if (ownsDataDir && Files.exists(dataDir)) {
            try (Stream<Path> paths = Files.walk(dataDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (IOException | UncheckedIOException e) {
                log.warn("could not remove the data directory {}", dataDir, e);
            }
        }
    }

    private boolean waitForExit() {
        try {
            return process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    public enum ACL {
        PRIVATE("private"),
        PUBLIC_READ("public-read");

        private final String value;

        ACL(String value) {
            this.value = value;
        }
    }

    public static final class Builder {

        private int port = 0; // 0 = pick a free port
        private Duration startTimeout = START_TIMEOUT;
        private Path binary; // null = the bundled binary for this platform
        private Path dataDir;
        private Path config;
        private String region;
        private String domain;
        private final Map<String, String> accessKeys = new LinkedHashMap<>();
        private final Map<String, ACL> buckets = new LinkedHashMap<>();

        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                return socket.getLocalPort();
            }
        }

        public Builder port(int port) { this.port = port; return this; }

        /** How long start() waits for the ready line. A slow CI machine may need more than the default. */
        public Builder startTimeout(Duration startTimeout) { this.startTimeout = startTimeout; return this; }

        /**
         * Run this executable instead of the one bundled for the current platform -- a local build of
         * oss-go-fake, or a platform the jar does not ship. It receives the same arguments.
         */
        public Builder binary(Path binary) { this.binary = binary; return this; }

        /** Keep data here across runs. Without it, a temporary directory is created and removed on stop. */
        public Builder dataDir(Path dataDir) { this.dataDir = dataDir; return this; }

        /** An oss-go-fake YAML config file; the other builder settings override what it says. */
        public Builder config(Path config) { this.config = config; return this; }

        /** Region V4 credential scopes must name; oss-go-fake defaults to cn-hangzhou. */
        public Builder region(String region) { this.region = region; return this; }

        /** Base domain of virtual-hosted requests; without it only path-style requests are served. */
        public Builder domain(String domain) { this.domain = domain; return this; }

        public Builder accessKey(String id, String secret) { accessKeys.put(id, secret); return this; }

        /** Declare a private bucket, created at start when missing. */
        public Builder bucket(String name) { return bucket(name, ACL.PRIVATE); }

        public Builder bucket(String name, ACL acl) { buckets.put(name, acl); return this; }

        /**
         * @throws IllegalStateException when neither an access key nor a config file is given --
         *         the process would refuse to start, and failing here names the cause sooner
         * @throws IOException when no free port or temporary directory can be had
         */
        public OSSEmbeddedFakeServer build() throws IOException {
            if (accessKeys.isEmpty() && config == null) {
                throw new IllegalStateException("oss-go-fake needs an access key or a config file that declares one");
            }
            final int listenPort = port != 0 ? port : freePort();
            final boolean ownsDataDir = dataDir == null;
            final Path data = ownsDataDir ? Files.createTempDirectory("oss-fake-data-") : dataDir;

            final List<String> arguments = new ArrayList<>(List.of(
                    "--listen", "127.0.0.1:" + listenPort, "--data-dir", data.toString()));
            if (config != null) {
                arguments.addAll(List.of("--config", config.toString()));
            }
            if (region != null) {
                arguments.addAll(List.of("--region", region));
            }
            if (domain != null) {
                arguments.addAll(List.of("--domain", domain));
            }
            accessKeys.forEach((id, secret) -> arguments.addAll(List.of("--access-key", id + ":" + secret)));
            buckets.forEach((name, acl) -> arguments.addAll(List.of("--bucket", name + ":" + acl.value)));
            return new OSSEmbeddedFakeServer(List.copyOf(arguments), listenPort, data, ownsDataDir, startTimeout, binary);
        }
    }
}

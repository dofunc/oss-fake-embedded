package com.dofunc.ossfake;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;

class OSSEmbeddedFakeServerTest {

    private static final String AK = "test-ak";
    private static final String SK = "test-sk";
    private static final String REGION = "cn-hangzhou";
    private static final String BUCKET = "test-bucket";

    @TempDir
    Path temp;

    private static OSSEmbeddedFakeServer.Builder builder() {
        return OSSEmbeddedFakeServer.builder().accessKey(AK, SK).bucket(BUCKET);
    }

    private static OSS client(OSSEmbeddedFakeServer server, SignVersion version, String secret) {
        final ClientBuilderConfiguration conf = new ClientBuilderConfiguration();
        conf.setSignatureVersion(version);
        conf.setSLDEnabled(true);
        conf.setMaxErrorRetry(0);
        return OSSClientBuilder.create()
                .endpoint(server.getEndpoint())
                .credentialsProvider(new DefaultCredentialProvider(AK, secret))
                .clientConfiguration(conf)
                .region(REGION)
                .build();
    }

    private static void put(OSS client, String key, String content) {
        client.putObject(BUCKET, key, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String get(OSS client, String key) throws IOException {
        try (InputStream in = client.getObject(BUCKET, key).getObjectContent()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertRoundTrip(OSSEmbeddedFakeServer server, SignVersion version) throws IOException {
        final OSS client = client(server, version, SK);
        try {
            put(client, "dir/hello.txt", "hello " + version);
            assertEquals("hello " + version, get(client, "dir/hello.txt"));
        } finally {
            client.shutdown();
        }
    }

    private static String errorCodeOf(Runnable call) {
        return assertThrows(OSSException.class, call::run).getErrorCode();
    }

    /** The platform directory the server picks, computed independently of it. */
    private static String platform() {
        final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        final String cpu = arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "amd64";
        final String system = os.contains("mac") ? "darwin" : os.contains("windows") ? "windows" : "linux";
        return system + "-" + cpu;
    }

    private static String binaryName() {
        return platform().startsWith("windows") ? "oss-go-fake.exe" : "oss-go-fake";
    }

    private static Path cachedBinary() throws IOException {
        final Properties properties = new Properties();
        try (InputStream in = OSSEmbeddedFakeServerTest.class.getResourceAsStream("/oss-fake/version.properties")) {
            properties.load(in);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "oss-fake-embedded", properties.getProperty("version"),
                platform(), binaryName());
    }

    private static long bundledSize() throws IOException {
        final URL resource = OSSEmbeddedFakeServerTest.class.getResource("/oss-fake/" + platform() + "/" + binaryName());
        return resource.openConnection().getContentLengthLong();
    }

    /** The pid a test script recorded next to the data directory. */
    private static long scriptPid(Path dataDir) throws IOException {
        final Path pidFile = Path.of(dataDir + ".pid");
        final long pid = Long.parseLong(Files.readString(pidFile).trim());
        Files.delete(pidFile);
        return pid;
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    void readsAndWritesWithV4() throws IOException {
        final OSSEmbeddedFakeServer server = builder().build();
        server.start();
        try {
            assertRoundTrip(server, SignVersion.V4);
        } finally {
            server.stop();
        }
    }

    @Test
    void readsAndWritesWithV1() throws IOException {
        final OSSEmbeddedFakeServer server = builder().build();
        server.start();
        try {
            assertRoundTrip(server, SignVersion.V1);
        } finally {
            server.stop();
        }
    }

    @Test
    void reportsSignatureErrorsIncludingOnHead() throws IOException {
        final OSSEmbeddedFakeServer server = builder().build();
        server.start();
        final OSS good = client(server, SignVersion.V4, SK);
        final OSS wrong = client(server, SignVersion.V4, "wrong-secret");
        try {
            put(good, "k", "x");
            assertEquals("SignatureDoesNotMatch", errorCodeOf(() -> wrong.getObject(BUCKET, "k")));
            // HEAD has no body: the SDK must read the code from x-oss-err, not guess AccessForbidden.
            assertEquals("SignatureDoesNotMatch", errorCodeOf(() -> wrong.getObjectMetadata(BUCKET, "k")));
            assertEquals("NoSuchKey", errorCodeOf(() -> good.getObjectMetadata(BUCKET, "missing")));
            assertEquals("NoSuchBucket", errorCodeOf(() -> good.getObjectMetadata("no-such-bucket", "k")));
        } finally {
            good.shutdown();
            wrong.shutdown();
            server.stop();
        }
    }

    @Test
    void picksDistinctFreePorts() throws IOException {
        final OSSEmbeddedFakeServer first = builder().build();
        final OSSEmbeddedFakeServer second = builder().build();
        first.start();
        second.start();
        try {
            assertNotEquals(first.getPort(), second.getPort());
            assertRoundTrip(first, SignVersion.V4);
            assertRoundTrip(second, SignVersion.V4);
        } finally {
            first.stop();
            second.stop();
        }
    }

    @Test
    void failsWhenThePortIsTaken() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            final OSSEmbeddedFakeServer server = builder().port(taken.getLocalPort()).build();
            final IllegalStateException e = assertThrows(IllegalStateException.class, server::start);
            assertTrue(e.getMessage().contains("bind"), e.getMessage());
            assertFalse(server.isActive());
            assertFalse(Files.exists(server.getDataDir()));
        }
    }

    @Test
    void refusesToBuildWithoutCredentials() {
        assertThrows(IllegalStateException.class, () -> OSSEmbeddedFakeServer.builder().bucket(BUCKET).build());
    }

    @Test
    void refusesASecondStart() throws IOException {
        final OSSEmbeddedFakeServer server = builder().build();
        server.start();
        try {
            assertThrows(IllegalStateException.class, server::start);
            assertTrue(server.isActive());
        } finally {
            server.stop();
        }
    }

    @Test
    void stopFreesThePortAndRemovesTheOwnedDataDir() throws IOException {
        final OSSEmbeddedFakeServer server = builder().build();
        server.start();
        server.stop();
        assertFalse(server.isActive());
        assertFalse(Files.exists(server.getDataDir()));
        try (ServerSocket rebound = new ServerSocket(server.getPort(), 0, InetAddress.getLoopbackAddress())) {
            assertEquals(server.getPort(), rebound.getLocalPort());
        }
    }

    @Test
    void stopIsIdempotentAndWorksWithoutStart() throws IOException {
        final OSSEmbeddedFakeServer started = builder().build();
        started.start();
        started.stop();
        assertDoesNotThrow(started::stop);

        final OSSEmbeddedFakeServer neverStarted = builder().build();
        assertTrue(Files.isDirectory(neverStarted.getDataDir()));
        assertDoesNotThrow(neverStarted::stop);
        assertFalse(Files.exists(neverStarted.getDataDir()));
    }

    @Test
    void failsFastWhenTheProcessExits() throws IOException {
        final OSSEmbeddedFakeServer server = OSSEmbeddedFakeServer.builder()
                .config(temp.resolve("missing.yaml")).build();
        final IllegalStateException e = assertThrows(IllegalStateException.class, server::start);
        assertTrue(e.getMessage().contains("read config"), e.getMessage());
        assertFalse(server.isActive());
        assertFalse(Files.exists(server.getDataDir()));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void givesUpAfterTheStartTimeout() throws IOException {
        final OSSEmbeddedFakeServer server = builder().binary(script("never-ready.sh"))
                .startTimeout(Duration.ofSeconds(2)).build();
        final long begin = System.nanoTime();
        final IllegalStateException e = assertThrows(IllegalStateException.class, server::start);
        final Duration took = Duration.ofNanos(System.nanoTime() - begin);
        assertTrue(e.getMessage().contains("not ready within"), e.getMessage());
        assertTrue(took.compareTo(Duration.ofSeconds(2)) >= 0 && took.compareTo(Duration.ofSeconds(10)) < 0, took.toString());
        assertFalse(alive(scriptPid(server.getDataDir())));
        assertFalse(Files.exists(server.getDataDir()));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void killsAProcessThatIgnoresTerm() throws IOException {
        final OSSEmbeddedFakeServer server = builder().binary(script("ignore-term.sh")).build();
        server.start();
        final long pid = scriptPid(server.getDataDir());
        assertTrue(alive(pid));
        final long begin = System.nanoTime();
        server.stop();
        final Duration took = Duration.ofNanos(System.nanoTime() - begin);
        assertTrue(took.compareTo(Duration.ofSeconds(4)) >= 0, "stop returned before the TERM wait: " + took);
        assertFalse(alive(pid));
        assertFalse(server.isActive());
        assertFalse(Files.exists(server.getDataDir()));
    }

    @Test
    void runsAGivenBinary() throws IOException {
        final Path own = temp.resolve(binaryName());
        try (InputStream in = OSSEmbeddedFakeServerTest.class.getResourceAsStream("/oss-fake/" + platform() + "/" + binaryName())) {
            Files.copy(in, own);
        }
        assertTrue(own.toFile().setExecutable(true));
        final OSSEmbeddedFakeServer server = builder().binary(own).build();
        server.start();
        try {
            assertRoundTrip(server, SignVersion.V4);
        } finally {
            server.stop();
        }
    }

    @Test
    void keepsAGivenDataDirAcrossRuns() throws IOException {
        final Path data = temp.resolve("data");
        final OSSEmbeddedFakeServer first = builder().dataDir(data).build();
        first.start();
        final OSS writer = client(first, SignVersion.V4, SK);
        put(writer, "kept.txt", "still here");
        writer.shutdown();
        first.stop();
        assertTrue(Files.isDirectory(data));

        final OSSEmbeddedFakeServer second = builder().dataDir(data).build();
        second.start();
        final OSS reader = client(second, SignVersion.V4, SK);
        try {
            assertEquals("still here", get(reader, "kept.txt"));
        } finally {
            reader.shutdown();
            second.stop();
        }
    }

    @Test
    void reusesTheExtractedBinary() throws IOException {
        final OSSEmbeddedFakeServer server = builder().build();
        server.start();
        server.stop();
        final FileTime extracted = Files.getLastModifiedTime(cachedBinary());
        server.start();
        server.stop();
        assertEquals(extracted, Files.getLastModifiedTime(cachedBinary()));
    }

    @Test
    void extractsSafelyFromTwoThreads() throws Exception {
        deleteRecursively(cachedBinary().getParent());
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final List<OSSEmbeddedFakeServer> servers = new ArrayList<>();
        try {
            final Callable<OSSEmbeddedFakeServer> startOne = () -> {
                final OSSEmbeddedFakeServer server = builder().build();
                server.start();
                return server;
            };
            final List<Future<OSSEmbeddedFakeServer>> started = pool.invokeAll(List.of(startOne, startOne));
            for (Future<OSSEmbeddedFakeServer> future : started) {
                servers.add(future.get());
            }
            servers.forEach(server -> assertTrue(server.isActive()));
            assertEquals(bundledSize(), Files.size(cachedBinary()));
        } finally {
            servers.forEach(OSSEmbeddedFakeServer::stop);
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsAnUnsupportedPlatform() throws IOException {
        final String arch = System.getProperty("os.arch");
        final OSSEmbeddedFakeServer server = builder().build();
        System.setProperty("os.arch", "s390x");
        try {
            final IllegalStateException e = assertThrows(IllegalStateException.class, server::start);
            assertTrue(e.getMessage().contains("s390x") && e.getMessage().contains("darwin-arm64"), e.getMessage());
        } finally {
            System.setProperty("os.arch", arch);
        }
        assertFalse(Files.exists(server.getDataDir()));
    }

    @Test
    void survivesAProcessKilledFromOutside() throws IOException {
        final OSSEmbeddedFakeServer server = builder().build();
        server.start();
        try {
            // Match on the executable: Windows does not expose another process's arguments.
            final Optional<ProcessHandle> child = ProcessHandle.current().children()
                    .filter(p -> p.info().command().map(command -> command.endsWith(binaryName())).orElse(false))
                    .findFirst();
            assertTrue(child.isPresent(), "oss-go-fake child process not found");
            child.get().destroyForcibly();
            child.get().onExit().join();
            assertFalse(server.isActive());
        } finally {
            assertDoesNotThrow(server::stop);
        }
    }

    /** Copies a test script out of the classpath and makes it executable. */
    private Path script(String name) throws IOException {
        final Path target = temp.resolve(name);
        try (InputStream in = OSSEmbeddedFakeServerTest.class.getResourceAsStream("/scripts/" + name)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        assertTrue(target.toFile().setExecutable(true));
        return target;
    }
}

# oss-fake-embedded

Start [oss-go-fake](https://github.com/dofunc/oss-go-fake), a local Alibaba Cloud OSS emulator, from a JUnit test. Your code talks to it through the official OSS SDK, with real V1 or V4 signatures, and no cloud account.

The jar carries the oss-go-fake binary for every supported platform. Nothing else to install: no Go, no Docker.

## Dependency

```xml
<dependency>
    <groupId>com.dofunc</groupId>
    <artifactId>oss-fake-embedded</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

The only runtime dependency is `slf4j-api`. The process output is logged through SLF4J with an `[oss-fake]` prefix.

## Usage

```java
class StorageTest {

    static OSSEmbeddedFakeServer server;
    static OSS client;

    @BeforeAll
    static void startOSS() throws IOException {
        server = OSSEmbeddedFakeServer.builder()
                .accessKey("test-ak", "test-sk")
                .bucket("test-bucket")
                .build();
        server.start(); // returns once the process is serving

        ClientBuilderConfiguration conf = new ClientBuilderConfiguration();
        conf.setSignatureVersion(SignVersion.V4); // or SignVersion.V1
        conf.setSLDEnabled(true);                 // path-style: the bucket goes in the path
        client = OSSClientBuilder.create()
                .endpoint(server.getEndpoint())   // http://127.0.0.1:<port>
                .credentialsProvider(new DefaultCredentialProvider("test-ak", "test-sk"))
                .clientConfiguration(conf)
                .region("cn-hangzhou")            // oss-go-fake's default region
                .build();
    }

    @AfterAll
    static void stopOSS() {
        client.shutdown();
        server.stop();
    }

    @Test
    void storesAFile() {
        client.putObject("test-bucket", "a/b.txt", new ByteArrayInputStream("hello".getBytes()));
        // ...
    }
}
```

### Builder options

| Method | Default | Meaning |
|---|---|---|
| `accessKey(id, secret)` | | An access key the server accepts. At least one, or a `config` file declaring one |
| `bucket(name)`, `bucket(name, ACL)` | | A bucket created at start. ACL is `PRIVATE` or `PUBLIC_READ` |
| `port(int)` | a free port | Port on `127.0.0.1`. The process always listens on the loopback address |
| `region(String)` | `cn-hangzhou` | Region V4 credential scopes must name. Give the client the same region |
| `domain(String)` | none | Base domain for virtual-hosted requests. Without it, only path-style is served |
| `dataDir(Path)` | a temp directory | Where objects are stored. A directory you give is kept; the default one is removed on `stop()` |
| `config(Path)` | none | An oss-go-fake YAML config file. Builder settings override it |
| `startTimeout(Duration)` | 15 s | How long `start()` waits for the process to report ready |
| `binary(Path)` | the bundled binary | Run another oss-go-fake executable, such as a local build |

### Lifecycle

- `start()` blocks until the process is serving. If it exits or stays silent past the start timeout, the process is ended and an `IllegalStateException` carries its last output lines.
- `stop()` ends the process (SIGTERM, then a forced kill after 5 seconds) and removes the data directory it created. It is safe to call twice or without a successful start, and it also runs from a JVM shutdown hook.
- `isActive()`, `getPort()`, `getEndpoint()` and `getDataDir()` describe the running instance.
- An instance is not thread-safe. Start and stop it from one owner. Separate instances run side by side on separate ports.

## Platforms and the binary cache

| Platform | Bundled binary |
|---|---|
| macOS arm64, macOS x86-64 | `darwin-arm64`, `darwin-amd64` |
| Linux x86-64, Linux arm64 | `linux-amd64`, `linux-arm64` |
| Windows x86-64 | `windows-amd64` |

On first start, the binary for the current platform is copied to `java.io.tmpdir/oss-fake-embedded/<version>/<platform>/` and reused afterwards. On another platform, `start()` fails with the list above; point `binary(...)` at an oss-go-fake build for it.

Java 17 or newer.

## Versions

The version of this jar is the version of the oss-go-fake it bundles: `oss-fake-embedded:0.1.0` runs oss-go-fake `v0.1.0`. See the [oss-go-fake README](https://github.com/dofunc/oss-go-fake#readme) for what the emulator supports and how it differs from OSS.

## License

[Apache-2.0](LICENSE)

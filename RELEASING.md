# Releasing

This jar follows oss-go-fake: version `X.Y.Z` bundles oss-go-fake `vX.Y.Z`.

1. Wait until the oss-go-fake release `vX.Y.Z` is published with its binaries and `checksums.txt`.
2. In `pom.xml`, set `<version>` and `<oss-go-fake.version>` to `X.Y.Z`.
3. Copy the five sha256 values from that release's `checksums.txt` into the `sha256.*` properties. The build fails on any mismatch, so a wrong value cannot slip through.
4. Run `mvn verify` and check that every platform's binary is in the jar: `unzip -l target/oss-fake-embedded-X.Y.Z.jar`.
5. Update the version in `README.md`, commit, and push `main`.
6. Once CI is green: `git tag vX.Y.Z && git push origin vX.Y.Z`.

The `release` workflow builds the sources and javadoc jars, signs everything with GPG, and publishes through the Sonatype Central Portal. It waits until the deployment is published. The artifacts show up on `repo1.maven.org` within an hour or so.

## Repository secrets

| Secret | Content |
|---|---|
| `CENTRAL_USERNAME`, `CENTRAL_PASSWORD` | A Central Portal user token (central.sonatype.com, Account, Generate User Token) |
| `GPG_PRIVATE_KEY` | The ASCII-armored signing key: `gpg --armor --export-secret-keys <key-id>` |
| `GPG_PASSPHRASE` | Its passphrase |

The public half of the key must be on a public keyserver such as `keyserver.ubuntu.com` or `keys.openpgp.org`, or Central rejects the signatures.

## Trying an unreleased oss-go-fake

Build a snapshot in the oss-go-fake checkout (`goreleaser release --snapshot --clean --skip=docker`), then build this jar against it:

```sh
mvn verify -P local-dist                                      # expects ../../golang/oss-go-fake/dist
mvn verify -P local-dist -Doss-go-fake.dist=/path/to/dist     # anywhere else
```

Never publish a jar built with `local-dist`.

# Publishing once4k to Maven Central

The build is configured to publish to **Maven Central via the Sonatype Central Portal** using the
[`com.vanniktech.maven.publish`](https://vanniktech.github.io/gradle-maven-publish-plugin/) plugin
(`mavenPublishing { … }` in [`build.gradle.kts`](build.gradle.kts)). The publish itself is a manual,
credentialed step — run by the maintainer, not by CI.

## One-time setup

1. **Sonatype Central account** — sign up at <https://central.sonatype.com/>.
2. **Verify the namespace** — register and verify `com.digitalbluebird` (a DNS TXT record on
   `digitalbluebird.com` proves ownership). Central Portal rejects artifacts under an unverified
   namespace.
3. **Generate a user token** — in the account settings; it gives the username/password used below.
4. **GPG signing key** — create one (`gpg --gen-key`), publish the public half
   (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`), and note the key id and passphrase.

## Credentials

In `~/.gradle/gradle.properties` (never in the repo):

```properties
mavenCentralUsername=<central-portal-token-username>
mavenCentralPassword=<central-portal-token-password>

signing.keyId=<last 8 chars of the GPG key id>
signing.password=<GPG key passphrase>
signing.secretKeyRingFile=/Users/<you>/.gnupg/secring.gpg
```

## Publishing a release

Central Portal accepts **releases only**, so bump the version off `-SNAPSHOT` in `build.gradle.kts`
(e.g. `0.1.0`) first, then:

```bash
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

To upload but release manually from the portal, use `./gradlew publishToMavenCentral` instead. After
a release, tag it (`git tag v0.1.0 && git push --tags`), roll `CHANGELOG.md`'s **Unreleased** entries
into a dated section, and bump to the next `-SNAPSHOT`.

## Sanity checks that need no credentials

```bash
./gradlew publishToMavenLocal            # needs signing keys; publishes to ~/.m2
./gradlew generatePomFileForMavenPublication
```

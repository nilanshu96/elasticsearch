/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.packaging.test;

import org.elasticsearch.packaging.util.Distribution;
import org.elasticsearch.packaging.util.FileUtils;
import org.elasticsearch.packaging.util.Installation;
import org.elasticsearch.packaging.util.Packages;
import org.elasticsearch.packaging.util.Platforms;
import org.elasticsearch.packaging.util.ServerUtils;
import org.elasticsearch.packaging.util.Shell;
import org.elasticsearch.packaging.util.docker.Docker;
import org.elasticsearch.packaging.util.docker.DockerFileMatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.elasticsearch.packaging.util.Archives.ARCHIVE_OWNER;
import static org.elasticsearch.packaging.util.Archives.installArchive;
import static org.elasticsearch.packaging.util.Archives.verifyArchiveInstallation;
import static org.elasticsearch.packaging.util.FileMatcher.Fileness.File;
import static org.elasticsearch.packaging.util.FileMatcher.file;
import static org.elasticsearch.packaging.util.FileMatcher.p600;
import static org.elasticsearch.packaging.util.FileMatcher.p660;
import static org.elasticsearch.packaging.util.FileUtils.rm;
import static org.elasticsearch.packaging.util.Packages.assertInstalled;
import static org.elasticsearch.packaging.util.Packages.assertRemoved;
import static org.elasticsearch.packaging.util.Packages.installPackage;
import static org.elasticsearch.packaging.util.Packages.verifyPackageInstallation;
import static org.elasticsearch.packaging.util.docker.Docker.runContainer;
import static org.elasticsearch.packaging.util.docker.Docker.runContainerExpectingFailure;
import static org.elasticsearch.packaging.util.docker.Docker.waitForElasticsearch;
import static org.elasticsearch.packaging.util.docker.Docker.waitForPathToExist;
import static org.elasticsearch.packaging.util.docker.DockerRun.builder;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

public class KeystoreManagementTests extends PackagingTestCase {

    public static final String ERROR_INCORRECT_PASSWORD = "Provided keystore password was incorrect";
    public static final String ERROR_CORRUPTED_KEYSTORE = "Keystore has been corrupted or tampered with";
    public static final String ERROR_KEYSTORE_NOT_PASSWORD_PROTECTED = "ERROR: Keystore is not password-protected";
    public static final String ERROR_KEYSTORE_NOT_FOUND = "ERROR: Elasticsearch keystore not found";
    private static final String USERNAME = "elastic";
    private static final String PASSWORD = "nothunter2";

    /** Test initial archive state */
    public void test10InstallArchiveDistribution() throws Exception {
        assumeTrue(distribution().isArchive());

        installation = installArchive(sh, distribution);
        verifyArchiveInstallation(installation, distribution());

        final Installation.Executables bin = installation.executables();
        Shell.Result r = sh.runIgnoreExitCode(bin.keystoreTool + " has-passwd");
        assertFalse("has-passwd should fail", r.isSuccess());
        assertThat("has-passwd should indicate missing keystore", r.stderr, containsString(ERROR_KEYSTORE_NOT_FOUND));
    }

    /** Test initial package state */
    public void test11InstallPackageDistribution() throws Exception {
        assumeTrue(distribution().isPackage());

        assertRemoved(distribution);
        installation = installPackage(sh, distribution);
        assertInstalled(distribution);
        verifyPackageInstallation(installation, distribution, sh);

        final Installation.Executables bin = installation.executables();
        Shell.Result r = sh.runIgnoreExitCode(bin.keystoreTool + " has-passwd");
        assertFalse("has-passwd should fail", r.isSuccess());
        assertThat("has-passwd should indicate unprotected keystore", r.stderr, containsString(ERROR_KEYSTORE_NOT_PASSWORD_PROTECTED));
        Shell.Result r2 = bin.keystoreTool.run("list");
        assertThat(r2.stdout, containsString("keystore.seed"));
    }

    /** Test initial Docker state */
    public void test12InstallDockerDistribution() throws Exception {
        assumeTrue(distribution().isDocker());

        installation = Docker.runContainer(distribution(), builder());

        try {
            waitForPathToExist(installation.config("elasticsearch.keystore"));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        final Installation.Executables bin = installation.executables();
        Shell.Result r = sh.runIgnoreExitCode(bin.keystoreTool + " has-passwd");
        assertFalse("has-passwd should fail", r.isSuccess());
        assertThat("has-passwd should indicate unprotected keystore", r.stdout, containsString(ERROR_KEYSTORE_NOT_PASSWORD_PROTECTED));
        Shell.Result r2 = bin.keystoreTool.run("list");
        assertThat(r2.stdout, containsString("keystore.seed"));
    }

    public void test20CreateKeystoreManually() throws Exception {
        rmKeystoreIfExists();
        createKeystore(null);

        final Installation.Executables bin = installation.executables();
        verifyKeystorePermissions();

        Shell.Result r = bin.keystoreTool.run("list");
        assertThat(r.stdout, containsString("keystore.seed"));
    }

    public void test30AutoCreateKeystore() throws Exception {
        assumeTrue("Packages and docker are installed with a keystore file", distribution.isArchive());
        rmKeystoreIfExists();

        startElasticsearch();
        stopElasticsearch();

        Platforms.onWindows(() -> sh.chown(installation.config("elasticsearch.keystore")));

        verifyKeystorePermissions();

        final Installation.Executables bin = installation.executables();
        Shell.Result r = bin.keystoreTool.run("list");
        assertThat(r.stdout, containsString("keystore.seed"));
    }

    public void test40KeystorePasswordOnStandardInput() throws Exception {
        assumeTrue("packages will use systemd, which doesn't handle stdin", distribution.isArchive());
        assumeThat(installation, is(notNullValue()));

        String password = "^|<>\\&exit"; // code insertion on Windows if special characters are not escaped

        rmKeystoreIfExists();
        createKeystore(password);

        assertPasswordProtectedKeystore();

        awaitElasticsearchStartup(runElasticsearchStartCommand(password, true, false));
        ServerUtils.runElasticsearchTests();
        stopElasticsearch();
    }

    public void test41WrongKeystorePasswordOnStandardInput() throws Exception {
        assumeTrue("packages will use systemd, which doesn't handle stdin", distribution.isArchive());
        assumeThat(installation, is(notNullValue()));

        assertPasswordProtectedKeystore();

        Shell.Result result = runElasticsearchStartCommand("wrong", false, false);
        assertElasticsearchFailure(result, Arrays.asList(ERROR_INCORRECT_PASSWORD, ERROR_CORRUPTED_KEYSTORE), null);
    }

    public void test42KeystorePasswordOnTty() throws Exception {
        /* Windows issue awaits fix: https://github.com/elastic/elasticsearch/issues/49340 */
        assumeTrue("expect command isn't on Windows", distribution.platform != Distribution.Platform.WINDOWS);
        assumeTrue("packages will use systemd, which doesn't handle stdin", distribution.isArchive());
        assumeThat(installation, is(notNullValue()));

        String password = "keystorepass";

        rmKeystoreIfExists();
        createKeystore(password);

        assertPasswordProtectedKeystore();

        awaitElasticsearchStartup(runElasticsearchStartCommand(password, true, true));
        ServerUtils.runElasticsearchTests();
        stopElasticsearch();
    }

    public void test43WrongKeystorePasswordOnTty() throws Exception {
        /* Windows issue awaits fix: https://github.com/elastic/elasticsearch/issues/49340 */
        assumeTrue("expect command isn't on Windows", distribution.platform != Distribution.Platform.WINDOWS);
        assumeTrue("packages will use systemd, which doesn't handle stdin", distribution.isArchive());
        assumeThat(installation, is(notNullValue()));

        assertPasswordProtectedKeystore();

        Shell.Result result = runElasticsearchStartCommand("wrong", false, true);
        // error will be on stdout for "expect"
        assertThat(result.stdout, anyOf(containsString(ERROR_INCORRECT_PASSWORD), containsString(ERROR_CORRUPTED_KEYSTORE)));
    }

    /**
     * If we have an encrypted keystore, we shouldn't require a password to
     * view help information.
     */
    public void test44EncryptedKeystoreAllowsHelpMessage() throws Exception {
        assumeTrue("users call elasticsearch directly in archive case", distribution.isArchive());

        String password = "keystorepass";

        rmKeystoreIfExists();
        createKeystore(password);

        assertPasswordProtectedKeystore();
        Shell.Result r = installation.executables().elasticsearch.run("--help");
        assertThat(r.stdout, startsWith("Starts Elasticsearch"));
    }

    public void test50KeystorePasswordFromFile() throws Exception {
        assumeTrue("only for systemd", Platforms.isSystemd() && distribution().isPackage());
        String password = "!@#$%^&*()|\\<>/?";
        Path esKeystorePassphraseFile = installation.config.resolve("eks");

        rmKeystoreIfExists();
        createKeystore(password);

        assertPasswordProtectedKeystore();

        try {
            sh.run("sudo systemctl set-environment ES_KEYSTORE_PASSPHRASE_FILE=" + esKeystorePassphraseFile);

            Files.createFile(esKeystorePassphraseFile);
            Files.write(esKeystorePassphraseFile, List.of(password));

            startElasticsearch();
            ServerUtils.runElasticsearchTests();
            stopElasticsearch();
        } finally {
            sh.run("sudo systemctl unset-environment ES_KEYSTORE_PASSPHRASE_FILE");
        }
    }

    public void test51WrongKeystorePasswordFromFile() throws Exception {
        assumeTrue("only for systemd", Platforms.isSystemd() && distribution().isPackage());
        Path esKeystorePassphraseFile = installation.config.resolve("eks");

        assertPasswordProtectedKeystore();

        try {
            sh.run("sudo systemctl set-environment ES_KEYSTORE_PASSPHRASE_FILE=" + esKeystorePassphraseFile);

            if (Files.exists(esKeystorePassphraseFile)) {
                rm(esKeystorePassphraseFile);
            }

            Files.createFile(esKeystorePassphraseFile);
            Files.write(esKeystorePassphraseFile, List.of("wrongpassword"));

            Packages.JournaldWrapper journaldWrapper = new Packages.JournaldWrapper(sh);
            Shell.Result result = runElasticsearchStartCommand(null, false, false);
            assertElasticsearchFailure(result, Arrays.asList(ERROR_INCORRECT_PASSWORD, ERROR_CORRUPTED_KEYSTORE), journaldWrapper);
        } finally {
            sh.run("sudo systemctl unset-environment ES_KEYSTORE_PASSPHRASE_FILE");
        }
    }

    /**
     * Check that we can mount a password-protected keystore to a docker image
     * and provide a password via an environment variable.
     */
    @AwaitsFix(bugUrl = "Keystore fails to save with resource busy")
    public void test60DockerEnvironmentVariablePassword() throws Exception {
        assumeTrue(distribution().isDocker());
        String password = "keystore-password";

        Path localConfigDir = getMountedLocalConfDirWithKeystore(password, installation.config);

        // restart ES with password and mounted config dir containing password protected keystore
        runContainer(
            distribution(),
            builder().volume(localConfigDir.resolve("config"), installation.config)
                .envVar("KEYSTORE_PASSWORD", password)
                .envVar("ELASTIC_PASSWORD", PASSWORD)
        );
        waitForElasticsearch(installation, USERNAME, PASSWORD);
        ServerUtils.runElasticsearchTests(USERNAME, PASSWORD);
    }

    /**
     * Check that we can mount a password-protected keystore to a docker image
     * and provide a password via a file, pointed at from an environment variable.
     */
    @AwaitsFix(bugUrl = "Keystore fails to save with resource busy")
    public void test61DockerEnvironmentVariablePasswordFromFile() throws Exception {
        assumeTrue(distribution().isDocker());

        Path tempDir = null;
        try {
            tempDir = createTempDir(KeystoreManagementTests.class.getSimpleName());

            String password = "keystore-password";
            String passwordFilename = "password.txt";
            Files.writeString(tempDir.resolve(passwordFilename), password + "\n");
            Files.setPosixFilePermissions(tempDir.resolve(passwordFilename), p600);

            Path localConfigDir = getMountedLocalConfDirWithKeystore(password, installation.config);

            // restart ES with password and mounted config dir containing password protected keystore
            runContainer(
                distribution(),
                builder().volume(localConfigDir.resolve("config"), installation.config)
                    .volume(tempDir, "/run/secrets")
                    .envVar("KEYSTORE_PASSWORD_FILE", "/run/secrets/" + passwordFilename)
                    .envVar("ELASTIC_PASSWORD", PASSWORD)
            );

            waitForElasticsearch(installation, USERNAME, PASSWORD);
            ServerUtils.runElasticsearchTests(USERNAME, PASSWORD);
        } finally {
            if (tempDir != null) {
                rm(tempDir);
            }
        }
    }

    /**
     * Check that if we provide the wrong password for a mounted and password-protected
     * keystore, Elasticsearch doesn't start.
     */
    @AwaitsFix(bugUrl = "Keystore fails to save with resource busy")
    public void test62DockerEnvironmentVariableBadPassword() throws Exception {
        assumeTrue(distribution().isDocker());
        String password = "keystore-password";

        Path localConfigPath = getMountedLocalConfDirWithKeystore(password, installation.config);

        // restart ES with password and mounted config dir containing password protected keystore
        Shell.Result r = runContainerExpectingFailure(
            distribution(),
            builder().volume(localConfigPath.resolve("config"), installation.config).envVar("KEYSTORE_PASSWORD", "wrong")
        );
        assertThat(r.stderr, containsString(ERROR_INCORRECT_PASSWORD));
    }

    /**
     * In the Docker context, it's a little bit tricky to get a password-protected
     * keystore. All of the utilities we'd want to use are on the Docker image.
     * This method mounts a temporary directory to a Docker container, password-protects
     * the keystore, and then returns the path of the file that appears in the
     * mounted directory (now accessible from the local filesystem).
     */
    private Path getMountedLocalConfDirWithKeystore(String password, Path dockerKeystore) throws IOException {
        // Mount a temporary directory for copying the keystore
        Path dockerTemp = Path.of("/usr/tmp/keystore-tmp");
        Path tempDirectory = createTempDir(KeystoreManagementTests.class.getSimpleName());

        // It's very tricky to properly quote a pipeline that you're passing to
        // a docker exec command, so we're just going to put a small script in the
        // temp folder.
        List<String> setPasswordScript = List.of(
            "echo \"" + password,
            password,
            "\" | " + installation.executables().keystoreTool.toString() + " passwd"
        );

        Files.write(tempDirectory.resolve("set-pass.sh"), setPasswordScript);

        runContainer(distribution(), builder().volume(tempDirectory, dockerTemp));
        try {
            waitForPathToExist(dockerTemp);
            waitForPathToExist(dockerKeystore);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // We need a local shell to put the correct permissions on our mounted directory.
        Shell localShell = new Shell();
        localShell.run("docker exec --tty " + Docker.getContainerId() + " chown elasticsearch:root " + dockerTemp);
        localShell.run("docker exec --tty " + Docker.getContainerId() + " chown elasticsearch:root " + dockerTemp.resolve("set-pass.sh"));

        sh.run("bash " + dockerTemp.resolve("set-pass.sh"));

        // copy keystore to temp file to make it available to docker host
        sh.run("cp -arf" + dockerKeystore + " " + dockerTemp);
        return tempDirectory;
    }

    /** Create a keystore. Provide a password to password-protect it, otherwise use null */
    private void createKeystore(String password) throws Exception {
        Path keystore = installation.config("elasticsearch.keystore");
        final Installation.Executables bin = installation.executables();
        bin.keystoreTool.run("create");

        if (distribution().isDocker()) {
            try {
                waitForPathToExist(keystore);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        if (password != null) {
            setKeystorePassword(password);
        }

        // this is a hack around the fact that we can't run a command in the same session as the same user but not as administrator.
        // the keystore ends up being owned by the Administrators group, so we manually set it to be owned by the vagrant user here.
        // from the server's perspective the permissions aren't really different, this is just to reflect what we'd expect in the tests.
        // when we run these commands as a role user we won't have to do this
        Platforms.onWindows(() -> sh.chown(keystore));
    }

    private void rmKeystoreIfExists() {
        Path keystore = installation.config("elasticsearch.keystore");
        if (distribution().isDocker()) {
            try {
                waitForPathToExist(keystore);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            // Move the auto-created one out of the way, or else the CLI prompts asks us to confirm
            sh.run("rm " + keystore);
        } else {
            if (Files.exists(keystore)) {
                FileUtils.rm(keystore);
            }
        }
    }

    private void setKeystorePassword(String password) throws Exception {
        final Installation.Executables bin = installation.executables();

        // set the password by passing it to stdin twice
        Platforms.onLinux(() -> bin.keystoreTool.run("passwd", password + "\n" + password + "\n"));

        Platforms.onWindows(
            () -> sh.run("Invoke-Command -ScriptBlock {echo '" + password + "'; echo '" + password + "'} | " + bin.keystoreTool + " passwd")
        );
    }

    private void assertPasswordProtectedKeystore() {
        Shell.Result r = installation.executables().keystoreTool.run("has-passwd");
        assertThat("keystore should be password protected", r.exitCode, is(0));
    }

    private void verifyKeystorePermissions() {
        Path keystore = installation.config("elasticsearch.keystore");
        switch (distribution.packaging) {
            case TAR:
            case ZIP:
                assertThat(keystore, file(File, ARCHIVE_OWNER, ARCHIVE_OWNER, p660));
                break;
            case DEB:
            case RPM:
                assertThat(keystore, file(File, "root", "elasticsearch", p660));
                break;
            case DOCKER:
            case DOCKER_UBI:
            case DOCKER_IRON_BANK:
            case DOCKER_CLOUD:
            case DOCKER_CLOUD_ESS:
                assertThat(keystore, DockerFileMatcher.file(p660));
                break;
            default:
                throw new IllegalStateException("Unknown Elasticsearch packaging type.");
        }
    }
}

package org.mockserver.persistence;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.scheduler.Scheduler;

import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class ExpectationFileSystemPersistenceTest {

    private MockServerLogger mockServerLogger;
    private RequestMatchers requestMatchers;

    @Before
    public void createMockServerMatcher() {
        Configuration matcherConfiguration = configuration();
        mockServerLogger = new MockServerLogger(matcherConfiguration, ExpectationFileSystemPersistenceTest.class);
        requestMatchers = new RequestMatchers(matcherConfiguration, mockServerLogger, new Scheduler(matcherConfiguration, mockServerLogger), new WebSocketClientRegistry(matcherConfiguration, mockServerLogger));
    }

    @Test
    public void shouldPersistExpectationsToJsonOnAdd() throws Exception {
        // given
        File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectations.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileSystemPersistenceTest.class);
        ExpectationFileSystemPersistence expectationFileSystemPersistence = null;
        try {
            // when
            expectationFileSystemPersistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleFirst")
            )
                .withId("one")
                .thenRespond(
                    response()
                        .withBody("some first response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleSecond")
            )
                .withId("two")
                .thenRespond(
                    response()
                        .withBody("some second response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleThird")
            )
                .withId("three")
                .thenRespond(
                    response()
                        .withBody("some third response")
                ), API);
            MILLISECONDS.sleep(1500);

            // then
            String expectedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content", new String(Files.readAllBytes(persistedExpectations.toPath()), StandardCharsets.UTF_8), is(expectedFileContents));
        } finally {
            if (expectationFileSystemPersistence != null) {
                expectationFileSystemPersistence.stop();
            }
        }
    }

    @Test
    public void shouldPersistExpectationsToJsonOnRemove() throws Exception {
        // given
        File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectations.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileSystemPersistenceTest.class);
        ExpectationFileSystemPersistence expectationFileSystemPersistence = null;
        try {
            // when
            expectationFileSystemPersistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleFirst")
            )
                .withId("one")
                .thenRespond(
                    response()
                        .withBody("some first response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleSecond")
            )
                .withId("two")
                .thenRespond(
                    response()
                        .withBody("some second response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleThird")
            )
                .withId("three")
                .thenRespond(
                    response()
                        .withBody("some third response")
                ), API);
            requestMatchers.clear(
                request()
                    .withPath("/simpleSecond")
            );
            MILLISECONDS.sleep(1500);

            // then
            String expectedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content", new String(Files.readAllBytes(persistedExpectations.toPath()), StandardCharsets.UTF_8), is(expectedFileContents));
        } finally {
            if (expectationFileSystemPersistence != null) {
                expectationFileSystemPersistence.stop();
            }
        }
    }

    @Test
    public void shouldPersistExpectationsToJsonOnUpdate() throws Exception {
        // given
        File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectations.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileSystemPersistenceTest.class);
        ExpectationFileSystemPersistence expectationFileSystemPersistence = null;
        try {
            // when
            expectationFileSystemPersistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleFirst")
            )
                .withId("one")
                .thenRespond(
                    response()
                        .withBody("some first response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleSecond")
            )
                .withId("two")
                .thenRespond(
                    response()
                        .withBody("some second response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleThird")
            )
                .withId("three")
                .thenRespond(
                    response()
                        .withBody("some third response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleSecondUpdated")
            )
                .withId("two")
                .thenRespond(
                    response()
                        .withBody("some second updated response")
                ), API);
            MILLISECONDS.sleep(1500);

            // then
            String expectedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecondUpdated\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second updated response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content", new String(Files.readAllBytes(persistedExpectations.toPath()), StandardCharsets.UTF_8), is(expectedFileContents));
        } finally {
            if (expectationFileSystemPersistence != null) {
                expectationFileSystemPersistence.stop();
            }
        }
    }

    @Test
    public void shouldPersistExpectationsToJsonOnUpdateAll() throws Exception {
        // given
        File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectations.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileSystemPersistenceTest.class);
        ExpectationFileSystemPersistence expectationFileSystemPersistence = null;
        try {
            // when
            expectationFileSystemPersistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleFirst")
            )
                .withId("one")
                .thenRespond(
                    response()
                        .withBody("some first response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleSecond")
            )
                .withId("two")
                .thenRespond(
                    response()
                        .withBody("some second response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleThird")
            )
                .withId("three")
                .thenRespond(
                    response()
                        .withBody("some third response")
                ), API);
            requestMatchers.update(new Expectation[]{
                new Expectation(
                    request()
                        .withPath("/simpleFirst")
                )
                    .withId("one")
                    .thenRespond(
                    response()
                        .withBody("some first response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleSecondUpdated")
                )
                    .withId("two")
                    .thenRespond(
                    response()
                        .withBody("some second updated response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleFourth")
                )
                    .withId("four")
                    .thenRespond(
                    response()
                        .withBody("some fourth response")
                )
            }, API);
            MILLISECONDS.sleep(1500);

            // then
            String expectedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecondUpdated\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second updated response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFourth\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some fourth response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"four\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content", new String(Files.readAllBytes(persistedExpectations.toPath()), StandardCharsets.UTF_8), is(expectedFileContents));
        } finally {
            if (expectationFileSystemPersistence != null) {
                expectationFileSystemPersistence.stop();
            }
        }
    }

    @Test
    public void shouldPersistExpectationsToJsonOnUpdateAllFromFileWatcher() throws Exception {
        // given
        File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectations.getAbsolutePath())
            .initializationJsonPath(persistedExpectations.getAbsolutePath())
            .watchInitializationJson(true);
        MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileSystemPersistenceTest.class);
        ExpectationFileSystemPersistence expectationFileSystemPersistence = null;
        try {
            // when
            expectationFileSystemPersistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleFirst")
            )
                .withId("one")
                .thenRespond(
                    response()
                        .withBody("some first response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleSecond")
            )
                .withId("two")
                .thenRespond(
                    response()
                        .withBody("some second response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleThird")
            )
                .withId("three")
                .thenRespond(
                    response()
                        .withBody("some third response")
                ), API);
            MILLISECONDS.sleep(1500);
            requestMatchers.update(new Expectation[]{
                new Expectation(
                    request()
                        .withPath("/simpleFirst")
                )
                    .withId("one")
                    .thenRespond(
                    response()
                        .withBody("some first response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleSecondUpdated")
                )
                    .withId("two")
                    .thenRespond(
                    response()
                        .withBody("some second updated response")
                ),
                new Expectation(
                    request()
                        .withPath("/simpleFourth")
                )
                    .withId("four")
                    .thenRespond(
                    response()
                        .withBody("some fourth response")
                )
            }, new MockServerMatcherNotifier.Cause(persistedExpectations.getAbsolutePath(), MockServerMatcherNotifier.Cause.Type.FILE_INITIALISER));
            MILLISECONDS.sleep(1500);

            // then
            String expectedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleThird\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some third response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"three\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content", new String(Files.readAllBytes(persistedExpectations.toPath()), StandardCharsets.UTF_8), is(expectedFileContents));
        } finally {
            if (expectationFileSystemPersistence != null) {
                expectationFileSystemPersistence.stop();
            }
        }
    }

    @Test
    public void shouldPersistExpectationsWithFiniteTimeToLiveIncludingEndDate() throws Exception {
        File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectations.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileSystemPersistenceTest.class);
        ExpectationFileSystemPersistence expectationFileSystemPersistence = null;
        try {
            expectationFileSystemPersistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/finiteTtl"),
                Times.unlimited(),
                TimeToLive.exactly(SECONDS, 300L),
                0
            )
                .withId("finite")
                .thenRespond(
                    response()
                        .withBody("some response")
                ), API);
            MILLISECONDS.sleep(1500);

            String fileContents = new String(Files.readAllBytes(persistedExpectations.toPath()), StandardCharsets.UTF_8);
            assertThat(fileContents, containsString("\"timeUnit\" : \"SECONDS\""));
            assertThat(fileContents, containsString("\"timeToLive\" : 300"));
            assertThat(fileContents, containsString("\"endDate\""));
        } finally {
            if (expectationFileSystemPersistence != null) {
                expectationFileSystemPersistence.stop();
            }
        }
    }


    // ---------------------------------------------------------------- expectations that could not be loaded

    private static final String ONE_INVALID_OF_THREE = "[" +
        " { \"id\" : \"good-one\", \"httpRequest\" : { \"path\" : \"/one\" }, \"httpResponse\" : { \"statusCode\" : 200 } }," +
        " { \"id\" : \"bad-zero\", \"httpRequest\" : { \"path\" : \"/zero\" }, \"httpResponse\" : { \"statusCode\" : 0 } }," +
        " { \"id\" : \"good-two\", \"httpRequest\" : { \"path\" : \"/two\" }, \"httpResponse\" : { \"statusCode\" : 201 } }" +
        " ]";

    private static java.util.List<File> backupsOf(File persisted) {
        File[] found = persisted.getParentFile().listFiles((dir, name) -> name.startsWith(persisted.getName() + ".invalid-entries-") && name.endsWith(".bak"));
        return found == null ? java.util.Collections.emptyList() : java.util.Arrays.asList(found);
    }

    private static void awaitContent(File file, String fragment) throws Exception {
        long deadline = System.currentTimeMillis() + SECONDS.toMillis(20);
        while (!new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains(fragment)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + file + " to contain " + fragment);
            }
            MILLISECONDS.sleep(50);
        }
    }

    @Test
    public void shouldBackUpTheLoadedFileBeforeTheFirstSaveWouldDropItsInvalidExpectations() throws Exception {
        // given - persistence writes back to the file it was initialised from, and one entry in it is invalid
        File persisted = File.createTempFile("persistedWithInvalidEntry", ".json");
        persisted.deleteOnExit();
        Files.write(persisted.toPath(), ONE_INVALID_OF_THREE.getBytes(StandardCharsets.UTF_8));
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persisted.getAbsolutePath())
            .initializationJsonPath(persisted.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileSystemPersistenceTest.class);
        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers);
            new org.mockserver.server.initialize.ExpectationInitializerLoader(configuration, logger, requestMatchers);

            // when - two saves
            requestMatchers.add(new Expectation(request().withPath("/added")).withId("added").thenRespond(response().withStatusCode(202)), API);
            awaitContent(persisted, "added");
            requestMatchers.add(new Expectation(request().withPath("/added-again")).withId("added-again").thenRespond(response().withStatusCode(202)), API);
            awaitContent(persisted, "added-again");

            // then - the file now holds only what loaded plus the additions, and ONE copy keeps the original
            String saved = new String(Files.readAllBytes(persisted.toPath()), StandardCharsets.UTF_8);
            assertThat(saved.contains("bad-zero"), is(false));
            java.util.List<File> backups = backupsOf(persisted);
            assertThat(String.valueOf(backups), backups.size(), is(1));
            assertThat(new String(Files.readAllBytes(backups.get(0).toPath()), StandardCharsets.UTF_8), is(ONE_INVALID_OF_THREE));
            backups.forEach(File::deleteOnExit);
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    @Test
    public void shouldNotBackUpALoadedFileWhoseExpectationsAllLoaded() throws Exception {
        File persisted = File.createTempFile("persistedAllValid", ".json");
        persisted.deleteOnExit();
        Files.write(persisted.toPath(), ("[ { \"id\" : \"good-one\", \"httpRequest\" : { \"path\" : \"/one\" }, \"httpResponse\" : { \"statusCode\" : 200 } } ]").getBytes(StandardCharsets.UTF_8));
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persisted.getAbsolutePath())
            .initializationJsonPath(persisted.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, ExpectationFileSystemPersistenceTest.class);
        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers);
            new org.mockserver.server.initialize.ExpectationInitializerLoader(configuration, logger, requestMatchers);

            requestMatchers.add(new Expectation(request().withPath("/added")).withId("added").thenRespond(response().withStatusCode(202)), API);
            awaitContent(persisted, "added");

            assertThat(backupsOf(persisted).isEmpty(), is(true));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }
}

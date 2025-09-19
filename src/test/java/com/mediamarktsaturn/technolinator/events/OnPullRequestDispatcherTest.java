package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kohsuke.github.GHEvent;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import static com.mediamarktsaturn.technolinator.TestUtil.url;
import static com.mediamarktsaturn.technolinator.events.DispatcherBase.CONFIG_FILE;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@QuarkusTest
@GitHubAppTest
class OnPullRequestDispatcherTest {

    @InjectMock
    AnalysisProcessHandler handler;

    @BeforeEach
    void setup() {
        Mockito.reset(handler);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/events/pull_request_opened.json",
        "/events/pull_request_reopened.json",
        "/events/pull_request_opened.json"
    })
    void testPullRequestActionable(String payloadClassPath) throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath(payloadClassPath)
            .event(GHEvent.PULL_REQUEST);

        // Then
        await().untilAsserted(() -> Mockito.verify(handler)
            .onPullRequest(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/test/branch", "main", null)), any()));

        RestAssured.get("/q/metrics")
            .then()
            .statusCode(200)
            .body(CoreMatchers.containsString("""
                repo_analysis_current_count{kind="pull-request",repo="app-test"} 0.0
                """));
    }

    @Test
    void testPullRequestReportdisabled() throws IOException {
        // When
        GitHubAppTesting.given()
            .github(mocks -> {
                mocks.configFile(CONFIG_FILE).fromClasspath("/configs/disabled_pr_report.yml");
            })
            .when()
            .payloadFromClasspath("/events/pull_request_opened.json")
            .event(GHEvent.PULL_REQUEST);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));

        RestAssured.get("/q/metrics")
            .then()
            .statusCode(200)
            .body(CoreMatchers.containsString("""
                pull_request_total{repo="app-test",status="DISABLED_PR_REPORTS"} 1.0
                """));
    }

    @Test
    void testPullRequestClosed() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/pull_request_closed.json")
            .event(GHEvent.PULL_REQUEST);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));
    }

    static ArgumentMatcher<PullRequestEvent> matches(String repoUrl, String pushRef, String defaultBranch, TechnolinatorConfig config) {
        return got ->
            got.repoUrl().sameFile(url(repoUrl))
                && got.ref().equals(pushRef)
                && got.defaultBranch().equals(defaultBranch)
                && got.config().equals(Optional.ofNullable(config));
    }

    @Test
    void testPullRequestBranchEligibleForAnalysis_noBranchConfig() {
        // Given
        var prPayload = mock(org.kohsuke.github.GHEventPayload.PullRequest.class);
        var pullRequest = mock(org.kohsuke.github.GHPullRequest.class);
        var head = mock(org.kohsuke.github.GHCommitPointer.class);

        Mockito.when(prPayload.getPullRequest()).thenReturn(pullRequest);
        Mockito.when(pullRequest.getHead()).thenReturn(head);
        Mockito.when(head.getRef()).thenReturn("feature/test-branch");

        // When & Then - should allow all PRs when no branch config
        assertTrue(OnPullRequestDispatcher.isPullRequestBranchEligibleForAnalysis(prPayload, Optional.empty()));
    }

    @Test
    void testPullRequestBranchEligibleForAnalysis_includePullRequestsDisabled() {
        // Given
        var prPayload = mock(org.kohsuke.github.GHEventPayload.PullRequest.class);
        var pullRequest = mock(org.kohsuke.github.GHPullRequest.class);
        var head = mock(org.kohsuke.github.GHCommitPointer.class);

        Mockito.when(prPayload.getPullRequest()).thenReturn(pullRequest);
        Mockito.when(pullRequest.getHead()).thenReturn(head);
        Mockito.when(head.getRef()).thenReturn("feature/test-branch");

        var branchConfig = new TechnolinatorConfig.BranchConfig(List.of("feature/.*"), false);
        var config = ConfigBuilder.create().branches(branchConfig).build();

        // When & Then - should allow all PRs when includePullRequests is false
        assertTrue(OnPullRequestDispatcher.isPullRequestBranchEligibleForAnalysis(prPayload, Optional.of(config)));
    }

    @Test
    void testPullRequestBranchEligibleForAnalysis_includePullRequestsEnabled_matchingPattern() {
        // Given
        var prPayload = mock(org.kohsuke.github.GHEventPayload.PullRequest.class);
        var pullRequest = mock(org.kohsuke.github.GHPullRequest.class);
        var head = mock(org.kohsuke.github.GHCommitPointer.class);

        Mockito.when(prPayload.getPullRequest()).thenReturn(pullRequest);
        Mockito.when(pullRequest.getHead()).thenReturn(head);
        Mockito.when(head.getRef()).thenReturn("feature/test-branch");

        var branchConfig = new TechnolinatorConfig.BranchConfig(List.of("feature/.*"), true);
        var config = ConfigBuilder.create().branches(branchConfig).build();

        // When & Then
        assertTrue(OnPullRequestDispatcher.isPullRequestBranchEligibleForAnalysis(prPayload, Optional.of(config)));
    }

    @Test
    void testPullRequestBranchEligibleForAnalysis_includePullRequestsEnabled_nonMatchingPattern() {
        // Given
        var prPayload = mock(org.kohsuke.github.GHEventPayload.PullRequest.class);
        var pullRequest = mock(org.kohsuke.github.GHPullRequest.class);
        var head = mock(org.kohsuke.github.GHCommitPointer.class);

        Mockito.when(prPayload.getPullRequest()).thenReturn(pullRequest);
        Mockito.when(pullRequest.getHead()).thenReturn(head);
        Mockito.when(head.getRef()).thenReturn("bugfix/test-branch");

        var branchConfig = new TechnolinatorConfig.BranchConfig(List.of("feature/.*"), true);
        var config = ConfigBuilder.create().branches(branchConfig).build();

        // When & Then
        assertFalse(OnPullRequestDispatcher.isPullRequestBranchEligibleForAnalysis(prPayload, Optional.of(config)));
    }

    @Test
    void testPullRequestBranchEligibleForAnalysis_includePullRequestsEnabled_multiplePatterns() {
        // Given
        var prPayload = mock(org.kohsuke.github.GHEventPayload.PullRequest.class);
        var pullRequest = mock(org.kohsuke.github.GHPullRequest.class);
        var head = mock(org.kohsuke.github.GHCommitPointer.class);

        Mockito.when(prPayload.getPullRequest()).thenReturn(pullRequest);
        Mockito.when(pullRequest.getHead()).thenReturn(head);
        Mockito.when(head.getRef()).thenReturn("hotfix/urgent-fix");

        var branchConfig = new TechnolinatorConfig.BranchConfig(List.of("feature/.*", "hotfix/.*", "release/.*"), true);
        var config = ConfigBuilder.create().branches(branchConfig).build();

        // When & Then
        assertTrue(OnPullRequestDispatcher.isPullRequestBranchEligibleForAnalysis(prPayload, Optional.of(config)));
    }
}

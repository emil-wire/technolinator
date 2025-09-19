package com.mediamarktsaturn.technolinator.events;

import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler;
import com.mediamarktsaturn.technolinator.sbom.Project;
import io.quarkiverse.githubapp.testing.GitHubAppTest;
import io.quarkiverse.githubapp.testing.GitHubAppTesting;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.smallrye.mutiny.Uni;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@QuarkusTest
@GitHubAppTest
class OnPushDispatcherTest {

    @InjectMock
    AnalysisProcessHandler handler;

    @BeforeEach
    void setup() {
        Mockito.reset(handler);
    }

    @Test
    void testOnPushToDefaultBranch() throws IOException {
        // When
        GitHubAppTesting.when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(handler)
            .onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", null)), any()));

        RestAssured.get("/q/metrics")
            .then()
            .statusCode(200)
            .body(CoreMatchers.containsString("""
                repo_analysis_current_count{kind="push",repo="app-test"} 0.0
                """));
    }

    @Test
    void testConfigProjectName() throws IOException {
        // Given
        var config = ConfigBuilder.create().project(new TechnolinatorConfig.ProjectConfig("overriddenName")).build();
        Mockito.when(handler.onPush(any(), any())).thenReturn(Uni.createFrom().item(Result.success(Project.none())));

        // When
        GitHubAppTesting.given()
            .github(mocks -> mocks.configFile(CONFIG_FILE).fromClasspath("/configs/project_name_override.yml"))
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(handler).onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", config)), any()));
    }

    @Test
    void testConfigDisabled() throws IOException {
        // When
        GitHubAppTesting.given()
            .github(mocks -> mocks.configFile(CONFIG_FILE).fromClasspath("/configs/disabled.yml"))
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verifyNoInteractions(handler));
    }

    @Test
    void testConfigAllOptions() throws IOException {
        // Given
        var config = ConfigBuilder.create()
            .enable(true)
            .project(new TechnolinatorConfig.ProjectConfig("awesomeProject"))
            .analysis(new TechnolinatorConfig.AnalysisConfig("projectLocation", true, true, true, true, null))
            .build();

        // When
        GitHubAppTesting.given()
            .github(mocks -> mocks.configFile(CONFIG_FILE).fromClasspath("/configs/full_blown.yml"))
            .when()
            .payloadFromClasspath("/events/push_to_default_branch.json")
            .event(GHEvent.PUSH);

        // Then
        await().untilAsserted(() -> Mockito.verify(handler).onPush(argThat(matches("https://github.com/heubeck/app-test", "refs/heads/main", "main", config)), any()));
    }

    @Test
    void testRepoEnabledConfig_noRestriction() {
        // Given
        var cur = new OnPushDispatcher();
        cur.enabledRepos = List.of();

        // When && Then
        assertThat(cur.isEnabledByConfig("technolinator")).isTrue();
    }

    @Test
    void testRepoEnabledConfig_restriction() {
        // Given
        var cur = new OnPushDispatcher();
        cur.enabledRepos = List.of(" technolinator ", "", " analyzeMe");

        // When && Then
        assertThat(cur.isEnabledByConfig("technolinator")).isTrue();
        assertThat(cur.isEnabledByConfig("fluggegecheimen")).isFalse();
    }

    static ArgumentMatcher<PushEvent> matches(String repoUrl, String pushRef, String defaultBranch, TechnolinatorConfig config) {
        return got ->
            got.repoUrl().sameFile(url(repoUrl))
                && got.ref().equals(pushRef)
                && got.defaultBranch().equals(defaultBranch)
                && got.config().equals(Optional.ofNullable(config));
    }

    @Test
    void testBranchEligibleForAnalysis_defaultBranch() {
        // Given
        var pushPayload = mock(org.kohsuke.github.GHEventPayload.Push.class);
        var repository = mock(org.kohsuke.github.GHRepository.class);
        Mockito.when(pushPayload.getRef()).thenReturn("refs/heads/main");
        Mockito.when(pushPayload.getRepository()).thenReturn(repository);
        Mockito.when(repository.getDefaultBranch()).thenReturn("main");

        // When & Then
        assertTrue(OnPushDispatcher.isBranchEligibleForAnalysis(pushPayload, Optional.empty()));
    }

    @Test
    void testBranchEligibleForAnalysis_nonDefaultBranch_noConfig() {
        // Given
        var pushPayload = mock(org.kohsuke.github.GHEventPayload.Push.class);
        var repository = mock(org.kohsuke.github.GHRepository.class);
        Mockito.when(pushPayload.getRef()).thenReturn("refs/heads/feature/test");
        Mockito.when(pushPayload.getRepository()).thenReturn(repository);
        Mockito.when(repository.getDefaultBranch()).thenReturn("main");

        // When & Then
        assertFalse(OnPushDispatcher.isBranchEligibleForAnalysis(pushPayload, Optional.empty()));
    }

    @Test
    void testBranchEligibleForAnalysis_nonDefaultBranch_withMatchingPattern() {
        // Given
        var pushPayload = mock(org.kohsuke.github.GHEventPayload.Push.class);
        var repository = mock(org.kohsuke.github.GHRepository.class);
        Mockito.when(pushPayload.getRef()).thenReturn("refs/heads/feature/test-branch");
        Mockito.when(pushPayload.getRepository()).thenReturn(repository);
        Mockito.when(repository.getDefaultBranch()).thenReturn("main");

        var branchConfig = new TechnolinatorConfig.BranchConfig(List.of("feature/.*"), false);
        var config = ConfigBuilder.create().branches(branchConfig).build();

        // When & Then
        assertTrue(OnPushDispatcher.isBranchEligibleForAnalysis(pushPayload, Optional.of(config)));
    }

    @Test
    void testBranchEligibleForAnalysis_nonDefaultBranch_withNonMatchingPattern() {
        // Given
        var pushPayload = mock(org.kohsuke.github.GHEventPayload.Push.class);
        var repository = mock(org.kohsuke.github.GHRepository.class);
        Mockito.when(pushPayload.getRef()).thenReturn("refs/heads/bugfix/test-branch");
        Mockito.when(pushPayload.getRepository()).thenReturn(repository);
        Mockito.when(repository.getDefaultBranch()).thenReturn("main");

        var branchConfig = new TechnolinatorConfig.BranchConfig(List.of("feature/.*"), false);
        var config = ConfigBuilder.create().branches(branchConfig).build();

        // When & Then
        assertFalse(OnPushDispatcher.isBranchEligibleForAnalysis(pushPayload, Optional.of(config)));
    }

    @Test
    void testBranchEligibleForAnalysis_multiplePatterns() {
        // Given
        var pushPayload = mock(org.kohsuke.github.GHEventPayload.Push.class);
        var repository = mock(org.kohsuke.github.GHRepository.class);
        Mockito.when(pushPayload.getRef()).thenReturn("refs/heads/hotfix/urgent-fix");
        Mockito.when(pushPayload.getRepository()).thenReturn(repository);
        Mockito.when(repository.getDefaultBranch()).thenReturn("main");

        var branchConfig = new TechnolinatorConfig.BranchConfig(List.of("feature/.*", "hotfix/.*", "release/.*"), false);
        var config = ConfigBuilder.create().branches(branchConfig).build();

        // When & Then
        assertTrue(OnPushDispatcher.isBranchEligibleForAnalysis(pushPayload, Optional.of(config)));
    }

    @Test
    void testBranchEligibleForAnalysis_invalidPattern() {
        // Given
        var pushPayload = mock(org.kohsuke.github.GHEventPayload.Push.class);
        var repository = mock(org.kohsuke.github.GHRepository.class);
        Mockito.when(pushPayload.getRef()).thenReturn("refs/heads/feature/test-branch");
        Mockito.when(pushPayload.getRepository()).thenReturn(repository);
        Mockito.when(repository.getDefaultBranch()).thenReturn("main");

        var branchConfig = new TechnolinatorConfig.BranchConfig(List.of("[invalid"), false);
        var config = ConfigBuilder.create().branches(branchConfig).build();

        // When & Then
        assertFalse(OnPushDispatcher.isBranchEligibleForAnalysis(pushPayload, Optional.of(config)));
    }

    @Test
    void testBranchEligibleForAnalysis_customPatterns() {
        var pushPayload = mock(org.kohsuke.github.GHEventPayload.Push.class);
        var repository = mock(org.kohsuke.github.GHRepository.class);
        Mockito.when(pushPayload.getRepository()).thenReturn(repository);
        Mockito.when(repository.getDefaultBranch()).thenReturn("dev");

        var branchConfig = new TechnolinatorConfig.BranchConfig(List.of(
            "feat/.*", "chore/.*", ".*-cherry-pick", "release/.*"
        ), false);
        var config = ConfigBuilder.create().branches(branchConfig).build();

        String[] matchingBranches = {
            "feat/add-new-feature",
            "chore/update-dependencies",
            "chore/bulletproofing-cbox-ccmigration-cherry-pick",
            "release/q2-2025"
        };

        for (String branch : matchingBranches) {
            Mockito.when(pushPayload.getRef()).thenReturn("refs/heads/" + branch);
            assertTrue(OnPushDispatcher.isBranchEligibleForAnalysis(pushPayload, Optional.of(config)),
                "Branch should be eligible: " + branch);
        }

        String[] nonMatchingBranches = {
            "random-branch",
            "user/personal-work",
            "dependabot/npm_and_yarn/some-package"
        };

        for (String branch : nonMatchingBranches) {
            Mockito.when(pushPayload.getRef()).thenReturn("refs/heads/" + branch);
            assertFalse(OnPushDispatcher.isBranchEligibleForAnalysis(pushPayload, Optional.of(config)),
                "Branch should NOT be eligible: " + branch);
        }
    }
}

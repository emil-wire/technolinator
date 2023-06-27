package com.mediamarktsaturn.technolinator.sbom;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.cyclonedx.generators.json.BomJsonGenerator14;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.ExternalReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.technolinator.Result;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * API client for Dependency-Track
 */
@ApplicationScoped
public class DependencyTrackClient {

    private static final String API_PATH = "/api/v1", API_KEY = "X-API-Key";

    private final WebClient client;
    private final String dtrackBaseUrl, dtrackApiUrl, dtrackApikey;

    public DependencyTrackClient(
        Vertx vertx,
        @ConfigProperty(name = "dtrack.apikey")
        String dtrackApikey,
        @ConfigProperty(name = "dtrack.url")
        String dtrackUrl
    ) {
        this.client = WebClient.create(vertx);
        this.dtrackApikey = dtrackApikey.trim();
        this.dtrackBaseUrl = dtrackUrl.trim();
        this.dtrackApiUrl = dtrackBaseUrl + API_PATH;
    }

    /**
     * Uploads the given  [sbom] to Dependency-Track, deactivating other versions of the same project if any
     */
    public Uni<Result<Project>> uploadSBOM(String projectName, String projectVersion, Bom sbom, ProjectDetails projectDetails) {
        var sbomBase64 = Base64.getEncoder().encodeToString(new BomJsonGenerator14(sbom).toJsonString().getBytes(StandardCharsets.UTF_8));
        var payload = new JsonObject(Map.of(
            "projectName", projectName,
            "projectVersion", projectVersion,
            "autoCreate", true,
            "bom", sbomBase64
        ));

        return client.putAbs(dtrackApiUrl + "/bom")
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(payload)
            .map(Unchecked.function(result -> {
                if (result.statusCode() == 200) {
                    return result;
                } else {
                    throw new Exception("Status " + result.statusCode());
                }
            }))
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to upload project %s in version %s", projectName, projectVersion))
            .onItem().invoke(() -> Log.infof("Uploaded project %s in version %s", projectName, projectVersion))
            .call(() -> deactivatePreviousVersion(projectName, projectVersion))
            .chain(i -> getCurrentVersionUrl(projectName, projectVersion))
            .call(r -> {
                if (r instanceof Result.Success<Project>(Project project) && project instanceof Project.Available p) {
                    return tagAndDescribeProject(p.projectId(), projectDetails);
                } else {
                    return Uni.createFrom().voidItem();
                }
            })
            .onFailure().recoverWithItem(Result.Failure::new);
    }

    Uni<Result<Project>> getCurrentVersionUrl(String projectName, String projectVersion) {
        return client.getAbs(dtrackApiUrl + "/project/lookup")
            .addQueryParam("name", projectName)
            .addQueryParam("version", projectVersion)
            .putHeader(API_KEY, dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to lookup project %s in version %s", projectName, projectVersion))
            .chain(response -> {
                if (response.statusCode() == 200) {
                    var projectUUID = response.bodyAsJsonObject().getString("uuid");
                    return Uni.createFrom().item(Result.success(Project.available("%s/projects/%s".formatted(dtrackBaseUrl, projectUUID), projectUUID)));
                } else {
                    Log.errorf("Failed to deactivate previous versions of project %s in version %s, status: %s, message: %s", projectName, projectVersion, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().failure(new Exception("Status " + response.statusCode()));
                }
            }).onFailure().recoverWithItem(Result.Failure::new);
    }

    Uni<Void> deactivatePreviousVersion(String projectName, String projectVersion) {
        return client.getAbs(dtrackApiUrl + "/project")
            .addQueryParam("name", projectName)
            .addQueryParam("excludeInactive", "true")
            .putHeader(API_KEY, dtrackApikey)
            .putHeader("Accept", "application/json")
            .send()
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.errorf(e, "Failed to list projects named %s", projectName))
            .chain(response -> {
                if (response.statusCode() == 200) {
                    return Uni.combine().all().unis(
                        response.bodyAsJsonArray().stream()
                            .filter(o -> o instanceof JsonObject && isDifferentVersionOfSameProject((JsonObject) o, projectName, projectVersion))
                            .map(p -> ((JsonObject) p).getString("uuid"))
                            .map(this::deactivateProject)
                            .toList()
                    ).discardItems();
                } else {
                    Log.errorf("Failed to deactivate previous versions of project %s in version %s, status: %s, message: %s", projectName, projectVersion, response.statusCode(), response.bodyAsString());
                    return Uni.createFrom().failure(new Exception("Status " + response.statusCode()));
                }
            }).onFailure().recoverWithNull();
    }

    Uni<Void> deactivateProject(String projectUUID) {
        return client.patchAbs(dtrackApiUrl + "/project/" + projectUUID)
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(JsonObject.of("active", false))
            .onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.warnf(e, "Failed to disabled project %s", projectUUID))
            .onItem().invoke(() -> Log.infof("Disabled project %s", projectUUID))
            .onFailure().recoverWithNull()
            .replaceWithVoid();
    }

    Uni<Void> tagAndDescribeProject(String projectUUID, ProjectDetails projectDetails) {
        var tagsArray = new JsonArray(projectDetails.tags().stream()
            .filter(t -> t != null && !t.isBlank())
            .map(tag -> JsonObject.of("name", tag)
            ).toList());
        var description = projectDetails.description();
        var descValue = description == null || description.isBlank() ? "" : description;
        var extRefs = JsonArray.of(
            JsonObject.of(
                "type", ExternalReference.Type.VCS.getTypeName(),
                "url", projectDetails.vcsUrl()
            ),
            JsonObject.of(
                "type", ExternalReference.Type.WEBSITE.getTypeName(),
                "url", projectDetails.websiteUrl()
            ),
            JsonObject.of(
                "type", ExternalReference.Type.RELEASE_NOTES.getTypeName(),
                "url", projectDetails.websiteUrl() + "/releases"
            )
        );
        return client.patchAbs(dtrackApiUrl + "/project/" + projectUUID)
            .putHeader(API_KEY, dtrackApikey)
            .sendJsonObject(JsonObject.of(
                "tags", tagsArray,
                "description", descValue,
                "externalReferences", extRefs
            )).onFailure().retry().atMost(3)
            .onFailure().invoke(e -> Log.warnf(e, "Failed to tag and describe project %s", projectUUID))
            .onFailure().recoverWithNull()
            .replaceWithVoid();
    }

    boolean isDifferentVersionOfSameProject(JsonObject project, String projectName, String projectVersion) {
        return
            project.getString("name").equals(projectName)
                && !project.getString("version").equals(projectVersion);
    }

    public record ProjectDetails(
        String description,
        String websiteUrl,
        String vcsUrl,
        List<String> tags
    ) {
    }
}

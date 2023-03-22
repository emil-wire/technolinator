# Technolinator

![dependencies](https://dtrack.mmst.eu/api/v1/badge/vulns/project/technolinator/main) ![policies](https://dtrack.mmst.eu/api/v1/badge/violations/project/technolinator/main)
[![Quality Gate Status](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=alert_status&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Maintainability Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=sqale_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Reliability Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=reliability_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain) [![Security Rating](https://sonarqube.cloud.mmst.eu/api/project_badges/measure?project=technolinator%3Amain&metric=security_rating&token=squ_c20d5a134cfb4e85c6046de00451b6f4d21ee225)](https://sonarqube.cloud.mmst.eu/dashboard?id=technolinator%3Amain)

The MediaMarktSaturn GitHub Bot for SBOM creation and upload to Dependency-Track.

It wraps around [cdxgen](https://github.com/CycloneDX/cdxgen) which covers many programming languages and build systems.
It's build using [Quarkus](https://quarkus.io/) and handles GitHub webhooks by the [Quarkus GitHub App](https://quarkiverse.github.io/quarkiverse-docs/quarkus-github-app/dev/index.html).

## Runtime

ENV configuration:

| Parameter                         | Default                | Description                                                                 |
|-----------------------------------|------------------------|-----------------------------------------------------------------------------|
| PORT                              | 8080                   | Http port to listen to for GitHub Webhook events                            |
| QUARKUS_GITHUB_APP_APP_ID         |                        | Created during app creation on GitHub                                       |
| QUARKUS_GITHUB_APP_WEBHOOK_SECRET |                        | Created during app creation on GitHub                                       |
| QUARKUS_GITHUB_APP_PRIVATE_KEY    |                        | Created during app creation on GitHub                                       |
| GITHUB_TOKEN                      |                        | Optional. Raises GH api quota for cdxgen and enables `go mod` projects      |
| DTRACK_APIKEY                     |                        | API key to access Dependency-Track                                          |
| DTRACK_URL                        |                        | Baseurl of Dependency-Track                                                 |
| CDXGEN_FETCH_LICENSE              | true                   | see [cdxgen](https://github.com/CycloneDX/cdxgen#environment-variables)     |
| CDXGEN_USE_GOSUM                  | true                   | see [cdxgen](https://github.com/CycloneDX/cdxgen#environment-variables)     |
| ANALYSIS_RECURSIVE_DEFAULT        | true                   | default value for the `analysis.recursvie` config                           |
| APP_CLEAN_WRAPPER_SCRIPTS         | true                   | Remove wrapper scripts like gradlew or mvnw for not downloading these tools |
| APP_ANALYSIS_TIMEOUT              | 30M                    | Maximal duration of an analysis before getting aborted                      |
| APP_ENABLED_REPOS                 |                        | Comma separated list of repo names that should be analyzed; all if empty    |
| SENSITIVE_ENV_VARS                | sentivie from above    | Comma separated list of env var names that must not be logged               |

## Repository specific configuration

Technolinator respects a configuration file in the default branch of repositories:
**`.github/technolinator.yml`**

with the following options:
```yaml
# whether Technolinator does analysis at all; default: true
enable: true
project:
    # desired name of the project in dependency-track; default depends on build system, for maven it's: "groupId:artifactId"
    name: awesomeProject
analysis:
    # the location targeted by cdxgen; default: repository root
    location: projectLocation
    # whether cdxgen should scan for projects recursively in 'location' or only 'location' itself; default: false
    recursive: false
    # folders within 'analysis.location' to exclude from created sbom (e.g. non-production stuff)
    excludes:
        - subfolder1
        - just/another/path/below/projectLocation
gradle:
    # define this project as gradle multi project (acc. to https://docs.gradle.org/current/userguide/intro_multi_project_builds.html)
    multiProject: false
    # list of arguments to be provided to cdxgen as GRADLE_ARGS; env vars notated with ${ENV_VAR} will be resolved (see below)
    args:
        - -PyourProperitary=property
maven:
    # list of arguments to be provided to cdxgen as MVN_ARGS; env vars notated with ${ENV_VAR} will be resolved (see below)
    args:
        - -Pall
env:
    # additional env parameter for cdxgen; env vars notated with ${ENV_VAR} will be resolved (see below)
    THIS_IS: just another value
jdk:
    # select JDK version used by cdxgen on JVM based projects (options below)
    version: 19
```

The configuration file is optional and only necessary to override default behavior.

### Config of jdk.version

Different JDK installations can be provided to Technolinator by its own env.
Env vars of pattern `JAVA\d+_HOME` will be detected, and the `\d+` values can be used for `jdk.version`.
In the standard container image, JDK _17_ and _19_ are build in, with 19 used by default via `JAVA_HOME`.

### Config of env vars available for use in repo specific configuration

Any environment variable backed into the runtime can be referred to.
Please mind to add sensitive env names (like GitHub token or artifact repository secrets) to the `SENSITIVE_ENV_VARS` to not having them outputted via logging, see the [Dockerfile](src/main/docker/Dockerfile) for the defaults.

## Adopting for private use

For using Technolinator inside your organization with private artifact repositories we recommend to create a derived container image containing needed configuration like Maven or Gradle setting files and ENV.

This could look like:

```dockerfile
FROM ghcr.io/mediamarktsaturn/technolinator:1.27.0

COPY --chown=1001:root assets/settings.xml /root/.m2/settings.xml

ENV SENSITIVE_ENV_VARS="QUARKUS_GITHUB_APP_APP_ID,QUARKUS_GITHUB_APP_WEBHOOK_SECRET,QUARKUS_GITHUB_APP_PRIVATE_KEY,ARTIFACTORY_USER,ARTIFACTORY_PASSWORD,GITHUB_TOKEN,DTRACK_APIKEY" \
    ARTIFACTORY_URL="https://cloud.artifactory.com/artifactory" \
    DTRACK_URL="https://dependency-track.awesome.org"
```

## Observability

Technolinators process can best be followed using its log. Every push event received is noted, and the output of cdxgen is logged as well.
In addition, Technolinator provides Prometheus metrics about push events and analysis results.

There's a Grafana dashboard available in [here](_dashboards), that visualizes these metrics.

---

_This repository is published under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)_

**_get to know us 👉 [https://mms.tech](https://mms.tech) 👈_**

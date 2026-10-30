package io.github.nols1000.release.android

import io.github.nols1000.release.common.Http
import io.github.nols1000.release.common.run
import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import java.net.URLEncoder

/**
 * Gets a Google access token for the publishing service account without a stored key.
 *
 * On GitHub Actions the job's OIDC token is exchanged through Workload Identity Federation.
 * Locally the logged-in gcloud user impersonates the service account.
 */
class GoogleAuth(private val exec: ExecOperations) {
    fun accessToken(scope: String): String =
        if (System.getenv("ACTIONS_ID_TOKEN_REQUEST_URL") != null) fromGitHubOidc(scope) else fromGcloud(scope)

    private fun fromGitHubOidc(scope: String): String {
        val provider = requireEnv("GCP_WIF_PROVIDER")
        val serviceAccount = requireEnv("GCP_SERVICE_ACCOUNT")

        val audience = URLEncoder.encode("https://iam.googleapis.com/$provider", Charsets.UTF_8)
        val idToken = Http.json(
            "GET",
            requireEnv("ACTIONS_ID_TOKEN_REQUEST_URL") + "&audience=$audience",
            bearer = requireEnv("ACTIONS_ID_TOKEN_REQUEST_TOKEN"),
        )["value"] as String

        val federatedToken = Http.json(
            "POST",
            "https://sts.googleapis.com/v1/token",
            body = mapOf(
                "grantType" to "urn:ietf:params:oauth:grant-type:token-exchange",
                "audience" to "//iam.googleapis.com/$provider",
                "scope" to "https://www.googleapis.com/auth/cloud-platform",
                "requestedTokenType" to "urn:ietf:params:oauth:token-type:access_token",
                "subjectToken" to idToken,
                "subjectTokenType" to "urn:ietf:params:oauth:token-type:jwt",
            ),
        )["access_token"] as String

        return serviceAccountToken(serviceAccount, federatedToken, scope)
    }

    /** Needs `roles/iam.serviceAccountTokenCreator` on the service account for the gcloud user. */
    private fun fromGcloud(scope: String): String {
        val serviceAccount = System.getenv("GCP_SERVICE_ACCOUNT")
            ?: throw GradleException("Set GCP_SERVICE_ACCOUNT to the publishing service account to publish from this machine")
        // `gcloud auth print-access-token --impersonate-service-account` ignores `--scopes`, so ask IAM directly.
        val userToken = exec.run("gcloud", "auth", "print-access-token").stdout
        return serviceAccountToken(serviceAccount, userToken, scope)
    }

    private fun serviceAccountToken(serviceAccount: String, callerToken: String, scope: String): String =
        Http.json(
            "POST",
            "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/$serviceAccount:generateAccessToken",
            bearer = callerToken,
            body = mapOf("scope" to listOf(scope)),
        )["accessToken"] as String

    private fun requireEnv(name: String) =
        System.getenv(name) ?: throw GradleException("Environment variable $name is not set")
}

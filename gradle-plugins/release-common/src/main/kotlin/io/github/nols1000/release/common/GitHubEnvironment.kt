package io.github.nols1000.release.common

import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations

/** Sets up a GitHub deployment environment and its secrets through the locally logged-in `gh` CLI. */
class GitHubEnvironment(
    private val exec: ExecOperations,
    private val logger: Logger,
    val repo: String,
    val name: String,
) {
    /** Creates the environment if needed and restricts deployments to tags matching [tagPattern]. */
    fun ensureExists(tagPattern: String = "v*") {
        api("PUT", "repos/$repo/environments/$name", mapOf(
            "deployment_branch_policy" to mapOf("protected_branches" to false, "custom_branch_policies" to true),
        ))
        val policies = Json.parse(api("GET", "repos/$repo/environments/$name/deployment-branch-policies"))
        @Suppress("UNCHECKED_CAST")
        val existing = (policies["branch_policies"] as? List<Map<String, Any?>>).orEmpty()
        if (existing.none { it["name"] == tagPattern && it["type"] == "tag" }) {
            api("POST", "repos/$repo/environments/$name/deployment-branch-policies", mapOf("name" to tagPattern, "type" to "tag"))
        }
        logger.lifecycle("GitHub environment '$name' in $repo accepts deployments from tags '$tagPattern'")
    }

    fun setSecret(key: String, value: String) {
        exec.run("gh", "secret", "set", key, "--env", name, "--repo", repo, stdin = value)
        logger.lifecycle("Set secret $key in '$name'")
    }

    fun setVariable(key: String, value: String) {
        exec.run("gh", "variable", "set", key, "--env", name, "--repo", repo, "--body", value)
        logger.lifecycle("Set variable $key=$value in '$name'")
    }

    private fun api(method: String, path: String, body: Any? = null): String {
        val input = if (body != null) arrayOf("--input", "-") else emptyArray()
        return exec.run("gh", "api", "-X", method, path, *input, stdin = body?.let(Json::write)).stdout
    }

    companion object {
        /** Uses [configured] or asks `gh` for the repository of the current git remote. */
        fun resolveRepo(exec: ExecOperations, configured: String?): String =
            configured?.takeIf { it.isNotBlank() }
                ?: exec.run("gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner").stdout
    }
}

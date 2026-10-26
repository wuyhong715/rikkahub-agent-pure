package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Result-side verification for composite tools.
 *
 * A composite tool runs several steps the model would otherwise sequence by hand. That saves
 * round trips, but it also hides the intermediate state the model used to see — so every
 * composite result carries a `checks` array: one entry per promise the tool made, with the
 * evidence that it held. `ok` decides whether the result may be used; `evidence` is what the
 * model quotes when it explains itself.
 *
 * The rule for authors of composite tools: a check that can never be false is not a check.
 * Emit one only where the tool can actually fail that step.
 */
internal data class ResultCheck(
    val name: String,
    val ok: Boolean,
    val evidence: String,
)

internal fun checksJson(checks: List<ResultCheck>): JsonArray = buildJsonArray {
    checks.forEach { check ->
        add(
            buildJsonObject {
                put("name", check.name)
                put("ok", check.ok)
                put("evidence", check.evidence)
            }
        )
    }
}

internal fun JsonObjectBuilder.putChecks(checks: List<ResultCheck>) {
    put("checks", checksJson(checks))
}

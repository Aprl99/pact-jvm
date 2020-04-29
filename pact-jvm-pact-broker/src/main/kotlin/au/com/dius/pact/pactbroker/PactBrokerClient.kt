package au.com.dius.pact.pactbroker

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.salomonbrys.kotson.jsonArray
import com.github.salomonbrys.kotson.jsonObject
import com.github.salomonbrys.kotson.set
import com.github.salomonbrys.kotson.toJson
import com.google.gson.JsonObject
import mu.KLogging
import java.net.URLDecoder
import java.util.function.Consumer

/**
 * Wraps the response for a Pact from the broker with the link data associated with the Pact document.
 */
data class PactResponse(val pactFile: Any, val links: Map<String, Map<String, Any>>)

sealed class TestResult {
  object Ok: TestResult() {
    override fun toBoolean() = true

    override fun merge(result: TestResult) = when (result) {
      is Ok -> this
      is Failed -> result
    }
  }

  data class Failed(val results: List<Map<String, Any?>> = emptyList(), val description: String = ""): TestResult() {
    override fun toBoolean() = false

    override fun merge(result: TestResult) = when (result) {
      is Ok -> this
      is Failed -> Failed(results + result.results, when {
        description.isNotEmpty() && result.description.isNotEmpty() && description != result.description ->
          "$description, ${result.description}"
        description.isNotEmpty() -> description
        else -> result.description
      })
    }
  }

  abstract fun toBoolean(): Boolean
  abstract fun merge(result: TestResult): TestResult

  companion object {
    fun fromBoolean(result: Boolean) = if (result) Ok else Failed()
  }
}

/**
 * Pact broker base class
 */
abstract class PactBrokerClientBase(val pactBrokerUrl: String, val options: Map<String, Any> = mapOf()) {

  protected abstract fun newHalClient(): IHalClient

  @Deprecated(message = "Use the version that takes a test result",
    replaceWith = ReplaceWith("publishVerificationResults"))
  open fun publishVerificationResults(
    docAttributes: Map<String, Map<String, Any>>,
    result: Boolean,
    version: String,
    buildUrl: String? = null
  ): Result<Boolean, Exception>
    = publishVerificationResults(docAttributes, TestResult.fromBoolean(result), version, buildUrl)

  /**
   * Publishes the result to the "pb:publish-verification-results" link in the document attributes.
   */
  @JvmOverloads
  open fun publishVerificationResults(
    docAttributes: Map<String, Map<String, Any>>,
    result: TestResult,
    version: String,
    buildUrl: String? = null
  ): Result<Boolean, Exception> {
    val halClient = newHalClient()
    val publishLink = docAttributes.mapKeys { it.key.toLowerCase() } ["pb:publish-verification-results"] // ktlint-disable curly-spacing
    return if (publishLink != null) {
      val jsonObject = buildPayload(result, version, buildUrl)

      val lowercaseMap = publishLink.mapKeys { it.key.toLowerCase() }
      if (lowercaseMap.containsKey("href")) {
        halClient.postJson(lowercaseMap["href"].toString(), jsonObject.toString())
      } else {
        Err(RuntimeException("Unable to publish verification results as there is no " +
          "pb:publish-verification-results link"))
      }
    } else {
      Err(RuntimeException("Unable to publish verification results as there is no " +
        "pb:publish-verification-results link"))
    }
  }

  fun buildPayload(result: TestResult, version: String, buildUrl: String?): JsonObject {
    val jsonObject = jsonObject("success" to result.toBoolean(), "providerApplicationVersion" to version)
    if (buildUrl != null) {
      jsonObject.add("buildUrl", buildUrl.toJson())
    }

    logger.debug { "Test result = $result" }
    if (result is TestResult.Failed && result.results.isNotEmpty()) {
      val values = result.results
        .groupBy { it["interactionId"] }
        .map { mismatches ->
          val values = mismatches.value
            .filter { !it.containsKey("exception") }
            .flatMap { mismatch ->
              when (mismatch["type"]) {
                "body" -> {
                  when (val bodyMismatches = mismatch["comparison"]) {
                    is Map<*, *> -> bodyMismatches.entries.filter { it.key != "diff" }.flatMap { entry ->
                      val values = entry.value as List<Map<String, Any>>
                      values.map {
                        jsonObject("attribute" to "body", "identifier" to entry.key, "description" to it["mismatch"],
                          "diff" to it["diff"])
                      }
                    }
                    else -> listOf(jsonObject("attribute" to "body", "description" to bodyMismatches.toString()))
                  }
                }
                "status" -> listOf(jsonObject("attribute" to "status", "description" to mismatch["description"]))
                "header" -> {
                  listOf(jsonObject(mismatch.filter { it.key != "interactionId" }
                  .map {
                    if (it.key == "type") {
                      "attribute" to it.value
                    } else {
                      it.toPair()
                    }
                  }))
                }
                "metadata" -> {
                  listOf(jsonObject(mismatch.filter { it.key != "interactionId" }
                    .flatMap {
                      when {
                        it.key == "type" -> listOf("attribute" to it.value)
                        else -> listOf("identifier" to it.key, "description" to it.value)
                      }
                    }))
                }
                else -> listOf(jsonObject(
                  mismatch.filterNot { it.key == "interactionId" || it.key == "type" }.entries.map {
                    it.toPair()
                  }
                ))
              }
            }
          val interactionJson = jsonObject("interactionId" to mismatches.key, "success" to false,
            "description" to result.description,
            "mismatches" to jsonArray(values)
          )

          val exceptionDetails = mismatches.value.find { it.containsKey("exception") }
          if (exceptionDetails != null) {
            val exp = exceptionDetails["exception"] as Exception
            interactionJson["exception"] = jsonObject("message" to exp.message,
              "exceptionClass" to exp.javaClass.name)
          }

          interactionJson
        }
      jsonObject.add("testResults", jsonArray(values))
    }
    return jsonObject
  }

  open fun fetchLatestConsumersWithNoTag(provider: String): List<PactBrokerConsumer> {
    return try {
      val halClient = newHalClient()
      val consumers = mutableListOf<PactBrokerConsumer>()
      halClient.navigate(mapOf("provider" to provider), LATEST_PROVIDER_PACTS_WITH_NO_TAG)
        .forAll(PACTS, Consumer { pact ->
          val href = URLDecoder.decode(pact["href"].toString(), UTF8)
          val name = pact["name"].toString()
          if (options.containsKey("authentication")) {
            consumers.add(PactBrokerConsumer(name, href, pactBrokerUrl, options["authentication"] as List<String>))
          } else {
            consumers.add(PactBrokerConsumer(name, href, pactBrokerUrl, emptyList()))
          }
        })
      consumers
    } catch (_: NotFoundHalResponse) {
      // This means the provider is not defined in the broker, so fail gracefully.
      emptyList()
    }
  }

  companion object : KLogging() {
    const val LATEST_PROVIDER_PACTS_WITH_NO_TAG = "pb:latest-untagged-pact-version"
    const val PACTS = "pacts"
    const val UTF8 = "UTF-8"
  }
}

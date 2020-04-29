package au.com.dius.pact.provider

import au.com.dius.pact.model.Interaction
import au.com.dius.pact.model.Pact
import au.com.dius.pact.pactbroker.TestResult
import au.com.dius.pact.provider.ProviderVerifierBase.Companion.PACT_VERIFIER_PUBLISH_RESULTS
import mu.KLogging
import org.apache.commons.lang3.builder.HashCodeBuilder

/**
 * Accumulates the test results for the interactions. Once all the interactions for a pact have been verified,
 * the result is submitted back to the broker
 */
interface TestResultAccumulator {
  @Deprecated(message = "Use the version that takes a TestResult parameter")
  fun updateTestResult(pact: Pact<out Interaction>, interaction: Interaction, testExecutionResult: Boolean)
  fun updateTestResult(pact: Pact<out Interaction>, interaction: Interaction, testExecutionResult: TestResult)

  fun clearTestResult(pact: Pact<out Interaction>)
}

object DefaultTestResultAccumulator : TestResultAccumulator, KLogging() {

  val testResults: MutableMap<Int, MutableMap<Int, TestResult>> = mutableMapOf()
  var verificationReporter: VerificationReporter = DefaultVerificationReporter

  override fun updateTestResult(pact: Pact<out Interaction>, interaction: Interaction, testExecutionResult: Boolean) {
    updateTestResult(pact, interaction, TestResult.fromBoolean(testExecutionResult))
  }

  override fun updateTestResult(
    pact: Pact<out Interaction>,
    interaction: Interaction,
    testExecutionResult: TestResult
  ) {
    logger.debug { "Received test result '$testExecutionResult' for Pact ${pact.provider.name}-${pact.consumer.name} " +
      "and ${interaction.description}" }
    val pactHash = calculatePactHash(pact)
    val interactionResults = testResults.getOrPut(pactHash) { mutableMapOf() }
    val interactionHash = calculateInteractionHash(interaction)
    val testResult = interactionResults[interactionHash]
    if (testResult == null) {
      interactionResults[interactionHash] = testExecutionResult
    } else {
      interactionResults[interactionHash] = testResult.merge(testExecutionResult)
    }
    val unverifiedInteractions = unverifiedInteractions(pact, interactionResults)
    if (unverifiedInteractions.isEmpty()) {
      logger.debug {
        "All interactions for Pact ${pact.provider.name}-${pact.consumer.name} have a verification result"
      }
      if (verificationReporter.publishingResultsDisabled()) {
        logger.warn { "Skipping publishing of verification results as it has been disabled " +
          "($PACT_VERIFIER_PUBLISH_RESULTS is not 'true')" }
      } else {
        verificationReporter.reportResults(pact, interactionResults.values.fold(TestResult.Ok) {
          acc: TestResult, result -> acc.merge(result)
        }, lookupProviderVersion(), null)
      }
      testResults.remove(pactHash)
    } else {
      logger.warn { "Not all of the ${pact.interactions.size} were verified. The following were missing:" }
      unverifiedInteractions.forEach {
        logger.warn { "    ${it.description}" }
      }
    }
  }

  fun calculateInteractionHash(interaction: Interaction): Int {
    val builder = HashCodeBuilder().append(interaction.description)
    interaction.providerStates.forEach { builder.append(it.name) }
    return builder.toHashCode()
  }

  fun calculatePactHash(pact: Pact<out Interaction>) =
    HashCodeBuilder().append(pact.consumer.name).append(pact.provider.name).toHashCode()

  fun lookupProviderVersion(): String {
    val version = System.getProperty("pact.provider.version")
    return if (version.isNullOrEmpty()) {
      logger.warn { "Set the provider version using the 'pact.provider.version' property. Defaulting to '0.0.0'" }
      "0.0.0"
    } else {
      version
    }
  }

  private fun lookupProviderTag(): String? = System.getProperty("pact.provider.tag")

  fun unverifiedInteractions(pact: Pact<out Interaction>, results: MutableMap<Int, TestResult>): List<Interaction> {
    logger.debug { "Number of interactions #${pact.interactions.size} and results: ${results.values}" }
    return pact.interactions.filter { !results.containsKey(calculateInteractionHash(it)) }
  }

  override fun clearTestResult(pact: Pact<out Interaction>) {
    val pactHash = calculatePactHash(pact)
    testResults.remove(pactHash)
  }
}

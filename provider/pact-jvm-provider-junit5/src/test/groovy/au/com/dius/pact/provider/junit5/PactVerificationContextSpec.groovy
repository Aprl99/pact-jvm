package au.com.dius.pact.provider.junit5

import au.com.dius.pact.core.model.Interaction
import au.com.dius.pact.core.model.Request
import au.com.dius.pact.core.model.RequestResponseInteraction
import au.com.dius.pact.core.model.Response
import au.com.dius.pact.core.pactbroker.TestResult
import au.com.dius.pact.provider.IProviderVerifier
import au.com.dius.pact.provider.ProviderInfo
import au.com.dius.pact.core.support.expressions.ValueResolver
import org.junit.jupiter.api.extension.ExtensionContext
import spock.lang.Specification

class PactVerificationContextSpec extends Specification {

  @SuppressWarnings('UnnecessaryGetter')
  def 'sets the test result to an error result if the test fails with an exception'() {
    given:
    PactVerificationContext context
    ExtensionContext.Store store = Stub {
      get(_) >> { args ->
        if (args[0] == 'interactionContext') {
          context
        }
      }
    }
    ExtensionContext extContext = Stub {
      getStore(_) >> store
    }
    TestTarget target = Stub {
      executeInteraction(_, _) >> { throw new IOException('Boom!') }
    }
    IProviderVerifier verifier = Stub()
    ValueResolver valueResolver = Stub()
    ProviderInfo provider = Stub {
      getName() >> 'Stub'
    }
    String consumerName = 'Test'
    Interaction interaction = new RequestResponseInteraction('Test Interaction', [], new Request(),
      new Response(), '12345')
    TestResult testResult = TestResult.Ok.INSTANCE

    context = new PactVerificationContext(store, extContext, target, verifier, valueResolver,
      provider, consumerName, interaction, testResult)

    when:
    context.verifyInteraction()

    then:
    thrown(AssertionError)
    context.testExecutionResult instanceof TestResult.Failed
    context.testExecutionResult.results.size() == 1
    context.testExecutionResult.results[0].message == 'Request to provider failed with an exception'
    context.testExecutionResult.results[0].exception.cause instanceof IOException
    context.testExecutionResult.results[0].interactionId == '12345'
  }

}

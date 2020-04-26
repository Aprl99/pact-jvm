package au.com.dius.pact.consumer.groovy

import au.com.dius.pact.consumer.Headers
import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.PactTestExecutionContext
import au.com.dius.pact.consumer.PactVerificationResult
import au.com.dius.pact.consumer.StatefulMockProvider
import au.com.dius.pact.consumer.VerificationResult
import au.com.dius.pact.model.Consumer
import au.com.dius.pact.model.MockProviderConfig
import au.com.dius.pact.model.OptionalBody
import au.com.dius.pact.model.PactFragment
import au.com.dius.pact.model.PactReader
import au.com.dius.pact.model.PactSpecVersion
import au.com.dius.pact.model.Provider
import au.com.dius.pact.model.ProviderState
import au.com.dius.pact.model.Request
import au.com.dius.pact.model.RequestResponseInteraction
import au.com.dius.pact.model.RequestResponsePact
import au.com.dius.pact.model.Response
import au.com.dius.pact.model.generators.Generators
import au.com.dius.pact.model.generators.ProviderStateGenerator
import au.com.dius.pact.model.matchingrules.MatchingRules
import au.com.dius.pact.model.matchingrules.MatchingRulesImpl
import au.com.dius.pact.model.matchingrules.RegexMatcher
import groovy.json.JsonBuilder
import groovy.transform.CompileStatic
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.HttpMultipartMode
import org.apache.http.entity.mime.MultipartEntityBuilder
import scala.collection.JavaConverters$

import java.util.regex.Pattern

import static au.com.dius.pact.consumer.ConsumerPactRunnerKt.runConsumerTest

/**
 * Builder DSL for Pact tests
 */
@SuppressWarnings('PropertyName')
class PactBuilder extends BaseBuilder {

  private static final String CONTENT_TYPE = 'Content-Type'
  private static final String JSON = 'application/json'
  private static final String BODY = 'body'
  private static final String LOCALHOST = 'localhost'
  public static final String HEADER = 'header'

  Consumer consumer
  Provider provider
  Integer port = 0
  String requestDescription
  List requestData = []
  List responseData = []
  List interactions = []
  StatefulMockProvider server
  List<ProviderState> providerStates = []
  boolean requestState

  /**
   * Defines the service consumer
   * @param consumer consumer name
   */
  PactBuilder serviceConsumer(String consumer) {
    this.consumer = new Consumer(consumer)
    this
  }

  /**
   * Defines the provider the consumer has a pact with
   * @param provider provider name
   */
  PactBuilder hasPactWith(String provider) {
    this.provider = new Provider(provider)
    this
  }

  /**
   * Defines the port the provider will listen on
   * @param port port number
   */
  @SuppressWarnings('ConfusingMethodName')
  PactBuilder port(int port) {
    this.port = port
    this
  }

  /**
   * Defines the provider state the provider needs to be in for the interaction
   * @param providerState provider state description
   */
  PactBuilder given(String providerState) {
    this.providerStates << new ProviderState(providerState)
    this
  }

  /**
   * Defines the provider state the provider needs to be in for the interaction
   * @param providerState provider state description
   * @param params Data parameters for the provider state
   */
  PactBuilder given(String providerState, Map params) {
    this.providerStates << new ProviderState(providerState, params)
    this
  }

  /**
   * Defines the start of an interaction
   * @param requestDescription Description of the interaction. Must be unique.
   */
  PactBuilder uponReceiving(String requestDescription) {
    buildInteractions()
    this.requestDescription = requestDescription
    requestState = true
    this
  }

  def buildInteractions() {
    int numInteractions = Math.min(requestData.size(), responseData.size())
    for (int i = 0; i < numInteractions; i++) {
      MatchingRules requestMatchers = requestData[i].matchers
      MatchingRules responseMatchers = responseData[i].matchers
      Generators requestGenerators = requestData[i].generators
      Generators responseGenerators = responseData[i].generators
      Map headers = setupHeaders(requestData[i].headers ?: [:], requestMatchers, requestGenerators)
      Map query = setupQueryParameters(requestData[i].query ?: [:], requestMatchers, requestGenerators)
      Map responseHeaders = setupHeaders(responseData[i].headers ?: [:], responseMatchers, responseGenerators)
      String path = setupPath(requestData[i].path ?: '/', requestMatchers, requestGenerators)
      interactions << new RequestResponseInteraction(
        requestDescription,
        providerStates,
        new Request(requestData[i].method ?: 'get', path, query, headers,
          requestData[i].containsKey(BODY) ? OptionalBody.body(requestData[i].body.bytes) : OptionalBody.missing(),
          requestMatchers, requestGenerators),
        new Response(responseData[i].status ?: 200, responseHeaders,
          responseData[i].containsKey(BODY) ? OptionalBody.body(responseData[i].body.bytes) : OptionalBody.missing(),
          responseMatchers, responseGenerators)
      )
    }
    requestData = []
    responseData = []
  }

  private static Map setupHeaders(Map headers, MatchingRules matchers, Generators generators) {
    headers.collectEntries { key, value ->
      def header = HEADER
      if (value instanceof Matcher) {
        matchers.addCategory(header).addRule(key, value.matcher)
        [key, [value.value]]
      } else if (value instanceof Pattern) {
        def matcher = new RegexpMatcher(regex: value)
        matchers.addCategory(header).addRule(key, matcher.matcher)
        [key, [matcher.value]]
      } else if (value instanceof GeneratedValue) {
        generators.addGenerator(au.com.dius.pact.model.generators.Category.HEADER, key,
          new ProviderStateGenerator(value.expression))
        [key, [value.exampleValue]]
      } else {
        [key, value instanceof List ? value : [value]]
      }
    }
  }

  private static String setupPath(def path, MatchingRules matchers, Generators generators) {
    def category = 'path'
    if (path instanceof Matcher) {
      matchers.addCategory(category).addRule(path.matcher)
      path.value
    } else if (path instanceof Pattern) {
      def matcher = new RegexpMatcher(regex: path)
      matchers.addCategory(category).addRule(matcher.matcher)
      matcher.value
    } else if (path instanceof GeneratedValue) {
      generators.addGenerator(au.com.dius.pact.model.generators.Category.PATH,
        new ProviderStateGenerator(path.expression))
      path.exampleValue
    } else {
      path as String
    }
  }

  private static Map setupQueryParameters(Map query, MatchingRules matchers, Generators generators) {
    query.collectEntries { key, value ->
      def category = 'query'
      if (value[0] instanceof Matcher) {
        matchers.addCategory(category).addRule(key, value[0].matcher)
        [key, [value[0].value]]
      } else if (value[0] instanceof Pattern) {
        def matcher = new RegexpMatcher(regex: value[0].toString())
        matchers.addCategory(category).addRule(key, matcher.matcher)
        [key, [matcher.value]]
      } else if (value[0] instanceof GeneratedValue) {
        generators.addGenerator(au.com.dius.pact.model.generators.Category.QUERY, key,
          new ProviderStateGenerator(value[0].expression))
        [key, [value[0].exampleValue]]
      } else {
        [key, value]
      }
    }
  }

  /**
   * Defines the request attributes (body, headers, etc.)
   * @param requestData Map of attributes
   */
  PactBuilder withAttributes(Map requestData) {
    def request = [matchers: new MatchingRulesImpl(), generators: new Generators()] + requestData
    setupBody(requestData, request)
    if (requestData.query instanceof String) {
      request.query = PactReader.queryStringToMap(requestData.query)
    } else {
      request.query = requestData.query?.collectEntries { k, v ->
        if (v instanceof Collection) {
          [k, v]
        } else {
          [k, [v]]
        }
      }
    }
    this.requestData << request
    this
  }

  private setupBody(Map requestData, Map request) {
    if (requestData.containsKey(BODY)) {
      def body = requestData.body
      if (body instanceof PactBodyBuilder) {
        request.body = body.body
        request.matchers.addCategory(body.matchers)
        request.generators.addGenerators(body.generators)
      } else if (body != null && !(body instanceof String)) {
        if (requestData.prettyPrint == null && !compactMimeTypes(requestData) || requestData.prettyPrint) {
          request.body = new JsonBuilder(body).toPrettyString()
        } else {
          request.body = new JsonBuilder(body).toString()
        }
      }
    }
  }

  /**
   * Defines the response attributes (body, headers, etc.) that are returned for the request
   * @param responseData Map of attributes
   * @return
   */
  @SuppressWarnings('DuplicateMapLiteral')
  PactBuilder willRespondWith(Map responseData) {
    def response = [matchers: new MatchingRulesImpl(), generators: new Generators()] + responseData
    setupBody(responseData, response)
    this.responseData << response
    requestState = false
    this
  }

  private static boolean compactMimeTypes(Map reqResData) {
    reqResData.headers && reqResData.headers[CONTENT_TYPE] in COMPACT_MIME_TYPES
  }

  /**
   * Executes the providers closure in the context of the interactions defined on this builder.
   * @param options Optional map of options for the run
   * @param closure Test to execute
   * @return The result of the test run
   * @deprecated use runTest instead
   */
  @Deprecated
  VerificationResult run(Map options = [:], Closure closure) {
    PactFragment fragment = fragment()

    MockProviderConfig config
    def pactVersion = options.specificationVersion ?: PactSpecVersion.V3
    if (port == null) {
      config = MockProviderConfig.createDefault(pactVersion)
    } else {
      config = MockProviderConfig.httpConfig(LOCALHOST, port, pactVersion)
    }

    fragment.runConsumer(config, closure)
  }

  @Deprecated
  PactFragment fragment() {
    buildInteractions()
    new PactFragment(consumer, provider, JavaConverters$.MODULE$.asScalaBufferConverter(interactions).asScala())
  }

  /**
   * Allows the body to be defined using a Groovy builder pattern
   * @param mimeType Optional mimetype for the body
   * @param closure Body closure
   * @deprecated Use the withBody method that takes a Map for options
   */
  @Deprecated
  PactBuilder withBody(String mimeType, Closure closure) {
    withBody(mimeType: mimeType, closure)
  }

  /**
   * Allows the body to be defined using a Groovy builder pattern with an array as the root
   * @param mimeType Optional mimetype for the body
   * @param array body
   * @deprecated Use the withBody method that takes a Map for options
   */
  @Deprecated
  PactBuilder withBody(String mimeType, List array) {
    withBody(mimeType: mimeType, array)
  }

  /**
   * Allows the body to be defined using a Groovy builder pattern with an array as the root
   * using a each like matcher for all elements of the array
   * @param mimeType Optional mimetype for the body
   * @param matcher body
   * @deprecated Use the withBody method that takes a Map for options
   */
  @Deprecated
  PactBuilder withBody(String mimeType, LikeMatcher matcher) {
    withBody(mimeType: mimeType, matcher)
  }

  /**
   * Allows the body to be defined using a Groovy builder pattern
   * @param options The following options are available:
   *   - mimeType Optional mimetype for the body
   *   - prettyPrint If the body should be pretty printed
   * @param closure Body closure
   */
  PactBuilder withBody(Map options = [:], Closure closure) {
    def body = new PactBodyBuilder(mimetype: options.mimeType, prettyPrintBody: options.prettyPrint)
    closure.delegate = body
    closure.call()
    setupBody(body, options)
    this
  }

  /**
   * Allows the body to be defined using a Groovy builder pattern with an array as the root
   * @param options The following options are available:
   *   - mimeType Optional mimetype for the body
   *   - prettyPrint If the body should be pretty printed
   * @param array body
   */
  PactBuilder withBody(Map options = [:], List array) {
    def body = new PactBodyBuilder(mimetype: options.mimeType, prettyPrintBody: options.prettyPrint)
    body.bodyRepresentation = body.build(array)
    setupBody(body, options)
    this
  }

  /**
   * Allows the body to be defined using a Groovy builder pattern with an array as the root
   * @param options The following options are available:
   *   - mimeType Optional mimetype for the body
   *   - prettyPrint If the body should be pretty printed
   * @param matcher body
   */
  PactBuilder withBody(Map options = [:], LikeMatcher matcher) {
    def body = new PactBodyBuilder(mimetype: options.mimetype, prettyPrintBody: options.prettyPrint)
    body.bodyRepresentation = body.build(matcher)
    setupBody(body, options)
    this
  }

  private setupBody(PactBodyBuilder body, Map options) {
    if (requestState) {
      requestData.last().body = body.body
      requestData.last().matchers.addCategory(body.matchers)
      requestData.last().generators.addGenerators(body.generators)
      requestData.last().headers = requestData.last().headers ?: [:]
      if (!requestData.last().headers[CONTENT_TYPE]) {
        if (options.mimeType) {
          requestData.last().headers[CONTENT_TYPE] = options.mimeType
        } else {
          requestData.last().headers[CONTENT_TYPE] = JSON
        }
      }
    } else {
      responseData.last().body = body.body
      responseData.last().matchers.addCategory(body.matchers)
      responseData.last().generators.addGenerators(body.generators)
      responseData.last().headers = responseData.last().headers ?: [:]
      if (!responseData.last().headers[CONTENT_TYPE]) {
        if (options.mimeType) {
          responseData.last().headers[CONTENT_TYPE] = options.mimeType
        } else {
          responseData.last().headers[CONTENT_TYPE] = JSON
        }
      }
    }
  }

  /**
   * Executes the providers closure in the context of the interactions defined on this builder.
   * @param options Optional map of options for the run
   * @param closure Test to execute
   * @return The result of the test run
   */
  @CompileStatic
  PactVerificationResult runTest(Map options = [:], Closure closure) {
    buildInteractions()
    def pact = new RequestResponsePact(provider, consumer, interactions)

    def pactVersion = options.specificationVersion ?: PactSpecVersion.V3
    MockProviderConfig config = MockProviderConfig.httpConfig(LOCALHOST, port ?: 0, pactVersion as PactSpecVersion)

    def runTest = closure
    if (closure.maximumNumberOfParameters < 2) {
      if (closure.maximumNumberOfParameters == 1) {
        runTest =  { MockServer server, PactTestExecutionContext context -> closure.call(server) }
      } else {
        runTest =  { MockServer server, PactTestExecutionContext context -> closure.call() }
      }
    }

    runConsumerTest(pact, config, runTest)
  }

  /**
   * Runs the test (via the runTest method), and throws an exception if it was not successful.
   * @param options Optional map of options for the run
   * @param closure
   */
  void runTestAndVerify(Map options = [:], Closure closure) {
    PactVerificationResult result = runTest(options, closure)
    if (result != PactVerificationResult.Ok.INSTANCE) {
      if (result instanceof PactVerificationResult.Error) {
        if (result.mockServerState != PactVerificationResult.Ok.INSTANCE) {
          throw new AssertionError('Pact Test function failed with an exception, possibly due to ' +
            result.mockServerState, result.error)
        } else {
          throw new AssertionError('Pact Test function failed with an exception: ' + result.error.message, result.error)
        }
      }
      throw new PactFailedException(result)
    }
  }

  /**
   * Sets up a file upload request. This will add the correct content type header to the request
   * @param partName This is the name of the part in the multipart body.
   * @param fileName This is the name of the file that was uploaded
   * @param fileContentType This is the content type of the uploaded file
   * @param data This is the actual file contents
   */
  void withFileUpload(String partName, String fileName, String fileContentType, byte[] data) {
    def multipart = MultipartEntityBuilder.create()
      .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
      .addBinaryBody(partName, data, ContentType.create(fileContentType), fileName)
      .build()
    def os = new ByteArrayOutputStream()
    multipart.writeTo(os)
    if (requestState) {
      requestData.last().body = os.toString()
      requestData.last().headers = requestData.last().headers ?: [:]
      requestData.last().headers[CONTENT_TYPE] = multipart.contentType.value
      au.com.dius.pact.model.matchingrules.Category category  = requestData.last().matchers.addCategory(HEADER)
      category.addRule(CONTENT_TYPE, new RegexMatcher(Headers.MULTIPART_HEADER_REGEX, multipart.contentType.value))
    } else {
      responseData.last().body = os.toString()
      responseData.last().headers = responseData.last().headers ?: [:]
      responseData.last().headers[CONTENT_TYPE] = multipart.contentType.value
      au.com.dius.pact.model.matchingrules.Category category  = responseData.last().matchers.addCategory(HEADER)
      category.addRule(CONTENT_TYPE, new RegexMatcher(Headers.MULTIPART_HEADER_REGEX, multipart.contentType.value))
    }
  }

  /**
   * Marks a item as to be injected from the provider state
   * @param expression Expression to lookup in the provider state context
   * @param exampleValue Example value to use in the consumer test
   * @return example value
   */
  def fromProviderState(String expression, def exampleValue) {
    new GeneratedValue(expression, exampleValue)
  }

  @Override
  @SuppressWarnings('UnnecessaryOverridingMethod')
  def call(@DelegatesTo(value = PactBuilder, strategy = Closure.DELEGATE_FIRST) Closure closure) {
    super.build(closure)
  }

  @Override
  @SuppressWarnings('UnnecessaryOverridingMethod')
  def build(@DelegatesTo(value = PactBuilder, strategy = Closure.DELEGATE_FIRST) Closure closure) {
    super.build(closure)
  }
}

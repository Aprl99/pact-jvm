package au.com.dius.pact.model.v3.messaging

import au.com.dius.pact.model.HttpPart
import au.com.dius.pact.model.Interaction
import au.com.dius.pact.model.OptionalBody
import au.com.dius.pact.model.PactSpecVersion
import au.com.dius.pact.model.ProviderState
import au.com.dius.pact.model.Response
import au.com.dius.pact.model.generators.Generators
import au.com.dius.pact.model.matchingrules.MatchingRules
import au.com.dius.pact.model.matchingrules.MatchingRulesImpl
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Canonical
import groovy.util.logging.Slf4j
import org.apache.commons.lang3.StringUtils
import org.apache.http.entity.ContentType

/**
 * Message in a Message Pact
 */
@Canonical
@Slf4j
class Message implements Interaction {
  private static final String JSON = 'application/json'

  String description
  List<ProviderState> providerStates = []
  OptionalBody contents = OptionalBody.missing()
  MatchingRules matchingRules = new MatchingRulesImpl()
  Generators generators = new Generators()
  Map<String, Object> metaData = [:]
  String interactionId

  byte[] contentsAsBytes() {
    contents.orEmpty()
  }

  String getContentType() {
    metaData?.find { it.key.toLowerCase() == 'contenttype' || it.key.toLowerCase() == 'content-type' }?.value ?: JSON
  }

  ContentType getParsedContentType() {
    parseContentType(this.contentType)
  }

  @SuppressWarnings('UnusedMethodParameter')
  Map toMap(PactSpecVersion pactSpecVersion = PactSpecVersion.V3) {
    def map = [
      description: description,
      metaData: metaData
    ]
    if (!contents.missing) {
      map.contents = formatContents()
    }
    if (providerStates) {
      map.providerStates = providerStates*.toMap()
    }
    if (matchingRules?.notEmpty) {
      map.matchingRules = matchingRules.toMap(pactSpecVersion)
    }
    if (generators?.notEmpty) {
      map.generators = generators.toMap(pactSpecVersion)
    }
    map
  }

  def formatContents() {
    if (contents.present) {
      switch (parseContentType(contentType)?.mimeType) {
        case { isJson(it) } : return new JsonSlurper().parseText(contents.valueAsString())
        case 'application/octet-stream': return contentsAsBytes().encodeBase64().toString()
        default: return contents.valueAsString()
      }
    } else {
      ''
    }
  }

  /**
   * Builds a message from a Map
   */
  static Message fromMap(Map map) {
    Message message = new Message()
    message.description = map.description ?: ''
    if (map.providerStates) {
      message.providerStates = map.providerStates.collect { ProviderState.fromMap(it) }
    } else {
      message.providerStates = map.providerState ? [ new ProviderState(map.providerState.toString()) ] : []
    }
    if (map.containsKey('contents')) {
      if (map.contents == null) {
        message.contents = OptionalBody.nullBody()
      } else if (map.contents instanceof String && map.contents.empty) {
        message.contents = OptionalBody.empty()
      } else {
        message.contents = OptionalBody.body(JsonOutput.toJson(map.contents).bytes)
      }
    }
    message.matchingRules = MatchingRulesImpl.fromMap(map.matchingRules)
    message.generators = Generators.fromMap(map.generators)
    message.metaData = map.metaData ?: [:]
    message.interactionId = map['_id']
    message
  }

  HttpPart asPactRequest() {
    new Response(200, ['Content-Type': [contentType]], contents, matchingRules)
  }

  @Override
  @Deprecated
  String getProviderState() {
    providerStates.isEmpty() ? null : providerStates.first().name
  }

  /**
   * @deprecated This will be removed in 4.0
   */
  @Override
  @Deprecated
  boolean conflictsWith(Interaction other) {
//    TODO: Need to match the bodies
//    if (other instanceof Message) {
//      description == other.description &&
//        providerState == other.providerState &&
//        formatContents() != other.formatContents()
//    } else {
//      false
//    }
    !(other instanceof Message)
  }

  @Override
  String uniqueKey() {
    "${StringUtils.defaultIfEmpty(providerStates*.name.join(''), 'None')}_$description"
  }

  private static ContentType parseContentType(String contentType) {
    try {
      return ContentType.parse(contentType)
    } catch (e) {
      log.debug("Failed to parse content type '${contentType}'", e)
    }
  }

  private static boolean isJson(String contentType) {
    contentType ==~ /application\\/.*json/
  }
}

package au.com.dius.pact.core.pactbroker

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import au.com.dius.pact.core.pactbroker.util.HttpClientUtils.buildUrl
import au.com.dius.pact.core.pactbroker.util.HttpClientUtils.isJsonResponse
import au.com.dius.pact.core.support.HttpClient
import au.com.dius.pact.core.support.isNotEmpty
import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.bool
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.jsonNull
import com.github.salomonbrys.kotson.jsonObject
import com.github.salomonbrys.kotson.keys
import com.github.salomonbrys.kotson.nullObj
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.set
import com.github.salomonbrys.kotson.string
import com.google.common.net.UrlEscapers
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import mu.KLogging
import org.apache.http.HttpHost
import org.apache.http.HttpMessage
import org.apache.http.HttpResponse
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.auth.BasicScheme
import org.apache.http.impl.client.BasicAuthCache
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.util.EntityUtils
import java.net.URI
import java.net.URLDecoder
import java.util.function.BiFunction
import java.util.function.Consumer
import arrow.core.Either
import au.com.dius.pact.core.support.handleWith

/**
 * Interface to a HAL Client
 */
interface IHalClient {
  /**
   * Navigates to the Root
   */
  fun navigate(): IHalClient

  /**
   * Navigates the URL associated with the given link using the current HAL document
   * @param options Map of key-value pairs to use for parsing templated links
   * @param link Link name to navigate
   */
  fun navigate(options: Map<String, Any> = mapOf(), link: String): IHalClient

  /**
   * Navigates the URL associated with the given link using the current HAL document
   * @param link Link name to navigate
   */
  fun navigate(link: String): IHalClient

  /**
   * Returns the HREF of the named link from the current HAL document
   */
  fun linkUrl(name: String): String?

  /**
   * Calls the closure with a Map of attributes for all links associated with the link name
   * @param linkName Name of the link to loop over
   * @param closure Closure to invoke with the link attributes
   */
  fun forAll(linkName: String, closure: Consumer<Map<String, Any?>>)

  /**
   * Upload the JSON document to the provided path, using a PUT request
   * @param path Path to upload the document
   * @param bodyJson JSON contents for the body
   */
  @Deprecated("Use putJson instead")
  fun uploadJson(path: String, bodyJson: String): Any?

  /**
   * Upload the JSON document to the provided path, using a PUT request
   * @param path Path to upload the document
   * @param bodyJson JSON contents for the body
   * @param closure Closure that will be invoked with details about the response. The result from the closure will be
   * returned.
   */
  @Deprecated("Use putJson instead")
  fun uploadJson(path: String, bodyJson: String, closure: BiFunction<String, String, Any?>): Any?

  /**
   * Upload the JSON document to the provided path, using a PUT request
   * @param path Path to upload the document
   * @param bodyJson JSON contents for the body
   * @param closure Closure that will be invoked with details about the response. The result from the closure will be
   * returned.
   * @param encodePath If the path must be encoded beforehand.
   */
  @Deprecated("Use putJson instead")
  fun uploadJson(path: String, bodyJson: String, closure: BiFunction<String, String, Any?>, encodePath: Boolean): Any?

  /**
   * Upload the JSON document to the provided URL, using a POST request
   * @param url Url to upload the document to
   * @param body JSON contents for the body
   * @return Returns a Success result object with a boolean value to indicate if the request was successful or not. Any
   * exception will be wrapped in a Failure
   */
  fun postJson(url: String, body: String): Result<Boolean, Exception>

  /**
   * Upload the JSON document to the provided URL, using a POST request
   * @param url Url to upload the document to
   * @param body JSON contents for the body
   * @param handler Response handler
   * @return Returns a Success result object with the boolean value returned from the handler closure. Any
   * exception will be wrapped in a Failure
   */
  fun postJson(url: String, body: String, handler: ((status: Int, response: CloseableHttpResponse) -> Boolean)?): Result<Boolean, Exception>

  /**
   * Fetches the HAL document from the provided path
   * @param path The path to the HAL document. If it is a relative path, it is relative to the base URL
   * @param encodePath If the path should be encoded to make a valid URL
   */
  fun fetch(path: String, encodePath: Boolean): JsonElement

  /**
   * Fetches the HAL document from the provided path
   * @param path The path to the HAL document. If it is a relative path, it is relative to the base URL
   */
  fun fetch(path: String): JsonElement

  /**
   * Sets the starting context from a previous broker interaction (Pact document)
   */
  fun withDocContext(docAttributes: Map<String, Any?>): IHalClient

  /**
   * Sets the starting context from a previous broker interaction (Pact document)
   */
  fun withDocContext(docAttributes: JsonElement): IHalClient

  /**
   * Upload a JSON document to the current path link, using a PUT request
   */
  fun putJson(link: String, options: Map<String, Any>, json: String): Result<Boolean, Exception>

  /**
   * Upload a JSON document to the current path link, using a POST request
   */
  fun postJson(link: String, options: Map<String, Any>, json: String): Either<Exception, JsonElement>

  /**
   * Get JSON from the provided path
   */
  fun getJson(path: String): Result<JsonElement, Exception>

  /**
   * Get JSON from the provided path
   * @param path Path to fetch the JSON document from
   * @param encodePath If the path should be encoded
   */
  fun getJson(path: String, encodePath: Boolean): Result<JsonElement, Exception>
}

/**
 * HAL client for navigating the HAL links
 */
open class HalClient @JvmOverloads constructor(
  val baseUrl: String,
  var options: Map<String, Any> = mapOf()
) : IHalClient {

  var httpClient: CloseableHttpClient? = null
  var httpContext: HttpClientContext? = null
  var pathInfo: JsonElement? = null
  var lastUrl: String? = null
  var defaultHeaders: MutableMap<String, String> = mutableMapOf()
  private var maxPublishRetries = 5
  private var publishRetryInterval = 3000

  init {
    if (options.containsKey("halClient")) {
      val halClient = options["halClient"] as Map<String, Any>
      maxPublishRetries = halClient.getOrDefault("maxPublishRetries", this.maxPublishRetries) as Int
      publishRetryInterval = halClient.getOrDefault("publishRetryInterval", this.publishRetryInterval) as Int
    }
  }

  fun <Method : HttpMessage> initialiseRequest(method: Method): Method {
    defaultHeaders.forEach { (key, value) -> method.addHeader(key, value) }
    return method
  }

  override fun postJson(url: String, body: String) = postJson(url, body, null)

  override fun postJson(
    url: String,
    body: String,
    handler: ((status: Int, response: CloseableHttpResponse) -> Boolean)?
  ): Result<Boolean, Exception> {
    logger.debug { "Posting JSON to $url\n$body" }
    val client = setupHttpClient()

    return Result.of {
      val httpPost = initialiseRequest(HttpPost(url))
      httpPost.addHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
      httpPost.entity = StringEntity(body, ContentType.APPLICATION_JSON)

      client.execute(httpPost, httpContext).use {
        logger.debug { "Got response ${it.statusLine}" }
        logger.debug { "Response body: ${it.entity?.content?.reader()?.readText()}" }
        if (handler != null) {
          handler(it.statusLine.statusCode, it)
        } else {
          it.statusLine.statusCode < 300
        }
      }
    }
  }

  open fun setupHttpClient(): CloseableHttpClient {
    if (httpClient == null) {
      if (options.containsKey("authentication") && options["authentication"] !is List<*>) {
        HttpClient.logger.warn { "Authentication options needs to be a list of values, ignoring." }
      }
      val uri = URI(baseUrl)
      val result = HttpClient.newHttpClient(options["authentication"], uri, defaultHeaders,
        this.maxPublishRetries, this.publishRetryInterval)
      httpClient = result.first

      if (System.getProperty(PREEMPTIVE_AUTHENTICATION) == "true") {
        val targetHost = HttpHost(uri.host, uri.port, uri.scheme)
        logger.warn { "Using preemptive basic authentication with the pact broker at $targetHost" }
        val authCache = BasicAuthCache()
        val basicAuth = BasicScheme()
        authCache.put(targetHost, basicAuth)
        httpContext = HttpClientContext.create()
        httpContext!!.credentialsProvider = result.second
        httpContext!!.authCache = authCache
      }
    }

    return httpClient!!
  }

  override fun navigate(): IHalClient {
    pathInfo = fetch(ROOT)
    return this
  }

  override fun navigate(options: Map<String, Any>, link: String): IHalClient {
    pathInfo = pathInfo ?: fetch(ROOT)
    pathInfo = fetchLink(link, options)
    return this
  }

  override fun navigate(link: String) = navigate(mapOf(), link)

  override fun fetch(path: String) = fetch(path, true)

  override fun fetch(path: String, encodePath: Boolean): JsonElement {
    lastUrl = path
    logger.debug { "Fetching: $path" }
    when (val response = getJson(path, encodePath)) {
      is Ok -> return response.value
      is Err -> throw response.error
    }
  }

  override fun withDocContext(docAttributes: Map<String, Any?>): IHalClient {
    val links = JsonObject()
    links[LINKS] = jsonObject(docAttributes.entries.map {
      it.key to when (it.value) {
        is Map<*, *> -> jsonObject((it.value as Map<*, *>).entries.map { entry ->
          if (entry.key == "href") {
            entry.key.toString() to URLDecoder.decode(entry.value.toString(), "UTF-8")
          } else {
            entry.key.toString() to entry.value
          }
        })
        else -> jsonNull
      }
    })
    pathInfo = links
    return this
  }

  override fun withDocContext(json: JsonElement): IHalClient {
    pathInfo = json
    return this
  }

  override fun getJson(path: String) = getJson(path, true)

  override fun getJson(path: String, encodePath: Boolean): Result<JsonElement, Exception> {
    setupHttpClient()
    return Result.of {
      val httpGet = initialiseRequest(HttpGet(buildUrl(baseUrl, path, encodePath)))
      httpGet.addHeader("Content-Type", "application/json")
      httpGet.addHeader("Accept", "application/hal+json, application/json")

      val response = httpClient!!.execute(httpGet, httpContext)
      return@of handleHalResponse(response, path)
    }
  }

  private fun handleHalResponse(response: CloseableHttpResponse, path: String): JsonElement {
    if (response.statusLine.statusCode < 300) {
      val contentType = ContentType.getOrDefault(response.entity)
      if (isJsonResponse(contentType)) {
        return JsonParser.parseString(EntityUtils.toString(response.entity))
      } else {
        throw InvalidHalResponse("Expected a HAL+JSON response from the pact broker, but got '$contentType'")
      }
    } else {
      when (response.statusLine.statusCode) {
        404 -> throw NotFoundHalResponse("No HAL document found at path '$path'")
        else -> throw RequestFailedException("Request to path '$path' failed with response '${response.statusLine}'")
      }
    }
  }

  private fun fetchLink(link: String, options: Map<String, Any>): JsonElement {
    val href = hrefForLink(link, options)
    return this.fetch(href.first, href.second)
  }

  private fun hrefForLink(link: String, options: Map<String, Any>): Pair<String, Boolean> {
    if (pathInfo?.nullObj?.get(LINKS) == null) {
      throw InvalidHalResponse("Expected a HAL+JSON response from the pact broker, but got " +
        "a response with no '_links'. URL: '$baseUrl', LINK: '$link'")
    }

    val links = pathInfo!![LINKS]
    if (links.isJsonObject) {
      if (!links.obj.has(link)) {
        throw InvalidHalResponse("Link '$link' was not found in the response, only the following links where " +
          "found: ${links.obj.keys()}. URL: '$baseUrl', LINK: '$link'")
      }
      val linkData = links[link]

      if (linkData.isJsonArray) {
        if (options.containsKey("name")) {
          val linkByName = linkData.asJsonArray.find { it.isJsonObject && it["name"] == options["name"] }
          return if (linkByName != null && linkByName.isJsonObject && linkByName["templated"].isJsonPrimitive &&
            linkByName["templated"].bool) {
            parseLinkUrl(linkByName["href"].toString(), options) to false
          } else if (linkByName != null && linkByName.isJsonObject) {
            linkByName["href"].string to true
          } else {
            throw InvalidNavigationRequest("Link '$link' does not have an entry with name '${options["name"]}'. " +
              "URL: '$baseUrl', LINK: '$link'")
          }
        } else {
          throw InvalidNavigationRequest("Link '$link' has multiple entries. You need to filter by the link name. " +
            "URL: '$baseUrl', LINK: '$link'")
        }
      } else if (linkData.isJsonObject) {
        return if (linkData.obj.has("templated") && linkData["templated"].isJsonPrimitive &&
          linkData["templated"].bool) {
          parseLinkUrl(linkData["href"].string, options) to false
        } else {
          linkData["href"].string to true
        }
      } else {
        throw InvalidHalResponse("Expected link in map form in the response, but " +
          "found: $linkData. URL: '$baseUrl', LINK: '$link'")
      }
    } else {
      throw InvalidHalResponse("Expected a map of links in the response, but " +
        "found: $links. URL: '$baseUrl', LINK: '$link'")
    }
  }

  fun parseLinkUrl(href: String, options: Map<String, Any>): String {
    var result = ""
    var match = URL_TEMPLATE_REGEX.find(href)
    var index = 0
    while (match != null) {
      val start = match.range.first - 1
      if (start >= index) {
        result += href.substring(index..start)
      }
      index = match.range.last + 1
      val (key) = match.destructured
      result += encodePathParameter(options, key, match.value)

      match = URL_TEMPLATE_REGEX.find(href, index)
    }

    if (index < href.length) {
      result += href.substring(index)
    }
    return result
  }

  private fun encodePathParameter(options: Map<String, Any>, key: String, value: String): String? {
    return UrlEscapers.urlPathSegmentEscaper().escape(options[key]?.toString() ?: value)
  }

  fun initPathInfo() {
    pathInfo = pathInfo ?: fetch(ROOT)
  }

  override fun uploadJson(path: String, bodyJson: String) = uploadJson(path, bodyJson,
    BiFunction { _: String, _: String -> null }, true)

  override fun uploadJson(path: String, bodyJson: String, closure: BiFunction<String, String, Any?>) =
    uploadJson(path, bodyJson, closure, true)

  override fun uploadJson(
    path: String,
    bodyJson: String,
    closure: BiFunction<String, String, Any?>,
    encodePath: Boolean
  ): Any? {
    val client = setupHttpClient()
    val httpPut = initialiseRequest(HttpPut(buildUrl(baseUrl, path, encodePath)))
    httpPut.addHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
    httpPut.entity = StringEntity(bodyJson, ContentType.APPLICATION_JSON)

    client.execute(httpPut, httpContext).use {
      return when {
        it.statusLine.statusCode < 300 -> {
          EntityUtils.consume(it.entity)
          closure.apply("OK", it.statusLine.toString())
        }
        it.statusLine.statusCode == 409 -> {
          val body = it.entity.content.bufferedReader().readText()
          closure.apply("FAILED",
            "${it.statusLine.statusCode} ${it.statusLine.reasonPhrase} - $body")
        }
        else -> {
          val body = it.entity.content.bufferedReader().readText()
          handleFailure(it, body, closure)
        }
      }
    }
  }

  fun handleFailure(resp: HttpResponse, body: String?, closure: BiFunction<String, String, Any?>): Any? {
    if (resp.entity.contentType != null) {
      val contentType = ContentType.getOrDefault(resp.entity)
      if (isJsonResponse(contentType)) {
        var error = ""
        if (body.isNotEmpty()) {
          val jsonBody = JsonParser.parseString(body)
          if (jsonBody != null && jsonBody.obj.has("errors")) {
            if (jsonBody["errors"].isJsonArray) {
              error = " - " + jsonBody["errors"].asJsonArray.joinToString(", ") { it.asString }
            } else if (jsonBody["errors"].isJsonObject) {
              error = " - " + jsonBody["errors"].asJsonObject.entrySet().joinToString(", ") { entry ->
                if (entry.value.isJsonArray) {
                  "${entry.key}: ${entry.value.array.joinToString(", ") { it.asString }}"
                } else {
                  "${entry.key}: ${entry.value.asString}"
                }
              }
            }
          }
        }
        return closure.apply("FAILED", "${resp.statusLine.statusCode} ${resp.statusLine.reasonPhrase}$error")
      } else {
        return closure.apply("FAILED", "${resp.statusLine.statusCode} ${resp.statusLine.reasonPhrase} - $body")
      }
    } else {
      return closure.apply("FAILED", "${resp.statusLine.statusCode} ${resp.statusLine.reasonPhrase} - $body")
    }
  }

  override fun linkUrl(name: String): String? {
    if (pathInfo!!.obj.has(LINKS)) {
      val links = pathInfo!![LINKS]
      if (links.isJsonObject && links.obj.has(name)) {
        val linkData = links[name]
        if (linkData.isJsonObject && linkData.obj.has("href")) {
          return fromJson(linkData["href"]).toString()
        }
      }
    }

    return null
  }

  override fun forAll(linkName: String, closure: Consumer<Map<String, Any?>>) {
    initPathInfo()
    val links = pathInfo!![LINKS]
    if (links.isJsonObject && links.obj.has(linkName)) {
      val matchingLink = links[linkName]
      if (matchingLink.isJsonArray) {
        matchingLink.asJsonArray.forEach { closure.accept(asMap(it.asJsonObject)) }
      } else {
        closure.accept(asMap(matchingLink.asJsonObject))
      }
    }
  }

  override fun putJson(link: String, options: Map<String, Any>, json: String): Result<Boolean, Exception> {
    val href = hrefForLink(link, options)
    val httpPut = initialiseRequest(HttpPut(buildUrl(baseUrl, href.first, href.second)))
    httpPut.addHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
    httpPut.entity = StringEntity(json, ContentType.APPLICATION_JSON)

    return Result.of {
      httpClient!!.execute(httpPut, httpContext).use {
        when {
          it.statusLine.statusCode < 300 -> true
          else -> {
            logger.error { "PUT JSON request failed with status ${it.statusLine}" }
            false
          }
        }
      }
    }
  }

  override fun postJson(link: String, options: Map<String, Any>, json: String): Either<Exception, JsonElement> {
    val href = hrefForLink(link, options)
    val http = initialiseRequest(HttpPost(buildUrl(baseUrl, href.first, href.second)))
    http.addHeader("Content-Type", ContentType.APPLICATION_JSON.toString())
    http.addHeader("Accept", "application/hal+json, application/json")
    http.entity = StringEntity(json, ContentType.APPLICATION_JSON)

    return handleWith {
      httpClient!!.execute(http, httpContext).use {
        return@handleWith handleHalResponse(it, href.first)
      }
    }
  }

  companion object : KLogging() {
    const val ROOT = "/"
    const val LINKS = "_links"
    const val PREEMPTIVE_AUTHENTICATION = "pact.pactbroker.httpclient.usePreemptiveAuthentication"

    val URL_TEMPLATE_REGEX = Regex("\\{(\\w+)\\}")

    @JvmStatic
    fun asMap(jsonObject: JsonObject) = jsonObject.entrySet().associate { entry -> entry.key to fromJson(entry.value) }

    @JvmStatic
    fun fromJson(jsonValue: JsonElement): Any? {
      return when {
        jsonValue.isJsonObject -> asMap(jsonValue.asJsonObject)
        jsonValue.isJsonArray -> jsonValue.asJsonArray.map { fromJson(it) }
        jsonValue.isJsonNull -> null
        else -> {
          val primitive = jsonValue.asJsonPrimitive
          when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asBigDecimal
            else -> primitive.asString
          }
        }
      }
    }
  }
}

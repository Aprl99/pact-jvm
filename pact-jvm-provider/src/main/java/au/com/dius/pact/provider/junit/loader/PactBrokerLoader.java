package au.com.dius.pact.provider.junit.loader;

import au.com.dius.pact.model.Consumer;
import au.com.dius.pact.model.Pact;
import au.com.dius.pact.model.PactBrokerSource;
import au.com.dius.pact.model.PactReader;
import au.com.dius.pact.model.PactSource;
import au.com.dius.pact.provider.ConsumerInfo;
import au.com.dius.pact.provider.broker.PactBrokerClient;
import au.com.dius.pact.support.expressions.SystemPropertyResolver;
import au.com.dius.pact.support.expressions.ValueResolver;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static au.com.dius.pact.support.expressions.ExpressionParser.parseExpression;
import static au.com.dius.pact.support.expressions.ExpressionParser.parseListExpression;
import static java.util.stream.Collectors.toList;

/**
 * Out-of-the-box implementation of {@link PactLoader} that downloads pacts from Pact broker
 */
public class PactBrokerLoader implements PactLoader {
  private static final Logger LOGGER = LoggerFactory.getLogger(PactBrokerLoader.class);
  private static final String LATEST = "latest";

  private final String pactBrokerHost;
  private final String pactBrokerPort;
  private final String pactBrokerScheme;
  private final List<String> pactBrokerTags;
  private final List<String> pactBrokerConsumers;
  private boolean failIfNoPactsFound;
  private PactBrokerAuth authentication;
  private PactBrokerSource pactSource;
  private Class<? extends ValueResolver> valueResolverClass;
  private ValueResolver valueResolver;

  public PactBrokerLoader(final String pactBrokerHost, final String pactBrokerPort, final String pactBrokerProtocol) {
    this(pactBrokerHost, pactBrokerPort, pactBrokerProtocol, Collections.singletonList(LATEST), new ArrayList<>());
  }

  public PactBrokerLoader(final String pactBrokerHost, final String pactBrokerPort, final String pactBrokerProtocol,
      final List<String> tags, final List<String> consumers) {
    this.pactBrokerHost = pactBrokerHost;
    this.pactBrokerPort = pactBrokerPort;
    this.pactBrokerScheme = pactBrokerProtocol;
    this.pactBrokerTags = tags;
    this.pactBrokerConsumers = consumers;
    this.failIfNoPactsFound = true;
    this.pactSource = new PactBrokerSource(this.pactBrokerHost, this.pactBrokerPort, this.pactBrokerScheme);
  }

  public PactBrokerLoader(final PactBroker pactBroker) {
    this(pactBroker.host(), pactBroker.port(), defaultIfUnassigned(pactBroker.scheme(), pactBroker.protocol()),
      Arrays.asList(pactBroker.tags()), Arrays.asList(pactBroker.consumers()));
    this.failIfNoPactsFound = pactBroker.failIfNoPactsFound();
    this.authentication = pactBroker.authentication();
    this.valueResolverClass = pactBroker.valueResolver();
  }

  private static String defaultIfUnassigned(String scheme, String protocol) {
    return StringUtils.isBlank(scheme) || scheme.equals("${pactbroker.protocol:http}") ? protocol : scheme;
  }

  public List<Pact> load(final String providerName) throws IOException {
    List<Pact> pacts = new ArrayList<>();
    ValueResolver resolver = setupValueResolver();
    if (pactBrokerTags == null || pactBrokerTags.isEmpty()) {
      pacts.addAll(loadPactsForProvider(providerName, null, resolver));
    } else {
      for (String tag : pactBrokerTags.stream().flatMap(tag -> parseListExpression(tag, resolver).stream()).collect(toList())) {
        try {
          pacts.addAll(loadPactsForProvider(providerName, tag, resolver));
        } catch (NoPactsFoundException e) {
          // Ignoring exception at this point, it will be handled at a higher level
        }
      }
    }
    return pacts;
  }

  private ValueResolver setupValueResolver() {
    ValueResolver resolver = new SystemPropertyResolver();
    if (valueResolver != null) {
      resolver = valueResolver;
    } else if (valueResolverClass != null) {
      try {
        resolver = valueResolverClass.newInstance();
      } catch (InstantiationException | IllegalAccessException e) {
        LOGGER.warn("Failed to instantiate the value resolver, using the default", e);
      }
    }
    return resolver;
  }

  @Override
  public PactSource getPactSource() {
    return pactSource;
  }

  @Override
  public void setValueResolver(ValueResolver valueResolver) {
    this.valueResolver = valueResolver;
  }

  private List<Pact> loadPactsForProvider(final String providerName, final String tag, ValueResolver resolver) throws IOException {
    LOGGER.debug("Loading pacts from pact broker for provider " + providerName + " and tag " + tag);
    String scheme = parseExpression(pactBrokerScheme, resolver);
    String host = parseExpression(pactBrokerHost, resolver);
    String port = parseExpression(pactBrokerPort, resolver);

    if(StringUtils.isEmpty(host)){
      throw new IllegalArgumentException(String.format("Invalid pact broker host specified ('%s'). "
        + "Please provide a valid host or specify the system property 'pactbroker.host'.", pactBrokerHost));
    }

    if(StringUtils.isNotEmpty(port) && !port.matches("^[0-9]+")){
      throw new IllegalArgumentException(String.format("Invalid pact broker port specified ('%s'). "
        + "Please provide a valid port number or specify the system property 'pactbroker.port'.", pactBrokerPort));
    }

    URIBuilder uriBuilder = new URIBuilder().setScheme(scheme).setHost(host);
    if (StringUtils.isNotEmpty(port)) {
      uriBuilder.setPort(Integer.parseInt(port));
    }
    try {
      List<ConsumerInfo> consumers;
      PactBrokerClient pactBrokerClient = newPactBrokerClient(uriBuilder.build(), resolver);
      if (StringUtils.isEmpty(tag) || tag.equals(LATEST)) {
        consumers = pactBrokerClient.fetchConsumers(providerName).stream()
                        .map(ConsumerInfo::from).collect(toList());
      } else {
        consumers = pactBrokerClient.fetchConsumersWithTag(providerName, tag).stream()
                        .map(ConsumerInfo::from).collect(toList());
      }

      if (failIfNoPactsFound && consumers.isEmpty()) {
        throw new NoPactsFoundException("No consumer pacts were found for provider '" + providerName + "' and tag '" +
                                            tag + "'. (URL " + getUrlForProvider(providerName, tag, pactBrokerClient) + ")");
      }

      if (!pactBrokerConsumers.isEmpty()) {
        List<String> consumerInclusions = pactBrokerConsumers
          .stream()
          .flatMap(consumer -> parseListExpression(consumer, resolver).stream())
          .collect(toList());
        consumers = consumers.stream()
                        .filter(c -> consumerInclusions.isEmpty() || consumerInclusions.contains(c.getName()))
                        .collect(toList());
      }

      return consumers.stream()
                 .map(consumer -> this.loadPact(consumer, pactBrokerClient.getOptions()))
                 .collect(toList());
    } catch (URISyntaxException e) {
      throw new IOException("Was not able load pacts from broker as the broker URL was invalid", e);
    }
  }

  private String getUrlForProvider(String providerName, String tag, PactBrokerClient pactBrokerClient) {
    try {
      return pactBrokerClient.getUrlForProvider(providerName, tag);
    } catch (Exception e) {
      LOGGER.debug("Failed to get provider URL from the pact broker", e);
      return "Unknown";
    }
  }

  Pact loadPact(ConsumerInfo consumer, Map options) {
    Pact pact = PactReader.loadPact(options, consumer.getPactSource());
    Map<Consumer, List<Pact>> pacts = this.pactSource.getPacts();
    Consumer pactConsumer = consumer.toPactConsumer();
    List<Pact> pactList = pacts.getOrDefault(pactConsumer, new ArrayList<>());
    pactList.add(pact);
    pacts.put(pactConsumer, pactList);
    return pact;
  }

  PactBrokerClient newPactBrokerClient(URI url, ValueResolver resolver) {
    if (this.authentication == null || this.authentication.scheme().equalsIgnoreCase("none")) {
      LOGGER.debug("Authentication: None");
      return new PactBrokerClient(url.toString(), Collections.emptyMap());
    }

    String scheme = parseExpression(this.authentication.scheme(), resolver);
    if (StringUtils.isNotEmpty(scheme)) {
      // Legacy behavior (before support for bearer token was added):
      // If scheme was not explicitly set, basic was always used.
      // If it was explicitly set, the given value was used together with username and password
      String schemeToUse = scheme.equals("legacy") ? "basic": scheme;
      LOGGER.debug("Authentication: {}", schemeToUse);
      Map<String, List<String>> options = Collections.singletonMap("authentication", Arrays.asList(schemeToUse,
              parseExpression(this.authentication.username(), resolver),
              parseExpression(this.authentication.password(), resolver)));
      return new PactBrokerClient(url.toString(), options);
    }

    // Check if username is set. If yes, use basic auth.
    String username = parseExpression(this.authentication.username(), resolver);
    if (StringUtils.isNotEmpty(username)) {
      LOGGER.debug("Authentication: Basic");
      Map<String, List<String>> options = Collections.singletonMap("authentication", Arrays.asList("basic", username, parseExpression(this.authentication.password(), resolver)));
      return new PactBrokerClient(url.toString(), options);
    }

    // Check if token is set. If yes, use bearer auth.
    String token = parseExpression(this.authentication.token(), resolver);
    if (StringUtils.isNotEmpty(token)) {
      LOGGER.debug("Authentication: Bearer");
      Map<String, List<String>> options = Collections.singletonMap("authentication", Arrays.asList("bearer", token));
      return new PactBrokerClient(url.toString(), options);
    }

    throw new IllegalArgumentException("Invalid pact authentication specified. Either username or token must be set.");
  }

  public boolean isFailIfNoPactsFound() {
    return failIfNoPactsFound;
  }

  public void setFailIfNoPactsFound(boolean failIfNoPactsFound) {
    this.failIfNoPactsFound = failIfNoPactsFound;
  }
}

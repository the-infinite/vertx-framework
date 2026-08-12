package io.github.the_infinite.framework.doc;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.the_infinite.framework.RouteController;
import io.github.the_infinite.framework.doc.impl.ClassicHttpClient;
import io.github.the_infinite.framework.env.AppEnvironment;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.StaticHandler;

/**
 * Controller that exposes the service documentation in two complementary ways:
 * <ul>
 *   <li>The OpenAPI 3.1 JSON document, served at <code>/docs</code>.</li>
 *   <li>The Swagger UI static distribution, served from the <code>doc</code>
 *       classpath directory at the root anchor of the service.</li>
 * </ul>
 */
public class DocumentationController extends RouteController {
  private static final String TEST_CLIENT_HEADER = "X-Moovable-Test-Client";
  private static final String DOCS_PATH = "/docs";
  private static final String SWAGGER_UI_WEB_ROOTS = "doc";

  private JsonObject cachedOpenApiSpec = null;

  public DocumentationController(@NotNull Vertx vertx) {
    super(vertx, "/");
  }

  @Override
  public void registerRoutes() {
    //? Read the request bodies for the reserved testing endpoints.
    registrant.getRouter().route().order(-1).handler(BodyHandler.create());

    //? Catch-all guard that only intercepts the reserved test-execution requests.
    registrant.getRouter().route().order(0).handler(context -> {
      String testClient = context.request().getHeader(TEST_CLIENT_HEADER);
      if (testClient != null) {
        handleTestRequest(context, testClient);
        return;
      }
      context.next();
    });

    //? Expose the OpenAPI 3.1 specification as JSON.
    registrant.getRouter().route()
      .method(HttpMethod.GET)
      .path(DOCS_PATH)
      .order(1)
      .handler(context -> context.response()
        .putHeader("Content-Type", "application/json")
        .setStatusCode(200)
        .end(resolveOpenApiSpec().encodePrettily()));

    //? Serve the Swagger UI static assets from the `doc` directory on the classpath.
    registrant.getRouter().route()
      .order(2)
      .blockingHandler(StaticHandler.create(SWAGGER_UI_WEB_ROOTS));
  }

  private JsonObject resolveOpenApiSpec() {
    if (cachedOpenApiSpec == null) {
      cachedOpenApiSpec = OpenApi3Generator.generate(DocumentationRegistrant.getInstance());
    }
    return cachedOpenApiSpec;
  }

  private void handleTestRequest(io.vertx.ext.web.RoutingContext context, String clientName) {
    try {
      String method = context.request().method().name();
      String url = context.request().absoluteURI();

      Map<String, String> headers = new java.util.HashMap<>();
      context.request().headers().forEach(entry -> {
        String key = entry.getKey();
        if (!key.equalsIgnoreCase("X-TM30-Test-Client") &&
          !key.equalsIgnoreCase("X-TM30-Test-Params")) {
          headers.put(key, entry.getValue());
        }
      });

      String requestBody = context.body().asString();

      String paramsJsonStr = context.request().getHeader("X-TM30-Test-Params");
      JsonObject paramsJson = paramsJsonStr != null ? new JsonObject(paramsJsonStr) : new JsonObject();
      Map<String, String> parameters = new java.util.HashMap<>();
      paramsJson.forEach(entry -> parameters.put(entry.getKey(), String.valueOf(entry.getValue())));

      HttpClient selectedClient = null;
      for (HttpClient client : getAvailableHttpClients(DocumentationRegistrant.getInstance())) {
        if (normalizeClientName(client.getName()).equalsIgnoreCase(clientName)) {
          selectedClient = client;
          break;
        }
      }

      if (selectedClient == null) {
        String message = isProductionEnvironment()
          ? "HTTP Client not allowed in production: " + clientName
          : "HTTP Client not found: " + clientName;
        context.response().setStatusCode(400).end(message);
        return;
      }

      selectedClient.execute(method, url, headers, requestBody, parameters)
        .onSuccess(res -> {
          final var responseHeaders = new JsonObject();
          if (res.headers() != null) {
            res.headers().forEach(responseHeaders::put);
          }

          final var responseData = new JsonObject()
            .put("statusCode", res.statusCode())
            .put("headers", responseHeaders)
            .put("body", res.body());

          final var jsonResponse = new JsonObject()
            .put("status", "success")
            .put("message", "Request executed")
            .put("data", responseData);
          context.response()
            .putHeader("Content-Type", "application/json")
            .end(jsonResponse.encode());
        })
        .onFailure(err -> context.response().setStatusCode(500).end(err.getMessage()));
    } catch (Exception e) {
      context.response().setStatusCode(500).end(e.getMessage());
    }
  }

  private boolean isProductionEnvironment() {
    return AppEnvironment.getInstance().getKind() == AppEnvironment.EnvironmentKind.PRODUCTION;
  }

  private List<HttpClient> getAvailableHttpClients(DocumentationRegistrant registrant) {
    if (!isProductionEnvironment()) {
      return registrant.getHttpClients();
    }

    return registrant.getHttpClients().stream()
      .filter(this::isBasicHttpClient)
      .toList();
  }

  private boolean isBasicHttpClient(HttpClient client) {
    return client instanceof ClassicHttpClient || normalizeClientName(client.getName()).equals(normalizeClientName(new ClassicHttpClient().getName()));
  }

  private String normalizeClientName(String clientName) {
    return clientName.replace(" ", "-").toLowerCase(Locale.ROOT);
  }
}
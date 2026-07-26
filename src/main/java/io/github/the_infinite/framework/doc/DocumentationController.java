package io.github.the_infinite.framework.doc;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

import io.github.the_infinite.framework.RouteController;
import io.github.the_infinite.framework.doc.impl.ClassicHttpClient;
import io.github.the_infinite.framework.env.AppEnvironment;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * Controller that exposes the documentation metadata.
 * It provides an endpoint to retrieve global documentation settings and all registered route descriptions.
 */
public class DocumentationController extends RouteController {
  private final ObjectMapper mapper = new ObjectMapper();
  private Map<String, List<DocumentationRegistrant.RegisteredRoute>> cachedGroupedRoutes = null;

  public DocumentationController(@NotNull Vertx vertx) {
    super(vertx, "/");
  }

  @Override
  public void registerRoutes() {
    registrant.getRouter().route().order(-1).handler(BodyHandler.create());
    registrant.getRouter().route().order(0).handler(context -> {
      String testClient = context.request().getHeader("X-Moovable-Test-Client");
      String accept = context.request().getHeader("Accept");
      String path = context.request().path();

      if (testClient != null) {
        handleTestRequest(context, testClient);
      } else if ("/".equals(path) || (accept != null && accept.contains("text/html"))) {
        handleDocumentationRequest(context);
      } else {
        context.next();
      }
    });
  }

   private void handleDocumentationRequest(io.vertx.ext.web.RoutingContext context) {
     DocumentationRegistrant registrant = DocumentationRegistrant.getInstance();
     String modeParam = context.request().getParam("mode");
     DocumentationRegistrant.DocumentationMode mode = registrant.getDocumentationMode();

     // Allow override via query parameter
     if (modeParam != null) {
       try {
         mode = DocumentationRegistrant.DocumentationMode.valueOf(modeParam.toUpperCase());
       } catch (IllegalArgumentException ignored) {
         // Keep the default mode if invalid parameter
       }
     }

     String htmlContent;
     if (mode == DocumentationRegistrant.DocumentationMode.EXTERNAL) {
       htmlContent = renderExternalHtml(registrant);
     } else {
       htmlContent = renderHtml(registrant, context.request().path());
     }

     context.response()
       .putHeader("Content-Type", "text/html")
       .setStatusCode(200)
       .end(htmlContent);
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

  private String renderHtml(DocumentationRegistrant registrant, String documentationPath) {
    StringBuilder html = new StringBuilder();
    boolean isProduction = isProductionEnvironment();
    List<HttpClient> availableHttpClients = getAvailableHttpClients(registrant);
    String defaultClientId = normalizeClientName(new ClassicHttpClient().getName());

    html.append("<!DOCTYPE html><html><head><title>API Documentation</title>");
    html.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
     html.append("<style>")
       .append(".swagger-ui { --swagger-blue: #61affe; --swagger-green: #49cc90; --swagger-orange: #fca130; --swagger-red: #f93e3e; --swagger-cyan: #50e3c2; --swagger-ink: #3b4151; --swagger-muted: #6b7280; --swagger-border: #d9dee7; --swagger-surface: #fff; --swagger-surface-2: #f8fafc; --swagger-shadow: 0 10px 32px rgba(15, 23, 42, 0.08); background: linear-gradient(180deg, #f8fafc 0%, #eef2f7 100%); color: var(--swagger-ink); min-height: 100vh; font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; }")
       .append(".swagger-ui .page-shell { max-width: 1200px; margin: 0 auto; padding: 2rem 1rem 3rem; }")
       .append(".swagger-ui h1 { color: #213547; font-size: clamp(2rem, 4vw, 2.6rem); letter-spacing: -0.03em; margin: 0 0 1rem; }")
       .append(".swagger-ui h2, .swagger-ui h3, .swagger-ui h4 { color: var(--swagger-ink); margin-top: 0; }")
       .append(".swagger-ui h2 { font-size: 1.35rem; margin-bottom: 0.75rem; }")
       .append(".swagger-ui h3 { font-size: 1.1rem; margin-bottom: 0.6rem; }")
       .append(".swagger-ui h4 { margin-top: 1.25rem; margin-bottom: 0.6rem; font-size: 0.85rem; text-transform: uppercase; letter-spacing: 0.08em; color: #5b6472; }")
       .append(".swagger-ui .global-settings, .swagger-ui .route, .swagger-ui .try-it-out, .swagger-ui .test-result { background: var(--swagger-surface); border: 1px solid var(--swagger-border); border-radius: 12px; box-shadow: var(--swagger-shadow); }")
       .append(".swagger-ui .global-settings { padding: 1.5rem; margin-bottom: 1.5rem; }")
       .append(".swagger-ui .controller-section { margin-bottom: 2rem; }")
       .append(".swagger-ui .controller-name { background: linear-gradient(180deg, #eff4fb, #e9eef7); padding: 0.9rem 1.2rem; border: 1px solid var(--swagger-border); border-radius: 12px 12px 0 0; border-bottom: none; font-size: 1.05rem; font-weight: 700; color: #2f3b52; }")
       .append(".swagger-ui details.route { margin: 0; overflow: hidden; border-left: 5px solid var(--route-accent, var(--swagger-blue)); }")
       .append(".swagger-ui .route:not(:last-child) { border-bottom: none; }")
       .append(".swagger-ui .route-summary { padding: 1rem 1.1rem; cursor: pointer; display: flex; align-items: center; gap: 0.85rem; background: #fff; transition: background 0.2s ease, box-shadow 0.2s ease; }")
       .append(".swagger-ui .route-summary:hover { background: #f8fbff; }")
       .append(".swagger-ui .route[open] .route-summary { border-bottom: 1px solid #e6edf7; background: #f8fbff; }")
       .append(".swagger-ui .method { font-size: 0.78rem; padding: 0.45rem 0.75rem; border-radius: 999px; color: #fff; font-weight: 800; min-width: 88px; text-align: center; text-transform: uppercase; letter-spacing: 0.05em; box-shadow: 0 2px 10px rgba(0,0,0,0.08); }")
       .append(".swagger-ui .method-GET { background-color: #61affe; }")
       .append(".swagger-ui .method-POST { background-color: #49cc90; }")
       .append(".swagger-ui .method-PUT { background-color: #fca130; }")
       .append(".swagger-ui .method-DELETE { background-color: #f93e3e; }")
       .append(".swagger-ui .method-PATCH { background-color: #50e3c2; color: #17313a; }")
       .append(".swagger-ui .route-get { --route-accent: #61affe; } .swagger-ui .route-post { --route-accent: #49cc90; } .swagger-ui .route-put { --route-accent: #fca130; } .swagger-ui .route-delete { --route-accent: #f93e3e; } .swagger-ui .route-patch { --route-accent: #50e3c2; }")
       .append(".swagger-ui .path { font-family: 'Fira Code', 'Courier New', monospace; font-weight: 700; font-size: 1rem; color: #303133; word-break: break-all; }")
       .append(".swagger-ui .name { margin-left: auto; font-size: 0.9rem; color: #7c8594; font-weight: 500; }")
       .append(".swagger-ui .route-details { padding: 1.35rem 1.1rem 1.5rem; background: #fff; }")
       .append(".swagger-ui .route-details p { color: var(--swagger-ink); margin: 0.35rem 0; }")
       .append(".swagger-ui pre { background: #1f2937; color: #e5e7eb; padding: 1rem 1.1rem; border-radius: 10px; overflow-x: auto; font-size: 0.88rem; margin: 0; box-shadow: inset 0 0 0 1px rgba(255,255,255,0.04); line-height: 1.5; }")
       .append(".swagger-ui pre.json-rendered { font-size: 0.78rem; line-height: 1.6; }")
       .append(".swagger-ui .test-result pre.json-rendered { font-size: 0.76rem; }")
       .append(".swagger-ui code { background: #edf2f7; color: #2f3b52; padding: 0.2rem 0.45rem; border-radius: 6px; font-family: 'Fira Code', 'Courier New', monospace; font-size: 0.95em; }")
       .append(".swagger-ui ul { list-style: none; padding-left: 0; margin: 0.25rem 0 0; }")
       .append(".swagger-ui li { margin-bottom: 0.5rem; }")
       .append(".swagger-ui .try-it-out { margin-top: 1.5rem; padding: 1.25rem; background: linear-gradient(180deg, #ffffff, #fbfcfe); }")
       .append(".swagger-ui .body-editor { margin-top: 1rem; }")
       .append(".swagger-ui .body-editor__header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; margin-bottom: 0.65rem; }")
       .append(".swagger-ui .body-editor__header h4 { margin: 0; }")
       .append(".swagger-ui .body-actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }")
       .append(".swagger-ui .utility-btn, .swagger-ui .execute-btn { appearance: none; border: 1px solid transparent; border-radius: 8px; cursor: pointer; font-weight: 700; transition: transform 0.15s ease, box-shadow 0.15s ease, background-color 0.2s ease, border-color 0.2s ease; }")
       .append(".swagger-ui .utility-btn { background: #fff; border-color: #cfd7e3; color: #334155; padding: 0.55rem 0.8rem; font-size: 0.85rem; }")
       .append(".swagger-ui .utility-btn:hover { background: #f8fbff; border-color: #aab8cf; box-shadow: 0 6px 14px rgba(15,23,42,0.08); }")
       .append(".swagger-ui .utility-btn--primary { border-color: #61affe; color: #1e63b7; }")
       .append(".swagger-ui .utility-btn--secondary { border-color: #dbe2ec; color: #475569; }")
       .append(".swagger-ui .test-input { width: 100%; padding: 0.7rem 0.8rem; border: 1px solid #d3dae6; border-radius: 8px; margin-bottom: 0.65rem; font-family: inherit; box-sizing: border-box; background: #fff; color: #1f2937; box-shadow: inset 0 1px 2px rgba(15,23,42,0.04); }")
       .append(".swagger-ui .test-input:focus { outline: none; border-color: #61affe; box-shadow: 0 0 0 3px rgba(97,174,254,0.16); }")
       .append(".swagger-ui .request-body-input { min-height: 190px; resize: vertical; font-family: 'Fira Code', 'Courier New', monospace; background: #fcfdff; color: #1f2937; line-height: 1.5; }")
       .append(".swagger-ui .execute-btn { background: linear-gradient(180deg, #61affe, #4990e2); color: #fff; padding: 0.75rem 1.15rem; margin-top: 1rem; box-shadow: 0 8px 18px rgba(73,144,226,.24); }")
       .append(".swagger-ui .execute-btn:hover { transform: translateY(-1px); box-shadow: 0 10px 22px rgba(73,144,226,.3); }")
       .append(".swagger-ui .test-result { margin-top: 1.25rem; padding: 1rem; background: #f8fafc; border-radius: 10px; border: 1px solid #e2e8f0; display: none; }")
       .append(".swagger-ui .result-header { font-weight: 800; margin-bottom: 0.75rem; color: #213547; }")
       .append(".swagger-ui select { background: #fff; border: 1px solid #cfd7e3; border-radius: 8px; color: #334155; padding: 0.65rem 2.25rem 0.65rem 0.85rem; box-shadow: inset 0 1px 2px rgba(15,23,42,0.04); }")
       .append(".swagger-ui textarea { min-height: 180px; }")
       .append(".json-key { color: #9cdcfe; }")
       .append(".json-string { color: #ce9178; }")
       .append(".json-number { color: #b5cea8; }")
       .append(".json-boolean { color: #569cd6; }")
       .append(".json-null { color: #569cd6; font-style: italic; }")
       .append(".json-punctuation { color: #d4d4d4; }")
       .append("@media (max-width: 768px) { .swagger-ui .page-shell { padding: 1rem; } .swagger-ui .route-summary { flex-direction: column; align-items: flex-start; } .swagger-ui .method { min-width: auto; width: 100%; margin-bottom: 0.25rem; } .swagger-ui .name { margin-left: 0; } .swagger-ui .body-editor__header { align-items: flex-start; flex-direction: column; } }")
       .append("</style></head><body class='swagger-ui'><div class='page-shell'>");

    html.append("<h1>API Documentation</h1>");

    if (!isProduction && !availableHttpClients.isEmpty()) {
      html.append("<section class='global-settings'>");
      html.append("<h2>HTTP Client Settings</h2>");
      html.append("<p>Select an HTTP client to see its usage snippet and configure parameters:</p>");
      html.append("<select id='client-select' onchange='updateClient()' style='padding: 0.5rem; width: 100%; max-width: 300px; margin-bottom: 1rem;'>");
      for (HttpClient client : availableHttpClients) {
        String clientVal = normalizeClientName(client.getName());
        html.append("<option value='").append(clientVal).append("'")
          .append(clientVal.equals(defaultClientId) ? " selected" : "")
          .append(">").append(client.getName()).append("</option>");
      }
      html.append("</select>");

      for (HttpClient client : availableHttpClients) {
        String clientId = normalizeClientName(client.getName());
        html.append("<div id='snippet-").append(clientId).append("' class='client-snippet' style='display: none;'>");
        html.append("<p><strong>Description:</strong> ").append(client.getDescription()).append("</p>");

        if (!client.getParameters().isEmpty()) {
          html.append("<h4>Parameters</h4>");
          html.append("<div style='margin-bottom: 1rem; display: flex; flex-wrap: wrap; gap: 1rem;'>");
          for (HttpClientParameter param : client.getParameters()) {
            html.append("<div style='min-width: 200px;'>");
            html.append("<label style='display: block; font-size: 0.8rem; color: #606266;'>").append(param.label()).append(":</label>");
            html.append("<input type='").append(param.type()).append("' class='client-param' data-param='").append(param.name()).append("' value='").append(param.defaultValue()).append("' oninput='updateClientSnippet(\"").append(clientId).append("\")' style='padding: 0.4rem; width: 100%; border: 1px solid #dcdfe6; border-radius: 4px;'>");
            html.append("</div>");
          }
          html.append("</div>");
        }

        html.append("</div>");
      }

      html.append("</section>");
    }

    html.append("<section class='global-settings'>");
    html.append("<h2>Global Definitions</h2>");
    html.append("<p><strong>Default Rate Limit:</strong> ").append(registrant.getGlobalRateLimit() != null ? registrant.getGlobalRateLimit() + " requests" : "Unlimited").append("</p>");

    if (!registrant.getUnauthenticatedGlobalHeaders().isEmpty()) {
      html.append("<h3>Global Headers for Unprotected Endpoints</h3><ul>");
      registrant.getUnauthenticatedGlobalHeaders().forEach((k, v) -> html.append("<li><code>").append(k).append(": ").append(v).append("</code></li>"));
      html.append("</ul>");
    }

    if (!registrant.getAuthenticatedGlobalHeaders().isEmpty()) {
      html.append("<h3>Global Headers for Protected Endpoints</h3><ul>");
      registrant.getAuthenticatedGlobalHeaders().forEach((k, v) -> html.append("<li><code>").append(k).append(": ").append(v).append("</code></li>"));
      html.append("</ul>");
    }

    if (!registrant.getAuthSettings().isEmpty()) {
      html.append("<h3>Authentication</h3><ul>");
      registrant.getAuthSettings().forEach((k, v) -> html.append("<li><strong>").append(k).append(":</strong> ").append(v).append("</li>"));
      html.append("</ul>");
    }
    html.append("</section>");

    html.append("<h2>Endpoints</h2>");

    Map<String, List<DocumentationRegistrant.RegisteredRoute>> grouped = getGroupedRoutes(registrant);

    grouped.forEach((group, routes) -> {
      html.append("<div class='controller-section'>");
      html.append("<div class='controller-name'>").append(group).append("</div>");

      for (DocumentationRegistrant.RegisteredRoute route : routes) {
        html.append("<details class='route route-").append(route.method().toLowerCase(Locale.ROOT)).append("'>");
        html.append("<summary class='route-summary'>");
        html.append("<span class='method method-").append(route.method()).append("'>").append(route.method()).append("</span>");
        html.append("<span class='path'>").append(route.path()).append("</span>");
        html.append("<span class='name'>").append(route.description().name()).append("</span>");
        html.append("</summary>");

        html.append("<div class='route-details'>");
        html.append("<p>").append(route.description().description()).append("</p>");

        if (route.description().authenticationRequired()) {
          html.append("<p><strong>Authentication:</strong> Required (").append(route.description().authenticationComment() != null ? route.description().authenticationComment() : "No reason provided").append(")</p>");
        } else {
          html.append("<p><strong>Authentication:</strong> Not Required (").append(route.description().authenticationComment() != null ? route.description().authenticationComment() : "Public API").append(")</p>");
        }

        html.append("<p><strong>Header Order:</strong> ");
        html.append(route.description().authenticationRequired()
          ? "unprotected defaults -> protected defaults -> route-specific headers"
          : "unprotected defaults -> route-specific headers");
        html.append("</p>");

        if (route.description().rateLimit() != null) {
          html.append("<p><strong>Rate Limit:</strong> ").append(route.description().rateLimit()).append(" requests per minute</p>");
        }

        route.description().headers();
        if (!route.description().headers().isEmpty()) {
          html.append("<h4>Request Headers</h4><ul>");
          route.description().headers().forEach((k, v) -> html.append("<li><code>").append(k).append(": ").append(v).append("</code></li>"));
          html.append("</ul>");
        }

        if (route.description().pathParameters() != null && !route.description().pathParameters().isEmpty()) {
          html.append("<h4>Path Parameters</h4><ul>");
          route.description().pathParameters().forEach((k, v) -> html.append("<li><code>").append(k).append("</code>: ").append(v).append("</li>"));
          html.append("</ul>");
        }

        if (route.description().queryParameters() != null && !route.description().queryParameters().isEmpty()) {
          html.append("<h4>Query Parameters</h4><ul>");
          route.description().queryParameters().forEach((k, v) -> {
            html.append("<li><code>").append(k).append("</code>: ").append(v);
            if (route.description().queryParameterDefaults() != null && route.description().queryParameterDefaults().containsKey(k)) {
              html.append(" (Default: <code>").append(route.description().queryParameterDefaults().get(k)).append("</code>)");
            }
            html.append("</li>");
          });
          html.append("</ul>");
        }

        if (route.description().fileParameters() != null && !route.description().fileParameters().isEmpty()) {
          html.append("<h4>File Parameters</h4><ul>");
          route.description().fileParameters().forEach((k, v) -> {
            html.append("<li><code>").append(k).append("</code>: ")
              .append(v.description())
              .append(" (Type: <code>").append(v.type().name()).append("</code>, Extensions: <code>")
              .append(v.type().allowedExtensionsExample())
              .append("</code>");
            if (v.limit() != null) {
              html.append(", Max files: <code>").append(v.limit()).append("</code>");
            }
            html.append(")</li>");
          });
          html.append("</ul>");
        }

        if (route.description().requestBodyClass() != null) {
          appendExampleDisplay(html, route.description().requestBodyClass());
        }

        if (route.description().responseDTOs() != null && !route.description().responseDTOs().isEmpty()) {
          html.append("<h4>Responses</h4>");
          route.description().responseDTOs().forEach((code, dto) -> {
            html.append("<div style='margin-bottom: 1rem;'>");
            String typeName = dto instanceof RouteDescription.ResponseCasing rc
              ? rc.innerTypeName() : dto.getClass().getSimpleName();
            html.append("<strong style='display: block; margin-bottom: 0.5rem;'>Status ").append(code).append(" (").append(typeName).append(")</strong>");
            appendExampleDisplay(html, dto);
            html.append("</div>");
          });
        }

        html.append("</div>");

        String routeId = UUID.randomUUID().toString().substring(0, 8);
        html.append("<div class='try-it-out'>");
        html.append("<h3>Try it out</h3>");

        if (route.description().pathParameters() != null && !route.description().pathParameters().isEmpty()) {
          html.append("<h4>Path Parameters</h4>");
          for (Map.Entry<String, String> entry : route.description().pathParameters().entrySet()) {
            String defaultValue = route.description().pathParameterDefaults() != null ? route.description().pathParameterDefaults().getOrDefault(entry.getKey(), "") : "";
            html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>").append(entry.getKey()).append(" (").append(entry.getValue()).append("):</label>");
            html.append("<input type='text' class='test-input path-param-").append(routeId).append("' data-param='").append(entry.getKey()).append("' value='").append(defaultValue).append("'>");
          }
        }

        if (route.description().queryParameters() != null && !route.description().queryParameters().isEmpty()) {
          html.append("<h4>Query Parameters</h4>");
          for (Map.Entry<String, String> entry : route.description().queryParameters().entrySet()) {
            String defaultValue = route.description().queryParameterDefaults() != null ? route.description().queryParameterDefaults().getOrDefault(entry.getKey(), "") : "";
            html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>").append(entry.getKey()).append(" (").append(entry.getValue()).append("):</label>");
            html.append("<input type='text' class='test-input query-param-").append(routeId).append("' data-param='").append(entry.getKey()).append("' value='").append(defaultValue).append("'>");
          }
        }

        if (route.description().fileParameters() != null && !route.description().fileParameters().isEmpty()) {
          html.append("<h4>Files</h4>");
          for (Map.Entry<String, RouteDescription.FileParameter> entry : route.description().fileParameters().entrySet()) {
            RouteDescription.FileParameter fileParameter = entry.getValue();
            Integer maxFiles = fileParameter.limit() != null ? fileParameter.limit() : 1;
            boolean isMultiple = maxFiles > 1;
            String acceptedExtensions = fileParameter.type().allowedExtensionsExample();
            boolean hasExplicitAccept = acceptedExtensions.contains(".");
            html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>")
              .append(fileParameter.name())
              .append(" (").append(fileParameter.description()).append(")")
              .append(" - ").append(fileParameter.type().name())
              .append(" [").append(acceptedExtensions).append("]")
              .append("</label>");
            html.append("<input type='file' class='test-input file-input-").append(routeId)
              .append("' data-param='").append(fileParameter.name())
              .append("' data-limit='").append(maxFiles).append("'")
              .append(hasExplicitAccept ? " accept='" + acceptedExtensions + "'" : "")
              .append(isMultiple ? " multiple" : "")
              .append(">");
          }
        }

        html.append("<h4>Headers</h4>");
        html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>X-Correlation-ID:</label>");
        html.append("<input type='text' class='test-input header-input-").append(routeId).append("' data-header='X-Correlation-ID' value='").append(UUID.randomUUID()).append("'>");

        for (Map.Entry<String, String> entry : route.description().headers().entrySet()) {
          html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>").append(entry.getKey()).append(":</label>");
          html.append("<input type='text' class='test-input header-input-").append(routeId).append("' data-header='").append(entry.getKey()).append("' value='").append(entry.getValue()).append("'>");
        }

        if ("POST".equals(route.method()) || "PUT".equals(route.method()) || "PATCH".equals(route.method())) {
          Map<String, String> bodyExamples = getExamples(route.description().requestBodyClass());
          String requestBodyExample = bodyExamples.isEmpty() ? "" : bodyExamples.values().iterator().next();

          html.append("<div class='body-editor'>");
          html.append("<div class='body-editor__header'>");
          html.append("<h4>Request Body</h4>");
          html.append("<div class='body-actions'>");
          html.append("<button type='button' class='utility-btn utility-btn--primary' onclick='copyRequestBody(\"").append(routeId).append("\", this)'>Copy body</button>");
          html.append("<button type='button' class='utility-btn utility-btn--secondary' onclick='resetRequestBody(\"").append(routeId).append("\", this)'>Reset example</button>");
          html.append("</div></div>");
          html.append("<textarea id='body-").append(routeId).append("' class='test-input request-body-input' data-example=\"")
            .append(escapeForHtmlAttribute(requestBodyExample))
            .append("\">")
            .append(escapeForHtml(requestBodyExample))
            .append("</textarea>");
          html.append("</div>");
        }

        html.append("<button class='execute-btn' onclick='executeTest(\"").append(route.method()).append("\", \"").append(route.path()).append("\", \"").append(routeId).append("\")'>Execute</button>");
        html.append("<div id='result-").append(routeId).append("' class='test-result'></div>");
        html.append("</div>");

        html.append("</details>");
      }
      html.append("</div>");
    });

    html.append("""
      <script>
      const DEFAULT_CLIENT_ID = '%s';
      const IS_PRODUCTION = %s;
      const DOCUMENTATION_PATH = '%s';

      function delay(ms) {
        return new Promise(resolve => window.setTimeout(resolve, ms));
      }

      function getRequestBodyTextarea(routeId) {
        return document.getElementById('body-' + routeId);
      }

      function flashButton(button, label) {
        if (!button) return;
        const original = button.textContent;
        button.textContent = label;
        window.setTimeout(() => {
          button.textContent = original;
        }, 1100);
      }

      async function copyTextToClipboard(text) {
        const value = text == null ? '' : String(text);

        try {
          if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(value);
            return true;
          }
        } catch (error) {
          // Fall through to the legacy clipboard path.
        }

        const fallback = document.createElement('textarea');
        fallback.value = value;
        fallback.setAttribute('readonly', '');
        fallback.style.position = 'fixed';
        fallback.style.left = '-9999px';
        fallback.style.top = '-9999px';
        fallback.style.opacity = '0';
        document.body.appendChild(fallback);
        fallback.focus();
        fallback.select();

        let copied = false;
        try {
          copied = document.execCommand('copy');
        } catch (error) {
          copied = false;
        } finally {
          document.body.removeChild(fallback);
        }

        return copied;
      }

      async function copyRequestBody(routeId, button) {
        const textarea = getRequestBodyTextarea(routeId);
        if (!textarea) return;

        const copied = await copyTextToClipboard(textarea.value || '');
        if (copied) {
          flashButton(button, 'Copied');
        }
      }

      function resetRequestBody(routeId, button) {
        const textarea = getRequestBodyTextarea(routeId);
        if (!textarea) return;

        textarea.value = textarea.dataset.example || '';
        flashButton(button, 'Reset');
      }

      function headersToObject(headers) {
        const result = {};
        headers.forEach((value, key) => {
          result[key] = value;
        });
        return result;
      }

      function normalizeBody(body) {
        if (body == null) {
          return '';
        }

        if (typeof FormData !== 'undefined' && body instanceof FormData) {
          return body;
        }

        if (typeof body === 'string') {
          return body;
        }

        return JSON.stringify(body);
      }

       function escapeHtml(value) {
         return String(value ?? '')
           .replace(/&/g, '&amp;')
           .replace(/</g, '&lt;')
           .replace(/>/g, '&gt;')
           .replace(/"/g, '&quot;')
           .replace(/'/g, '&#39;');
       }

       function parseJsonIfPossible(value) {
        if (typeof value !== 'string') {
          return value;
        }

        const trimmed = value.trim();
        if (trimmed === '') {
          return null;
        }

        try {
          return JSON.parse(trimmed);
        } catch (error) {
          return value;
        }
      }

       function normalizeStructuredValue(value) {
         if (value == null || value === '') {
           return { json: false, text: '' };
         }

         if (typeof value === 'string') {
           const trimmed = value.trim();
           if (trimmed === '') {
             return { json: false, text: value };
           }

           try {
             return { json: true, value: JSON.parse(trimmed) };
           } catch (error) {
             return { json: false, text: value };
           }
         }

         if (typeof value === 'object') {
           return { json: true, value };
         }

         return { json: false, text: String(value) };
       }

       function jsonIndent(level) {
         return '  '.repeat(level);
       }

       const jsonNewline = String.fromCharCode(10);

       function jsonPunctuation(text) {
         return '<span class="json-punctuation">' + escapeHtml(text) + '</span>';
       }

       function renderJsonPrimitive(value) {
         if (value === null) {
           return '<span class="json-null">null</span>';
         }

         if (typeof value === 'string') {
           return '<span class="json-string">' + escapeHtml(JSON.stringify(value)) + '</span>';
         }

         if (typeof value === 'number') {
           return '<span class="json-number">' + escapeHtml(String(value)) + '</span>';
         }

         if (typeof value === 'boolean') {
           return '<span class="json-boolean">' + escapeHtml(String(value)) + '</span>';
         }

         return '<span class="json-string">' + escapeHtml(JSON.stringify(String(value))) + '</span>';
       }

       function renderJsonValue(value, depth) {
         if (Array.isArray(value)) {
           if (value.length === 0) {
             return jsonPunctuation('[]');
           }

           const lines = value.map(item => jsonIndent(depth + 1) + renderJsonValue(item, depth + 1));
           return `${jsonPunctuation('[')}
${lines.join(jsonPunctuation(',') + jsonNewline)}
${jsonIndent(depth)}${jsonPunctuation(']')}`;
         }

         if (value && typeof value === 'object') {
           const entries = Object.entries(value);
           if (entries.length === 0) {
             return jsonPunctuation('{}');
           }

           const lines = entries.map(([key, innerValue]) =>
             jsonIndent(depth + 1)
             + '<span class="json-key">' + escapeHtml(JSON.stringify(key)) + '</span>'
             + jsonPunctuation(': ')
             + renderJsonValue(innerValue, depth + 1)
           );

           return `${jsonPunctuation('{')}
${lines.join(jsonPunctuation(',') + jsonNewline)}
${jsonIndent(depth)}${jsonPunctuation('}')}`;
         }

         return renderJsonPrimitive(value);
       }

       function renderStructuredContent(value) {
         const normalized = normalizeStructuredValue(value);
         return normalized.json ? renderJsonValue(normalized.value, 0) : escapeHtml(normalized.text);
       }

       function decorateJsonBlocks(selector) {
         document.querySelectorAll(selector).forEach(pre => {
           pre.innerHTML = renderStructuredContent(pre.textContent || '');
           pre.classList.add('json-rendered');
         });
       }

      function getSelectedClientName() {
        const clientSelect = document.getElementById('client-select');
        return clientSelect ? clientSelect.value : DEFAULT_CLIENT_ID;
      }

      function trimTrailingSlash(path) {
        if (!path || path === '/') {
          return '';
        }

        return path.endsWith('/') ? path.slice(0, -1) : path;
      }

      function resolveServiceBasePath() {
        const path = window.location.pathname || DOCUMENTATION_PATH || '/';
        console.log(`Doc path is ${path}`);
        return trimTrailingSlash(path);
      }

      function joinUrlPath(basePath, routePath) {
        if (!routePath) {
          return basePath || '/';
        }

        const isAbsoluteHttpUrl = routePath.startsWith('http://') || routePath.startsWith('https://');
        if (isAbsoluteHttpUrl) {
          return routePath;
        }

        const normalizedBase = trimTrailingSlash(basePath || '');
        const normalizedRoute = routePath.startsWith('/') ? routePath : '/' + routePath;

        // Avoid duplicating prefixes when route metadata already contains the mount path.
        if (normalizedBase && (normalizedRoute === normalizedBase || normalizedRoute.startsWith(normalizedBase + '/'))) {
          return normalizedRoute;
        }

        return normalizedBase + normalizedRoute;
      }

      function getClientParameters(clientId) {
        const snippetDiv = document.getElementById('snippet-' + clientId);
        const clientParams = {};
        if (!snippetDiv) {
          return clientParams;
        }

        snippetDiv.querySelectorAll('.client-param').forEach(input => {
          clientParams[input.dataset.param] = input.value;
        });

        return clientParams;
      }

      function createFetchOptions(method, headers, requestBody) {
        const normalizedHeaders = { ...headers };
        const isFormData = typeof FormData !== 'undefined' && requestBody instanceof FormData;
        const hasBody = requestBody != null && requestBody !== '';

        if (isFormData) {
          // Let the browser inject multipart boundaries.
          delete normalizedHeaders['Content-Type'];
        } else if (hasBody && method !== 'GET' && method !== 'DELETE' && !normalizedHeaders['Content-Type']) {
          normalizedHeaders['Content-Type'] = 'application/json';
        }

        return {
          method,
          headers: normalizedHeaders,
          body: method === 'GET' || method === 'DELETE' ? undefined : requestBody
        };
      }

      async function executeClassicClient(request) {
        const response = await fetch(request.url, createFetchOptions(request.method, request.headers, request.body));
        return {
          statusCode: response.status,
          headers: headersToObject(response.headers),
          body: await response.text()
        };
      }

      async function executeThrottledClient(request) {
        const probability = Number.parseFloat(request.parameters.throttleProbability ?? '0.1');
        const latencyExponent = Number.parseFloat(request.parameters.latencyExponent ?? '2.0');

        if (Math.random() < probability) {
          const delayMs = Math.pow(Math.random() * 10, latencyExponent);
          await delay(delayMs);
        }

        return executeClassicClient(request);
      }

      async function executeBurstClient(request) {
        const parallelClientCount = Number.parseInt(request.parameters.parallelClientCount ?? '5', 10);
        const requestsPerClient = Number.parseInt(request.parameters.requestsPerClient ?? '100', 10);
        const executions = [];

        for (let clientIndex = 0; clientIndex < parallelClientCount; clientIndex++) {
          for (let requestIndex = 0; requestIndex < requestsPerClient; requestIndex++) {
            executions.push(
              executeClassicClient(request)
                .then(response => ({ response, ok: response.statusCode < 400 }))
                .catch(error => ({ ok: false, error: error instanceof Error ? error.message : String(error) }))
            );
          }
        }

        const settled = await Promise.all(executions);
        const succeeded = settled.filter(result => result.ok).length;
        const firstResponse = settled.find(result => result.response)?.response ?? null;

        return {
          statusCode: firstResponse?.statusCode ?? 200,
          headers: firstResponse?.headers ?? {},
          body: JSON.stringify({
            summary: 'Burst complete.',
            totalRequests: settled.length,
            succeeded,
            failed: settled.length - succeeded,
            sampleResponseBody: firstResponse?.body ?? null
          }, null, 2)
        };
      }

      const clientActors = {
        'classic-http-client': executeClassicClient,
        'throttled-http-client': executeThrottledClient,
        'burst-http-client': executeBurstClient
      };

      function updateClient() {
        const select = document.getElementById('client-select');
        const snippets = document.querySelectorAll('.client-snippet');
        snippets.forEach(function(snippet) { snippet.style.display = 'none'; });

        if (select && select.value) {
          const selectedSnippet = document.getElementById('snippet-' + select.value);
          if (selectedSnippet) {
            selectedSnippet.style.display = 'block';
          }
          updateClientSnippet(select.value);
        }
      }

      function updateClientSnippet(clientId) {
        const snippetDiv = document.getElementById('snippet-' + clientId);
        if (!snippetDiv) return;

        const pre = document.getElementById('pre-' + clientId);
        if (!pre) return;
        let content = pre.getAttribute('data-template');
        const inputs = snippetDiv.querySelectorAll('.client-param');
        inputs.forEach(function(input) {
          const paramName = input.getAttribute('data-param');
          const value = input.value || input.placeholder;
          content = content.split('{{' + paramName + '}}').join(value);
        });
        pre.textContent = content;
      }

       window.addEventListener('DOMContentLoaded', function() {
         if (!IS_PRODUCTION) {
           updateClient();
         }

         decorateJsonBlocks('pre[id^="ex-"]');
       });

      function updateExample(sectionId, exampleIndex) {
        const items = document.querySelectorAll('.' + sectionId + '-item');
        items.forEach(function(item) { item.style.display = 'none'; });
        const selected = document.getElementById(sectionId + '-' + exampleIndex);
        if (selected) selected.style.display = 'block';
      }

      async function executeTest(method, path, routeId) {
        const clientName = getSelectedClientName();
        const actor = clientActors[clientName] ?? clientActors[DEFAULT_CLIENT_ID];
        if (!actor) {
          alert('No HTTP client is available for this environment.');
          return;
        }

        const basePath = resolveServiceBasePath();
        const resolvedPath = joinUrlPath(basePath, path);
        const isAbsoluteResolvedUrl = resolvedPath.startsWith('http://') || resolvedPath.startsWith('https://');
        let finalUrl = isAbsoluteResolvedUrl
          ? resolvedPath
          : window.location.origin + resolvedPath;
        const params = {};
        document.querySelectorAll('.path-param-' + routeId).forEach(input => {
          params[input.dataset.param] = input.value;
          finalUrl = finalUrl.split(':' + input.dataset.param).join(input.value);
        });

        const qpArray = [];
        document.querySelectorAll('.query-param-' + routeId).forEach(input => {
          params[input.dataset.param] = input.value;
          if (input.value) {
            qpArray.push(encodeURIComponent(input.dataset.param) + '=' + encodeURIComponent(input.value));
          }
        });

        if (qpArray.length > 0) {
          finalUrl += (finalUrl.includes('?') ? '&' : '?') + qpArray.join('&');
        }

        const headers = {};
        document.querySelectorAll('.header-input-' + routeId).forEach(input => {
          if (input.value) {
            headers[input.dataset.header] = input.value;
          }
        });

        const bodyInput = document.getElementById('body-' + routeId);
        let requestBody = bodyInput ? bodyInput.value : null;
        if (requestBody) {
          for (const key in params) {
            requestBody = requestBody.split('{{' + key + '}}').join(params[key]);
            requestBody = requestBody.split(':' + key).join(params[key]);
          }
        }

        const fileInputs = document.querySelectorAll('.file-input-' + routeId);
        if (fileInputs.length > 0) {
          const formData = new FormData();

          // Keep body semantics stable under multipart: always send one JSON-encoded `body` field.
          const parsedRequestBody = parseJsonIfPossible(requestBody);
          formData.append('body', JSON.stringify(parsedRequestBody));

          fileInputs.forEach(input => {
            const files = input.files ? Array.from(input.files) : [];
            if (files.length === 0) {
              return;
            }

            const limit = Number.parseInt(input.dataset.limit || '1', 10);
            if (!Number.isNaN(limit) && limit > 0 && files.length > limit) {
              throw new Error('File parameter "' + input.dataset.param + '" allows at most ' + limit + ' file(s).');
            }

            files.forEach(file => formData.append(input.dataset.param, file));
          });
          requestBody = formData;
        }

        const resultDiv = document.getElementById('result-' + routeId);
        resultDiv.style.display = 'block';
        resultDiv.innerHTML = '<div class="result-header">Executing...</div>';

         try {
           const data = await actor({
             method,
             url: finalUrl,
             headers,
             body: normalizeBody(requestBody),
             parameters: getClientParameters(clientName)
           });

           let resultHtml = '<div class="result-header">Status: ' + data.statusCode + '</div>';
           resultHtml += '<h4>Response Headers</h4><pre class="json-rendered test-result-code" style="background:#272822; color:#f8f8f2; padding:1rem; border-radius:6px; overflow-x:auto;">' + renderStructuredContent(data.headers ?? {}) + '</pre>';
           resultHtml += '<h4>Response Body</h4><pre class="json-rendered test-result-code" style="background:#272822; color:#f8f8f2; padding:1rem; border-radius:6px; overflow-x:auto;">' + renderStructuredContent(data.body) + '</pre>';
           resultDiv.innerHTML = resultHtml;
         } catch (error) {
           const message = error instanceof Error ? error.message : String(error);
           resultDiv.innerHTML = '<div style="color: red;">Error: ' + message + '</div>';
         }
      }
      </script>
      """.formatted(defaultClientId, Boolean.toString(isProduction),
      escapeForJsSingleQuotedString(documentationPath)));
     html.append("</div></body></html>");
     return html.toString();
   }

   private String renderExternalHtml(DocumentationRegistrant registrant) {
     StringBuilder html = new StringBuilder();
     boolean isProduction = isProductionEnvironment();
     List<HttpClient> availableHttpClients = getAvailableHttpClients(registrant);
     String defaultClientId = normalizeClientName(new ClassicHttpClient().getName());
     Map<String, List<DocumentationRegistrant.RegisteredRoute>> grouped = getGroupedRoutes(registrant);
     Map<DocumentationRegistrant.RegisteredRoute, String> routeAnchorIds = buildRouteAnchorIds(grouped);

     html.append("<!DOCTYPE html><html><head><title>API Documentation</title>");
     html.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
     html.append("<style>")
       .append("* { margin: 0; padding: 0; box-sizing: border-box; }")
       .append("html { scroll-behavior: smooth; }")
       .append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', 'Helvetica', 'Arial', sans-serif; background: #f5f7fa; color: #223; line-height: 1.6; }")
       .append(".page-container { display: flex; min-height: 100vh; }")
       .append(".sidebar { width: 280px; background: #fff; border-right: 1px solid #e0e4e8; overflow-y: auto; position: sticky; top: 0; height: 100vh; padding: 2rem 0; }")
       .append(".sidebar h1 { font-size: 1.25rem; font-weight: 700; padding: 0 1.5rem 1.5rem; color: #213547; }")
       .append(".sidebar-group { padding: 1rem 0; border-bottom: 1px solid #eef2f7; }")
       .append(".sidebar-group:last-child { border-bottom: none; }")
       .append(".sidebar-group-title { font-size: 0.75rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; padding: 0.75rem 1.5rem 0.5rem; color: #768699; cursor: pointer; user-select: none; }")
       .append(".sidebar-group-title:hover { color: #213547; }")
       .append(".sidebar-endpoints { list-style: none; }")
       .append(".sidebar-endpoint a { display: block; padding: 0.5rem 1.5rem; color: #556575; font-size: 0.9rem; text-decoration: none; border-left: 3px solid transparent; transition: all 0.2s ease; }")
       .append(".sidebar-endpoint a:hover { background: #f0f4f8; color: #213547; }")
       .append(".sidebar-endpoint a.active { background: #e8f1ff; color: #1e63b7; border-left-color: #1e63b7; font-weight: 600; }")
       .append(".main-content { flex: 1; padding: 3rem 2rem; overflow-y: auto; }")
       .append(".content-header { margin-bottom: 3rem; }")
       .append(".content-header h1 { font-size: 2.5rem; margin-bottom: 0.5rem; color: #213547; }")
       .append(".content-header p { color: #768699; font-size: 1.1rem; }")
       .append(".section { margin-bottom: 4rem; }")
       .append(".section-header { padding-bottom: 1.5rem; border-bottom: 2px solid #e0e4e8; margin-bottom: 2rem; }")
       .append(".section-header h2 { font-size: 1.8rem; color: #213547; margin-bottom: 0.5rem; }")
       .append(".section-description { color: #556575; font-size: 0.95rem; margin-top: 0.5rem; }")
       .append(".endpoint { background: #fff; border: 1px solid #e0e4e8; border-radius: 8px; margin-bottom: 1.5rem; overflow: hidden; transition: box-shadow 0.2s ease; scroll-margin-top: 1.5rem; }")
       .append(".endpoint:hover { box-shadow: 0 4px 12px rgba(15, 23, 42, 0.08); }")
       .append(".endpoint:target { box-shadow: 0 0 0 3px rgba(97, 174, 254, 0.22), 0 4px 14px rgba(15, 23, 42, 0.1); border-color: #8ebcf6; }")
       .append(".endpoint-header { display: flex; align-items: center; gap: 1rem; padding: 1.25rem 1.5rem; background: #f8fafc; border-bottom: 1px solid #e0e4e8; cursor: pointer; }")
       .append(".endpoint-header:hover { background: #f0f4f8; }")
       .append(".endpoint-method { font-size: 0.75rem; padding: 0.35rem 0.65rem; border-radius: 4px; color: #fff; font-weight: 800; text-transform: uppercase; letter-spacing: 0.05em; min-width: 65px; text-align: center; }")
       .append(".endpoint-method-GET { background: #61affe; } .endpoint-method-POST { background: #49cc90; } .endpoint-method-PUT { background: #fca130; } .endpoint-method-DELETE { background: #f93e3e; } .endpoint-method-PATCH { background: #50e3c2; color: #17313a; }")
       .append(".endpoint-path { font-family: 'Fira Code', 'Courier New', monospace; font-weight: 600; color: #303133; font-size: 0.95rem; flex: 1; }")
       .append(".endpoint-name { margin-left: auto; font-size: 0.9rem; color: #768699; font-weight: 500; }")
       .append(".endpoint-body { padding: 1.5rem; display: none; }")
       .append(".endpoint.open .endpoint-body { display: block; }")
       .append(".endpoint-content h3 { font-size: 1rem; margin-bottom: 1rem; color: #213547; }")
       .append(".endpoint-description { color: #556575; margin-bottom: 1.5rem; font-size: 0.95rem; line-height: 1.6; }")
       .append(".endpoint-details { display: grid; grid-template-columns: 1fr 1fr; gap: 2rem; margin-bottom: 1.5rem; }")
       .append(".endpoint-details h4 { font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.08em; color: #768699; margin-bottom: 0.75rem; font-weight: 700; }")
       .append(".endpoint-details ul { list-style: none; }")
       .append(".endpoint-details li { padding: 0.35rem 0; color: #556575; font-size: 0.9rem; }")
       .append(".endpoint-details code { background: #edf2f7; color: #2f3b52; padding: 0.15rem 0.35rem; border-radius: 4px; font-family: 'Fira Code', 'Courier New', monospace; }")
       .append(".endpoint-section { margin-top: 1.5rem; }")
       .append(".endpoint-section h4 { font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.08em; color: #768699; margin-bottom: 0.75rem; font-weight: 700; }")
       .append(".global-settings, .try-it-out, .test-result { background: #fff; border: 1px solid #e0e4e8; border-radius: 8px; }")
       .append(".global-settings { padding: 1.25rem 1.35rem; margin-bottom: 1.5rem; }")
       .append(".try-it-out { margin-top: 1.4rem; padding: 1.15rem 1.2rem; background: linear-gradient(180deg, #ffffff, #fbfcfe); }")
       .append(".test-input { width: 100%; padding: 0.65rem 0.75rem; border: 1px solid #d3dae6; border-radius: 8px; margin-bottom: 0.65rem; font-family: inherit; box-sizing: border-box; background: #fff; color: #1f2937; }")
       .append(".test-input:focus { outline: none; border-color: #61affe; box-shadow: 0 0 0 3px rgba(97,174,254,0.16); }")
       .append(".request-body-input { min-height: 170px; resize: vertical; font-family: 'Fira Code', 'Courier New', monospace; line-height: 1.5; }")
       .append(".execute-btn { background: linear-gradient(180deg, #61affe, #4990e2); color: #fff; padding: 0.7rem 1.05rem; margin-top: 0.8rem; border: 0; border-radius: 8px; cursor: pointer; font-weight: 700; }")
       .append(".execute-btn:hover { transform: translateY(-1px); box-shadow: 0 8px 18px rgba(73,144,226,.24); }")
       .append(".test-result { margin-top: 1rem; padding: 1rem; background: #f8fafc; display: none; }")
       .append(".result-header { font-weight: 800; margin-bottom: 0.75rem; color: #213547; }")
       .append(".utility-btn { appearance: none; border: 1px solid #cfd7e3; border-radius: 8px; cursor: pointer; font-weight: 700; background: #fff; color: #334155; padding: 0.5rem 0.75rem; font-size: 0.82rem; }")
       .append(".utility-btn:hover { background: #f8fbff; border-color: #aab8cf; }")
       .append(".body-editor { margin-top: 1rem; }")
       .append(".body-editor__header { display: flex; align-items: center; justify-content: space-between; gap: 0.8rem; margin-bottom: 0.6rem; }")
       .append(".body-actions { display: flex; gap: 0.5rem; flex-wrap: wrap; }")
       .append("pre { background: #1f2937; color: #e5e7eb; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.82rem; margin-top: 0.5rem; line-height: 1.5; }")
       .append("pre.json-rendered { font-size: 0.74rem; line-height: 1.6; }")
       .append(".json-key { color: #9cdcfe; }")
       .append(".json-string { color: #ce9178; }")
       .append(".json-number { color: #b5cea8; }")
       .append(".json-boolean { color: #569cd6; }")
       .append(".json-null { color: #569cd6; font-style: italic; }")
       .append(".json-punctuation { color: #d4d4d4; }")
       .append("@media (max-width: 768px) { .page-container { flex-direction: column; } .sidebar { width: 100%; height: auto; border-right: none; border-bottom: 1px solid #e0e4e8; } .main-content { padding: 2rem 1rem; } }")
       .append("</style></head><body><div class='page-container'>");

     html.append("<nav class='sidebar'>");
     html.append("<h1>API Reference</h1>");
     grouped.forEach((group, routes) -> {
       html.append("<div class='sidebar-group'>");
       html.append("<div class='sidebar-group-title' onclick='toggleGroup(this)'>")
         .append(escapeForHtml(group))
         .append("</div>");
       html.append("<ul class='sidebar-endpoints'>");
       for (DocumentationRegistrant.RegisteredRoute route : routes) {
         String routeId = routeAnchorIds.get(route);
         html.append("<li class='sidebar-endpoint'><a href='#")
           .append(routeId)
           .append("' data-route-id='")
           .append(routeId)
           .append("' onclick='navigateToRoute(event, \"")
           .append(routeId)
           .append("\")'>")
           .append(escapeForHtml(route.description().name()))
           .append("</a></li>");
       }
       html.append("</ul>");
       html.append("</div>");
     });
     html.append("</nav>");

     html.append("<div class='main-content'>");
     html.append("<div class='content-header'>");
     html.append("<h1>API Reference</h1>");
     html.append("<p>Complete API documentation and reference</p>");
     html.append("</div>");

     if (!isProduction && !availableHttpClients.isEmpty()) {
       html.append("<section class='global-settings'>");
       html.append("<h2>HTTP Client Settings</h2>");
       html.append("<p>Select an HTTP client to see its usage snippet and configure parameters:</p>");
       html.append("<select id='client-select' onchange='updateClient()' style='padding: 0.5rem; width: 100%; max-width: 320px; margin: 0.75rem 0 1rem; border:1px solid #cfd7e3; border-radius:8px;'>");
       for (HttpClient client : availableHttpClients) {
         String clientVal = normalizeClientName(client.getName());
         html.append("<option value='").append(clientVal).append("'")
           .append(clientVal.equals(defaultClientId) ? " selected" : "")
           .append(">")
           .append(escapeForHtml(client.getName()))
           .append("</option>");
       }
       html.append("</select>");

       for (HttpClient client : availableHttpClients) {
         String clientId = normalizeClientName(client.getName());
         html.append("<div id='snippet-").append(clientId).append("' class='client-snippet' style='display: none;'>");
         html.append("<p><strong>Description:</strong> ").append(escapeForHtml(client.getDescription())).append("</p>");
         if (!client.getParameters().isEmpty()) {
           html.append("<h4>Parameters</h4>");
           html.append("<div style='margin-bottom: 1rem; display: flex; flex-wrap: wrap; gap: 1rem;'>");
           for (HttpClientParameter param : client.getParameters()) {
             html.append("<div style='min-width: 200px;'>");
             html.append("<label style='display: block; font-size: 0.8rem; color: #606266;'>").append(escapeForHtml(param.label())).append(":</label>");
             html.append("<input type='").append(escapeForHtml(param.type())).append("' class='client-param' data-param='").append(escapeForHtml(param.name())).append("' value='").append(escapeForHtml(param.defaultValue())).append("' oninput='updateClientSnippet(\"").append(clientId).append("\")' style='padding: 0.4rem; width: 100%; border: 1px solid #dcdfe6; border-radius: 4px;'>");
             html.append("</div>");
           }
           html.append("</div>");
         }

         html.append("</div>");
       }
       html.append("</section>");
     }

     grouped.forEach((group, routes) -> {
       html.append("<div class='section'>");
       html.append("<div class='section-header'>");
       html.append("<h2>").append(escapeForHtml(group)).append("</h2>");
       html.append("<p class='section-description'>")
         .append(escapeForHtml(registrant.getGroupDescription(group)))
         .append("</p>");
       html.append("</div>");

       for (DocumentationRegistrant.RegisteredRoute route : routes) {
         String routeId = routeAnchorIds.get(route);
         html.append("<div class='endpoint' id='")
           .append(routeId)
           .append("'>");

         html.append("<div class='endpoint-header' onclick='toggleEndpoint(this.parentElement)'>");
         html.append("<span class='endpoint-method endpoint-method-")
           .append(route.method())
           .append("'>")
           .append(route.method())
           .append("</span>");
         html.append("<span class='endpoint-path'>")
           .append(escapeForHtml(route.path()))
           .append("</span>");
         html.append("<span class='endpoint-name'>")
           .append(escapeForHtml(route.description().name()))
           .append("</span>");
         html.append("</div>");

         html.append("<div class='endpoint-body'>");
         html.append("<div class='endpoint-content'>");
         html.append("<p class='endpoint-description'>")
           .append(escapeForHtml(route.description().description()))
           .append("</p>");

         html.append("<div class='endpoint-details'>");

         html.append("<div>");
         html.append("<h4>Details</h4>");
         html.append("<ul>");
         html.append("<li><strong>Authentication:</strong> ");
         if (route.description().authenticationRequired()) {
           html.append("Required (")
             .append(escapeForHtml(route.description().authenticationComment() != null
               ? route.description().authenticationComment() : "Required"))
             .append(")");
         } else {
           html.append("Not Required (")
             .append(escapeForHtml(route.description().authenticationComment() != null
               ? route.description().authenticationComment() : "Public"))
             .append(")");
         }
         html.append("</li>");
         if (route.description().rateLimit() != null) {
           html.append("<li><strong>Rate Limit:</strong> ")
             .append(route.description().rateLimit())
             .append(" req/min</li>");
         }
         html.append("</ul>");
         html.append("</div>");

         html.append("<div>");
         if (route.description().pathParameters() != null && !route.description().pathParameters().isEmpty()) {
           html.append("<h4>Path Parameters</h4>");
           html.append("<ul>");
           route.description().pathParameters().forEach((k, v) ->
             html.append("<li><code>").append(escapeForHtml(k)).append("</code>: ")
               .append(escapeForHtml(v)).append("</li>")
           );
           html.append("</ul>");
         }
         html.append("</div>");

         html.append("</div>");

         if (route.description().queryParameters() != null && !route.description().queryParameters().isEmpty()) {
           html.append("<div class='endpoint-section'>");
           html.append("<h4>Query Parameters</h4>");
           html.append("<ul>");
           route.description().queryParameters().forEach((k, v) -> {
             html.append("<li><code>").append(escapeForHtml(k)).append("</code>: ")
               .append(escapeForHtml(v));
             if (route.description().queryParameterDefaults() != null && route.description().queryParameterDefaults().containsKey(k)) {
               html.append(" <em>(Default: ")
                 .append(escapeForHtml(route.description().queryParameterDefaults().get(k)))
                 .append(")</em>");
             }
             html.append("</li>");
           });
           html.append("</ul>");
           html.append("</div>");
         }

         if (!route.description().headers().isEmpty()) {
           html.append("<div class='endpoint-section'>");
           html.append("<h4>Headers</h4>");
           html.append("<ul>");
           route.description().headers().forEach((k, v) -> html.append("<li><code>")
             .append(escapeForHtml(k)).append("</code>: ").append(escapeForHtml(v)).append("</li>"));
           html.append("</ul>");
           html.append("</div>");
         }

         if (route.description().requestBodyClass() != null) {
           html.append("<div class='endpoint-section'>");
           html.append("<h4>Request Body Example</h4>");
           appendExampleDisplay(html, route.description().requestBodyClass());
           html.append("</div>");
         }

         if (route.description().responseDTOs() != null && !route.description().responseDTOs().isEmpty()) {
           html.append("<div class='endpoint-section'>");
           html.append("<h4>Response Examples</h4>");
           route.description().responseDTOs().forEach((code, dto) -> {
             String typeName = dto instanceof RouteDescription.ResponseCasing rc
               ? rc.innerTypeName() : dto.getClass().getSimpleName();
             html.append("<div style='margin-bottom: 1rem;'><strong>Status ")
               .append(code)
               .append(" (")
               .append(escapeForHtml(typeName))
               .append(")</strong>");
             appendExampleDisplay(html, dto);
             html.append("</div>");
           });
           html.append("</div>");
         }

         html.append("<div class='try-it-out'>");
         html.append("<h3>Try it out</h3>");

         if (route.description().pathParameters() != null && !route.description().pathParameters().isEmpty()) {
           html.append("<h4>Path Parameters</h4>");
           for (Map.Entry<String, String> entry : route.description().pathParameters().entrySet()) {
             String defaultValue = route.description().pathParameterDefaults() != null
               ? route.description().pathParameterDefaults().getOrDefault(entry.getKey(), "")
               : "";
             html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>")
               .append(escapeForHtml(entry.getKey())).append(" (").append(escapeForHtml(entry.getValue())).append("):</label>");
             html.append("<input type='text' class='test-input path-param-").append(routeId)
               .append("' data-param='").append(escapeForHtml(entry.getKey()))
               .append("' value='").append(escapeForHtml(defaultValue)).append("'>");
           }
         }

         if (route.description().queryParameters() != null && !route.description().queryParameters().isEmpty()) {
           html.append("<h4>Query Parameters</h4>");
           for (Map.Entry<String, String> entry : route.description().queryParameters().entrySet()) {
             String defaultValue = route.description().queryParameterDefaults() != null
               ? route.description().queryParameterDefaults().getOrDefault(entry.getKey(), "")
               : "";
             html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>")
               .append(escapeForHtml(entry.getKey())).append(" (").append(escapeForHtml(entry.getValue())).append("):</label>");
             html.append("<input type='text' class='test-input query-param-").append(routeId)
               .append("' data-param='").append(escapeForHtml(entry.getKey()))
               .append("' value='").append(escapeForHtml(defaultValue)).append("'>");
           }
         }

         if (route.description().fileParameters() != null && !route.description().fileParameters().isEmpty()) {
           html.append("<h4>Files</h4>");
           for (Map.Entry<String, RouteDescription.FileParameter> entry : route.description().fileParameters().entrySet()) {
             RouteDescription.FileParameter fileParameter = entry.getValue();
             Integer maxFiles = fileParameter.limit() != null ? fileParameter.limit() : 1;
             boolean isMultiple = maxFiles > 1;
             String acceptedExtensions = fileParameter.type().allowedExtensionsExample();
             boolean hasExplicitAccept = acceptedExtensions.contains(".");

             html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>")
               .append(escapeForHtml(fileParameter.name()))
               .append(" (").append(escapeForHtml(fileParameter.description())).append(")")
               .append(" - ").append(escapeForHtml(fileParameter.type().name()))
               .append(" [").append(escapeForHtml(acceptedExtensions)).append("]")
               .append("</label>");
             html.append("<input type='file' class='test-input file-input-").append(routeId)
               .append("' data-param='").append(escapeForHtml(fileParameter.name()))
               .append("' data-limit='").append(maxFiles).append("'")
               .append(hasExplicitAccept ? " accept='" + escapeForHtmlAttribute(acceptedExtensions) + "'" : "")
               .append(isMultiple ? " multiple" : "")
               .append(">");
           }
         }

         html.append("<h4>Headers</h4>");
         html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>X-Correlation-ID:</label>");
         html.append("<input type='text' class='test-input header-input-").append(routeId)
           .append("' data-header='X-Correlation-ID' value='").append(UUID.randomUUID()).append("'>");

         for (Map.Entry<String, String> entry : route.description().headers().entrySet()) {
           html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>")
             .append(escapeForHtml(entry.getKey())).append(":</label>");
           html.append("<input type='text' class='test-input header-input-").append(routeId)
             .append("' data-header='").append(escapeForHtml(entry.getKey()))
             .append("' value='").append(escapeForHtml(entry.getValue())).append("'>");
         }

         if ("POST".equals(route.method()) || "PUT".equals(route.method()) || "PATCH".equals(route.method())) {
           Map<String, String> bodyExamples = getExamples(route.description().requestBodyClass());
           String requestBodyExample = bodyExamples.isEmpty() ? "" : bodyExamples.values().iterator().next();

           html.append("<div class='body-editor'>");
           html.append("<div class='body-editor__header'>");
           html.append("<h4>Request Body</h4>");
           html.append("<div class='body-actions'>");
           html.append("<button type='button' class='utility-btn' onclick='copyRequestBody(\"").append(routeId).append("\", this)'>Copy body</button>");
           html.append("<button type='button' class='utility-btn' onclick='resetRequestBody(\"").append(routeId).append("\", this)'>Reset example</button>");
           html.append("</div></div>");
           html.append("<textarea id='body-").append(routeId).append("' class='test-input request-body-input' data-example=\"")
             .append(escapeForHtmlAttribute(requestBodyExample))
             .append("\">")
             .append(escapeForHtml(requestBodyExample))
             .append("</textarea>");
           html.append("</div>");
         }

         html.append("<button class='execute-btn' onclick='executeTest(\"").append(route.method())
           .append("\", \"").append(route.path()).append("\", \"").append(routeId)
           .append("\")'>Execute</button>");
         html.append("<div id='result-").append(routeId).append("' class='test-result'></div>");
         html.append("</div>");

         html.append("</div>");
         html.append("</div>");
         html.append("</div>");
       }
       html.append("</div>");
     });

     html.append("</div></div>");
     html.append("<script>");
     html.append("const DEFAULT_CLIENT_ID = '").append(defaultClientId).append("';");
     html.append("const IS_PRODUCTION = ").append(Boolean.toString(isProduction)).append(";");
     html.append("function delay(ms) { return new Promise(resolve => window.setTimeout(resolve, ms)); }");
     html.append("function getRequestBodyTextarea(routeId) { return document.getElementById('body-' + routeId); }");
     html.append("function flashButton(button, label) { if (!button) return; const original = button.textContent; button.textContent = label; window.setTimeout(() => { button.textContent = original; }, 1100); }");
     html.append("async function copyTextToClipboard(text) { const value = text == null ? '' : String(text); try { if (navigator.clipboard && window.isSecureContext) { await navigator.clipboard.writeText(value); return true; } } catch (error) {} const fallback = document.createElement('textarea'); fallback.value = value; fallback.setAttribute('readonly', ''); fallback.style.position = 'fixed'; fallback.style.left = '-9999px'; fallback.style.top = '-9999px'; fallback.style.opacity = '0'; document.body.appendChild(fallback); fallback.focus(); fallback.select(); let copied = false; try { copied = document.execCommand('copy'); } catch (error) { copied = false; } finally { document.body.removeChild(fallback); } return copied; }");
     html.append("async function copyRequestBody(routeId, button) { const textarea = getRequestBodyTextarea(routeId); if (!textarea) return; const copied = await copyTextToClipboard(textarea.value || ''); if (copied) { flashButton(button, 'Copied'); } }");
     html.append("function resetRequestBody(routeId, button) { const textarea = getRequestBodyTextarea(routeId); if (!textarea) return; textarea.value = textarea.dataset.example || ''; flashButton(button, 'Reset'); }");
     html.append("function headersToObject(headers) { const result = {}; headers.forEach((value, key) => { result[key] = value; }); return result; }");
     html.append("function normalizeBody(body) { if (body == null) { return ''; } if (typeof FormData !== 'undefined' && body instanceof FormData) { return body; } if (typeof body === 'string') { return body; } return JSON.stringify(body); }");
     html.append("function escapeHtml(value) { return String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\"/g, '&quot;').replace(/'/g, '&#39;'); }");
     html.append("function parseJsonIfPossible(value) { if (typeof value !== 'string') { return value; } const trimmed = value.trim(); if (trimmed === '') { return null; } try { return JSON.parse(trimmed); } catch (error) { return value; } }");
     html.append("function normalizeStructuredValue(value) { if (value == null || value === '') { return { json: false, text: '' }; } if (typeof value === 'string') { const trimmed = value.trim(); if (trimmed === '') { return { json: false, text: value }; } try { return { json: true, value: JSON.parse(trimmed) }; } catch (error) { return { json: false, text: value }; } } if (typeof value === 'object') { return { json: true, value }; } return { json: false, text: String(value) }; }");
     html.append("function jsonIndent(level) { return '  '.repeat(level); }");
     html.append("function jsonPunctuation(text) { return '<span class=\"json-punctuation\">' + escapeHtml(text) + '</span>'; }");
     html.append("function renderJsonPrimitive(value) { if (value === null) { return '<span class=\"json-null\">null</span>'; } if (typeof value === 'string') { return '<span class=\"json-string\">' + escapeHtml(JSON.stringify(value)) + '</span>'; } if (typeof value === 'number') { return '<span class=\"json-number\">' + escapeHtml(String(value)) + '</span>'; } if (typeof value === 'boolean') { return '<span class=\"json-boolean\">' + escapeHtml(String(value)) + '</span>'; } return '<span class=\"json-string\">' + escapeHtml(JSON.stringify(String(value))) + '</span>'; }");
     html.append("function renderJsonValue(value, depth) { if (Array.isArray(value)) { if (value.length === 0) { return jsonPunctuation('[]'); } const lines = value.map(item => jsonIndent(depth + 1) + renderJsonValue(item, depth + 1)); return jsonPunctuation('[') + '\\n' + lines.join(jsonPunctuation(',') + '\\n') + '\\n' + jsonIndent(depth) + jsonPunctuation(']'); } if (value && typeof value === 'object') { const entries = Object.entries(value); if (entries.length === 0) { return jsonPunctuation('{}'); } const lines = entries.map(([key, innerValue]) => jsonIndent(depth + 1) + '<span class=\"json-key\">' + escapeHtml(JSON.stringify(key)) + '</span>' + jsonPunctuation(': ') + renderJsonValue(innerValue, depth + 1)); return jsonPunctuation('{') + '\\n' + lines.join(jsonPunctuation(',') + '\\n') + '\\n' + jsonIndent(depth) + jsonPunctuation('}'); } return renderJsonPrimitive(value); }");
     html.append("function renderStructuredContent(value) { const normalized = normalizeStructuredValue(value); return normalized.json ? renderJsonValue(normalized.value, 0) : escapeHtml(normalized.text); }");
     html.append("function decorateJsonBlocks(selector) { document.querySelectorAll(selector).forEach(pre => { pre.innerHTML = renderStructuredContent(pre.textContent || ''); pre.classList.add('json-rendered'); }); }");
     html.append("function getSelectedClientName() { const clientSelect = document.getElementById('client-select'); return clientSelect ? clientSelect.value : DEFAULT_CLIENT_ID; }");
     html.append("function getClientParameters(clientId) { const snippetDiv = document.getElementById('snippet-' + clientId); const clientParams = {}; if (!snippetDiv) { return clientParams; } snippetDiv.querySelectorAll('.client-param').forEach(input => { clientParams[input.dataset.param] = input.value; }); return clientParams; }");
     html.append("function createFetchOptions(method, headers, requestBody) { const normalizedHeaders = { ...headers }; const isFormData = typeof FormData !== 'undefined' && requestBody instanceof FormData; const hasBody = requestBody != null && requestBody !== ''; if (isFormData) { delete normalizedHeaders['Content-Type']; } else if (hasBody && method !== 'GET' && method !== 'DELETE' && !normalizedHeaders['Content-Type']) { normalizedHeaders['Content-Type'] = 'application/json'; } return { method, headers: normalizedHeaders, body: method === 'GET' || method === 'DELETE' ? undefined : requestBody }; }");
     html.append("async function executeClassicClient(request) { const response = await fetch(request.url, createFetchOptions(request.method, request.headers, request.body)); return { statusCode: response.status, headers: headersToObject(response.headers), body: await response.text() }; }");
     html.append("async function executeThrottledClient(request) { const probability = Number.parseFloat(request.parameters.throttleProbability ?? '0.1'); const latencyExponent = Number.parseFloat(request.parameters.latencyExponent ?? '2.0'); if (Math.random() < probability) { const delayMs = Math.pow(Math.random() * 10, latencyExponent); await delay(delayMs); } return executeClassicClient(request); }");
     html.append("async function executeBurstClient(request) { const parallelClientCount = Number.parseInt(request.parameters.parallelClientCount ?? '5', 10); const requestsPerClient = Number.parseInt(request.parameters.requestsPerClient ?? '100', 10); const executions = []; for (let clientIndex = 0; clientIndex < parallelClientCount; clientIndex++) { for (let requestIndex = 0; requestIndex < requestsPerClient; requestIndex++) { executions.push(executeClassicClient(request).then(response => ({ response, ok: response.statusCode < 400 })).catch(error => ({ ok: false, error: error instanceof Error ? error.message : String(error) }))); } } const settled = await Promise.all(executions); const succeeded = settled.filter(result => result.ok).length; const firstResponse = settled.find(result => result.response)?.response ?? null; return { statusCode: firstResponse?.statusCode ?? 200, headers: firstResponse?.headers ?? {}, body: JSON.stringify({ summary: 'Burst complete.', totalRequests: settled.length, succeeded, failed: settled.length - succeeded, sampleResponseBody: firstResponse?.body ?? null }, null, 2) }; }");
     html.append("const clientActors = { 'classic-http-client': executeClassicClient, 'throttled-http-client': executeThrottledClient, 'burst-http-client': executeBurstClient };");
     html.append("function updateClient() { const select = document.getElementById('client-select'); const snippets = document.querySelectorAll('.client-snippet'); snippets.forEach(snippet => { snippet.style.display = 'none'; }); if (select && select.value) { const selectedSnippet = document.getElementById('snippet-' + select.value); if (selectedSnippet) { selectedSnippet.style.display = 'block'; } updateClientSnippet(select.value); } }");
     html.append("function updateClientSnippet(clientId) { const snippetDiv = document.getElementById('snippet-' + clientId); if (!snippetDiv) return; const pre = document.getElementById('pre-' + clientId); if (!pre) return; let content = pre.getAttribute('data-template'); const inputs = snippetDiv.querySelectorAll('.client-param'); inputs.forEach(input => { const paramName = input.getAttribute('data-param'); const value = input.value || input.placeholder; content = content.split('{{' + paramName + '}}').join(value); }); pre.textContent = content; }");
     html.append("async function executeTest(method, path, routeId) { const clientName = getSelectedClientName(); const actor = clientActors[clientName] ?? clientActors[DEFAULT_CLIENT_ID]; if (!actor) { alert('No HTTP client is available for this environment.'); return; } let finalUrl = path.startsWith('http://') || path.startsWith('https://') ? path : window.location.origin + path; const params = {}; document.querySelectorAll('.path-param-' + routeId).forEach(input => { params[input.dataset.param] = input.value; finalUrl = finalUrl.split(':' + input.dataset.param).join(input.value); }); const qpArray = []; document.querySelectorAll('.query-param-' + routeId).forEach(input => { params[input.dataset.param] = input.value; if (input.value) { qpArray.push(encodeURIComponent(input.dataset.param) + '=' + encodeURIComponent(input.value)); } }); if (qpArray.length > 0) { finalUrl += (finalUrl.includes('?') ? '&' : '?') + qpArray.join('&'); } const headers = {}; document.querySelectorAll('.header-input-' + routeId).forEach(input => { if (input.value) { headers[input.dataset.header] = input.value; } }); const bodyInput = document.getElementById('body-' + routeId); let requestBody = bodyInput ? bodyInput.value : null; if (requestBody) { for (const key in params) { requestBody = requestBody.split('{{' + key + '}}').join(params[key]); requestBody = requestBody.split(':' + key).join(params[key]); } } const fileInputs = document.querySelectorAll('.file-input-' + routeId); if (fileInputs.length > 0) { const formData = new FormData(); const parsedRequestBody = parseJsonIfPossible(requestBody); formData.append('body', JSON.stringify(parsedRequestBody)); fileInputs.forEach(input => { const files = input.files ? Array.from(input.files) : []; if (files.length === 0) { return; } const limit = Number.parseInt(input.dataset.limit || '1', 10); if (!Number.isNaN(limit) && limit > 0 && files.length > limit) { throw new Error('File parameter \"' + input.dataset.param + '\" allows at most ' + limit + ' file(s).'); } files.forEach(file => formData.append(input.dataset.param, file)); }); requestBody = formData; } const resultDiv = document.getElementById('result-' + routeId); resultDiv.style.display = 'block'; resultDiv.innerHTML = '<div class=\"result-header\">Executing...</div>'; try { const data = await actor({ method, url: finalUrl, headers, body: normalizeBody(requestBody), parameters: getClientParameters(clientName) }); let resultHtml = '<div class=\"result-header\">Status: ' + data.statusCode + '</div>'; resultHtml += '<h4>Response Headers</h4><pre class=\"json-rendered test-result-code\" style=\"background:#272822; color:#f8f8f2; padding:1rem; border-radius:6px; overflow-x:auto;\">' + renderStructuredContent(data.headers ?? {}) + '</pre>'; resultHtml += '<h4>Response Body</h4><pre class=\"json-rendered test-result-code\" style=\"background:#272822; color:#f8f8f2; padding:1rem; border-radius:6px; overflow-x:auto;\">' + renderStructuredContent(data.body) + '</pre>'; resultDiv.innerHTML = resultHtml; } catch (error) { const message = error instanceof Error ? error.message : String(error); resultDiv.innerHTML = '<div style=\"color: red;\">Error: ' + escapeHtml(message) + '</div>'; } }");
     html.append("function toggleEndpoint(element) { element.classList.toggle('open'); }");
     html.append("function toggleGroup(element) { const list = element.nextElementSibling; if (list) list.style.display = list.style.display === 'none' ? 'block' : 'none'; }");
     html.append("function setActiveSidebarLink(routeId) { document.querySelectorAll('.sidebar-endpoint a').forEach(link => { link.classList.toggle('active', link.dataset.routeId === routeId); }); }");
     html.append("function openRouteByHash(hash, smooth) { const routeId = (hash || '').replace(/^#/, ''); if (!routeId) return; const route = document.getElementById(routeId); if (!route) return; route.classList.add('open'); setActiveSidebarLink(routeId); route.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', block: 'start' }); }");
     html.append("function navigateToRoute(event, routeId) { if (event) event.preventDefault(); if (!routeId) return; if (window.location.hash === '#' + routeId) { openRouteByHash('#' + routeId, true); return; } window.location.hash = routeId; }");
     html.append("window.addEventListener('hashchange', function() { openRouteByHash(window.location.hash, true); });");
     html.append("window.addEventListener('DOMContentLoaded', function() { if (!IS_PRODUCTION) { updateClient(); } decorateJsonBlocks('pre[id^=\"ex-\"]'); if (window.location.hash) { openRouteByHash(window.location.hash, false); } });");
     html.append("</script>");
     html.append("</body></html>");

     return html.toString();
   }

   private Map<DocumentationRegistrant.RegisteredRoute, String> buildRouteAnchorIds(
     Map<String, List<DocumentationRegistrant.RegisteredRoute>> grouped) {
     Map<DocumentationRegistrant.RegisteredRoute, String> routeAnchorIds = new LinkedHashMap<>();
     int sequence = 1;

     for (Map.Entry<String, List<DocumentationRegistrant.RegisteredRoute>> entry : grouped.entrySet()) {
       String groupIdSegment = toFragmentIdSegment(entry.getKey());
       for (DocumentationRegistrant.RegisteredRoute route : entry.getValue()) {
         String routeName = route.description() != null ? route.description().name() : route.path();
         String routeId = "route-" + groupIdSegment + "-" + toFragmentIdSegment(routeName) + "-" + sequence++;
         routeAnchorIds.put(route, routeId);
       }
     }

     return routeAnchorIds;
   }

   private String toFragmentIdSegment(String value) {
     if (value == null || value.isBlank()) {
       return "item";
     }

     return value.toLowerCase(Locale.ROOT)
       .replaceAll("[^a-z0-9]+", "-")
       .replaceAll("^-+|-+$", "")
       .replaceAll("-{2,}", "-");
   }

   private String escapeForHtml(String value) {
    if (value == null) {
      return "";
    }

    return value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }

  private String escapeForHtmlAttribute(String value) {
    return escapeForHtml(value);
  }

  private String escapeForJsSingleQuotedString(String value) {
    if (value == null) {
      return "";
    }

    return value
      .replace("\\", "\\\\")
      .replace("'", "\\'");
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

  private Map<String, List<DocumentationRegistrant.RegisteredRoute>> getGroupedRoutes(DocumentationRegistrant registrant) {
    if (cachedGroupedRoutes != null) {
      return cachedGroupedRoutes;
    }

    Map<String, List<DocumentationRegistrant.RegisteredRoute>> grouped = new TreeMap<>();
    List<DocumentationRegistrant.RegisteredRoute> routes = new ArrayList<>(registrant.getRegisteredRoutes());

    // Sort routes by path to have a consistent order within groups
    routes.sort(Comparator.comparing(DocumentationRegistrant.RegisteredRoute::path)
      .thenComparing(DocumentationRegistrant.RegisteredRoute::method));

    for (DocumentationRegistrant.RegisteredRoute route : routes) {
      if ("DocumentationController".equals(route.controllerClass())) {
        continue;
      }

      List<DocumentationRegistrant.RegisteredRoute> groupRoutes = grouped.computeIfAbsent(route.description().group(), ignored -> new ArrayList<>());
      groupRoutes.add(route);
    }

    cachedGroupedRoutes = grouped;
    return cachedGroupedRoutes;
  }

   private void appendExampleDisplay(StringBuilder html, DocumentableDTO dto) {
     if (dto == null) return;
     String sectionId = "ex-" + UUID.randomUUID().toString().substring(0, 8);
     try {
       String example = DocumentationJsonFormatter.formatJsonIfPossible(dto.toExample());
       html.append("<pre id='").append(sectionId).append("-0' class='").append(sectionId).append("-item json-rendered' style='white-space: pre-wrap; word-wrap: break-word;'>")
         .append(escapeForHtml(example))
         .append("</pre>");
     } catch (Exception e) {
       html.append("<pre>Could not generate example: ").append(escapeForHtml(e.getMessage())).append("</pre>");
     }
   }

   private void appendExampleDisplay(StringBuilder html, Class<?> dtoClass) {
     if (dtoClass == null) return;
     html.append("<h4>").append("Request Body").append(" (").append(dtoClass.getSimpleName()).append(")</h4>");
     Map<String, String> examples = getExamples(dtoClass);
     String sectionId = "ex-" + UUID.randomUUID().toString().substring(0, 8);

     if (examples.size() > 1) {
       html.append("<div style='margin-bottom: 0.5rem;'>");
       html.append("<label style='font-size: 0.8rem; color: #606266; margin-right: 0.5rem;'>Select Example:</label>");
       html.append("<select onchange='updateExample(\"").append(sectionId).append("\", this.value)' style='padding: 0.2rem; font-size: 0.8rem; border-radius: 4px; border: 1px solid #dcdfe6;'>");
       int optIndex = 0;
       for (String name : examples.keySet()) {
         html.append("<option value='").append(optIndex).append("'>").append(name).append("</option>");
         optIndex++;
       }
       html.append("</select></div>");
     }

     int index = 0;
     for (Map.Entry<String, String> entry : examples.entrySet()) {
       html.append("<pre id='").append(sectionId).append("-").append(index).append("' class='").append(sectionId).append("-item json-rendered' style='").append(index == 0 ? "" : "display: none;").append(" white-space: pre-wrap; word-wrap: break-word;'>")
         .append(escapeForHtml(entry.getValue()))
         .append("</pre>");
       index++;
     }
   }

  private Map<String, String> getExamples(Class<?> dtoClass) {
    Map<String, String> examples = new LinkedHashMap<>();
    if (dtoClass == null) return examples;

    // 1. Look for fields with @ResponseExample
    try {
      for (Field field : dtoClass.getDeclaredFields()) {
        if (field.isAnnotationPresent(ResponseExample.class)) {
          if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
            try {
              field.setAccessible(true);
              Object value = field.get(null);
              String name = field.getAnnotation(ResponseExample.class).value();

              //? Okay then.
              if (value instanceof DocumentableDTO documentableDTO) {
                examples.put(name, DocumentationJsonFormatter.formatJsonIfPossible(documentableDTO.toExample()));
              } else {
                examples.put(name, DocumentationJsonFormatter.formatJsonIfPossible(
                  mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                ));
              }
            } catch (Exception e) {
              examples.put("Error (" + field.getName() + ")", "Could not generate example: " + e.getMessage());
            }
          }
        }
      }

      if (!examples.isEmpty()) {
        return examples;
      }
    } catch (NoClassDefFoundError | SecurityException e) {
      // Fallback if field reflection fails
    }

    // 2. Fallback to DocumentableDTO.toExample()
    if (DocumentableDTO.class.isAssignableFrom(dtoClass)) {
      try {
        final var instance = (DocumentableDTO) dtoClass.getDeclaredConstructor().newInstance();
        examples.putIfAbsent("Default", DocumentationJsonFormatter.formatJsonIfPossible(instance.toExample()));
      } catch (Exception e) {
        // Ignore failures for default instantiation
      }
    }

    if (examples.isEmpty()) {
      try {
        examples.put("None", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("class", dtoClass.getSimpleName(), "info", "No example available")));
      } catch (Exception e) {
        examples.put("None", "No example available");
      }
    }

    return examples;
  }
}

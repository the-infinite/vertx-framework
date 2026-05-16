package io.github.the_infinite.core.doc;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.the_infinite.core.RouteController;
import io.github.the_infinite.core.doc.impl.ClassicHttpClient;
import io.github.the_infinite.core.env.AppEnvironment;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

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
    registrant.getRouter().route().order(-1).handler(io.vertx.ext.web.handler.BodyHandler.create());
    registrant.getRouter().route().order(0).handler(context -> {
      String testClient = context.request().getHeader("X-TM30-Test-Client");
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
    String htmlContent = renderHtml(registrant);
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

  private String renderHtml(DocumentationRegistrant registrant) {
    StringBuilder html = new StringBuilder();
    boolean isProduction = isProductionEnvironment();
    List<HttpClient> availableHttpClients = getAvailableHttpClients(registrant);
    String defaultClientId = normalizeClientName(new ClassicHttpClient().getName());

    html.append("<!DOCTYPE html><html><head><title>API Documentation</title>");
    html.append("<meta name='viewport' content='width=device-width, initial-scale=1'>");
    html.append("<style>")
      .append("body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; line-height: 1.6; color: #333; max-width: 1000px; margin: 0 auto; padding: 2rem; background-color: #f4f7f6; }")
      .append("@media (max-width: 768px) { body { padding: 1rem; } .route-summary { flex-direction: column; align-items: flex-start; } .method { min-width: auto; width: 100%; margin-bottom: 0.5rem; } .name { margin-left: 0; margin-top: 0.5rem; } }")
      .append("h1, h2, h3 { color: #2c3e50; }")
      .append(".global-settings { background: #fff; padding: 1.5rem; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 2rem; }")
      .append(".controller-section { margin-bottom: 3rem; }")
      .append(".controller-name { background: #eef2f7; padding: 0.75rem 1.5rem; border-radius: 8px 8px 0 0; border: 1px solid #d1d9e6; border-bottom: none; font-size: 1.25rem; font-weight: bold; color: #34495e; }")
      .append(".route { background: #fff; border-radius: 0; box-shadow: 0 2px 4px rgba(0,0,0,0.05); margin-bottom: 0; border: 1px solid #e1e4e8; overflow: hidden; }")
      .append(".route:not(:last-child) { border-bottom: none; }")
      .append(".route:last-child { border-radius: 0 0 8px 8px; }")
      .append(".route-summary { padding: 1rem; cursor: pointer; display: flex; align-items: center; background: #fff; transition: background 0.2s; }")
      .append(".route[open] .route-summary { border-bottom: 1px solid #e1e4e8; background: #f8f9fa; }")
      .append(".method { font-size: 0.8rem; padding: 0.4rem 0.8rem; border-radius: 4px; color: #fff; font-weight: 700; min-width: 80px; text-align: center; margin-right: 1rem; text-transform: uppercase; }")
      .append(".method-GET { background-color: #61affe; }")
      .append(".method-POST { background-color: #49cc90; }")
      .append(".method-PUT { background-color: #fca130; }")
      .append(".method-DELETE { background-color: #f93e3e; }")
      .append(".method-PATCH { background-color: #50e3c2; }")
      .append(".path { font-family: 'Fira Code', 'Courier New', monospace; font-weight: 600; font-size: 1.1rem; color: #303133; word-break: break-all; }")
      .append(".name { margin-left: auto; font-size: 0.9rem; color: #909399; font-weight: 400; }")
      .append(".route-details { padding: 1.5rem; background: #fff; }")
      .append("h4 { margin-top: 1.5rem; margin-bottom: 0.5rem; color: #606266; font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; }")
      .append("pre { background-color: #272822; color: #f8f8f2; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.9rem; margin: 0; }")
      .append("code { background: #e1e4e8; padding: 0.2rem 0.4rem; border-radius: 3px; font-family: monospace; }")
      .append("ul { list-style: none; padding-left: 0; }")
      .append("li { margin-bottom: 0.5rem; }")
      .append(".try-it-out { margin-top: 2rem; border-top: 1px solid #e1e4e8; padding: 1.5rem; background: #fafafa; border-radius: 8px; border: 1px solid #eee; }")
      .append(".test-input { width: 100%; padding: 0.5rem; border: 1px solid #dcdfe6; border-radius: 4px; margin-bottom: 0.5rem; font-family: inherit; box-sizing: border-box; }")
      .append(".execute-btn { background-color: #2c3e50; color: #fff; padding: 0.6rem 1.2rem; border: none; border-radius: 4px; cursor: pointer; font-weight: bold; margin-top: 1rem; }")
      .append(".execute-btn:hover { background-color: #34495e; }")
      .append(".test-result { margin-top: 1.5rem; padding: 1rem; background: #f8f9fa; border-radius: 6px; border: 1px solid #e1e4e8; display: none; }")
      .append(".result-header { font-weight: bold; margin-bottom: 0.5rem; color: #2c3e50; }")
      .append("</style></head><body>");

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

        html.append("<h4>Usage Snippet</h4>");
        String snippet = client.getUsageSnippet();
        html.append("<pre id='pre-").append(clientId).append("' data-template=\"").append(snippet.replace("\"", "&quot;")).append("\">").append(snippet).append("</pre>");
        html.append("</div>");
      }

      html.append("</section>");
    }

    html.append("<section class='global-settings'>");
    html.append("<h2>Global Definitions</h2>");
    html.append("<p><strong>Default Rate Limit:</strong> ").append(registrant.getGlobalRateLimit() != null ? registrant.getGlobalRateLimit() + " requests" : "Unlimited").append("</p>");

    if (!registrant.getGlobalHeaders().isEmpty()) {
      html.append("<h3>Global Headers</h3><ul>");
      registrant.getGlobalHeaders().forEach((k, v) -> html.append("<li><code>").append(k).append(": ").append(v).append("</code></li>"));
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

    grouped.forEach((controller, routes) -> {
      if ("DocumentationController".equals(controller))
        return;
      html.append("<div class='controller-section'>");
      html.append("<div class='controller-name'>").append(controller).append("</div>");

      for (DocumentationRegistrant.RegisteredRoute route : routes) {
        html.append("<details class='route'>");
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

        html.append("<h4>Headers</h4>");
        html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>X-Correlation-ID:</label>");
        html.append("<input type='text' class='test-input header-input-").append(routeId).append("' data-header='X-Correlation-ID' value='").append(UUID.randomUUID()).append("'>");

        for (Map.Entry<String, String> entry : route.description().headers().entrySet()) {
          html.append("<label style='display:block; font-size:0.8rem; margin-bottom:0.2rem;'>").append(entry.getKey()).append(":</label>");
          html.append("<input type='text' class='test-input header-input-").append(routeId).append("' data-header='").append(entry.getKey()).append("' value='").append(entry.getValue()).append("'>");
        }

        if ("POST".equals(route.method()) || "PUT".equals(route.method()) || "PATCH".equals(route.method())) {
          html.append("<h4>Request Body</h4>");
          html.append("<textarea id='body-").append(routeId).append("' class='test-input' style='height: 150px; font-family: monospace;'>");
          Map<String, String> bodyExamples = getExamples(route.description().requestBodyClass());
          if (!bodyExamples.isEmpty()) {
            html.append(bodyExamples.values().iterator().next());
          }
          html.append("</textarea>");
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

      function delay(ms) {
        return new Promise(resolve => window.setTimeout(resolve, ms));
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

        if (typeof body === 'string') {
          return body;
        }

        return JSON.stringify(body);
      }

      function formatResponseBody(body) {
        if (body == null || body === '') {
          return '';
        }

        if (typeof body === 'string') {
          try {
            return JSON.stringify(JSON.parse(body), null, 2);
          } catch (error) {
            return body;
          }
        }

        return JSON.stringify(body, null, 2);
      }

      function getSelectedClientName() {
        const clientSelect = document.getElementById('client-select');
        return clientSelect ? clientSelect.value : DEFAULT_CLIENT_ID;
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
        const hasBody = requestBody != null && requestBody !== '';

        if (hasBody && method !== 'GET' && method !== 'DELETE' && !normalizedHeaders['Content-Type']) {
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

        let finalUrl = window.location.origin + path;
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
          resultHtml += '<h4>Response Headers</h4><pre style="background:#272822; color:#f8f8f2; padding:1rem; border-radius:6px; overflow-x:auto;">' + JSON.stringify(data.headers ?? {}, null, 2) + '</pre>';
          resultHtml += '<h4>Response Body</h4><pre style="background:#272822; color:#f8f8f2; padding:1rem; border-radius:6px; overflow-x:auto;">' + formatResponseBody(data.body) + '</pre>';
          resultDiv.innerHTML = resultHtml;
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error);
          resultDiv.innerHTML = '<div style="color: red;">Error: ' + message + '</div>';
        }
      }
      </script>
      """.formatted(defaultClientId, Boolean.toString(isProduction)));
    html.append("</body></html>");
    return html.toString();
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

    // Sort routes by path to have a consistent order within controllers
    routes.sort(Comparator.comparing(DocumentationRegistrant.RegisteredRoute::path)
      .thenComparing(DocumentationRegistrant.RegisteredRoute::method));

    for (DocumentationRegistrant.RegisteredRoute route : routes) {
      grouped.computeIfAbsent(route.controllerClass(), k -> new ArrayList<>()).add(route);
    }

    cachedGroupedRoutes = grouped;
    return cachedGroupedRoutes;
  }

  private void appendExampleDisplay(StringBuilder html, DocumentableDTO dto) {
    if (dto == null) return;
    String sectionId = "ex-" + UUID.randomUUID().toString().substring(0, 8);
    try {
      String example = dto.toExample();
      html.append("<pre id='").append(sectionId).append("-0' class='").append(sectionId).append("-item'>")
        .append(example)
        .append("</pre>");
    } catch (Exception e) {
      html.append("<pre>Could not generate example: ").append(e.getMessage()).append("</pre>");
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
      html.append("<pre id='").append(sectionId).append("-").append(index).append("' class='").append(sectionId).append("-item' style='").append(index == 0 ? "" : "display: none;").append("'>")
        .append(entry.getValue())
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
                examples.put(name, documentableDTO.toExample());
              } else {
                examples.put(name, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
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
        examples.putIfAbsent("Default", instance.toExample());
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

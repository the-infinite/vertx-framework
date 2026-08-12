window.onload = function() {
  //<editor-fold desc="Changeable Configuration Block">

  // The framework serves the OpenAPI 3.1 specification at /docs.
  // A different specification URL can be supplied with the `url` query parameter.
  const params = new URLSearchParams(window.location.search);
  const specUrl = params.get('url') || './docs';

  // the following lines will be replaced by docker/configurator, when it runs in a docker-container
  window.ui = SwaggerUIBundle({
    url: specUrl,
    dom_id: '#swagger-ui',
    deepLinking: true,
    presets: [
      SwaggerUIBundle.presets.apis,
      SwaggerUIStandalonePreset
    ],
    plugins: [
      SwaggerUIBundle.plugins.DownloadUrl
    ],
    layout: "StandaloneLayout"
  });

  //</editor-fold>
};
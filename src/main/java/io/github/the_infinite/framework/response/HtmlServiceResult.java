package io.github.the_infinite.framework.response;

/**
 * A service result that renders HTML content.
 */
public class HtmlServiceResult extends TypedServiceResult<String> {
    public HtmlServiceResult(String htmlContent, int code) {
        super(ResponseType.HTML, "success", htmlContent, code);
    }

    @Override
    public String serialize() {
        return data;
    }
}

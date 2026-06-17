package io.github.the_infinite.framework.queue;

import java.util.HashMap;
import java.util.Map;

import lombok.Getter;

/**
 * Configuration options for publishing messages to RabbitMQ queues.
 * Provides a fluent API for setting various messages and publishing properties.
 */
@Getter
public class PublishOptions {
  // Getters
  private int priority = 1;
  private String contentType = "application/json";
  private String contentEncoding = null;
  private Integer deliveryMode = null; // 1 = non-persistent, 2 = persistent
  private Long expiration = null; // TTL in milliseconds
  private String messageId = null;
  private String correlationId = null;
  private String replyTo = null;
  private String type = null;
  private String userId = null;
  private String appId = null;
  private final Map<String, Object> headers = new HashMap<>();
  private boolean mandatory = false;
  private boolean immediate = false;

  /**
   * Sets the message priority (1-9).
   * @param priority the priority level
   * @return this instance for chaining
   */
  public PublishOptions setPriority(int priority) {
    this.priority = Math.clamp(priority, 1, 9);
    return this;
  }

  /**
   * Sets the content type of the message.
   * @param contentType the content type (e.g., "application/json")
   * @return this instance for chaining
   */
  public PublishOptions setContentType(String contentType) {
    this.contentType = contentType;
    return this;
  }

  /**
   * Sets the content encoding.
   * @param contentEncoding the encoding type
   * @return this instance for chaining
   */
  public PublishOptions setContentEncoding(String contentEncoding) {
    this.contentEncoding = contentEncoding;
    return this;
  }

  /**
   * Sets the delivery mode: 1 for non-persistent, 2 for persistent.
   * @param deliveryMode the delivery mode
   * @return this instance for chaining
   */
  public PublishOptions setDeliveryMode(int deliveryMode) {
    this.deliveryMode = deliveryMode;
    return this;
  }

  /**
   * Sets the message expiration/TTL in milliseconds.
   * @param expirationMs the TTL in milliseconds
   * @return this instance for chaining
   */
  public PublishOptions setExpiration(long expirationMs) {
    this.expiration = expirationMs;
    return this;
  }

  /**
   * Sets the message ID.
   * @param messageId the message ID
   * @return this instance for chaining
   */
  public PublishOptions setMessageId(String messageId) {
    this.messageId = messageId;
    return this;
  }

  /**
   * Sets the correlation ID for tracking related messages.
   * @param correlationId the correlation ID
   * @return this instance for chaining
   */
  public PublishOptions setCorrelationId(String correlationId) {
    this.correlationId = correlationId;
    return this;
  }

  /**
   * Sets the reply-to queue name for RPC patterns.
   * @param replyTo the reply-to queue name
   * @return this instance for chaining
   */
  public PublishOptions setReplyTo(String replyTo) {
    this.replyTo = replyTo;
    return this;
  }

  /**
   * Sets the message type.
   * @param type the message type
   * @return this instance for chaining
   */
  public PublishOptions setType(String type) {
    this.type = type;
    return this;
  }

  /**
   * Sets the user ID.
   * @param userId the user ID
   * @return this instance for chaining
   */
  public PublishOptions setUserId(String userId) {
    this.userId = userId;
    return this;
  }

  /**
   * Sets the application ID.
   * @param appId the application ID
   * @return this instance for chaining
   */
  public PublishOptions setAppId(String appId) {
    this.appId = appId;
    return this;
  }

  /**
   * Adds a custom header to the message.
   * @param key the header key
   * @param value the header value
   * @return this instance for chaining
   */
  public PublishOptions addHeader(String key, Object value) {
    this.headers.put(key, value);
    return this;
  }

  /**
   * Adds multiple headers to the message.
   * @param headers the headers to add
   * @return this instance for chaining
   */
  public PublishOptions addHeaders(Map<String, Object> headers) {
    this.headers.putAll(headers);
    return this;
  }

  /**
   * Sets the mandatory flag. If true, the message will be returned if it cannot be routed.
   * @param mandatory the mandatory flag
   * @return this instance for chaining
   */
  public PublishOptions setMandatory(boolean mandatory) {
    this.mandatory = mandatory;
    return this;
  }

  /**
   * Sets the immediate flag. If true, the message will be returned if there are no consumers.
   * @param immediate the immediate flag
   * @return this instance for chaining
   */
  public PublishOptions setImmediate(boolean immediate) {
    this.immediate = immediate;
    return this;
  }

  public Map<String, Object> getHeaders() {
    return new HashMap<>(headers);
  }
}

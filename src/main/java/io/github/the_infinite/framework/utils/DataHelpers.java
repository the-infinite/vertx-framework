package io.github.the_infinite.framework.utils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import io.github.the_infinite.framework.ConfigurationRegistrant;
import io.github.the_infinite.framework.logging.console.ConsoleLogger;
import io.vertx.core.Future;
import io.vertx.core.Promise;


@SuppressWarnings("unused")
public final class DataHelpers {
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
  private static final ObjectMapper OBJECT_WRITER = new ObjectMapper()
    .registerModule(new JavaTimeModule())
    .findAndRegisterModules()
    .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
    .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final Base64.Encoder B64_ENCODER = Base64.getUrlEncoder();
  private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

  private DataHelpers() {
    // Prevent instantiation
  }

  public static <T> T deserializeObject(String str, Class<T> clazz) throws JsonProcessingException {
    return OBJECT_WRITER.readValue(str, clazz);
  }


  public static <T> String serializeObject(T pojo) throws JsonProcessingException {
    return OBJECT_WRITER.writeValueAsString(pojo);
  }

  public static String toBase64(String value) {
    return B64_ENCODER.encodeToString(value.getBytes());
  }

  public static String fromBase64(String value) {
    return new String(B64_DECODER.decode(value));
  }

  /// Creates the styled count string from the given count.
  ///
  /// @param count The count to consider
  @NotNull
  public static String getStyledCount(Number count) {
    if (count == null || count.equals(0)) {
      return "0";
    }

    int div;
    String ret;

    // If this is divisible by a billion.
    if ((count.longValue() / 1000000000) > 0) {
      div = 100000000;
      ret = "B";
    }


    // If this is dividable by a million
    else if ((count.longValue() / 1000000) > 0) {
      div = 100000;
      ret = "M";
    }

    // If this is divisible by a thousand
    else if ((count.longValue() / 1000) > 0) {
      div = 100;
      ret = "K";
    }

    // Else in general
    else {
      return Long.toString(count.longValue()); // Just return this as a string.
    }

    // First, do this to store the value inside as an order of magnitude greater than what we return.
    // This is to make
    var val = count.longValue() / div;
    val = val / 10; // Now we divide it by 10 and store as a floating point number.

    // Now we return this after we have finished modifying it to suit our tastes.
    return val + ret;
  }

  /// Creates the styled storage string from the given count in bytes.
  ///
  /// @param count The count to consider in bytes
  @NotNull
  public static String getStyledStorage(Number count) {
    if (count == null || count.equals(0)) {
      return "0 bytes";
    }

    final var size = count.doubleValue();
    final var sizeUnits = new String[]{"bytes", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB", "ZiB", "YiB"};
    final var index = Math.floor(Math.log(size) / Math.log(1024));
    final var styledSize = size / Math.pow(1024, index);
    return String.format("%.2f %s", styledSize, sizeUnits[(int) index]);
  }

  /// Creates the styled duration string from the given duration in milliseconds.
  ///
  /// @param durationMs The duration to consider in milliseconds
  @NotNull
  public static String getStyledDuration(Number durationMs) {
    if (durationMs == null) {
      return "0 ms";
    }
    final var duration = durationMs.longValue();
    if (duration >= 604800000) return String.format("%.2f weeks", duration / 604800000.0);
    if (duration >= 86400000) return String.format("%.2f days", duration / 86400000.0);
    if (duration >= 3600000) return String.format("%.2f hours", duration / 3600000.0);
    if (duration >= 60000) return String.format("%.2f minutes", duration / 60000.0);
    if (duration >= 1000) return String.format("%.2f seconds", duration / 1000.0);
    return String.format("%.2f ms", (double) duration);
  }

  /// Formats the given number with commas as a thousand separators.
  ///
  /// @param number The number to format
  @NotNull
  public static String formatNumber(Number number) {
    if (number == null || number.equals(0)) {
      return "0";
    }
    return String.format("%,d", number.longValue());
  }

  /**
   * Removes all trailing occurrences of `char` from the end of `input`.
   */
  public static TrimResult removeTrailingChar(String input, char c, int maxLength) {
    if (input == null) return new TrimResult(null, 0);

    int count = 0;
    int end = input.length();

    while ((maxLength != -1 && input.length() - count > maxLength) || (end > 0 && input.charAt(end - 1) == c)) {
      count++;
      end--;
    }

    return new TrimResult(input.substring(0, end), count);
  }

  /**
   * Creates a list of numbers starting at `start`.
   */
  public static List<Integer> range(int start, int length, int step) {
    return IntStream.range(0, length).map(i -> start + i * step).boxed().collect(Collectors.toList());
  }

  /**
   * Executes a shell command using Vert.x Future.
   */
  public static Future<CommandResult> executeCommand(String program, List<String> args) {
    Promise<CommandResult> promise = Promise.promise();

    // Using a worker thread because ProcessBuilder is blocking
    new Thread(() -> {
      try {
        List<String> command = new ArrayList<>();
        command.add(program);
        if (args != null) command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command);
        Process process = builder.start();

        // Capture Output
        String stdout = new BufferedReader(new InputStreamReader(process.getInputStream())).lines().collect(Collectors.joining("\n"));

        String stderr = new BufferedReader(new InputStreamReader(process.getErrorStream())).lines().collect(Collectors.joining("\n"));

        int exitCode = process.waitFor();

        if (exitCode != 0) {
          promise.fail(new RuntimeException("Command failed with exit code " + exitCode + ": " + stderr));
        } else {
          promise.complete(new CommandResult(process, stdout, stderr));
        }
      } catch (Exception e) {
        promise.fail(e);
      }
    }).start();

    return promise.future();
  }

  /**
   * Returns the ordinal representation of a number.
   */
  public static String getOrdinalNum(int n) {
    String suffix;
    if (n % 100 >= 11 && n % 100 <= 13) {
      suffix = "th";
    } else {
      suffix = switch (n % 10) {
        case 1 -> "st";
        case 2 -> "nd";
        case 3 -> "rd";
        default -> "th";
      };
    }
    return n + suffix;
  }

  /**
   * Validates if a string is a valid username.
   */
  public static boolean isUsername(String str) {
    if (str == null || str.isEmpty()) return false;

    // First char must be alphanumeric
    if (!Character.isLetterOrDigit(str.charAt(0))) return false;

    for (int i = 1; i < str.length(); i++) {
      char c = str.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '.' && c != '_') {
        return false;
      }
    }
    return true;
  }

  /**
   * Formats a timestamp (Java DateTimeFormatter is stricter than PHP dates).
   * Defaults to ISO_LOCAL_DATE_TIME-like format "yyyy-MM-dd HH:mm:ss".
   */
  public static String formatTimestamp(Instant value, String pattern) {
    final var console = ConsoleLogger.getInstance(ConfigurationRegistrant.global());
    try {
      Instant date = (value != null) ? value : Instant.now();
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault());
      return formatter.format(date);
    } catch (Exception e) {
      console.debug(e.getMessage());
      return "<?>";
    }
  }

  public static String createId() {
    return UUID.randomUUID().toString();
  }

  /**
   * Extracts and decodes query parameters from a URL.
   * Returns a Map of Lists to safely handle duplicate query parameters.
   */
  public static Map<String, List<String>> extractQueryParams(String url) {
    if (url == null || url.isBlank()) {
      return Collections.emptyMap();
    }

    // 1. Isolate the query string
    int queryStart = url.indexOf('?');
    if (queryStart == -1 || queryStart == url.length() - 1) {
      return Collections.emptyMap();
    }

    String query = url.substring(queryStart + 1);

    // 2. Strip out any URL fragments (#anchor)
    int fragmentStart = query.indexOf('#');
    if (fragmentStart != -1) {
      query = query.substring(0, fragmentStart);
    }

    // Use LinkedHashMap to preserve the insertion order of parameters
    Map<String, List<String>> queryPairs = new LinkedHashMap<>();
    String[] pairs = query.split("&");

    for (String pair : pairs) {
      if (pair.isEmpty()) continue;

      int idx = pair.indexOf("=");

      try {
        // 3. Decode the key
        String key = idx > 0
          ? URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8)
          : URLDecoder.decode(pair, StandardCharsets.UTF_8);

        // 4. Decode the value (or default to an empty string if no '=' is present)
        String value = idx > 0 && pair.length() > idx + 1
          ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
          : "";

        // 5. Add to the list for this key
        queryPairs.computeIfAbsent(key, k -> new ArrayList<>()).add(value);

      } catch (IllegalArgumentException e) {
        // Thrown by URLDecoder if the string contains illegal escape patterns (e.g., "%2")
        System.err.println("Could not decode parameter sequence: " + pair);
      }
    }

    return queryPairs;
  }

  /**
   * Hashes a string using SHA-512.
   */
  public static String hash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-512");
      String salt = "@MilestoneRocks@2025"; // Hardcoded salt from original code
      byte[] hashEncoded = digest.digest((value + salt).getBytes(StandardCharsets.UTF_8));

      // Return as Hex
      StringBuilder hexString = new StringBuilder(2 * hashEncoded.length);
      for (byte b : hashEncoded) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) hexString.append('0');
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (Exception e) {
      throw new RuntimeException("Hashing failed", e);
    }
  }

  /**
   * Signs data using RSA Private Key (Replacing JWT logic with Public/Private Keypair).
   */
  public static Future<String> signData(String privateKeyPem, String data) {
    return Future.future(promise -> {
      try {
        // Parse PEM
        String cleanKey = privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleanKey);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = kf.generatePrivate(spec);

        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(privateKey);
        rsa.update(data.getBytes(StandardCharsets.UTF_8));

        // Return Base64 encoded signature
        promise.complete(Base64.getEncoder().encodeToString(rsa.sign()));
      } catch (Exception e) {
        promise.fail(e);
      }
    });
  }

  /**
   * Verifies data using RSA Public Key.
   */
  public static Future<Boolean> verifySignature(String publicKeyPem, String data, String signatureBase64) {
    return Future.future(promise -> {
      try {
        String cleanKey = publicKeyPem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(cleanKey);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey publicKey = kf.generatePublic(spec);

        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initVerify(publicKey);
        rsa.update(data.getBytes(StandardCharsets.UTF_8));

        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
        promise.complete(rsa.verify(signatureBytes));
      } catch (Exception e) {
        promise.fail(new RuntimeException("Verification failed", e));
      }
    });
  }

  public static String toKebabCase(String str) {
    if (str == null) return null;
    return str.replaceAll("([a-z])([A-Z]+)", "$1-$2").toLowerCase();
  }

  public static boolean validateEmail(String str) {
    return str != null && EMAIL_PATTERN.matcher(str).matches();
  }

  public static String toTitleCase(String str) {
    if (str == null || str.isEmpty()) return str;
    return Arrays.stream(str.toLowerCase().split(" ")).map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1)).collect(Collectors.joining(" "));
  }

  public static String toCamelCase(String str) {
    if (str == null) return null;
    // Simple regex strategy for camelCase conversion
    String[] parts = str.split("[\\W_]+");
    StringBuilder camelCaseString = new StringBuilder(parts[0].toLowerCase());
    for (int i = 1; i < parts.length; i++) {
      camelCaseString.append(toTitleCase(parts[i]));
    }
    return camelCaseString.toString();
  }

  private static String randomSeed(long argument, int base, int limit, boolean mixedCase, Random random) {
    final var regex = ".";
    final var pattern = Pattern.compile(regex);
    final var matcher = pattern.matcher(" ".repeat(limit));
    return matcher.replaceAll(matchResult -> {
      if (!matchResult.hasMatch()) {
        return matchResult.group();
      }
      final var randomBoolean = mixedCase && random.nextBoolean();
      return hex(Math.abs(random.nextLong()) * argument, base, randomBoolean);
    });
  }

  private static String hex(long value, int base, boolean uppercase) {
    final var result = Long.toString(Math.abs(value), base);

    if (uppercase) {
      return result.toUpperCase();
    }

    return result;
  }

  /**
   * Scans the classpath to find all subclasses of a given base class.
   * @param baseClass The class/interface to find implementors for.
   * @param packageName The root package to scan (e.g., "io.github.the_infinite") to keep it fast.
   * @return A list of matching classes.
   */
  public static <T> List<Class<? extends T>> findSubclasses(
    @NotNull Class<T> baseClass,
    @Nullable String packageName
  ) {
    if (packageName == null || packageName.isBlank()) {

      //? ClassGraph uses a try-with-resources block because it opens files
      try (ScanResult scanResult = new ClassGraph().enableClassInfo().scan()) {
        return scanResult.getSubclasses(baseClass.getName())
          .loadClasses(baseClass).stream()
          .map(cls -> (Class<? extends T>) cls)
          .collect(Collectors.toList());
      }
    }

    //? ClassGraph uses a try-with-resources block because it opens files
    try (ScanResult scanResult = new ClassGraph()
      .enableClassInfo()                  // Read class metadata
      .acceptPackages(packageName)        // Restrict to your app's namespace
      .scan()) {                          // Execute the scan

      //? Fetch all subclasses and load them into the JVM
      return scanResult.getSubclasses(baseClass.getName())
        .loadClasses(baseClass).stream()
        .map(cls -> (Class<? extends T>) cls)
        .collect(Collectors.toList());
    }
  }

  /**
   * Scans the classpath to find all subclasses of a given base class.
   * @param baseClass The class/interface to find implementors for.
   * @see #findSubclasses(Class, String)
   * @return A list of matching classes.
   */
  public static <T> List<Class<? extends T>> findSubclasses(Class<T> baseClass) {
    return findSubclasses(baseClass, null);
  }

  /**
   * Creates a unique token similar to the TS implementation.
   */
  public static String createToken(long randomizer, long bounds, int base, String separator, boolean mixedCase) {
    final var random = new Random();
    final var prefix =
      Long.toString(Instant.now().getEpochSecond(), base) + separator + Long.toString(Math.abs(randomizer), base);
    final var lengthLimit = bounds + 3;
    final var limit = (int) Math.max(0, lengthLimit - prefix.length());
    final var ending = randomSeed(bounds * randomizer, base, limit, mixedCase, random);
    final var result = new StringBuilder("%s%s%s".formatted(prefix, separator, ending));


    while (result.length() < lengthLimit) {
      result.append(randomSeed(bounds * randomizer, base, limit, mixedCase, random));
    }

    if (result.length() > lengthLimit) {
      return result.substring(0, (int) bounds);
    }

    return result.toString();
  }

  public static String createToken(long randomizer, long bounds, int base, String separator) {
    return createToken(randomizer, bounds, base, separator, false);
  }

  public static String createToken(long randomizer, long bounds, int base) {
    return createToken(randomizer, bounds, base, "");
  }

  public static String createToken(long randomizer, long bounds) {
    return createToken(randomizer, bounds, 32);
  }

  public static String createToken(long randomizer) {
    return createToken(randomizer, Long.SIZE);
  }

  public static String createToken() {
    final var randomizer = new Random();
    return createToken(randomizer.nextInt());
  }

  public static String getUrl(String host, String resource) {
    return host + (host.endsWith("/") ? "" : "/") + resource;
  }

  public static double getPercentageDifference(double higher, double lower) {
    if (lower == 0) return 0;
    return ((higher - lower) / lower) * 100;
  }

  public static String createOTP(int digits, int base) {
    final var random = new Random();
    final var otp = new StringBuilder();
    for (int i = 0; i < digits; i++) {
      String charVal = Integer.toString(random.nextInt(base), base).toUpperCase();
      otp.append(charVal);
    }
    return otp.toString();
  }

  public static String sanitizeEmailAddress(String email) {
    if (email == null) return null;

    // Remove +alias
    String sanitized = email.replaceAll("\\+[^@]+@", "@").toLowerCase();

    // Remove special chars before @
    // We split by @ to operate safely on the local part
    String[] parts = sanitized.split("@");
    if (parts.length < 2) return sanitized;

    String local = parts[0];
    String domain = parts[1];

    local = local.replaceAll("[!#$%&'*+\\-/=?^_`{|}~]", "");

    return local + "@" + domain;
  }

  // Result object for removeTrailingChar
  public record TrimResult(String text, int count) {
  }

  // Result object for executeCommand
  public record CommandResult(Process process, String stdout, String stderr) {
  }
}

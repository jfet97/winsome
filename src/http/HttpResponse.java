package http;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.vavr.control.Either;

public class HttpResponse {

  private String HTTPVersion = "";
  private String statusCode = "";
  private String reasonPhrase = "";
  private Map<String, String> headers = new HashMap<String, String>();
  private String body = "";

  private HttpResponse() {
  }

  public static Either<String, HttpResponse> build(String HTTPVersion, String statusCode, String reasonPhrase) {
    HttpResponse instance = new HttpResponse();
    var errorMessage = "";

    if (HTTPVersion == null) {
      errorMessage = "HTTPVersion cannot be null";
    } else if (statusCode == null) {
      errorMessage = "statusCode cannot be null";
    } else if (reasonPhrase == null) {
      errorMessage = "reasonPhrase cannot be null";
    } else {
      instance.HTTPVersion = HTTPVersion;
      instance.statusCode = statusCode;
      instance.reasonPhrase = reasonPhrase;
    }

    if (!errorMessage.equals("")) {
      return Either.left(errorMessage);
    } else {
      return Either.right(instance);
    }
  }

  public static Either<String, HttpResponse> parse(String response) {
    var instance = new HttpResponse();
    var errorMessage = "";

    if (response == null) {
      errorMessage = "HTTP response cannot be null";
    } else {
      try {

        var responseAndBody = response.split(HttpConstants.CRLF + HttpConstants.CRLF);
        var responseBeforeBodyEntries = responseAndBody[0].split(HttpConstants.CRLF);

        // parse response line
        var responseLineEntries = responseBeforeBodyEntries[0].split(" ");
        instance.HTTPVersion = responseLineEntries[0];
        instance.statusCode = responseLineEntries[1];
        instance.reasonPhrase = responseLineEntries[2];

        // parse headers

        Arrays.stream(responseBeforeBodyEntries).skip(1).forEach(header -> {
          var keyValue = header.split(": ");
          instance.headers.put(keyValue[0], keyValue[1]);
        });

        // parse body
        if (responseAndBody.length > 1) {
          instance.body = responseAndBody[1];
        }

      } catch (Exception e) {
        e.printStackTrace();
        errorMessage = "invalid or not supported HTTP response:\n" + e.getMessage();
      }
    }

    if (!errorMessage.equals("")) {
      return Either.left(errorMessage);
    } else {
      return Either.right(instance);
    }
  }

  public Either<String, HttpResponse> setHeader(String key, String value) {
    if (key != null && value != null) {
      this.headers.put(key, value);
      return Either.right(this);
    } else {
      return Either.left("HTTP response header's key and value cannot be null");
    }
  }

  public Either<String, HttpResponse> deleteHeader(String key) {
    if (key != null) {
      this.headers.remove(key);
      return Either.right(this);
    } else {
      return Either.left("HTTP response header's key to delete cannot be null");
    }
  }

  public Either<String, HttpResponse> setBody(String body) {
    if (body != null) {
      this.body = body;
      return Either.right(this);
    } else {
      return Either.left("HTTP response body cannot be null");
    }
  }

  public boolean isValid() {
    var isValid = true;

    // always mandatory
    if (this.HTTPVersion.equals("") || this.statusCode.equals("") || this.reasonPhrase.equals("")) {
      isValid = false;
    }

    return isValid;
  }

  @Override
  public String toString() {

    var response = this.HTTPVersion + " " + this.statusCode + " " + this.reasonPhrase + HttpConstants.CRLF;

    for (var entry : this.headers.entrySet()) {
      response += entry.getKey() + ": " + entry.getValue() + HttpConstants.CRLF;
    }

    response += HttpConstants.CRLF;

    response += this.body;

    return response;
  }

  // getters
  public String getStatusCode() {
    return this.statusCode;
  }

  public String getReasonPhrase() {
    return this.reasonPhrase;
  }

  public String getHTTPVersion() {
    return this.HTTPVersion;
  }

  public Map<String, String> getHeaders() {
    return Collections.unmodifiableMap(this.headers);
  }

  public String getBody() {
    return this.body;
  }

  // builders
  private static Either<String, HttpResponse> buildFromCode(String bodyN, String mime, Boolean keepAliveConnection,
      String[] code) {
    String body = bodyN != null ? bodyN : "";
    String bodyLength = body.getBytes().length + "";

    return HttpResponse.build(HttpConstants.HTTPV11, code[0], code[1])
        .flatMap(r -> r.setHeader("Connection", keepAliveConnection ? "keep-alive" : "close"))
        .flatMap(r -> r.setHeader("Content-Length", bodyLength))
        .flatMap(r -> mime != null ? r.setHeader("Content-Type", mime) : Either.right(r))
        .flatMap(r -> r.setHeader("Access-Control-Allow-Origin", "*")) // temp hotfix because of CORS
        .flatMap(r -> r.setHeader("Access-Control-Allow-Methods", "*")) // temp hotfix because of CORS
        .flatMap(r -> r.setHeader("Access-Control-Allow-Headers", "*")) // temp hotfix because of CORS
        .flatMap(r -> r.setHeader("Access-Control-Allow-Credentials", "true")) // temp hotfix because of CORS
        .flatMap(r -> r.setBody(body));
  }

  public static Either<String, HttpResponse> build200(String bodyN, String mime, Boolean keepAliveConnection) {
    return buildFromCode(bodyN, mime, keepAliveConnection, HttpConstants.OK_200);
  }

  public static Either<String, HttpResponse> build201(String bodyN, String mime, Boolean keepAliveConnection) {
    return buildFromCode(bodyN, mime, keepAliveConnection, HttpConstants.CREATED_201);
  }

  public static Either<String, HttpResponse> build204(String bodyN, String mime, Boolean keepAliveConnection) {
    return buildFromCode(bodyN, mime, keepAliveConnection, HttpConstants.CREATED_204);
  }

  public static Either<String, HttpResponse> build400(String bodyN, String mime, Boolean keepAliveConnection) {
    return buildFromCode(bodyN, mime, keepAliveConnection, HttpConstants.BAD_REQUEST_400);

  }

  public static Either<String, HttpResponse> build401(String bodyN, String mime, Boolean keepAliveConnection) {
    return buildFromCode(bodyN, mime, keepAliveConnection, HttpConstants.UNAUTHORIZED_401);
  }

  public static Either<String, HttpResponse> build403(String bodyN, String mime, Boolean keepAliveConnection) {
    return buildFromCode(bodyN, mime, keepAliveConnection, HttpConstants.FORBIDDEN_403);
  }

  public static Either<String, HttpResponse> build404(String bodyN, String mime, Boolean keepAliveConnection) {
    return buildFromCode(bodyN, mime, keepAliveConnection, HttpConstants.NOT_FOUND_404);
  }

  public static Either<String, HttpResponse> build405(String bodyN, String mime, Boolean keepAliveConnection) {
    return buildFromCode(bodyN, mime, keepAliveConnection, HttpConstants.METHOD_NOT_ALLOWED_405);
  }

  public static Either<String, HttpResponse> build500(String bodyN, String mime, Boolean keepAliveConnection) {
    return buildFromCode(bodyN, mime, keepAliveConnection, HttpConstants.INTERNAL_SERVER_ERROR_500);
  }
}

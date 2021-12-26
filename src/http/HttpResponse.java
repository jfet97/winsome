package http;

import java.util.Arrays;
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

  public static final String HTTPV11 = "HTTP/1.1";
  public static final String HTTPV20 = "HTTP/2.0";
  public static final String CRLF = "\r\n";

  public static final String[] CODE_200 = {"200", "OK"};
  public static final String[] CODE_201 = {"201", "Created"};
  public static final String[] CODE_400 = {"400", "Bad Request"};
  public static final String[] CODE_401 = {"401", "Unauthorized"};
  public static final String[] CODE_404 = {"404", "Not Found"};
  public static final String[] CODE_405 = {"405", "Method Not Allowed"};
  public static final String[] CODE_500 = {"500", "Internal Server Error"};

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

        var responseAndBody = response.split(CRLF + CRLF);
        var responseBeforeBodyEntries = responseAndBody[0].split(CRLF);

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

    var response = this.HTTPVersion + " " + this.statusCode + " " + this.reasonPhrase + CRLF;

    for (var entry : this.headers.entrySet()) {
      response += entry.getKey() + ": " + entry.getValue() + CRLF;
    }

    response += CRLF;

    response += this.body;

    return response;
  }
}

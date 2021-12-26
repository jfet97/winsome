package http;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.vavr.control.Either;

public class HttpRequest {

  private String method = "";
  private String requestTarget = "";
  private String HTTPVersion = "";
  private Map<String, String> headers = new HashMap<String, String>();
  private String body = "";

  private HttpRequest() {
  }

  public static final String HTTPV11 = "HTTP/1.1";
  public static final String HTTPV20 = "HTTP/2.0";
  public static final String CRLF = "\r\n";

  public static Either<String, HttpRequest> build(String method) {
    HttpRequest instance = new HttpRequest();
    var errorMessage = "";

    if (method == null) {
      errorMessage = "HTTP method cannot be null";
    } else {
      switch (method) {
        case "GET":
        case "POST":
        case "PUT":
        case "PATCH":
        case "DELETE": {
          instance.method = method;
          break;
        }
        default: {
          errorMessage = method + " is not yet supported by HttpRequest";
          break;
        }
      }
    }

    if (!errorMessage.equals("")) {
      return Either.left(errorMessage);
    } else {
      return Either.right(instance);
    }
  }

  public static Either<String, HttpRequest> parse(String request) {
    var instance = new HttpRequest();
    var errorMessage = "";

    if (request == null) {
      errorMessage = "HTTP request cannot be null";
    } else {
      try {

        var requestAndBody = request.split(CRLF + CRLF);
        var requestBeforeBodyEntries = requestAndBody[0].split(CRLF);

        // parse request line
        var requestLineEntries = requestBeforeBodyEntries[0].split(" ");
        instance.method = requestLineEntries[0];
        instance.requestTarget = requestLineEntries[1];
        instance.HTTPVersion = requestLineEntries[2];

        switch (instance.method) {
          case "GET":
          case "POST":
          case "PUT":
          case "PATCH":
          case "DELETE": {
            break;
          }
          default: {
            errorMessage = instance.method + " is not yet supported by HttpRequest";
          }
        }

        // parse headers
        if (errorMessage.equals("")) {
          Arrays.stream(requestBeforeBodyEntries).skip(1).forEach(header -> {
            var keyValue = header.split(": ");
            instance.headers.put(keyValue[0], keyValue[1]);
          });
        }

        // parse body
        if (errorMessage.equals("")) {
          if (requestAndBody.length > 1) {
            instance.body = requestAndBody[1];
          }
        }

      } catch (Exception e) {
        errorMessage = "invalid or not supported HTTP request:\n" + e.getMessage();
      }
    }

    if (!errorMessage.equals("")) {
      return Either.left(errorMessage);
    } else {
      return Either.right(instance);
    }
  }

  public Either<String, HttpRequest> setRequestTarget(String requestTarget) {
    if (requestTarget != null) {
      this.requestTarget = requestTarget;
      return Either.right(this);
    } else {
      return Either.left("HTTP request target cannot be null");
    }
  }

  public Either<String, HttpRequest> setHTTPVersion(String HTTPVersion) {
    if (HTTPVersion != null) {
      this.HTTPVersion = HTTPVersion;
      return Either.right(this);
    } else {
      return Either.left("HTTP request version cannot be null");
    }
  }

  public Either<String, HttpRequest> setHeader(String key, String value) {
    if (key != null && value != null) {
      this.headers.put(key, value);
      return Either.right(this);
    } else {
      return Either.left("HTTP request header's key and value cannot be null");
    }
  }

  public Either<String, HttpRequest> deleteHeader(String key) {
    if (key != null) {
      this.headers.remove(key);
      return Either.right(this);
    } else {
      return Either.left("HTTP request header's key to delete cannot be null");
    }
  }

  public Either<String, HttpRequest> setBody(String body) {
    if (body != null) {
      this.body = body;
      return Either.right(this);
    } else {
      return Either.left("HTTP request body cannot be null");
    }
  }

  public boolean isValid() {
    var isValid = true;

    // always mandatory
    if (this.method.equals("") || this.requestTarget.equals("") || this.HTTPVersion.equals("")) {
      isValid = false;
    }

    // From MDN:
    // Sending body/payload in a GET request may cause some existing implementations
    // to reject the request — while not prohibited by the specification, the
    // semantics are undefined. It is better to just avoid sending payloads in GET
    // requests.
    if (this.method.equals("GET") && !this.body.equals("")) {
      isValid = false;
    }

    if (!this.method.equals("GET") && this.body.equals("")) {
      isValid = false;
    }

    return isValid;
  }

  @Override
  public String toString() {

    var request = this.method + " " + this.requestTarget + " " + this.HTTPVersion + CRLF;

    for (var entry : this.headers.entrySet()) {
      request += entry.getKey() + ": " + entry.getValue() + CRLF;
    }

    request += CRLF;

    request += this.body;

    return request;
  }

  // getters
  public String getMethod() {
    return this.method;
  }

  public String getRequestTarget() {
    return this.requestTarget;
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
}

package http;

import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import io.vavr.Function2;
import io.vavr.control.Either;

public class HttpRequest {

  private String method = "";
  private String requestTarget = "";
  private String HTTPVersion = "";
  private Map<String, String> headers = new HashMap<String, String>();
  private Map<String, String> queryParams = new HashMap<String, String>();
  private String body = "";
  public Object context = null;

  private HttpRequest() {
  }

  public static final String HTTPV11 = "HTTP/1.1";
  public static final String HTTPV20 = "HTTP/2.0";
  public static final String CRLF = "\r\n";

  public static final String GET = "GET";
  public static final String POST = "POST";
  public static final String PUT = "PUT";
  public static final String PATCH = "PATCH";
  public static final String DELETE = "DELETE";
  public static final String OPTIONS = "OPTIONS";

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
        case "DELETE":
        case "OPTIONS": {
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
          case "DELETE":
          case "OPTIONS": {
            break;
          }
          default: {
            errorMessage = instance.method + " is not yet supported by HttpRequest";
          }
        }

        // parse query params (key=value pairs)
        if (errorMessage.equals("") && instance.requestTarget.contains("?")) {
          try {
            var paramsString = instance.requestTarget.substring(instance.requestTarget.indexOf("?") + 1);
            var pairs = paramsString.split("&");

            Arrays.stream(pairs).forEach(pair -> {
              try {
                int idx = pair.indexOf("=");
                var key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                var value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");

                instance.queryParams.put(key, value);
              } catch (Exception e) {
                e.printStackTrace();
              }
            });
          } catch (Exception e) {
            e.printStackTrace();
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
        e.printStackTrace();
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
    if ((this.method.equals("GET") || this.method.equals("DELETE") || method.equals("OPTIONS"))
        && !this.body.equals("")) {
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
    // remove query params, if any
    try {
      return this.requestTarget.split("\\?")[0];
    } catch (Exception e) {
      e.printStackTrace();
      return this.requestTarget;
    }
  }

  public String getHTTPVersion() {
    return this.HTTPVersion;
  }

  public Map<String, String> getHeaders() {
    return Collections.unmodifiableMap(this.headers);
  }

  public Map<String, String> getQueryParams() {
    return Collections.unmodifiableMap(this.queryParams);
  }

  public String getBody() {
    return this.body;
  }

  // static utilities
  public static Either<String, HttpRequest> buildPostRequest(String requestTarget, String body, Map<String, String> headers) {

    return HttpRequest.build(HttpRequest.POST)
        .flatMap(r -> r.setHTTPVersion(HTTPV11))
        .flatMap(r -> r.setRequestTarget(requestTarget))
        .flatMap(r -> {
          var toRet = Either.<String, HttpRequest>right(r);

          for (var entry : headers.entrySet()) {
            toRet = toRet.flatMap(tr -> tr.setHeader(entry.getKey(), entry.getValue()));
          }

          return toRet;
        })
        .flatMap(r -> r.setBody(body));
  }
}

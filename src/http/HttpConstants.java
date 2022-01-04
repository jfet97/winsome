package http;

public class HttpConstants {
  public static final String HTTPV11 = "HTTP/1.1";
  public static final String HTTPV20 = "HTTP/2.0";

  public static final String CRLF = "\r\n";

  public static final String[] OK_200 = { "200", "OK" };
  public static final String[] CREATED_201 = { "201", "Created" };
  public static final String[] CREATED_204 = { "204", "No Content" };
  public static final String[] BAD_REQUEST_400 = { "400", "Bad Request" };
  public static final String[] UNAUTHORIZED_401 = { "401", "Unauthorized" };
  public static final String[] FORBIDDEN_403 = { "403", "Forbidden" };
  public static final String[] NOT_FOUND_404 = { "404", "Not Found" };
  public static final String[] METHOD_NOT_ALLOWED_405 = { "405", "Method Not Allowed" };
  public static final String[] INTERNAL_SERVER_ERROR_500 = { "500", "Internal Server Error" };

  public static final String MIME_APPLICATION_JSON = "application/json";
  public static final String MIME_TEXT_PLAIN = "text/plain";
  public static final String MIME_TEXT_HTML = "text/html";

  public static final String GET = "GET";
  public static final String POST = "POST";
  public static final String PUT = "PUT";
  public static final String PATCH = "PATCH";
  public static final String DELETE = "DELETE";
  public static final String OPTIONS = "OPTIONS";

  private HttpConstants() {
  }
}

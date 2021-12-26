package http.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import http.HttpRequest;

public class HttpRequestTest {

  private static final String CRLF = "\r\n";

  private static final String validGET = "" +
      "GET /test/index.html HTTP/1.1" + CRLF +
      "User-Agent: Mozilla/4.0(compatible; MSIE5.01; Windows NT)" + CRLF +
      "Host: www.tutorialspoint.com" + CRLF +
      "Accept-Language: en-us" + CRLF +
      "Accept-Encoding: gzip, deflate" + CRLF +
      "Connection: Keep-Alive" + CRLF +
      CRLF;

  private static final String validPOST = "" +
      "POST /test/index.html HTTP/1.1" + CRLF +
      "User-Agent: Mozilla/4.0(compatible; MSIE5.01; Windows NT)" + CRLF +
      "Host: www.tutorialspoint.com" + CRLF +
      "Accept-Language: en-us" + CRLF +
      "Accept-Encoding: gzip, deflate" + CRLF +
      "Connection: Keep-Alive" + CRLF +
      CRLF +
      "{\"field\":\"property\",\"array\":[1,2,{\"three\":3}]}";

  @Test
  void parseValidGET() {
    var erequest = HttpRequest.parse(validGET);
    assertTrue(erequest.isRight());

    var request = erequest.get();
    assertTrue(request.isValid());
    System.out.println(request);
  }

  @Test
  void parseValidPOST() {
    var erequest = HttpRequest.parse(validPOST);
    assertTrue(erequest.isRight());

    var request = erequest.get();
    assertTrue(request.isValid());
    System.out.println(request);
  }

  @Test
  void buildValidGET() {
    var erequest = HttpRequest.build("GET")
        .flatMap(req -> req.setRequestTarget("/test/index.html"))
        .flatMap(req -> req.setHTTPVersion(HttpRequest.HTTPV11))
        .flatMap(req -> req.setHeader("User-Agent", "Mozilla/4.0(compatible; MSIE5.01; Windows NT)"))
        .flatMap(req -> req.setHeader("Host", "www.tutorialspoint.com"))
        .flatMap(req -> req.setHeader("Accept-Language", "en-us"))
        .flatMap(req -> req.setHeader("Accept-Encoding", "gzip, deflate"))
        .flatMap(req -> req.setHeader("Connection", "Keep-Alive"));

    assertTrue(erequest.isRight());

    var request = erequest.get();
    assertTrue(request.isValid());
    System.out.println(request);
  }

  @Test
  void buildValidPOST() {
    var erequest = HttpRequest.build("POST")
        .flatMap(req -> req.setRequestTarget("/test/index.html"))
        .flatMap(req -> req.setHTTPVersion(HttpRequest.HTTPV11))
        .flatMap(req -> req.setHeader("User-Agent", "Mozilla/4.0(compatible; MSIE5.01; Windows NT)"))
        .flatMap(req -> req.setHeader("Host", "www.tutorialspoint.com"))
        .flatMap(req -> req.setHeader("Accept-Language", "en-us"))
        .flatMap(req -> req.setHeader("Accept-Encoding", "gzip, deflate"))
        .flatMap(req -> req.setHeader("Connection", "Keep-Alive"))
        .flatMap(req -> req.setBody("{\"field\":\"property\",\"array\":[1,2,{\"three\":3}]}"));

    assertTrue(erequest.isRight());

    var request = erequest.get();
    assertTrue(request.isValid());
    System.out.println(request);
  }
}

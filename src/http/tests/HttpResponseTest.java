package http.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import http.HttpConstants;
import http.HttpResponse;

public class HttpResponseTest {
  private static final String CRLF = "\r\n";

  private static final String valid200 = "" +
      "HTTP/1.1 200 OK" + CRLF +
      "Server: nginx/0.8.54" + CRLF +
      "Date: Mon, 02 Jan 2012 02:33:17 GMT" + CRLF +
      "Content-Type: text/html" + CRLF +
      "Content-Length: 92" + CRLF +
      "Connection: Keep-Alive" + CRLF +
      CRLF +
      "<!DOCTYPE html><html><body><h1>My First Heading</h1><p>My first paragraph.</p></body></html>";

  private static final String valid404 = "" +
      "HTTP/1.1 404 Not Found" + CRLF +
      "Server: nginx/0.8.54" + CRLF +
      "Date: Mon, 02 Jan 2012 02:33:17 GMT" + CRLF +
      "Content-Type: text/html" + CRLF +
      "Content-Length: 172" + CRLF +
      "Connection: Keep-Alive" + CRLF +
      CRLF +
      "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body bgcolor=\"white\"><center><h1>404 Not Found</h1></center><hr><center>nginx/0.8.54</center></body></html>";

  @Test
  void parseValid200() {
    var eresponse = HttpResponse.parse(valid200);
    assertTrue(eresponse.isRight());

    var response = eresponse.get();
    assertTrue(response.isValid());
    System.out.println(response);
  }

  @Test
  void parseValid404() {
    var eresponse = HttpResponse.parse(valid404);
    assertTrue(eresponse.isRight());

    var response = eresponse.get();
    assertTrue(response.isValid());
    System.out.println(response);
  }

  @Test
  void buildValid200() {
    var eresponse = HttpResponse.build(HttpConstants.HTTPV11, HttpConstants.OK_200[0], HttpConstants.OK_200[1])
        .flatMap(req -> req.setHeader("Server", "nginx/0.8.54"))
        .flatMap(req -> req.setHeader("Date", "02 Jan 2012 02:33:17 GMT"))
        .flatMap(req -> req.setHeader("Content-Type", "text/html"))
        .flatMap(req -> req.setHeader("Content-Length", "92"))
        .flatMap(req -> req.setHeader("Connection", "Keep-Alive"))
        .flatMap(req -> req
            .setBody("<!DOCTYPE html><html><body><h1>My First Heading</h1><p>My first paragraph.</p></body></html>"));

    assertTrue(eresponse.isRight());

    var response = eresponse.get();
    assertTrue(response.isValid());
    System.out.println(response);
  }

  @Test
  void buildValid404() {
    var eresponse = HttpResponse.build(HttpConstants.HTTPV11, HttpConstants.NOT_FOUND_404[0], HttpConstants.NOT_FOUND_404[1])
        .flatMap(req -> req.setHeader("Server", "nginx/0.8.54"))
        .flatMap(req -> req.setHeader("Date", "02 Jan 2012 02:33:17 GMT"))
        .flatMap(req -> req.setHeader("Content-Type", "text/html"))
        .flatMap(req -> req.setHeader("Content-Length", "172"))
        .flatMap(req -> req.setHeader("Connection", "Keep-Alive")).flatMap(req -> req
            .setBody(
                "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body bgcolor=\"white\"><center><h1>404 Not Found</h1></center><hr><center>nginx/0.8.54</center></body></html>"));

    assertTrue(eresponse.isRight());

    var response = eresponse.get();
    assertTrue(response.isValid());
    System.out.println(response);
  }

}

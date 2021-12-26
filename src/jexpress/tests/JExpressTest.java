package jexpress.tests;

import static org.junit.Assert.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import http.HttpRequest;
import http.HttpResponse;
import jexpress.JExpress;
import utils.Wrapper;

public class JExpressTest {

  @Test
  public void testPost() {

    var jexpress = new JExpress();

    var usersCalled = Wrapper.of(false);
    var usersIdCalled = Wrapper.of(false);
    var usersResponse = Wrapper.of("");
    var usersIdResponse = Wrapper.of("");

    jexpress.post("/users", (request, params, reply) -> {

      usersCalled.value = true;
      usersIdCalled.value = false;

      var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_200[0], HttpResponse.CODE_200[1])
          .flatMap(req -> req.setHeader("Server", "nginx/0.8.54"))
          .flatMap(req -> req.setHeader("Date", "02 Jan 2012 02:33:17 GMT"))
          .flatMap(req -> req.setHeader("Content-Type", "text/html"))
          .flatMap(req -> req.setHeader("Connection", "Keep-Alive"))
          .flatMap(req -> req
              .setBody("<!DOCTYPE html><html><body><h1>Users</h1></body></html>"));

      reply.accept(response);

      usersResponse.value = response.toString();
    });

    jexpress.post("/users/:id", (request, params, reply) -> {

      usersCalled.value = false;
      usersIdCalled.value = true;

      var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_200[0], HttpResponse.CODE_200[1])
          .flatMap(req -> req.setHeader("Server", "nginx/0.8.54"))
          .flatMap(req -> req.setHeader("Date", "02 Jan 2012 02:33:17 GMT"))
          .flatMap(req -> req.setHeader("Content-Type", "text/html"))
          .flatMap(req -> req.setHeader("Connection", "Keep-Alive"))
          .flatMap(req -> req
              .setBody("<!DOCTYPE html><html><body><h1>User " + params.get("id") + "</h1></body></html>"));

      reply.accept(response);

      usersIdResponse.value = response.toString();
    });

    var usersRequest = HttpRequest.build("POST")
        .flatMap(req -> req.setRequestTarget("/users"))
        .flatMap(req -> req.setHTTPVersion(HttpRequest.HTTPV11))
        .flatMap(req -> req.setBody("Random Body"))
        .get();

    jexpress.handle(usersRequest);

    assertTrue(usersCalled.value);
    assertFalse(usersIdCalled.value);

    var usersIdRequest = HttpRequest.build("POST")
        .flatMap(req -> req.setRequestTarget("/users/123456"))
        .flatMap(req -> req.setHTTPVersion(HttpRequest.HTTPV11))
        .flatMap(req -> req.setBody("Random Body"))
        .get();

    jexpress.handle(usersIdRequest);

    assertFalse(usersCalled.value);
    assertTrue(usersIdCalled.value);

    assertNotEquals(usersResponse.value, "");
    assertNotEquals(usersIdResponse.value, "");

    System.out.println(usersResponse.value);
    System.out.println(usersIdResponse.value);

  }

  @Test
  public void testMiddlewares() {

    var jexpress = new JExpress();

    var usersResponse = Wrapper.of("");
    var usersIdResponse = Wrapper.of("");

    var failedAuthResponseSent = Wrapper.of(false);

    var usersRequest = HttpRequest.build("GET")
        .flatMap(req -> req.setRequestTarget("/users"))
        .flatMap(req -> req.setHTTPVersion(HttpRequest.HTTPV11))
        .get();

    var usersIdRequest = HttpRequest.build("GET")
        .flatMap(req -> req.setRequestTarget("/users/123456"))
        .flatMap(req -> req.setHeader("Authorization", "Bearer "
            + "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJjaWFvIjoiSW8gbWkgY2hpYW1vIEFuZHJlYSBTaW1vbmUgQ29zdGEifQ.DLVLk4dpKiZQ8DzsteywZY01zuPhJa55Msu3_JwYP-k"))
        .flatMap(req -> req.setHTTPVersion(HttpRequest.HTTPV11))
        .get();

    jexpress.get("/users", (request, params, reply) -> {

      var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_200[0], HttpResponse.CODE_200[1])
          .flatMap(req -> req.setHeader("Server", "nginx/0.8.54"))
          .flatMap(req -> req.setHeader("Date", "02 Jan 2012 02:33:17 GMT"))
          .flatMap(req -> req.setHeader("Content-Type", "text/html"))
          .flatMap(req -> req.setHeader("Connection", "Keep-Alive"))
          .flatMap(req -> req
              .setBody("<!DOCTYPE html><html><body><h1>Users</h1></body></html>"));

      reply.accept(response);

      usersResponse.value = response.toString();

    });

    jexpress.get("/users/:id", (request, params, reply) -> {

      var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_200[0], HttpResponse.CODE_200[1])
          .flatMap(req -> req.setHeader("Server", "nginx/0.8.54"))
          .flatMap(req -> req.setHeader("Date", "02 Jan 2012 02:33:17 GMT"))
          .flatMap(req -> req.setHeader("Content-Type", "text/html"))
          .flatMap(req -> req.setHeader("Connection", "Keep-Alive"))
          .flatMap(req -> req
              .setBody("<!DOCTYPE html><html><body><h1>User " + params.get("id") + "</h1></body></html>"));

      reply.accept(response);

      usersIdResponse.value = response.toString();
    });

    jexpress.use((request, params, reply, next) -> {
      System.out.println("The request is:\n" + request + "\n");
      next.run(); // run the next middleware or the route hander
    });

    jexpress.use((request, params, reply, next) -> {

      if (request.getHeaders().containsKey("Authorization")) {
        // run the next middleware or the route handler only if the user is authorized
        next.run();
      } else {
        var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.CODE_401[0], HttpResponse.CODE_401[1])
            .flatMap(req -> req.setHeader("Server", "nginx/0.8.54"))
            .flatMap(req -> req.setHeader("Date", "02 Jan 2012 02:33:17 GMT"))
            .flatMap(req -> req.setHeader("Content-Type", "text/html"))
            .flatMap(req -> req.setHeader("Connection", "Keep-Alive"))
            .flatMap(req -> req
                .setBody("<!DOCTYPE html><html>Unauthorized</html>"));

        reply.accept(response);

        failedAuthResponseSent.value = true;
      }
    });

    jexpress.handle(usersRequest);
    jexpress.handle(usersIdRequest);

    assertEquals(usersResponse.value, "");
    assertNotEquals(usersIdResponse.value, "");

    assertTrue(failedAuthResponseSent.value);

    System.out.println(usersResponse.value + "\n");
    System.out.println(usersIdResponse.value + "\n");

  }
}

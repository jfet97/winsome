package jexpress.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import http.HttpRequest;
import http.HttpResponse;
import jexpress.JExpress;

public class JExpressTest {

  private static class Wrapper<T> {
    public T value;

    private Wrapper(T value) {
      this.value = value;
    }

    public static <T> Wrapper<T> of(T value) {
      return new Wrapper<T>(value);
    }
  }

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
          .flatMap(req -> req.setHeader("Content-Length", "92"))
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
          .flatMap(req -> req.setHeader("Content-Length", "92"))
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

    System.out.println(usersResponse.value);
    System.out.println(usersIdResponse.value);

  }
}

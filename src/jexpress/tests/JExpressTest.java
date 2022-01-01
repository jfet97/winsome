package jexpress.tests;

import static org.junit.Assert.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.Date;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;

import org.junit.jupiter.api.Test;

import domain.user.User;
import http.HttpRequest;
import http.HttpResponse;
import jexpress.JExpress;
import secrets.Secrets;
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

      var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.OK_200[0], HttpResponse.OK_200[1])
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

      var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.OK_200[0], HttpResponse.OK_200[1])
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

    var algorithm = Algorithm.HMAC256(Secrets.JWT_SIGN_SECRET);
    // var algorithm = Algorithm.HMAC256(Secrets.JWT_SIGN_SECRET + " "); // invalid signature
    var cal = Calendar.getInstance();
    cal.setTimeInMillis(new Date().getTime());
    cal.add(Calendar.DATE, 1);
    // cal.add(Calendar.DATE, -1); // expected expiration error
    var jwt = JWT.create()
        .withExpiresAt(cal.getTime())
        .withClaim("username", "meulno")
        .sign(algorithm);

    var usersIdRequest = HttpRequest.build("GET")
        .flatMap(req -> req.setRequestTarget("/users/123456"))
        .flatMap(req -> req.setHeader("Authorization", "Bearer " + jwt))
        .flatMap(req -> req.setHTTPVersion(HttpRequest.HTTPV11))
        .get();

    jexpress.get("/users", (request, params, reply) -> {

      var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.OK_200[0], HttpResponse.OK_200[1])
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

      var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.OK_200[0], HttpResponse.OK_200[1])
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

      var token = request.getHeaders().get("Authorization");
      var error = false;

      try {
        var verifier = JWT.require(Algorithm.HMAC256(Secrets.JWT_SIGN_SECRET))
            .withClaimPresence("username")
            .build(); // Reusable verifier instance
        var dec = verifier.verify(token.substring(7));

        var usernameClaim = dec.getClaim("username");

        if (usernameClaim.isNull()) {
          throw new RuntimeException();
        }

        request.context = User.of(usernameClaim.asString(), "INVALD_USER", null);

        // run the next middleware or the route handler only if the user is authorized
        next.run();

      } catch (JWTVerificationException e) {
        // Invalid signature/claims e.g. token expired
        // reply accordingly
        System.out.println(e.getMessage());
        error = true;
      } catch (Exception e) {
        // reply accordingly
        e.printStackTrace();
        error = true;
      }

      if (error) {
        var response = HttpResponse.build(HttpResponse.HTTPV11, HttpResponse.UNAUTHORIZED_401[0], HttpResponse.UNAUTHORIZED_401[1])
            .flatMap(req -> req.setHeader("Server", "nginx/0.8.54"))
            .flatMap(req -> req.setHeader("Date", "02 Jan 2012 02:33:17 GMT"))
            .flatMap(req -> req.setHeader("Content-Type", "text/html"))
            .flatMap(req -> req.setHeader("Connection", "Keep-Alive"))
            .flatMap(req -> req
                .setBody("<!DOCTYPE html><html>Unauthorized</html>"));

        reply.accept(response);

        failedAuthResponseSent.value = true;
      }

      System.out.println("-------------------------------------------\n");
    });

    jexpress.use((request, params, reply, next) -> {
      System.out.println("Using context...");
      var user = (User) request.context;

      assertEquals(user.username, "meulno");

      next.run(); // run the next middleware or the route hander
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

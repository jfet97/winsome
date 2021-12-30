package server;

import java.io.File;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.fasterxml.jackson.databind.ObjectMapper;

import domain.feedback.Feedback;
import domain.post.Post;
import domain.user.User;
import http.HttpResponse;
import io.vavr.control.Either;
import jexpress.JExpress;
import secrets.Secrets;
import utils.Wrapper;
import winsome.Winsome;

public class MainServer {
  public static void main(String[] args) {

    var port = 12345;
    var ip = "192.168.1.113";

    var objectMapper = new ObjectMapper();

    var jexpress = JExpress.of();
    var winsome = Wrapper.of(Winsome.of());

    try {
      winsome.value = objectMapper.readValue(
          new File("/Volumes/PortableSSD/MacMini/UniPi/Reti/Winsome/src/server/winsome.json"), Winsome.class);
    } catch (Exception e) {
    }

    var walletThread = new Thread(winsome.value.makeWalletRunnable(2000L, 70).get());
    var persistenceThread = new Thread(winsome.value.makePersistenceRunnable(500L,
        "/Volumes/PortableSSD/MacMini/UniPi/Reti/Winsome/src/server/winsome.json", false).get());

    // auth middleware
    jexpress.use((req, params, reply, next) -> {

      var target = req.getRequestTarget();
      if (target.equals("/login") || target.equals("/register")) {
        // auth for these two routes is not needed
        next.run();
      }

      var token = req.getHeaders().get("Authorization");
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

        req.context = User.of(usernameClaim.asString(), "INVALD_USER", null);

        // run the next middleware or the route handler only if the user is authorized
        next.run();

      } catch (JWTVerificationException e) {
        // Invalid signature/claims e.g. token expired
        // reply accordingly
        System.out.println(e.getMessage());
        error = true;
      } catch (Exception e) {
        // reply accordingly
        error = true;
      }

      if (error) {
        var response = HttpResponse.build401(
            Feedback.error("unauthorized").toJSON(),
            HttpResponse.MIME_APPLICATION_JSON, true);

        reply.accept(response);
      }

    });

    jexpress.get("/", (req, params, reply) -> {
      reply.accept(
          HttpResponse.build200("<html><h1>Welcome to Winsome!</h1></html>", HttpResponse.MIME_TEXT_HTML, true));
    });

    jexpress.post("/register", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = objectMapper.readValue(req.getBody(), User.class);

        toRet = winsome.value
            .register(user.username, user.password, user.tags)
            .flatMap(u -> HttpResponse.build200(
                Feedback.right(user.username + " is now part of the Winsome universe!").toJSON(),
                HttpResponse.MIME_APPLICATION_JSON, true));

      } catch (Exception e) {
        toRet = Either.left("invalid body: " + e.getMessage());
      }

      reply.accept(toRet);
    });

    jexpress.post("/login", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = objectMapper.readValue(req.getBody(), User.class);

        toRet = winsome.value
            .login(user.username, user.password)
            .flatMap(jwt -> HttpResponse.build200(
                Feedback.right(jwt).toJSON(),
                HttpResponse.MIME_APPLICATION_JSON, true));

      } catch (Exception e) {
        toRet = Either.left("invalid body: " + e.getMessage());
      }

      reply.accept(toRet);
    });

    jexpress.get("/logout", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        toRet = winsome.value
            .logout(user.username)
            .flatMap(
                __ -> HttpResponse.build200(Feedback.right("logged out").toJSON(),
                    HttpResponse.MIME_APPLICATION_JSON, true));

      } catch (Exception e) {
        toRet = Either.left("invalid body: " + e.getMessage());
      }

      reply.accept(toRet);
    });

    jexpress.post("/posts", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;
        var post = objectMapper.readValue(req.getBody(), Post.class);

        toRet = winsome.value
            .createPost(user.username, post.title, post.content)
            .flatMap(
                p -> HttpResponse.build200(Feedback.right(p.toJSON()).toJSON(),
                    HttpResponse.MIME_APPLICATION_JSON, true));

      } catch (Exception e) {
        toRet = Either.left("invalid body: " + e.getMessage());
      }

      reply.accept(toRet);
    });

    jexpress.delete("/posts/:uuid", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        toRet = winsome.value
            .deletePost(user.username, params.get("uuid"))
            .flatMap(
                p -> HttpResponse.build200(Feedback.right(p.toJSON()).toJSON(),
                    HttpResponse.MIME_APPLICATION_JSON, true));

      } catch (Exception e) {
        toRet = Either.left("invalid body: " + e.getMessage());
      }

      reply.accept(toRet);
    });

    var server = Server.of(jexpress, ip, port);
    var serverThread = new Thread(server);

    serverThread.start();
    walletThread.start();
    persistenceThread.start();
    try {
      serverThread.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

}

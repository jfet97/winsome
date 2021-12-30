package server;

import com.fasterxml.jackson.databind.ObjectMapper;

import domain.feedback.Feedback;
import domain.user.User;
import http.HttpResponse;
import io.vavr.control.Either;
import jexpress.JExpress;
import winsome.Winsome;

public class MainServer {
  public static void main(String[] args) {

    var port = 12345;
    var ip = "192.168.1.113";

    var jexpress = JExpress.of();
    var winsome = Winsome.of();

    var objectMapper = new ObjectMapper();

    jexpress.get("/", (req, params, reply) -> {
      reply.accept(
          HttpResponse.build200("<html><h1>Welcome to Winsome!</h1></html>", HttpResponse.MIME_TEXT_HTML, true));
    });

    jexpress.post("/register", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = objectMapper.readValue(req.getBody(), User.class);

        toRet = winsome
            .register(user.username, user.password, user.tags)
            .flatMap(u -> HttpResponse.build200(
                Feedback.right(user.username + " is now part of the Winsome universe!").toJSON(),
                HttpResponse.MIME_APPLICATION_JSON, true));

      } catch (Exception e) {
        toRet = Either.left("invalid body: " + e.getMessage());
      }

      reply.accept(toRet);
    });

    var server = Server.of(jexpress, ip, port);
    var serverThread = new Thread(server);

    serverThread.start();
    try {
      serverThread.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

}

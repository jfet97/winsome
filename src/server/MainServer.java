package server;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import domain.feedback.Feedback;
import domain.post.Post;
import domain.user.User;
import http.HttpResponse;
import io.vavr.control.Either;
import jexpress.JExpress;
import secrets.Secrets;
import utils.ToJSON;
import utils.Wrapper;
import winsome.Winsome;

public class MainServer {

  private static String USERS_ROUTE = "/users";
  private static String FOLLOWERS_ROUTE = "/followers";
  private static String FOLLOWING_ROUTE = "/following";
  private static String POSTS_ROUTE = "/posts";
  private static String LOGIN_ROUTE = "/login";
  private static String LOGOUT_ROUTE = "/logout";
  private static String BLOG_ROUTE = "/blog";
  private static String FEED_ROUTE = "/feed";
  private static String COMMENTS_ROUTE = "/comments";
  private static String REACTIONS_ROUTE = "/reactions";

  public static void main(String[] args) {

    var port = 12345;
    var ip = "192.168.1.113";

    var objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    var jexpress = JExpress.of();
    var winsome = Winsome.of();

    try {
      winsome = objectMapper.readValue(
          new File("/Volumes/PortableSSD/MacMini/UniPi/Reti/Winsome/src/server/winsome.json"), Winsome.class);
    } catch (Exception e) {
    }

    var walletThread = new Thread(winsome.makeWalletRunnable(2000L, 70).get());
    var persistenceThread = new Thread(winsome.makePersistenceRunnable(500L,
        "/Volumes/PortableSSD/MacMini/UniPi/Reti/Winsome/src/server/winsome.json", false).get());

    addJExpressHandlers(jexpress, objectMapper, winsome);

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

  private static void addJExpressHandlers(JExpress jexpress, ObjectMapper objectMapper, Winsome winsome) {
    // auth middleware
    jexpress.use((req, params, reply, next) -> {

      var target = req.getRequestTarget();
      var method = req.getMethod();
      if (target.equals(LOGIN_ROUTE) || (target.equals(USERS_ROUTE) && method.equals("POST"))) {
        // auth not needed when a user tries to login
        // auth not needed when a user tries to sign up
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
        error = true;
      } catch (Exception e) {
        // reply accordingly
        error = true;
      }

      if (error) {
        var response = HttpResponse.build401(
            Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
            HttpResponse.MIME_APPLICATION_JSON, true);

        reply.accept(response);
      }

    });

    // welcome page
    jexpress.get("/", (req, params, reply) -> {
      reply.accept(
          HttpResponse.build200("<html><h1>Welcome to Winsome!</h1></html>", HttpResponse.MIME_TEXT_HTML, true));
    });

    // register new user
    jexpress.post(USERS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = objectMapper.readValue(req.getBody(), User.class);

        toRet = winsome
            .register(user.username, user.password, user.tags)
            .flatMap(u -> HttpResponse.build201(
                Feedback.right(
                    ToJSON.toJSON(user.username
                        + " is now part of the Winsome universe!"))
                    .toJSON(),
                HttpResponse.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(ToJSON.toJSON(err)).toJSON(),
                HttpResponse.MIME_APPLICATION_JSON,
                true));

      } catch (JsonProcessingException e) {
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpResponse.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);
    });

    // login
    jexpress.post(LOGIN_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = objectMapper.readValue(req.getBody(), User.class);

        toRet = winsome
            .login(user.username, user.password)
            .flatMap(jwt -> HttpResponse.build200(
                Feedback.right(ToJSON.toJSON(jwt)).toJSON(),
                HttpResponse.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(ToJSON.toJSON(err)).toJSON(),
                HttpResponse.MIME_APPLICATION_JSON,
                true));

      } catch (JsonProcessingException e) {
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpResponse.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);
    });

    // logout
    jexpress.post(LOGOUT_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        toRet = winsome
            .logout(user.username)
            .flatMap(__ -> HttpResponse.build200(
                Feedback.right(
                    ToJSON.toJSON("logged out")).toJSON(),
                HttpResponse.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(ToJSON.toJSON(err)).toJSON(),
                HttpResponse.MIME_APPLICATION_JSON,
                true));

      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);
    });

    // add new post
    jexpress.post(USERS_ROUTE + "/:user_id" + POSTS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        // an user is authorized to create posts only for itself
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build401(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        } else {
          var post = objectMapper.readValue(req.getBody(), Post.class);

          toRet = winsome
              .createPost(user.username, post.title, post.content)
              .flatMap(
                  p -> HttpResponse.build200(Feedback.right(p.toJSON()).toJSON(),
                      HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        }

      } catch (JsonProcessingException e) {
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpResponse.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);
    });

    // delete a post
    jexpress.delete(USERS_ROUTE + "/:user_id" + POSTS_ROUTE + "/:post_id", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        // an user is authorized to delete only own posts
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build401(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        } else {
          toRet = winsome
              .deletePost(user.username, params.get("post_id"))
              .flatMap(p -> HttpResponse.build200(
                  Feedback.right(p.toJSON()).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        }

      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);
    });

    // view a post
    jexpress.get(USERS_ROUTE + "/:user_id" + POSTS_ROUTE + "/:post_id", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // authenticated user
        var user = (User) req.context;

        // it seems that any user can see any other user's post

        toRet = winsome
            .showPost(user.username, params.get("user_id"), params.get("post_id"))
            .flatMap(p -> HttpResponse.build200(
                Feedback.right(p.toJSON()).toJSON(),
                HttpResponse.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(ToJSON.toJSON(err)).toJSON(),
                HttpResponse.MIME_APPLICATION_JSON,
                true));

      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);
    });

    // get a list of users with at least one common tag with the requestor
    jexpress.get(USERS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        toRet = winsome
            .listUsers(user.username)
            .map(us -> ToJSON.sequence(us.stream().map(ToJSON::toJSON).collect(Collectors.toList())))
            .flatMap(
                jus -> HttpResponse.build200(Feedback.right(jus).toJSON(),
                    HttpResponse.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(ToJSON.toJSON(err)).toJSON(),
                HttpResponse.MIME_APPLICATION_JSON,
                true));

      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);

    });

    // get a list of users which follows the requestor
    jexpress.get(USERS_ROUTE + "/:user_id" + FOLLOWERS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        // an user is authorized to see only its own followers
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build401(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        } else {
          toRet = winsome
              .listFollowers(user.username)
              .map(us -> ToJSON.sequence(us.stream().map(ToJSON::toJSON).collect(Collectors.toList())))
              .flatMap(
                  jus -> HttpResponse.build200(Feedback.right(jus).toJSON(),
                      HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        }

      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);

    });

    // get a list of users followed by requestor
    jexpress.get(USERS_ROUTE + "/:user_id" + FOLLOWING_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        // an user is authorized to see only which users are followed by him
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build401(Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        } else {
          toRet = winsome
              .listFollowing(user.username)
              .map(us -> ToJSON.sequence(us.stream().map(ToJSON::toJSON).collect(Collectors.toList())))
              .flatMap(jus -> HttpResponse.build200(Feedback.right(jus).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        }

      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);

    });

    // follow an user
    jexpress.post(USERS_ROUTE + "/:user_id" + FOLLOWING_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;
        var userToFollow = objectMapper.readValue(req.getBody(), User.class);

        // an user cannot force another user to follow someone
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build401(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        } else {
          toRet = winsome
              .followUser(user.username, userToFollow.username)
              .flatMap(__ -> HttpResponse.build200(
                  Feedback.right(
                      ToJSON.toJSON("now " + user.username + " is following " + userToFollow.username))
                      .toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        }

      } catch (JsonProcessingException e) {
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpResponse.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);

    });

    // unfollow an user
    jexpress.delete(USERS_ROUTE + "/:user_id" + FOLLOWING_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;
        var userToUnfollow = objectMapper.readValue(req.getBody(), User.class);

        // an user cannot force another user to unfollow someone
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build401(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        } else {
          toRet = winsome
              .unfollowUser(user.username, userToUnfollow.username)
              .flatMap(__ -> HttpResponse.build200(
                  Feedback.right(
                      ToJSON.toJSON(user.username + " has unfollowed " + userToUnfollow.username))
                      .toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        }

      } catch (JsonProcessingException e) {
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpResponse.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);

    });

    // get the blog of a user
    jexpress.get(USERS_ROUTE + "/:user_id" + BLOG_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        // an user is authorized to see only its own blog
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build401(Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        } else {
          toRet = winsome
              .viewBlog(user.username)
              .map(ps -> ps
                  .stream()
                  .map(p -> p.toJSON())
                  .collect(Collectors.toList()))
              .map(ps -> ToJSON.sequence(ps))
              .flatMap(jps -> HttpResponse.build200(Feedback.right(jps).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        }

      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);

    });

    // get the feed of a user
    jexpress.get(USERS_ROUTE + "/:user_id" + FEED_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        // an user is authorized to see only its own feed
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build401(Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        } else {
          toRet = winsome
              .showFeed(user.username)
              .map(ps -> ps
                  .stream()
                  .map(p -> p.toJSON())
                  .collect(Collectors.toList()))
              .map(ps -> ToJSON.sequence(ps))
              .flatMap(jps -> HttpResponse.build200(Feedback.right(jps).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        }

      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);

    });

  }

}

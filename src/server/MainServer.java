package server;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.stream.Collectors;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import domain.comment.Comment;
import domain.feedback.Feedback;
import domain.jwt.WinsomeJWT;
import domain.post.Post;
import domain.reaction.Reaction;
import domain.user.User;
import http.HttpResponse;
import io.vavr.control.Either;
import jexpress.JExpress;
import secrets.Secrets;
import server.RMI.RemoteServer;
import server.RMI.IRemoteServer;
import utils.Pair;
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
  private static String WALLET_ROUTE = "/wallet";

  public static void main(String[] args) throws RemoteException, UnknownHostException, SocketException {

    // configs
    var tcp_port = 12345;
    var remote_registry_port = 7777;
    var udp_port = 33333;
    var multicast_port = 44444;
    var multicast_ip = "239.255.32.32";
    var server_ip = "192.168.1.113";
    var persistence_path = "/Volumes/PortableSSD/MacMini/UniPi/Reti/Winsome/src/server/winsome.json";
    var author_perc = 70;
    var persistence_interval = 500L;
    var wallet_interval = 2000L;
    var stub_name = "winsome-asc";

    // main instances
    var objectMapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
    var jexpress = JExpress.of();
    var winsome = Winsome.of();

    // restore the server status from the json file
    try {
      winsome = objectMapper.readValue(
          new File(persistence_path), Winsome.class);
    } catch (Exception e) {
    }

    // RMI configuration
    var psr = configureRMI(winsome, remote_registry_port, stub_name);
    var stub = psr.snd();
    var remoteServer = psr.fst();

    // multicast configuration
    var pdm = configureMulticast(udp_port, multicast_ip);
    var ds = pdm.fst();
    var multicastGroup = pdm.snd();

    // wallet thread configuration
    var walletThread = new Thread(
        configureWalletThread(winsome, wallet_interval, author_perc, multicastGroup, multicast_port, ds));

    // persistence thread configuration
    var persistenceThread = new Thread(configurePersistenceThread(winsome, persistence_interval, persistence_path));

    // jexpress framework handlers
    configureJExpressHandlers(jexpress, objectMapper, winsome, multicast_ip + ":" + multicast_port);

    // server configuration
    var server = Server.of(jexpress, server_ip, tcp_port);
    var serverThread = new Thread(server);

    // start threads
    serverThread.start();
    walletThread.start();
    persistenceThread.start();
    try {
      serverThread.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    ds.close();
  }

  // -----------------------------
  // config methods

  private static Pair<RemoteServer, IRemoteServer> configureRMI(Winsome winsome, Integer remoteRegistryPort,
      String stubName)
      throws RemoteException {

    var remoteServerWrapped = Wrapper.<RemoteServer>of(null);

    remoteServerWrapped.value = RemoteServer.of(winsome, username -> {

      // when a new user log in, send to him all its current followers
      // replacing whatever the client had before
      winsome.synchronizedActionOnFollowersOfUser(username, fs -> {
        try {
          remoteServerWrapped.value.replaceAll(username, fs);
        } catch (RemoteException e) {
          e.printStackTrace();
        }
      });

    });

    var stub = (IRemoteServer) UnicastRemoteObject.exportObject(remoteServerWrapped.value, 0);
    LocateRegistry.createRegistry(remoteRegistryPort);
    LocateRegistry.getRegistry(remoteRegistryPort).rebind(stubName, stub);

    // when the set of followers of an user changes, notify the user
    winsome.setOnChangeFollowers((performer, receiver, hasFollowed) -> {
      // this callback may be called concurrently by multiple threads
      // only do thread safe operations

      try {
        remoteServerWrapped.value.notify(performer, receiver, hasFollowed);
      } catch (RemoteException e) {
        e.printStackTrace();
      }

    });

    return Pair.of(remoteServerWrapped.value, stub);
  }

  private static Pair<DatagramSocket, InetAddress> configureMulticast(Integer udp_port, String multicast_ip)
      throws UnknownHostException, SocketException {

    try (var ds = new DatagramSocket(udp_port);) {

      // create and check the validity of the multicast group
      var multicastGroup = InetAddress.getByName(multicast_ip);
      if (!multicastGroup.isMulticastAddress()) {
        ds.close();
        throw new IllegalArgumentException(multicast_ip + " is not a multicast address");
      }

      return Pair.of(ds, multicastGroup);
    }
  }

  private static Runnable configureWalletThread(Winsome winsome, Long wallet_interval, Integer author_perc,
      InetAddress multicastGroup, Integer multicast_port, DatagramSocket ds) {
    return winsome.makeWalletRunnable(wallet_interval, author_perc, () -> {
      var notification = "push";
      var notificationBytes = notification.getBytes();

      var dp = new DatagramPacket(notificationBytes, notificationBytes.length, multicastGroup, multicast_port);
      try {
        ds.send(dp);
        System.out.println("notification pushed");
      } catch (IOException e) {
        e.printStackTrace();
      }
    }).get();
  }

  private static Runnable configurePersistenceThread(Winsome winsome, Long persistence_interval,
      String persistence_path) {
    return winsome.makePersistenceRunnable(persistence_interval, persistence_path, false).get();
  }

  private static void configureJExpressHandlers(JExpress jexpress, ObjectMapper objectMapper, Winsome winsome,
      String multicastIpPort) {

    // auth middleware
    configureJExpressAuthMiddleware(jexpress);

    // multicast info
    jexpress.get("/multicast", (req, params, reply) -> {
      reply.accept(
          HttpResponse.build200(
              Feedback.right(
                  ToJSON.toJSON(
                      multicastIpPort))
                  .toJSON(),
              HttpResponse.MIME_APPLICATION_JSON, true));
    });

    // users
    configureJExpressUsersHandler(jexpress, objectMapper, winsome);

    // login, logout
    configureJExpressLoginLogoutHandlers(jexpress, objectMapper, winsome);

    // posts
    configureJExpressPostsHandlers(jexpress, objectMapper, winsome);

  }

  private static void configureJExpressAuthMiddleware(JExpress jexpress) {
    jexpress.use((req, params, reply, next) -> {

      var target = req.getRequestTarget();
      var method = req.getMethod();
      if (target.equals(LOGIN_ROUTE) || (target.equals(USERS_ROUTE) && method.equals("POST"))) {
        // auth not needed when a user tries to login
        // auth not needed when a someone tries to sign up
        next.run();
      }

      var token = req.getHeaders().get("Authorization");
      var errorMessage = "";

      try {

        var valRes = WinsomeJWT.validateJWT(Secrets.JWT_SIGN_SECRET, token.substring(7));

        if (valRes.isRight()) {
          req.context = valRes.get();
        } else {
          errorMessage = valRes.getLeft();
        }

      } catch (Exception e) {
        // reply accordingly
        errorMessage = "cannot authenticate user";
      }

      if (!errorMessage.equals("")) {
        var response = HttpResponse.build401(
            Feedback.error(ToJSON.toJSON(errorMessage)).toJSON(),
            HttpResponse.MIME_APPLICATION_JSON, true);

        reply.accept(response);
      } else {
        // run the next middleware or the route handler only if the user is authorized
        next.run();
      }

    });

  }

  private static void configureJExpressLoginLogoutHandlers(JExpress jexpress, ObjectMapper objectMapper,
      Winsome winsome) {

    // login
    jexpress.post(LOGIN_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = objectMapper.readValue(req.getBody(), User.class);

        toRet = winsome
            .login(user.username, user.password)
            .flatMap(jwtJSON -> HttpResponse.build200(
                Feedback.right(jwtJSON).toJSON(),
                HttpResponse.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(err.contains("jwt") && err.contains("message") ? err : ToJSON.toJSON(err)).toJSON(),
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

  }

  private static void configureJExpressUsersHandler(JExpress jexpress, ObjectMapper objectMapper, Winsome winsome) {

    // signup
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
          toRet = HttpResponse.build403(
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
          toRet = HttpResponse.build403(Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
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
          toRet = HttpResponse.build403(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        } else {
          toRet = winsome
              .followUser(user.username, userToFollow.username)
              .flatMap(__ -> HttpResponse.build200(
                  Feedback.right(
                      ToJSON.toJSON(user.username + " is following " + userToFollow.username))
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
          toRet = HttpResponse.build403(
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
          toRet = HttpResponse.build403(Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
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
          toRet = HttpResponse.build403(Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
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

    // get the wallet of a user
    jexpress.get(USERS_ROUTE + "/:user_id" + WALLET_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);
      var currency = req.getQueryParams().get("currency");
      var useWincoins = currency != null && currency.equals("wincoin");
      var useBitcoins = currency != null && currency.equals("bitcoin");

      try {
        var user = (User) req.context;

        // an user is authorized to see only its own wallet
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        } else {

          var ess = Either.<String, String>right(null);

          if (useWincoins) {
            ess = winsome
                .getUserWalletInWincoin(user.username)
                .map(ws -> ToJSON.toJSON(ws));

            ;
          } else if (useBitcoins) {
            ess = winsome
                .getUserWalletInBitcoin(user.username)
                .map(ws -> ToJSON.toJSON(ws));
          } else {
            ess = winsome.getUserWallet(user.username)
                .map(ws -> ws.stream().map(w -> w.toJSON()).collect(Collectors.toList()))
                .map(ws -> ToJSON.sequence(ws));
          }

          toRet = ess.flatMap(jps -> HttpResponse.build200(Feedback.right(jps).toJSON(),
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

  private static void configureJExpressPostsHandlers(JExpress jexpress, ObjectMapper objectMapper, Winsome winsome) {

    // add new post
    jexpress.post(USERS_ROUTE + "/:user_id" + POSTS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        var user = (User) req.context;

        // an user is authorized to create posts only for itself
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(
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
          toRet = HttpResponse.build403(
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

    // rewin a post
    jexpress.post(USERS_ROUTE + "/:user_id" + POSTS_ROUTE + "/:post_id", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);
      var queryParams = req.getQueryParams();

      try {
        // authenticated user
        var user = (User) req.context;

        if (queryParams.containsKey("rewin") && queryParams.get("rewin").equals("true")) {
          toRet = winsome
              .rewinPost(user.username, params.get("user_id"), params.get("post_id"))
              .flatMap(p -> HttpResponse.build200(
                  Feedback.right(p.toJSON()).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        } else {
          toRet = HttpResponse.build400(
              Feedback.error(ToJSON.toJSON("Missing 'rewin' param or 'rewin' param not true")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
        }

      } catch (Exception e) {
        toRet = Either.left(e.getMessage());
      }

      reply.accept(toRet);
    });

    // add a reaction to a post
    jexpress.post(USERS_ROUTE + "/:user_id" + POSTS_ROUTE + "/:post_id" + REACTIONS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // authenticated user
        var user = (User) req.context;

        var reaction = objectMapper.readValue(req.getBody(), Reaction.class);

        if (reaction.isUpvote != null && reaction.isUpvote instanceof Boolean) {
          toRet = winsome
              .ratePost(user.username, params.get("user_id"), params.get("post_id"), reaction.isUpvote)
              .flatMap(r -> HttpResponse.build200(
                  Feedback.right(r.toJSON()).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        } else {
          toRet = HttpResponse.build400(
              Feedback.error(ToJSON.toJSON("Missing boolean 'isUpvote' field")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
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

    // add a comment to a post
    jexpress.post(USERS_ROUTE + "/:user_id" + POSTS_ROUTE + "/:post_id" + COMMENTS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // authenticated user
        var user = (User) req.context;

        var comment = objectMapper.readValue(req.getBody(), Comment.class);

        if (comment.text != null && comment.text instanceof String) {
          toRet = winsome
              .addComment(user.username, params.get("user_id"), params.get("post_id"), comment.text)
              .flatMap(c -> HttpResponse.build200(
                  Feedback.right(c.toJSON()).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpResponse.MIME_APPLICATION_JSON,
                  true));
        } else {
          toRet = HttpResponse.build400(
              Feedback.error(ToJSON.toJSON("Missing string 'text' field")).toJSON(),
              HttpResponse.MIME_APPLICATION_JSON,
              true);
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

  }

}

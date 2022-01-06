package server;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import domain.comment.Comment;
import domain.feedback.Feedback;
import domain.jwt.WinsomeJWT;
import domain.post.Post;
import domain.reaction.Reaction;
import domain.user.User;
import domain.user.UserTags;
import http.HttpConstants;
import http.HttpResponse;
import io.vavr.control.Either;
import jexpress.JExpress;
import server.RMI.RemoteServer;
import server.RMI.IRemoteServer;
import utils.Pair;
import utils.ToJSON;
import utils.Wrapper;
import winsome.Winsome;

public class ServerMain {

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

    if (args.length != 1) {
      System.out.println("Missing server configuration file.\nUse: java ServerMain path/to/config.json");
      return;
    }

    // main instances
    var objectMapper = new ObjectMapper();
    var jexpress = JExpress.of();

    // read the config file
    var config = readConfigFile(args, objectMapper);

    // restore the server status from the json file
    // or create a new instance if it is not possible
    var winsome = restoreServer(objectMapper, config.persistence_path);

    // set the jwt secret (used internally to ccreate access tokens)
    winsome.setJWTSecret(config.jwt_secret);

    // RMI configuration
    var psr = configureRMI(winsome, config.remote_registry_port, config.stub_name);
    var stub = psr.snd();
    var remoteServer = psr.fst();

    // multicast configuration
    var pdm = configureMulticast(config.udp_port, config.multicast_ip);
    var ds = pdm.fst();
    var multicastGroup = pdm.snd();

    // wallet thread configuration
    var walletThread = new Thread(
        configureWalletThread(winsome, config.wallet_interval, config.author_percentage, multicastGroup,
            config.multicast_port, ds));

    // persistence thread configuration
    var persistenceThread = new Thread(
        configurePersistenceThread(winsome, config.persistence_interval, config.persistence_path));

    // jexpress framework handlers
    configureJExpressHandlers(jexpress, objectMapper, winsome, config.jwt_secret,
        config.multicast_ip + ":" + config.multicast_port);

    // server configuration
    var server = Server.of(jexpress, config.server_ip, config.tcp_port);
    var serverThread = new Thread(server);

    // start threads
    serverThread.start();
    walletThread.start();
    persistenceThread.start();

    System.out.println("Server has started");

    try {
      serverThread.join();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    ds.close();
  }

  // -----------------------------
  // methods

  // try to read the configuration file
  private static ServerConfig readConfigFile(String[] args, ObjectMapper objectMapper) {
    try {
      var config = objectMapper.readValue(new File(args[0]), ServerConfig.class);

      if (!config.isValid()) {
        throw new RuntimeException("invalid configuration");
      } else {
        return config;
      }
    } catch (Exception e) {
      throw new RuntimeException("cannot parse server configuration file: " + e.getMessage());
    }
  }

  // try to restore the server state from a file
  private static Winsome restoreServer(ObjectMapper objectMapper, String persistencePath) {
    var toRet = (Winsome) null;
    try {
      toRet = objectMapper.readValue(
          new File(persistencePath), Winsome.class);

      // if jackson has put null somewhere because of an invalid
      // json file, an exception will be raised
      toRet.toJSON();
    } catch (Exception e) {
      e.printStackTrace();
      toRet = Winsome.of();
    }
    return toRet;
  }

  private static Pair<RemoteServer, IRemoteServer> configureRMI(Winsome winsome, Integer remoteRegistryPort,
      String stubName)
      throws RemoteException {

    var remoteServer = Wrapper.<RemoteServer>of(null);

    remoteServer.value = RemoteServer.of(winsome, (username, remoteClient) -> {
      // when a new user log in, send to him all its current followers
      // replacing whatever the client had before

      winsome.synchronizedActionOnFollowersOfUser(username, fs -> {
        // fs: list of usernames of the users that follow username
        var eres = Either.sequence(
            // get the tags of each follower
            fs
                .stream()
                .map(f -> winsome
                    .getUserTags(f)
                    .map(ts -> UserTags.of(f, ts)))
                .collect(Collectors.toList()))
            // collect all into a map <follower, list of its tags>
            .map(uts -> uts
                .asJava()
                .stream()
                .collect(Collectors.toMap(ut -> ut.username, ut -> ut.tags)))
            // collect the errors, if any
            .mapLeft(seq -> seq.mkString("\n"));

        if (eres.isRight()) {
          try {
            // notify the proper client
            remoteClient.replaceFollowers(eres.get());
          } catch (RemoteException e) {
            e.printStackTrace();
          }
        } else {
          System.out.println(eres.getLeft());
        }
      });

    });

    // usual RMI config
    var stub = (IRemoteServer) UnicastRemoteObject.exportObject(remoteServer.value, 0);
    LocateRegistry.createRegistry(remoteRegistryPort);
    LocateRegistry.getRegistry(remoteRegistryPort).rebind(stubName, stub);

    // when the set of followers of an user changes, notify the user
    winsome.setOnChangeFollowers((performer, receiver, hasFollowed) -> {
      // this callback may be called concurrently by multiple threads
      // only do thread safe operations

      try {
        // notify the client about the change
        remoteServer.value.notify(performer.username, performer.tags, receiver, hasFollowed);
      } catch (RemoteException e) {
        e.printStackTrace();
      }

    });

    return Pair.of(remoteServer.value, stub);
  }

  private static Pair<DatagramSocket, InetAddress> configureMulticast(Integer udp_port, String multicast_ip)
      throws UnknownHostException, SocketException {

    var ds = new DatagramSocket(udp_port);

    // create and check the validity of the multicast group
    var multicastGroup = InetAddress.getByName(multicast_ip);
    if (!multicastGroup.isMulticastAddress()) {
      ds.close();
      throw new IllegalArgumentException(multicast_ip + " is not a multicast address");
    }

    return Pair.of(ds, multicastGroup);

  }

  private static Runnable configureWalletThread(Winsome winsome, Long wallet_interval, Integer author_perc,
      InetAddress multicastGroup, Integer multicast_port, DatagramSocket ds) {
    return winsome.makeWalletRunnable(wallet_interval, author_perc, () -> {
      // this action will run each time the wallet has been updated

      var notification = "wallet updated";
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

  // jexpress :)
  private static void configureJExpressHandlers(JExpress jexpress, ObjectMapper objectMapper, Winsome winsome,
      String jwtSecret, String multicastIpPort) {

    // auth middleware
    configureJExpressAuthMiddleware(jexpress, winsome, jwtSecret);

    // CORS middleware
    configureJExpressCORSMiddleware(jexpress);

    // multicast info
    jexpress.get("/multicast", (req, params, reply) -> {
      reply.accept(
          HttpResponse.build200(
              Feedback.right(
                  ToJSON.toJSON(
                      multicastIpPort))
                  .toJSON(),
              HttpConstants.MIME_APPLICATION_JSON, true));
    });

    // users
    configureJExpressUsersHandlers(jexpress, objectMapper, winsome);

    // login, logout
    configureJExpressLoginLogoutHandlers(jexpress, objectMapper, winsome);

    // posts
    configureJExpressPostsHandlers(jexpress, objectMapper, winsome);

  }

  private static void configureJExpressAuthMiddleware(JExpress jexpress, Winsome winsome, String jwtSecret) {
    jexpress.use((req, params, reply, next) -> {

      var target = req.getRequestTarget();
      var method = req.getMethod();

      if (method.equals(HttpConstants.OPTIONS) || target.equals(LOGIN_ROUTE) ||
          (target.equals(USERS_ROUTE) && method.equals(HttpConstants.POST))) {
        // auth not needed when a user tries to login
        // auth not needed when a someone tries to sign up
        // auth not needed for preflight requests
        next.run();
        return;
      }

      var errorMessage = "";

      try {
        // jwt validation

        var jwt = req.getHeaders().get("Authorization").substring(7);

        // validate the jwt and extract the user that made the reqeust from it
        var euser = WinsomeJWT
            .validateJWT(jwtSecret, jwt)
            .flatMap(user -> winsome
                .getUserJWT(user.username)
                .flatMap(currJWT -> currJWT.equals(jwt) ? Either.right(user) : Either.left("invalid auth token")));

        if (euser.isRight()) {
          // set the context as the user
          req.context = euser.get();
        } else {
          errorMessage = euser.getLeft();
        }

      } catch (Exception e) {
        // reply accordingly
        errorMessage = "cannot authenticate user";
      }

      if (!errorMessage.equals("")) {
        // if an error has occurred, the requestor has not been authenticated
        // so reply with a 401 UNAUTHORIZED
        reply.accept(HttpResponse.build401(
            Feedback.error(ToJSON.toJSON(errorMessage)).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON, true));
      } else {
        // run the next middleware or the route handler only if the user is
        // authenticated
        next.run();
      }
    });

  }

  private static void configureJExpressCORSMiddleware(JExpress jexpress) {
    // needed by jexpress to be able to handle OPTIONS requests
    jexpress.options("/*", (req, params, reply) -> {
      reply.accept(HttpResponse.build500("", HttpConstants.MIME_TEXT_PLAIN, false));
    });

    // simply accept all OPTIONS requests
    // (needed headers are temporary added by the builder itself)
    jexpress.use((req, params, reply, next) -> {
      if (req.getMethod().equals(HttpConstants.OPTIONS)) {
        reply.accept(HttpResponse.build200("", HttpConstants.MIME_TEXT_PLAIN, true));
      } else {
        next.run();
      }

    });
  }

  private static void configureJExpressLoginLogoutHandlers(JExpress jexpress, ObjectMapper objectMapper,
      Winsome winsome) {

    // login
    jexpress.post(LOGIN_ROUTE, (req, params, reply) -> {

      var queryParams = req.getQueryParams();
      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // to extract username and password, interpret the body
        // as a User instance
        var user = objectMapper.readValue(req.getBody(), User.class);

        // should the login be forced although the user is already logged in?
        var forceLogin = false;
        if (queryParams.containsKey("force") && queryParams.get("force").equals("true")) {
          forceLogin = true;
        }

        toRet = winsome
            // try to login and reply accordingly with the result of the operation
            .login(user.username, user.password, forceLogin)
            .flatMap(jwtJSON -> HttpResponse.build200(
                Feedback.right(jwtJSON).toJSON(),
                HttpConstants.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> {
              // mask this error
              if (err.equals("INVALID_JWT_SECRET")) {
                return HttpResponse.build500(
                    Feedback.error(
                        ToJSON.toJSON("internal server error")).toJSON(),
                    HttpConstants.MIME_APPLICATION_JSON, false);
              } else {
                return HttpResponse.build400(
                    Feedback.error(
                        ToJSON.toJSON(err)).toJSON(),
                    HttpConstants.MIME_APPLICATION_JSON, true);
              }
            });

      } catch (JsonProcessingException e) {
        // wrong body in the request because jackson has failed
        e.printStackTrace();
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left("internal server error");
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // logout
    jexpress.post(LOGOUT_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        toRet = winsome
            // try to logout and reply accordingly with the result of the operation
            .logout(user.username)
            .flatMap(__ -> HttpResponse.build200(
                Feedback.right(
                    ToJSON.toJSON("logged out")).toJSON(),
                HttpConstants.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(ToJSON.toJSON(err)).toJSON(),
                HttpConstants.MIME_APPLICATION_JSON, true));

      } catch (Exception e) {
        e.printStackTrace();
        // something really bad has happened, jexpress will return a 500
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

  }

  private static void configureJExpressUsersHandlers(JExpress jexpress, ObjectMapper objectMapper, Winsome winsome) {

    // signup
    jexpress.post(USERS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // to extract username and password, interpret the body
        // as a User instance
        var user = objectMapper.readValue(req.getBody(), User.class);

        toRet = winsome
            // try to signup and reply accordingly with the result of the operation
            .register(user.username, user.password, user.tags)
            .flatMap(u -> HttpResponse.build201(
                Feedback.right(
                    ToJSON.toJSON(user.username
                        + " is now part of the Winsome universe!"))
                    .toJSON(),
                HttpConstants.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(ToJSON.toJSON(err)).toJSON(),
                HttpConstants.MIME_APPLICATION_JSON, true));

      } catch (JsonProcessingException e) {
        // wrong body in the request because jackson has failed
        e.printStackTrace();
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // get a user by its id
    jexpress.get(USERS_ROUTE + "/:user_id", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // try to list the users and reply accordingly with the result of the operation
        toRet = winsome
            .getUser(user.username)
            .flatMap(u -> HttpResponse.build200(
                Feedback.right(u.toJSON(false)).toJSON(),
                HttpConstants.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(ToJSON.toJSON(err)).toJSON(),
                HttpConstants.MIME_APPLICATION_JSON, true));

      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // get a list of users with at least one common tag with the requestor
    jexpress.get(USERS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // try to list the users and reply accordingly with the result of the operation
        toRet = winsome
            .listUsers(user.username)
            .flatMap(us -> {
              // us: list of usernames of the users
              return Either.sequence(
                  // get the tags of each user
                  us
                      .stream()
                      .map(u -> winsome
                          .getUserTags(u)
                          .map(ts -> UserTags.of(u, ts)))
                      .collect(Collectors.toList()))
                  // after each UserTags instance has been stringified
                  // convert the list into a json array
                  .map(uts -> ToJSON
                      .sequence(uts
                          .map(ut -> ut.ToJSON())
                          .asJava()))
                  // collect the errors, if any
                  .mapLeft(seq -> seq.mkString("\n"));
            })
            .flatMap(jus -> HttpResponse.build200(
                Feedback.right(jus).toJSON(),
                HttpConstants.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(ToJSON.toJSON(err)).toJSON(),
                HttpConstants.MIME_APPLICATION_JSON, true));

      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });
  
    // get a list of users which follows the requestor
    jexpress.get(USERS_ROUTE + "/:user_id" + FOLLOWERS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // an user is authorized to see only its own followers
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON,
              true);
        } else {
          // try to list the followers and reply accordingly with the result of the
          // operation
          toRet = winsome
              .listFollowers(user.username)
              .flatMap(us -> {
                // us: list of usernames of the followers
                return Either.sequence(
                    // get the tags of each user
                    us
                        .stream()
                        .map(u -> winsome
                            .getUserTags(u)
                            .map(ts -> UserTags.of(u, ts)))
                        .collect(Collectors.toList()))
                    // after each UserTags instance has been stringified
                    // convert the list into a json array
                    .map(uts -> ToJSON
                        .sequence(uts
                            .map(ut -> ut.ToJSON())
                            .asJava()))
                    // collect the errors, if any
                    .mapLeft(seq -> seq.mkString("\n"));
              })
              .flatMap(jus -> HttpResponse.build200(
                  Feedback.right(jus).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        }

      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // get a list of users followed by requestor
    jexpress.get(USERS_ROUTE + "/:user_id" + FOLLOWING_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // an user is authorized to see only which users are followed by him
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON,
              true);
        } else {
          // try to list the following and reply accordingly with the result of the
          // operation
          toRet = winsome
              .listFollowing(user.username)
              .flatMap(us -> {
                // us: list of usernames of the followers
                return Either.sequence(
                    // get the tags of each user
                    us
                        .stream()
                        .map(u -> winsome
                            .getUserTags(u)
                            .map(ts -> UserTags.of(u, ts)))
                        .collect(Collectors.toList()))
                    // after each UserTags instance has been stringified
                    // convert the list into a json array
                    .map(uts -> ToJSON
                        .sequence(uts
                            .map(ut -> ut.ToJSON())
                            .asJava()))
                    // collect the errors, if any
                    .mapLeft(seq -> seq.mkString("\n"));
              })
              .flatMap(jus -> HttpResponse.build200(
                  Feedback.right(jus).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        }

      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // follow an user
    jexpress.post(USERS_ROUTE + "/:user_id" + FOLLOWING_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // to extract the user to follow, interpret the body
        // as a User instance
        var userToFollow = objectMapper.readValue(req.getBody(), User.class);

        // an user cannot force another user to follow someone
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON, true);
        } else {
          // try to follow the user and reply accordingly with the result of the operation
          toRet = winsome
              .followUser(user.username, userToFollow.username)
              .flatMap(__ -> HttpResponse.build200(
                  Feedback.right(
                      ToJSON.toJSON(user.username + " is following " + userToFollow.username))
                      .toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON,
                  true));
        }

      } catch (JsonProcessingException e) {
        // wrong body in the request because jackson has failed
        e.printStackTrace();
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // unfollow an user
    jexpress.delete(USERS_ROUTE + "/:user_id" + FOLLOWING_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // to extract the user to follow, interpret the body
        // as a User instance
        var userToUnfollow = objectMapper.readValue(req.getBody(), User.class);

        // an user cannot force another user to unfollow someone
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON,
              true);
        } else {
          // try to unfollow the user and reply accordingly with the result of the
          // operation
          toRet = winsome
              .unfollowUser(user.username, userToUnfollow.username)
              .flatMap(__ -> HttpResponse.build200(
                  Feedback.right(
                      ToJSON.toJSON(user.username + " has unfollowed " + userToUnfollow.username))
                      .toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        }

      } catch (JsonProcessingException e) {
        // wrong body in the request because jackson has failed
        e.printStackTrace();
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // get the blog of a user
    jexpress.get(USERS_ROUTE + "/:user_id" + BLOG_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // an user is authorized to see only its own blog
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(
              Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON, true);
        } else {
          // try to view the blog and reply accordingly with the result of the operation
          toRet = winsome
              .viewBlog(user.username)
              .map(ps -> ps
                  .stream()
                  // serialize each post into json
                  .map(p -> p.toJSON())
                  .collect(Collectors.toList()))
              // the list becomes a json array
              .map(ps -> ToJSON.sequence(ps))
              .flatMap(jps -> HttpResponse.build200(
                  Feedback.right(jps).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        }

      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // get the feed of a user
    jexpress.get(USERS_ROUTE + "/:user_id" + FEED_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // an user is authorized to see only its own feed
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON,
              true);
        } else {
          // try to get the feed of the user and reply accordingly
          // with the result of the operation
          toRet = winsome
              .showFeed(user.username)
              .map(ps -> ps
                  .stream()
                  // serialize each post into json
                  .map(p -> p.toJSON())
                  .collect(Collectors.toList()))
              // the list becomes a json array
              .map(ps -> ToJSON.sequence(ps))
              .flatMap(jps -> HttpResponse.build200(
                  Feedback.right(jps).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        }

      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // get the wallet of a user
    jexpress.get(USERS_ROUTE + "/:user_id" + WALLET_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      // get the desired currency from the query params
      var currency = req.getQueryParams().get("currency");
      var useWincoins = currency != null && currency.equals("wincoin");
      var useBitcoins = currency != null && currency.equals("bitcoin");

      try {
        // extract the user from the context
        var user = (User) req.context;

        // an user is authorized to see only its own wallet
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(Feedback.error(ToJSON.toJSON("unauthorized")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON,
              true);
        } else {
          // try to get the wallet of the user and reply accordingly
          // with the result of the operation

          var total = Wrapper.of("");
          var rate = Wrapper.of(0.);

          if (useWincoins) {
            // get the total of the user wallet
            // in wincoins, than convert the amount
            // into a json string
            total.value = winsome
                .getUserWalletInWincoin(user.username)
                .map(ws -> ToJSON.toJSON(ws))
                .fold(__ -> "", ws -> ws);
          } else if (useBitcoins) {
            // get the total of the user wallet
            // in bitcoins
            var epair = winsome
                .getUserWalletInBitcoin(user.username);

            // convert the total amount of bitcoin
            // into a json string
            total.value = epair
                .map(p -> p.snd())
                .map(ws -> ToJSON.toJSON(ws))
                .fold(__ -> "", ws -> ws);

            // extract the used rate for further usage
            rate.value = epair
                .map(p -> p.fst())
                .fold(__ -> 0., r -> r);
          }

          // get the history of the transactions
          toRet = winsome.getUserWallet(user.username)
              .map(ws -> ws
                  .stream()
                  .map(w -> {
                    // convert each gain, if needed
                    if (useBitcoins) {
                      // convert each gain in bitcoins
                      w.gain = rate.value * w.gain;
                    }
                    return w;
                  })
                  // serialize each transaction in json
                  .map(w -> w.toJSON())
                  .collect(Collectors.toList()))
              .map(ws -> {
                // create a json response on the fly

                var toRetI = "{";
                toRetI += "\"history\":" + ToJSON.sequence(ws) + "";
                if (!total.value.equals("")) {
                  toRetI += ",\"total\":" + total.value + "";
                }
                toRetI += "}";

                return toRetI;
              })
              .flatMap(jps -> HttpResponse.build200(
                  Feedback.right(jps).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        }

      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

  }

  private static void configureJExpressPostsHandlers(JExpress jexpress, ObjectMapper objectMapper, Winsome winsome) {

    // add new post or rewin an existing one
    jexpress.post(USERS_ROUTE + "/:user_id" + POSTS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);
      var queryParams = req.getQueryParams();

      try {
        // extract the user from the context
        var user = (User) req.context;

        // an user is authorized to create posts only for itself
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON, true);
        } else if (queryParams.containsKey("rewinPost")) {
          // ?rewinPost=<postId>
          // the selected post will be rewined

          // try to rewin the post
          toRet = winsome
              .getAuthorFromPostUuid(queryParams.get("rewinPost"))
              .flatMap(a -> winsome.rewinPost(user.username, a, queryParams.get("rewinPost")))
              .flatMap(p -> HttpResponse.build200(
                  Feedback.right(p.toJSON()).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        } else {
          // the received post will be created

          // to exosttract the post to create, interpret the body
          // as a User instance
          var post = objectMapper.readValue(req.getBody(), Post.class);

          // try to create the post
          toRet = winsome
              .createPost(user.username, post.title, post.content)
              .flatMap(
                  p -> HttpResponse.build200(
                      Feedback.right(p.toJSON()).toJSON(),
                      HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        }

      } catch (JsonProcessingException e) {
        e.printStackTrace();
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // delete a post
    jexpress.delete(USERS_ROUTE + "/:user_id" + POSTS_ROUTE + "/:post_id", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // an user is authorized to delete only own posts
        if (!user.username.equals(params.get("user_id"))) {
          toRet = HttpResponse.build403(
              Feedback.error(
                  ToJSON.toJSON("unauthorized")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON, true);
        } else {
          toRet = winsome
              // try to delete the post
              .deletePost(user.username, params.get("post_id"))
              .flatMap(p -> HttpResponse.build200(
                  Feedback.right(p.toJSON()).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        }

      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // view a post by author and its id
    jexpress.get(USERS_ROUTE + "/:user_id" + POSTS_ROUTE + "/:post_id", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // try to retriwve the post
        toRet = winsome
            .showPost(user.username, params.get("user_id"), params.get("post_id"))
            .flatMap(p -> HttpResponse.build200(
                Feedback.right(p.toJSON()).toJSON(),
                HttpConstants.MIME_APPLICATION_JSON, true))
            .recoverWith(err -> HttpResponse.build400(
                Feedback.error(ToJSON.toJSON(err)).toJSON(),
                HttpConstants.MIME_APPLICATION_JSON,
                true));

      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // view a post by its id
    jexpress.get(POSTS_ROUTE + "/:post_id", (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // if the author query param is presend and its value
        // is "true", return only the post's author
        var authorQueryParam = req.getQueryParams().get("author");
        var returnAuthorOnly = authorQueryParam != null && authorQueryParam.equals("true");

        var temp = winsome
            .getAuthorFromPostUuid(params.get("post_id"));

        if (returnAuthorOnly) {
          // return only the author
          toRet = temp
              .flatMap(a -> HttpResponse.build200(
                  Feedback.right(ToJSON.toJSON(a)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        } else {
          // return the whole post
          toRet = temp
              .flatMap(a -> winsome.showPost(user.username, a, params.get("post_id")))
              .flatMap(p -> HttpResponse.build200(
                  Feedback.right(p.toJSON()).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true));
        }

        toRet = toRet.recoverWith(err -> HttpResponse.build400(
            Feedback.error(ToJSON.toJSON(err)).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON,
            true));

      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // add a reaction to a post
    jexpress.post(USERS_ROUTE + "/:user_id" + POSTS_ROUTE + "/:post_id" + REACTIONS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // to extract the reaction, interpret the body
        // as a Reaction instance
        var reaction = objectMapper.readValue(req.getBody(), Reaction.class);

        if (reaction.isUpvote != null && reaction.isUpvote instanceof Boolean) {
          // try to add the reaction
          toRet = winsome
              .ratePost(user.username, params.get("user_id"), params.get("post_id"), reaction.isUpvote)
              .flatMap(r -> HttpResponse.build200(
                  Feedback.right(r.toJSON()).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON,
                  true));
        } else {
          toRet = HttpResponse.build400(
              Feedback.error(ToJSON.toJSON("Missing boolean 'isUpvote' field")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON,
              true);
        }

      } catch (JsonProcessingException e) {
        // wrong body in the request because jackson has failed
        e.printStackTrace();
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

    // add a comment to a post
    jexpress.post(USERS_ROUTE + "/:user_id" + POSTS_ROUTE + "/:post_id" + COMMENTS_ROUTE, (req, params, reply) -> {

      var toRet = Either.<String, HttpResponse>right(null);

      try {
        // extract the user from the context
        var user = (User) req.context;

        // to extract the comment, interpret the body
        // as a Comment instance
        var comment = objectMapper.readValue(req.getBody(), Comment.class);

        if (comment.text != null && comment.text instanceof String) {
          // try to add the commeent
          toRet = winsome
              .addComment(user.username, params.get("user_id"), params.get("post_id"), comment.text)
              .flatMap(c -> HttpResponse.build200(
                  Feedback.right(c.toJSON()).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON, true))
              .recoverWith(err -> HttpResponse.build400(
                  Feedback.error(ToJSON.toJSON(err)).toJSON(),
                  HttpConstants.MIME_APPLICATION_JSON,
                  true));
        } else {
          toRet = HttpResponse.build400(
              Feedback.error(ToJSON.toJSON("Missing string 'text' field")).toJSON(),
              HttpConstants.MIME_APPLICATION_JSON,
              true);
        }

      } catch (JsonProcessingException e) {
        // wrong body in the request because jackson has failed
        e.printStackTrace();
        toRet = HttpResponse.build400(
            Feedback.error(
                ToJSON.toJSON("invalid body: " + e.getMessage())).toJSON(),
            HttpConstants.MIME_APPLICATION_JSON, true);
      } catch (Exception e) {
        // something really bad has happened, jexpress will return a 500
        e.printStackTrace();
        toRet = Either.left(e.getMessage());
      }

      // reply to the requestor
      reply.accept(toRet);
    });

  }

}

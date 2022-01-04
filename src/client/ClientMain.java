package client;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;

import client.RMI.IRemoteClient;
import client.RMI.RemoteClient;
import domain.comment.Comment;
import domain.jwt.WinsomeJWT;
import domain.post.Post;
import domain.reaction.Reaction;
import domain.user.User;
import domain.user.UserTags;
import domain.wallet.WalletTransaction;
import http.HttpConstants;
import http.HttpRequest;
import http.HttpResponse;
import io.vavr.control.Either;
import server.RMI.IRemoteServer;
import utils.Pair;
import utils.TriConsumer;
import utils.Wrapper;

public class ClientMain {

  // jackson main instance
  private static ObjectMapper objectMapper;

  // auth token
  private static String JWT = "";
  // current username
  private static String username = "";

  // utils
  private static Consumer<String> onRefreshedJWT = jtw -> {
  };
  private static Runnable onLogout = () -> {
  };
  private static TriConsumer<String, BufferedInputStream, PrintWriter> onLogin = (username, i, o) -> {
  };

  // reference to the multicast thread
  private static Thread multicastThread = null;

  public static void main(String[] args) {

    if (args.length < 1) {
      System.out.println("Missing client configuration file.\nUse: java ClientMain path/to/config.json");
      return;
    }

    // jackson main instance
    objectMapper = new ObjectMapper();

    // configs
    var config = (ClientConfig) null;

    try {
      config = objectMapper.readValue(new File(args[0]), ClientConfig.class);

      if (!config.isValid()) {
        throw new RuntimeException("invalid configuration");
      }
    } catch (Exception e) {
      throw new RuntimeException("cannot parse server configuration file: " + e.getMessage());
    }

    var tcp_port = config.tcp_port;
    var server_ip = config.server_ip;
    var auth_token_path = config.auth_token_path;
    var remote_registry_port = config.remote_registry_port;
    var stub_name = config.stub_name;

    // flag
    var doInitialLogin = false;

    System.out.println("welcome to Winsome CLI!");

    // remote method invocation
    var remoteServer = Wrapper.<IRemoteServer>of(null);
    try {
      remoteServer.value = configureRMI(remote_registry_port, stub_name);
    } catch (RemoteException e1) {
      System.out.println("Uh-oh, the server seems to be offline");
      return;
    } catch (NotBoundException e1) {
      System.out.println("Uh-oh, the server seems to be offline");
      return;
    }

    var remoteClient = Wrapper.<RemoteClient>of(null);
    var stub = Wrapper.<IRemoteClient>of(null);

    // try to recover the token of the previous session
    var path = Paths.get(auth_token_path);
    try {

      var jwt = Files.readString(path);
      JWT = jwt;

      var eusername = WinsomeJWT.extractUsernameFromJWT(jwt);
      if (eusername.isLeft()) {
        System.out.println(eusername.getLeft() + ", please login again");
      } else {
        username = eusername.get();
        doInitialLogin = true;
        System.out.println("logged as " + username);
      }

    } catch (IOException ex) {
      System.out.println("no auth token found, please login again");
    }

    // -
    // set callbacks

    // on login
    onLogin = (String username, BufferedInputStream input, PrintWriter output) -> {
      try {
        remoteClient.value = RemoteClient.of(username);
        stub.value = (IRemoteClient) UnicastRemoteObject.exportObject(remoteClient.value, 0);
        remoteServer.value.registerFollowersCallback(stub.value);

        // get multicast details
        multicastThread = new Thread((Runnable) configureMulticast(input, output)
            .fold(e -> () -> System.out.println(e), r -> r));

        // start the multicast thread
        multicastThread.start();

      } catch (RemoteException e) {
        e.printStackTrace();
      }
    };

    // when the token is being refreshed
    onRefreshedJWT = jwt -> {

      // update username
      JWT = jwt;
      var eusername = WinsomeJWT.extractUsernameFromJWT(jwt);
      if (eusername.isLeft()) {
        System.out.println("something went wrong during token refreshing");
        onLogout.run();
        return;
      } else {
        username = eusername.get();
      }

      // update the file containing the token
      try {
        Files.write(path, jwt.getBytes());
      } catch (IOException e) {
        e.printStackTrace();
      }
    };

    // on logout
    onLogout = () -> {
      JWT = "";
      username = "";

      multicastThread.interrupt();

      try {
        remoteServer.value.unregisterFollowersCallback(stub.value);
      } catch (RemoteException e1) {
        e1.printStackTrace();
      }

      try {
        // delete the stored token
        Files.deleteIfExists(path);
      } catch (IOException e) {
        e.printStackTrace();
      }
    };

    // open the tcp socker
    try (var socket = new Socket(server_ip, tcp_port)) {

      // BufferedInputStream because I have to read a precise
      // amount of bytes from the socket
      var inputStream = new BufferedInputStream(socket.getInputStream());
      var outputStream = new PrintWriter(socket.getOutputStream(), true);

      if (doInitialLogin) {
        onLogin.accept(username, inputStream, outputStream);
      }

      // start the CLI
      startCLI(inputStream, outputStream, remoteServer, remoteClient);

    } catch (ConnectException e) {
      System.out.println("Uh-oh, the server seems to be offline");
    } catch (Exception e) {
      e.printStackTrace();
    }

  }

  private static IRemoteServer configureRMI(Integer remoteRegistryPort,
      String stubName)
      throws RemoteException, NotBoundException {

    return (IRemoteServer) LocateRegistry.getRegistry(remoteRegistryPort).lookup(stubName);

  }

  private static Either<String, Runnable> configureMulticast(BufferedInputStream input, PrintWriter output) {

    var headers = new HashMap<String, String>();
    headers.put("Content-Length", "0");
    headers.put("Authorization", "Bearer " + JWT);

    return HttpRequest.buildGetRequest("/multicast", headers)
        .flatMap(req -> doRequest(req, input, output))
        .flatMap(res -> {
          var body = res.getBody();

          // extract 'res' from JSON
          try {
            var node = objectMapper.readTree(body);

            var pointer = JsonPointer.compile("/res");
            var resValue = node.at(pointer).asText();

            var ip = resValue.split(":")[0];
            var port = Integer.parseInt(resValue.split(":")[1]);

            return Either.right(Pair.of(ip, port));
          } catch (Exception e) {
            e.printStackTrace();
            return Either.left(e.getMessage());
          }
        })
        .map(pair -> () -> {
          var ip = pair.fst();
          var port = pair.snd();

          try {
            var group = InetAddress.getByName(ip);

            // open the multicast socket
            try (var ms = new MulticastSocket(port)) {
              // join the multicast group

              var ia = new InetSocketAddress(group, port);
              ms.joinGroup(ia, NetworkInterface.getByName("wlan1"));

              while (!Thread.currentThread().isInterrupted()) {
                // get the packet containing the push notification
                var dp = new DatagramPacket(new byte[256], 256);
                ms.receive(dp);

                if (Thread.currentThread().isInterrupted()) {
                  break;
                }

                var received = new String(dp.getData(), dp.getOffset(), dp.getLength());

                System.out.println("\n-----------------------------------------------------------------");
                System.out.println("received push notification from server: " + received);
                System.out.printf("-----------------------------------------------------------------\n> ");

              }

              ms.leaveGroup(ia, NetworkInterface.getByName("wlan1"));

            }

          } catch (UnknownHostException e1) {
            e1.printStackTrace();
          } catch (Exception e) {
            e.printStackTrace();
          }

        });

  }

  public static void startCLI(BufferedInputStream input, PrintWriter output, Wrapper<IRemoteServer> remoteServer,
      Wrapper<RemoteClient> remoteClient) {
    try (var scanner = new Scanner(System.in);) {

      while (true) {
        System.out.print("> ");
        var tokens = new LinkedList<String>();

        var m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(scanner.nextLine());
        while (m.find()) {
          tokens.add(m.group(1).replace("\"", ""));
        }

        var op = "";

        try {
          op = tokens.get(0);
        } catch (Exception e) {
        }

        switch (op) {
          case "exit": {
            System.exit(0);
            break;
          }
          case "help": {
            var toPrint = String.join("\n",
                "help: get help",
                "register <username> <password> <tags>: register a new user to winsome",
                "login <username> <password> [-f]: login an user into winsome [force the refreshing of the token]",
                "logout: logout the current user",
                "list users: list users having at least one common tag with the current user",
                "list followers: list the followers of the current user",
                "list following: list the users followed by the current user",
                "follow <username>: follow an user",
                "unfollow <username>: unfollow an user",
                "blog: view the posts of the current user",
                "post <title> <content>: create a new post",
                "show feed: show the feed of the current user",
                "show post <id>: show a specific post",
                "delete <idPost>: delete a specific post",
                "rewin <idPost>: rewin a specific post",
                "rate <idPost> +1/-1: rate a specific post",
                "comment <idPost> <comment>: comment a specific post",
                "wallet [btc]: show the wallet of the current user [in bitcoin]",
                "whoami: show the current user",
                "exit: exit");

            System.out.println(toPrint);
            break;
          }
          case "register": {
            // register <username> <password> <tags>
            //
            // register a new user to winsome

            System.out.println(
                (String) checkUserIsNotLogged()
                    .flatMap(__ -> handleRegisterCommandRMI(tokens, input, output, remoteServer.value))
                    .fold(s -> s, s -> s));

            // System.out.println(
            // handleRegisterCommandTCP(tokens, input, output)
            // .fold(s -> s, s -> s));
            break;
          }
          case "login": {
            // login <username> <password> [-f]
            //
            // login an user into winsome

            var eres = handleLoginCommand(tokens, input, output);

            if (eres.isLeft()) {
              System.out.println(eres.getLeft());
            } else {
              var pair = eres.get();
              onRefreshedJWT.accept(pair.fst());
              onLogin.accept(username, input, output);
              System.out.println(pair.snd());
            }

            break;
          }
          case "logout": {
            // logout
            //
            // logout an user

            var eres = checkUserIsLogged()
                .flatMap(__ -> handleLogoutCommand(tokens, input, output));

            if (eres.isLeft()) {
              System.out.println(eres.getLeft());
            } else {
              onLogout.run();
              System.out.println(eres.get());
            }
            break;
          }
          case "list": {
            // list users/followers/following
            //
            // list users having at least one common tag
            // list my followers
            // list users I follow

            var eres = checkUserIsLogged()
                .flatMap(__ -> handleListCommand(tokens, input, output, remoteClient.value));

            if (eres.isLeft()) {
              System.out.println(eres.getLeft());
            } else {
              var userTags = eres.get();

              // print users
              String leftAlignFormat = "| %-15s | %-30s %n";
              System.out.format(leftAlignFormat, "Username", "Tags");
              userTags
                  .stream()
                  .forEach(ut -> System.out.format(leftAlignFormat, ut.username,
                      ut.tags.stream().reduce("", (a, v) -> a.equals("") ? v : a + ", " + v)));

            }
            break;
          }
          case "follow": {
            // follow <username>
            //
            // follow an user

            System.out.println(
                (String) checkUserIsLogged()
                    .flatMap(__ -> handleFollowCommand(tokens, input, output))
                    .fold(s -> s, s -> s));

            break;
          }
          case "unfollow": {
            // unfollow <username>
            //
            // unfollow an user

            System.out.println(
                (String) checkUserIsLogged()
                    .flatMap(__ -> handleUnfollowCommand(tokens, input, output))
                    .fold(s -> s, s -> s));
            break;
          }
          case "blog": {
            // blog
            //
            // view my own posts

            var eres = checkUserIsLogged()
                .flatMap(__ -> handleBlogCommand(tokens, input, output));

            if (eres.isLeft()) {
              System.out.println(eres.getLeft());
            } else {
              var userTags = eres.get();

              // print users
              String leftAlignFormat = "| %-15s | %-20s | %-36s %n";
              System.out.format(leftAlignFormat, "Author", "Title", "ID");
              userTags
                  .stream()
                  .forEach(ps -> System.out.format(leftAlignFormat, ps.author, ps.title, ps.uuid));
            }
            break;
          }
          case "post": {
            // post <title> <content>
            //
            // create a new post in my blog

            System.out.println(
                (String) checkUserIsLogged()
                    .flatMap(__ -> handlePostCommand(tokens, input, output))
                    .fold(s -> s, title -> "new post created: " + title));
            break;
          }
          case "show": {
            // show feed/post <id>
            //
            // show my feed
            // show a specific post

            var eres = checkUserIsLogged()
                .flatMap(__ -> handleShowCommand(tokens, input, output));

            if (eres.isLeft()) {
              System.out.println(eres.getLeft());
            } else {
              var eres2 = eres.get();

              if (eres2.isLeft()) {
                // show post <id> command has ended successfully
                var post = eres2.getLeft();

                System.out.printf("%s%s\n", "Title: ", post.title);
                System.out.printf("%s%s\n", "Content: ", post.content);
                System.out.printf("%s%d%s%d%s\n", "Votes: ",
                    Integer.parseInt((String) post.unknowns.get("upvotes")),
                    " upvotes, ",
                    Integer.parseInt((String) post.unknowns.get("downvotes")),
                    " downvotes");
                System.out.println("Comments:");

                post.comments
                    .stream()
                    .map(c -> "\t" + c.author + ": " + c.text)
                    .forEach(System.out::println);

              } else {
                // show feed command has ended successfully
                var posts = eres2.get();

                String leftAlignFormat = "| %-15s | %-20s | %-36s %n";
                System.out.format(leftAlignFormat, "Author", "Title", "ID");

                posts
                    .stream()
                    .forEach(p -> System.out.format(leftAlignFormat, p.author, p.title, p.uuid));
              }
            }
            break;
          }
          case "delete": {
            // delete <idPost>
            //
            // delete a post

            System.out.println(
                (String) checkUserIsLogged()
                    .flatMap(__ -> handleDeleteCommand(tokens, input, output))
                    .fold(s -> s, uuid -> "deleted post: " + uuid));
            break;
          }
          case "rewin": {
            // rewin <idPost>
            //
            // rewin a post

            System.out.println(
                (String) checkUserIsLogged()
                    .flatMap(__ -> handleRewinCommand(tokens, input, output))
                    .fold(s -> s, title -> "post rewined: " + title));
            break;
          }
          case "rate": {
            // rate <idPost> +1/-1
            //
            // rate a post

            var eres = checkUserIsLogged()
                .flatMap(__ -> handleRateCommand(tokens, input, output));

            if (eres.isLeft()) {
              System.out.println(eres.getLeft());
            } else {
              var reaction = eres.get();
              System.out.println("successfully added a " + (reaction.isUpvote ? "positive" : "negative") + " reaction");
            }
            break;
          }
          case "comment": {
            // comment <idPost> <comment>
            //
            // comment a post

            var eres = checkUserIsLogged()
                .flatMap(__ -> handleCommentCommand(tokens, input, output));

            if (eres.isLeft()) {
              System.out.println(eres.getLeft());
            } else {
              var comment = eres.get();
              System.out.println("successfully added a comment");
            }
            break;
          }
          case "wallet": {
            // wallet /btc
            //
            // show my own wallet
            var eres = checkUserIsLogged()
                .flatMap(__ -> handleWalletCommand(tokens, input, output));

            if (eres.isLeft()) {
              System.out.println(eres.getLeft());
            } else {
              var pair = eres.get();

              var currency = tokens.size() == 2 ? "BTC" : "WC";

              String leftAlignFormat = "| %-30s | %-22.20f " + currency + "%n";
              String leftAlignFormatHeaders = "| %-30s | %-20s %n";
              System.out.format(leftAlignFormatHeaders, "Date", "Gain");

              pair.snd()
                  .stream()
                  .forEach(t -> System.out.format(leftAlignFormat, new Date(t.timestamp).toString(), t.gain));

              System.out.printf("Total: %22.20f %s\n", pair.fst(), currency);
            }
            break;
          }
          case "whoami": {
            // whoami

            System.out.println(
                (String) checkUserIsLogged()
                    .map(__ -> username)
                    .fold(s -> s, s -> s));
            break;
          }
          default: {
            System.out.println(op.equals("") ? "Empty command" : "Unknown command " + op);
            break;
          }
        }

      }
    } catch (Exception e) {
      e.printStackTrace();
      System.out.println("An error has occurred: " + e.getMessage());
    }
  }

  private static Either<String, String> handleFollowCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {
    if (tokens.size() != 2) {
      return Either.left("Invalid use of command follow.\nUse: follow <username>");
    } else {
      var newUser = User.of(tokens.get(1), " ", new LinkedList<String>(), false);
      var newUserJSON = newUser.toJSON();
      var newUserJSONLength = newUserJSON.getBytes().length;

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", newUserJSONLength + "");
      headers.put("Content-Type", HttpConstants.MIME_APPLICATION_JSON);
      headers.put("Authorization", "Bearer " + JWT);

      var erequest = HttpRequest.buildPostRequest("/users" + "/" + username + "/following", newUserJSON, headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        var body = res.getBody();

        // extract 'res' from JSON
        try {
          var node = objectMapper.readTree(body);

          var pointer = JsonPointer.compile("/res");

          return Either.right(node.at(pointer).asText());
        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }

      });
    }

  }

  private static Either<String, String> handleUnfollowCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {
    if (tokens.size() != 2) {
      return Either.left("Invalid use of command unfollow.\nUse: unfollow <username>");
    } else {
      var newUser = User.of(tokens.get(1), " ", new LinkedList<String>(), false);
      var newUserJSON = newUser.toJSON();
      var newUserJSONLength = newUserJSON.getBytes().length;

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", newUserJSONLength + "");
      headers.put("Content-Type", HttpConstants.MIME_APPLICATION_JSON);
      headers.put("Authorization", "Bearer " + JWT);

      var erequest = HttpRequest.buildDeleteRequest("/users" + "/" + username + "/following", newUserJSON, headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        var body = res.getBody();

        // extract 'res' from JSON
        try {
          var node = objectMapper.readTree(body);

          var pointer = JsonPointer.compile("/res");

          return Either.right(node.at(pointer).asText());
        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }

      });
    }

  }

  private static Either<String, String> handleRegisterCommandRMI(List<String> tokens, BufferedInputStream input,
      PrintWriter output, IRemoteServer remoteServer) {

    if (tokens.size() < 4) {
      return Either.left("Invalid use of command register.\nUse: register <username> <password> <tags>");
    } else {
      var username = tokens.get(1);
      var password = tokens.get(2);
      var tags = new ArrayList<>(tokens.subList(3, tokens.size()));

      try {
        return remoteServer.signUp(username, password, tags)
            .map(u -> "successfully registered a new user: " + u);
      } catch (RemoteException e) {
        e.printStackTrace();
        return Either.left(e.getMessage());
      }
    }

  }

  private static Either<String, String> handleRegisterCommandTCP(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {

    if (tokens.size() < 4) {
      return Either.left("Invalid use of command register.\nUse: register <username> <password> <tags>");
    } else {
      var newUser = User.of(tokens.get(1), tokens.get(2), tokens.subList(3, tokens.size()), false);
      var newUserJSON = newUser.toJSON();
      var newUserJSONLength = newUserJSON.getBytes().length;

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", newUserJSONLength + "");
      headers.put("Content-Type", HttpConstants.MIME_APPLICATION_JSON);

      var erequest = HttpRequest.buildPostRequest("/users", newUserJSON, headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        var body = res.getBody();

        // extract 'res' from JSON
        try {
          var node = objectMapper.readTree(body);

          var pointer = JsonPointer.compile("/res");

          return Either.right(node.at(pointer).asText());
        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }

      });
    }

  }

  private static Either<String, Pair<String, String>> handleLoginCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {

    if (!username.equals("")) {
      return Either.left("already logged as " + username);
    } else if ((tokens.size() != 3 && tokens.size() != 4) || (tokens.size() == 4 && !tokens.get(3).equals("-f"))) {
      return Either.left("Invalid use of command login.\nUse: login <username> <password> [-f]");
    } else {
      var usernamePassword = User.of(tokens.get(1), tokens.get(2), new LinkedList<>(), false);
      var usernamePasswordJSON = usernamePassword.toJSON();
      var usernamePasswordJSONLength = usernamePasswordJSON.getBytes().length;

      var isForced = tokens.size() == 4;

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", usernamePasswordJSONLength + "");
      headers.put("Content-Type", HttpConstants.MIME_APPLICATION_JSON);

      var erequest = HttpRequest.buildPostRequest(
          "/login" + (isForced ? "?force=true" : ""),
          usernamePasswordJSON,
          headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        var body = res.getBody();

        // extract 'jwt' and 'message' from JSON
        try {
          var node = objectMapper.readTree(body);

          var pointer = JsonPointer.compile("/res");
          if (node.at(pointer).isObject()) {
            var pointerJWT = JsonPointer.compile("/res/jwt");
            var pointerMessage = JsonPointer.compile("/res/message");

            var toRet = Pair.of(
                node.at(pointerJWT).asText(),
                node.at(pointerMessage).asText());

            return Either.right(toRet);
          } else {
            // res should be a message containing an error
            return Either.left(node.at(pointer).asText());
          }

        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }

      });
    }

  }

  private static Either<String, String> handleLogoutCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {
    if (tokens.size() != 1) {
      return Either.left("Invalid use of command logout.\nUse: logout");
    } else {

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", "0");
      headers.put("Authorization", "Bearer " + JWT);

      var erequest = HttpRequest.buildPostRequest("/logout", "", headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        // extract 'res' from JSON
        var body = res.getBody();

        try {
          var node = objectMapper.readTree(body);

          var pointer = JsonPointer.compile("/res");

          return Either.right(node.at(pointer).asText());
        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }
      });

    }
  }

  private static Either<String, List<UserTags>> handleListCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output, RemoteClient remoteClient) {

    if (tokens.size() != 2) {
      return Either.left("Invalid use of command list.\nUse: list users || list followers || list following");
    } else {

      var endpoint = tokens.get(1);
      var isValidEndpoint = endpoint.equals("users") || endpoint.equals("followers") || endpoint.equals("following");

      if (!isValidEndpoint) {
        return Either.left("Invalid use of command list.\nUse: list users || list followers || list following");
      }

      // handle list followers using the RemoteClient stub
      if (endpoint.equals("followers")) {

        try {
          var toRet = remoteClient
              .getFollowers()
              .entrySet()
              .stream()
              .map(e -> UserTags.of(e.getKey(), e.getValue()))
              .collect(Collectors.toList());

          return Either.right(toRet);

        } catch (RemoteException e) {

          e.printStackTrace();

          return Either.left(e.getMessage());
        }

      }

      var target = "/";
      if (endpoint.equals("followers") || endpoint.equals("following")) {
        target += "users/" + username + "/" + endpoint;
      } else {
        target += endpoint;
      }

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", "0");
      headers.put("Authorization", "Bearer " + JWT);

      var erequest = HttpRequest.buildGetRequest(target, headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        // extract data from JSON
        var body = res.getBody();

        try {
          var node = objectMapper.readTree(body);
          var pointerRes = JsonPointer.compile("/res");
          var pointerOk = JsonPointer.compile("/ok");

          var isOk = node.at(pointerOk).asBoolean();
          if (isOk) {
            // res is expected to be an array of UserTags
            return Either.right(
                objectMapper
                    .convertValue(node.at(pointerRes), new TypeReference<List<UserTags>>() {
                    }));
          } else {
            return Either.left(node.at(pointerRes).asText());
          }

        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }
      });

    }
  }

  private static Either<String, Either<Post, List<Post>>> handleShowCommand(List<String> tokens,
      BufferedInputStream input,
      PrintWriter output) {

    var isShowFeed = tokens.size() == 2;
    var isShowPost = tokens.size() == 3;

    if (!isShowFeed && !isShowPost) {
      return Either.left("Invalid use of command show.\nUse: show feed || show post <id>");
    } else if (isShowFeed && !tokens.get(1).equals("feed")) {
      return Either.left("Invalid use of command show.\nUse: show feed || show post <id>");
    } else if (isShowPost && !tokens.get(1).equals("post")) {
      return Either.left("Invalid use of command show.\nUse: show feed || show post <id>");
    } else {

      var target = "";

      if (isShowFeed) {
        target += "/users" + "/" + username + "/feed";
      } else if (isShowPost) {
        target += "/posts" + "/" + tokens.get(2);
      }

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", "0");
      headers.put("Authorization", "Bearer " + JWT);

      var erequest = HttpRequest.buildGetRequest(target, headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        // extract data from JSON
        var body = res.getBody();

        try {
          var node = objectMapper.readTree(body);
          var pointerRes = JsonPointer.compile("/res");
          var pointerOk = JsonPointer.compile("/ok");

          var isOk = node.at(pointerOk).asBoolean();
          if (isOk) {
            if (isShowFeed) {

              // res is expected to be an array of Posts
              var posts = objectMapper
                  .convertValue(node.at(pointerRes), new TypeReference<List<Post>>() {
                  });

              return Either.right(Either.right(posts));
            } else if (isShowPost) {

              // res is expected to be a single Post
              var post = objectMapper
                  .convertValue(node.at(pointerRes), new TypeReference<Post>() {
                  });

              return Either.right(Either.left(post));
            } else {
              throw new RuntimeException("should not happen");
            }
          } else {
            return Either.left(node.at(pointerRes).asText());
          }

        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }
      });

    }

  }

  private static Either<String, List<Post>> handleBlogCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {

    if (tokens.size() != 1) {
      return Either.left("Invalid use of command blog.\nUse: blog");
    } else {

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", "0");
      headers.put("Authorization", "Bearer " + JWT);

      var erequest = HttpRequest.buildGetRequest("/users" + "/" + username + "/blog", headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        // extract data from JSON
        var body = res.getBody();

        try {
          var node = objectMapper.readTree(body);
          var pointerRes = JsonPointer.compile("/res");
          var pointerOk = JsonPointer.compile("/ok");

          var isOk = node.at(pointerOk).asBoolean();
          if (isOk) {
            // res is expected to be an array of UserTags
            return Either.right(
                objectMapper
                    .convertValue(node.at(pointerRes), new TypeReference<List<Post>>() {
                    }));
          } else {
            return Either.left(node.at(pointerRes).asText());
          }

        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }
      });

    }
  }

  private static Either<String, String> handlePostCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {
    if (tokens.size() != 3) {
      return Either.left("Invalid use of command post.\nUse: post <title> <content>");
    } else {

      var post = Post.of(tokens.get(1), tokens.get(2), username);

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", post.toJSON().getBytes().length + "");
      headers.put("Authorization", "Bearer " + JWT);

      var erequest = HttpRequest.buildPostRequest("/users" + "/" + username + "/posts", post.toJSON(), headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        // extract data from JSON
        var body = res.getBody();

        try {
          var node = objectMapper.readTree(body);
          var pointerRes = JsonPointer.compile("/res");
          var pointerOk = JsonPointer.compile("/ok");

          var isOk = node.at(pointerOk).asBoolean();
          if (isOk) {
            // res is expected to be a post
            return Either.right(
                objectMapper
                    .convertValue(node.at(pointerRes), new TypeReference<Post>() {
                    }).title);
          } else {
            return Either.left(node.at(pointerRes).asText());
          }

        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }
      });

    }
  }

  private static Either<String, String> handleRewinCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {
    if (tokens.size() != 2) {
      return Either.left("Invalid use of command rewin.\nUse: rewin <idPost>");
    } else {

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", "0");
      headers.put("Authorization", "Bearer " + JWT);

      var erequest = HttpRequest.buildPostRequest("/users" + "/" + username + "/posts" + "?" +
          "rewinPost=" + tokens.get(1), "", headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        // extract data from JSON
        var body = res.getBody();

        try {
          var node = objectMapper.readTree(body);
          var pointerRes = JsonPointer.compile("/res");
          var pointerOk = JsonPointer.compile("/ok");

          var isOk = node.at(pointerOk).asBoolean();
          if (isOk) {
            // res is expected to be a post
            return Either.right(
                objectMapper
                    .convertValue(node.at(pointerRes), new TypeReference<Post>() {
                    }).title);
          } else {
            return Either.left(node.at(pointerRes).asText());
          }

        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }
      });

    }
  }

  private static Either<String, String> handleDeleteCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {
    if (tokens.size() != 2) {
      return Either.left("Invalid use of command delete.\nUse: delete <idPost>");
    } else {

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", "0");
      headers.put("Authorization", "Bearer " + JWT);

      var erequest = HttpRequest.buildDeleteRequest("/users" + "/" + username + "/posts" + "/" + tokens.get(1), "",
          headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        var body = res.getBody();

        // extract 'res' from JSON

        try {

          var node = objectMapper.readTree(body);
          var pointerRes = JsonPointer.compile("/res");
          var pointerOk = JsonPointer.compile("/ok");

          var isOk = node.at(pointerOk).asBoolean();

          if (isOk) {
            // res is expected to be a post
            return Either.right(
                objectMapper
                    .convertValue(node.at(pointerRes), new TypeReference<Post>() {
                    }).uuid);
          } else {
            // res is expected to be a string
            return Either.left(node.at(pointerRes).asText());
          }

        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }

      });

    }
  }

  private static Either<String, Reaction> handleRateCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {
    if (tokens.size() != 3 || (!tokens.get(2).equals("+1") && !tokens.get(2).equals("-1"))) {
      return Either.left("Invalid use of command rate.\nUse: rate <idPost> +1/-1");
    } else {

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", "0");
      headers.put("Authorization", "Bearer " + JWT);

      // 1 - get post's author
      return HttpRequest
          .buildGetRequest("/posts" + "/" + tokens.get(1) + "?author=true", headers)
          .flatMap(r -> doRequest(r, input, output))
          .flatMap(res -> {
            var body = res.getBody();

            try {

              var node = objectMapper.readTree(body);
              var pointerRes = JsonPointer.compile("/res");
              var pointerOk = JsonPointer.compile("/ok");

              var isOk = node.at(pointerOk).asBoolean();

              if (isOk) {
                // res is expected to be a string containing the author of the post
                return Either.right(node.at(pointerRes).asText());
              } else {
                // res is expected to be a string
                return Either.left(node.at(pointerRes).asText());
              }

            } catch (Exception e) {
              e.printStackTrace();
              return Either.left(e.getMessage());
            }

          })
          .flatMap(a -> {

            // 2 - rate the post
            var isUpvote = tokens.get(2).equals("+1") ? true : false;
            var reaction = Reaction.of(isUpvote, "", "").toJSON();

            headers.put("Content-Length", reaction.getBytes().length + "");
            headers.put("Content-Type", HttpConstants.MIME_APPLICATION_JSON);

            return HttpRequest
                .buildPostRequest("/users" + "/" + a + "/posts" + "/" + tokens.get(1) + "/reactions",
                    reaction,
                    headers);
          })
          .flatMap(r -> doRequest(r, input, output))
          .flatMap(res -> {
            var body = res.getBody();

            try {

              var node = objectMapper.readTree(body);
              var pointerRes = JsonPointer.compile("/res");
              var pointerOk = JsonPointer.compile("/ok");

              var isOk = node.at(pointerOk).asBoolean();

              if (isOk) {
                // res is expected to be a reaction
                return Either.right(
                    objectMapper
                        .convertValue(node.at(pointerRes), new TypeReference<Reaction>() {
                        }));
              } else {
                // res is expected to be a string
                return Either.left(node.at(pointerRes).asText());
              }

            } catch (Exception e) {
              e.printStackTrace();
              return Either.left(e.getMessage());
            }

          });
    }
  }

  private static Either<String, Comment> handleCommentCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {
    if (tokens.size() != 3) {
      return Either.left("Invalid use of command comment.\nUse: comment <idPost> <comment>");
    } else {

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", "0");
      headers.put("Authorization", "Bearer " + JWT);

      // 1 - get post's author
      return HttpRequest
          .buildGetRequest("/posts" + "/" + tokens.get(1) + "?author=true", headers)
          .flatMap(r -> doRequest(r, input, output))
          .flatMap(res -> {
            var body = res.getBody();

            try {

              var node = objectMapper.readTree(body);
              var pointerRes = JsonPointer.compile("/res");
              var pointerOk = JsonPointer.compile("/ok");

              var isOk = node.at(pointerOk).asBoolean();

              if (isOk) {
                // res is expected to be a string containing the author of the post
                return Either.right(node.at(pointerRes).asText());
              } else {
                // res is expected to be a string
                return Either.left(node.at(pointerRes).asText());
              }

            } catch (Exception e) {
              e.printStackTrace();
              return Either.left(e.getMessage());
            }

          })
          .flatMap(a -> {

            // 2 - comment the post
            var postUuid = tokens.get(1);
            var comment = Comment.of(tokens.get(2), "", "").toJSON();

            headers.put("Content-Length", comment.getBytes().length + "");
            headers.put("Content-Type", HttpConstants.MIME_APPLICATION_JSON);

            return HttpRequest
                .buildPostRequest("/users" + "/" + a + "/posts" + "/" + postUuid + "/comments",
                    comment,
                    headers);
          })
          .flatMap(r -> doRequest(r, input, output))
          .flatMap(res -> {
            var body = res.getBody();

            try {

              var node = objectMapper.readTree(body);
              var pointerRes = JsonPointer.compile("/res");
              var pointerOk = JsonPointer.compile("/ok");

              var isOk = node.at(pointerOk).asBoolean();

              if (isOk) {
                // res is expected to be a comment
                return Either.right(
                    objectMapper
                        .convertValue(node.at(pointerRes), new TypeReference<Comment>() {
                        }));
              } else {
                // res is expected to be a string
                return Either.left(node.at(pointerRes).asText());
              }

            } catch (Exception e) {
              e.printStackTrace();
              return Either.left(e.getMessage());
            }

          });
    }
  }

  private static Either<String, Pair<Double, List<WalletTransaction>>> handleWalletCommand(List<String> tokens,
      BufferedInputStream input,
      PrintWriter output) {

    if ((tokens.size() != 1 && tokens.size() != 2) || (tokens.size() == 2 && !tokens.get(1).equals("btc"))) {
      return Either.left("Invalid use of command wallet.\nUse: wallet || wallet btc");
    } else {

      var useBTC = tokens.size() == 2;
      var currency = useBTC ? "currency=bitcoin" : "currency=wincoin";

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", "0");
      headers.put("Authorization", "Bearer " + JWT);

      var erequest = HttpRequest.buildGetRequest("/users" + "/" + username + "/wallet" + "?" + currency, headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        // extract data from JSON
        var body = res.getBody();

        try {
          var node = objectMapper.readTree(body);
          var pointerRes = JsonPointer.compile("/res");
          var pointerResHistory = JsonPointer.compile("/res/history");
          var pointerResTotal = JsonPointer.compile("/res/total");
          var pointerOk = JsonPointer.compile("/ok");

          var isOk = node.at(pointerOk).asBoolean();
          if (isOk) {
            // res is expected to be an object { history: Transaction[], total }
            return Either.right(
                Pair.of(
                    node.at(pointerResTotal).asDouble(),
                    objectMapper
                        .convertValue(node.at(pointerResHistory), new TypeReference<List<WalletTransaction>>() {
                        })));
          } else {
            return Either.left(node.at(pointerRes).asText());
          }

        } catch (Exception e) {
          e.printStackTrace();
          return Either.left(e.getMessage());
        }
      });

    }

  }

  // do an HttpRequest, return a parsed HttpResponse
  private static Either<String, HttpResponse> doRequest(HttpRequest request, BufferedInputStream input,
      PrintWriter output) {

    // do request
    output.write(request.toString());
    output.flush();

    // parse response
    var octet = -1;
    var contentLength = -1;

    try (var res = new ByteArrayBuilder();) {

      // we have to detect the end of an HTTP response
      try {
        while ((octet = input.read()) != -1) {
          res.append(octet);

          if (contentLength != -1) {
            // contentLength already extracted
            contentLength--;

            if (contentLength == 0) {
              // we have just parsed all the response
              break;
            } else {
              // some chars of the response are still missing
              continue;
            }
          }

          // looking for the sequence CR LF CR LF
          if (new String(res.toByteArray()).lastIndexOf("\r\n\r\n") == -1) {
            // not found, try again
            continue;
          }

          // parse the request to extract Content-Length header
          var eres = HttpResponse.parse(new String(res.toByteArray()));

          // invalid http response because parser has failed
          if (eres.isLeft()) {
            return Either.left(eres.getLeft());
          }

          var contentLengthHeader = eres.get().getHeaders().get("Content-Length");
          var isThereContentLengthHeader = contentLengthHeader != null;

          if (!isThereContentLengthHeader) {
            return Either.left("Invalid HTTP response: missing Content-Length header");
          }

          contentLength = Integer.parseInt(contentLengthHeader);

        }
      } catch (IOException e) {
        e.printStackTrace();
        return Either.left(e.getMessage());
      }

      var resp = HttpResponse.parse(new String(res.toByteArray()));

      resp.forEach(r -> {
        if (r.getStatusCode().equals("401")) {
          System.out.println("you have been logged out because of an unauthorized request");
          onLogout.run();
        }
      });

      return resp;
    }

  }

  private static Either<String, Void> checkUserIsLogged() {
    if (username.equals("")) {
      return Either.left("error: user is not logged");
    } else {
      return Either.right(null);
    }
  }

  private static Either<String, Void> checkUserIsNotLogged() {
    if (!username.equals("")) {
      return Either.left("error: user is logged");
    } else {
      return Either.right(null);
    }
  }
}

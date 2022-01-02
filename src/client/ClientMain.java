package client;

import java.io.BufferedInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;

import domain.jwt.WinsomeJWT;
import domain.post.Post;
import domain.user.User;
import domain.user.UserTags;
import http.HttpRequest;
import http.HttpResponse;
import io.vavr.control.Either;
import utils.Pair;

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

  public static void main(String[] args) {

    var tcp_port = 12345;
    var server_ip = "192.168.1.113";
    var auth_token_path = "/Volumes/PortableSSD/MacMini/UniPi/Reti/Winsome/src/client/token.txt";

    System.out.println("welcome to Winsome CLI!");

    var path = Paths.get(auth_token_path);
    try {

      var jwt = Files.readString(path);
      JWT = jwt;

      var eusername = WinsomeJWT.extractUsernameFromJWT(jwt);
      if (eusername.isLeft()) {
        System.out.println(eusername.getLeft() + ", please login again");
      } else {
        username = eusername.get();
        System.out.println("logged as " + username);
      }

    } catch (IOException ex) {
      System.out.println("no auth token found, please login again");
    }

    // jackson main instance
    objectMapper = new ObjectMapper();

    try (var socket = new Socket(server_ip, tcp_port)) {

      // BufferedInputStream because I have to read a precise
      // amount of bytes from the socket
      var inputStream = new BufferedInputStream(socket.getInputStream());
      var outputStream = new PrintWriter(socket.getOutputStream(), true);

      onRefreshedJWT = jwt -> {
        // on refreshed jwt

        JWT = jwt;
        var eusername = WinsomeJWT.extractUsernameFromJWT(jwt);
        if (eusername.isLeft()) {
          System.out.println("something went wrong during token refreshing");
          onLogout.run();
          return;
        } else {
          username = eusername.get();
        }

        try {
          Files.write(path, jwt.getBytes());
        } catch (IOException e) {
          e.printStackTrace();
        }
      };

      onLogout = () -> {
        // on logout
        JWT = "";
        username = "";

        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          e.printStackTrace();
        }
      };

      startCLI(inputStream, outputStream);

    } catch (ConnectException e) {
      System.out.println("Uh-oh, the server seems to be offline");
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  public static void startCLI(BufferedInputStream input, PrintWriter output) {
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
          case "register": {
            // register <username> <password> <tags>
            //
            // register a new user to winsome

            System.out.println(
                handleRegisterCommand(tokens, input, output)
                    .fold(s -> s, s -> s));
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
                .flatMap(__ -> handleListCommand(tokens, input, output));

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
              System.out.println("");
            }
            break;
          }
          case "follow": {
            // follow <username>
            //
            // follow an user

            System.out.println(
                checkUserIsLogged()
                    .flatMap(__ -> handleFollowCommand(tokens, input, output))
                    .fold(s -> s, s -> s));

            break;
          }
          case "unfollow": {
            // unfollow <username>
            //
            // un follow an user

            System.out.println(
                checkUserIsLogged()
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
              System.out.println("");
            }
            break;
          }
          case "post": {
            // post <title> <content>
            //
            // create a new post in my blog

            System.out.println(
                checkUserIsLogged()
                    .flatMap(__ -> handlePostCommand(tokens, input, output))
                    .fold(s -> s, s -> "new post created: " + s));
            break;
          }
          case "show": {
            // show feed/post <id>
            //
            // show my feed
            // show a specific post
            break;
          }
          case "delete": {
            // delete <idPost>
            //
            // delete a post
            break;
          }
          case "rewin": {
            // rewin <idPost>
            //
            // rewin a post
            break;
          }
          case "rate": {
            // rate <idPost> +1/-1
            //
            // rate a post
            break;
          }
          case "comment": {
            // comment <idPost> <comment>
            //
            // comment a post
            break;
          }
          case "wallet": {
            // wallet /btc
            //
            // show my own wallet
            break;
          }
          case "whoami": {
            // whoami

            System.out.println(
                checkUserIsLogged()
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
      headers.put("Content-Type", HttpResponse.MIME_APPLICATION_JSON);
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
      headers.put("Content-Type", HttpResponse.MIME_APPLICATION_JSON);
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

  private static Either<String, String> handleRegisterCommand(List<String> tokens, BufferedInputStream input,
      PrintWriter output) {

    if (tokens.size() < 4) {
      return Either.left("Invalid use of command register.\nUse: register <username> <password> <tags>");
    } else {
      var newUser = User.of(tokens.get(1), tokens.get(2), tokens.subList(3, tokens.size()), false);
      var newUserJSON = newUser.toJSON();
      var newUserJSONLength = newUserJSON.getBytes().length;

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", newUserJSONLength + "");
      headers.put("Content-Type", HttpResponse.MIME_APPLICATION_JSON);

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
      headers.put("Content-Type", HttpResponse.MIME_APPLICATION_JSON);

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
      PrintWriter output) {

    if (tokens.size() != 2) {
      return Either.left("Invalid use of command list.\nUse: list users || list followers || list following");
    } else {

      var endpoint = tokens.get(1);
      var isValidEndpoint = endpoint.equals("users") || endpoint.equals("followers") || endpoint.equals("following");

      if (!isValidEndpoint) {
        return Either.left("Invalid use of command list.\nUse: list users || list followers || list following");
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

  private static void clientSays(String str) {
    System.out.println("Client: " + str);
  }

  private static void serverSays(String str) {
    System.out.println("Server: " + str);
  }

  private static Either<String, Void> checkUserIsLogged() {
    if (username.equals("")) {
      return Either.left("user is not logged");
    } else {
      return Either.right(null);
    }
  }
}

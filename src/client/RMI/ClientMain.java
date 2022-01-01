package client.RMI;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import domain.jwt.WinsomeJWT;
import domain.user.User;
import http.HttpRequest;
import http.HttpResponse;
import io.vavr.control.Either;
import utils.Pair;

public class ClientMain {

  // jackson main instance
  private static ObjectMapper objectMapper;

  // auth token
  private static String JWT = "";

  public static void main(String[] args) {

    var tcp_port = 12345;
    var server_ip = "192.168.1.113";
    var auth_token_path = "/Volumes/PortableSSD/MacMini/UniPi/Reti/Winsome/src/client/RMI/token.txt";

    printClient("welcome to Winsome CLI!");

    var path = Paths.get(auth_token_path);
    try {

      var jwt = Files.readString(path);
      JWT = jwt;

      var eusername = WinsomeJWT.extractUsernameFromJWT(jwt);
      if (eusername.isLeft()) {
        printClient(eusername.getLeft() + ", please login again");
      } else {
        printClient("logged as " + eusername.get());
      }

    } catch (IOException ex) {
      printClient("no auth token found, please login again");
    }

    // jackson main instance
    objectMapper = new ObjectMapper();

    try (var socket = new Socket(server_ip, tcp_port)) {

      var inputStream = new BufferedReader(new InputStreamReader(socket.getInputStream()));
      var outputStream = new PrintWriter(socket.getOutputStream(), true);

      startCLI(inputStream, outputStream, jwt -> {
        try {
          Files.write(path, jwt.getBytes());
        } catch (IOException e) {
          e.printStackTrace();
        }
      });

    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  public static void startCLI(BufferedReader input, PrintWriter output, Consumer<String> onRefreshedJWT) {
    try (var scanner = new Scanner(System.in);) {

      while (true) {
        System.out.print("> ");
        var tokens = Arrays.asList(scanner.nextLine().split(" "));
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

            printServer(
                handleRegisterCommand(tokens, input, output)
                    .fold(s -> s, s -> s));
            break;
          }
          case "login": {
            // login <username> <password>
            //
            // login an user into winsome

            var eres = handleLoginCommand(tokens, input, output);

            if (eres.isLeft()) {
              printServer(eres.getLeft());
            } else {
              var pair = eres.get();
              JWT = pair.fst();
              onRefreshedJWT.accept(JWT);
              printServer(pair.snd());
            }

            break;
          }
          case "logout": {
            // logout
            //
            // logout an user
            break;
          }
          case "list": {
            // list users/followers/following
            //
            // list users having at least one common tag
            // list my followers
            // list users I follow
            break;
          }
          case "follow": {
            // follow <username>
            //
            // follow an user
            break;
          }
          case "unfollow": {
            // unfollow <username>
            //
            // un follow an user
            break;
          }
          case "blog": {
            // blog
            //
            // view my onw posts
            break;
          }
          case "post": {
            // post <title> <content>
            //
            // create a new post in my blog
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
          default: {
            printClient(op.equals("") ? "Empty command" : "Unknown command " + op);
            break;
          }
        }

      }
    } catch (Exception e) {
      printClient("An error has occurred: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private static Either<String, String> handleRegisterCommand(List<String> tokens, BufferedReader input,
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
        } catch (JsonProcessingException e) {
          return Either.left(e.getMessage());
        }

      });
    }

  }

  private static Either<String, Pair<String, String>> handleLoginCommand(List<String> tokens, BufferedReader input,
      PrintWriter output) {

    if (tokens.size() != 3) {
      return Either.left("Invalid use of command login.\nUse: login <username> <password>");
    } else {
      var usernamePassword = User.of(tokens.get(1), tokens.get(2), new LinkedList<>(), false);
      var usernamePasswordJSON = usernamePassword.toJSON();
      var usernamePasswordJSONLength = usernamePasswordJSON.getBytes().length;

      var headers = new HashMap<String, String>();
      headers.put("Content-Length", usernamePasswordJSONLength + "");
      headers.put("Content-Type", HttpResponse.MIME_APPLICATION_JSON);

      var erequest = HttpRequest.buildPostRequest("/login", usernamePasswordJSON, headers);

      var result = erequest.flatMap(r -> doRequest(r, input, output));

      return result.flatMap(res -> {
        var body = res.getBody();

        // extract 'res' from JSON
        try {
          var node = objectMapper.readTree(body);

          var pointerJWT = JsonPointer.compile("/res/jwt");
          var pointerMessage = JsonPointer.compile("/res/message");

          var toRet = Pair.of(
              node.at(pointerJWT).asText(),
              node.at(pointerMessage).asText());

          return Either.right(toRet);
        } catch (JsonProcessingException e) {
          return Either.left(e.getMessage());
        }

      });
    }

  }

  // do an HttpRequest, return a parsed HttpResponse
  private static Either<String, HttpResponse> doRequest(HttpRequest request, BufferedReader input,
      PrintWriter output) {

    // do request
    output.write(request.toString());
    output.flush();

    // parse response
    var character = -1;
    var contentLength = -1;
    var res = new StringBuilder();

    // we have to detect the end of an HTTP response
    try {
      while ((character = input.read()) != -1) {
        res.append((char) character);

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
        if (res.lastIndexOf("\r\n\r\n") == -1) {
          // not found, try again
          continue;
        }

        // parse the request to extract Content-Length header
        var eres = HttpResponse.parse(res.toString());

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

    return HttpResponse.parse(res.toString());

  }

  private static void printClient(String str) {
    System.out.println("Client: " + str);
  }

  private static void printServer(String str) {
    System.out.println("Server: " + str);
  }
}

package winsome;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import domain.factories.UserFactory;
import domain.pojos.Post;
import domain.pojos.User;
import io.vavr.control.Either;
import secrets.Secrets;
import utils.HashPassword;
import utils.Pair;

public class Winsome {

  private final ConcurrentMap<String, User> network = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> loggedUsers = new ConcurrentHashMap<>();

  public Map<String, User> getNetwork() {
    return Collections.unmodifiableMap(this.network);
  }

  public Map<String, Boolean> getLoggedUsers() {
    return Collections.unmodifiableMap(this.loggedUsers);
  }

  /**
   * private void test() {
   * var user = network.get("test");
   * 
   * if (user != null) {
   * 
   * synchronized(user.followers) {
   * user.followers.add("Banana");
   * }
   * 
   * Post post = user.posts.get("idOfAPost");
   * 
   * if (post != null) {
   * synchronized (post.comments) {
   * post.comments.add("banane");
   * }
   * 
   * synchronized (post.comments) {
   * // iterate over post.comments
   * }
   * 
   * }
   * }
   * }
   */

  private <T> Either<String, T> nullGuard(T x, String name) {
    if (x == null) {
      return Either.left(name + " cannot be null");
    } else {
      return Either.right(x);
    }
  }

  public Either<String, User> register(String username, String password, List<String> tags) {

    // we have to avoid data race condition:
    // two or more threads that try to put the same new user

    var euser = UserFactory
        .create(username, password, tags)
        .toEither()
        .mapLeft(seq -> seq.mkString("\n"));

    return euser
        .map(u -> network.putIfAbsent(username, u))
        .flatMap(u -> {
          // putIfAbsent returns null if there was no previous mapping for the key
          // => success
          if (u == null) {
            return euser;
          } else {
            return Either.left("user already exists");
          }
        });
  }

  public Either<String, String> login(String username, String password) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(password, "password"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !HashPassword.hash(password).equals(u.password) ? Either.left("invalid password")
            : Either.right(u))
        .flatMap(u -> {
          // putIfAbsent returns null if there was no previous mapping for the key
          if (loggedUsers.putIfAbsent(u.username, true) == null) {

            var algorithm = Algorithm.HMAC256(Secrets.JWT_SIGN_SECRET);

            var cal = Calendar.getInstance();
            cal.setTimeInMillis(new Date().getTime());
            cal.add(Calendar.DATE, 1);

            var jwt = JWT.create()
                .withExpiresAt(cal.getTime())
                .withClaim("username", u.username)
                .withClaim("tags", u.tags)
                .sign(algorithm);

            return Either.right(jwt);
          } else {
            return Either.left("user already logged");
          }
        });
  }

  public Either<String, Void> logout(String username) {

    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> {
          if (loggedUsers.remove(u.username)) {
            return Either.<String, Void>right(null);
          } else {
            return Either.left("user was not logged");
          }
        });
  }

  public Either<String, List<String>> listUsers(String username) {

    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .map(u -> {
          return network.entrySet()
              .stream()
              .filter(e -> e.getKey() != u.username && e.getValue().tags.stream().anyMatch(t -> u.tags.contains(t)))
              .map(e -> e.getValue().username)
              .collect(Collectors.toList());
        });
  }

  public Either<String, List<String>> listFollowers(String username) {

    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .map(u -> u.getFollowers());
  }

  public Either<String, List<String>> listFollowing(String username) {

    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .map(u -> u.getFollowing());
  }

  public Either<String, Void> followUser(String username, String usernameToFollow) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(usernameToFollow, "usernameToFollow"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user " + username)
            : Either.<String, User>right(network.get(usernameToFollow))
                .flatMap(
                    u2 -> u2 == null ? Either.left("unknown user " + usernameToFollow) : Either.right(Pair.of(u, u2))))
        .flatMap(p -> {
          p.fst().addFollowing(p.snd().username);
          p.snd().addFollower(p.fst().username);
          return Either.<String, Void>right(null);
        });
  }

  public Either<String, Void> unfollowUser(String username, String usernameToUnfollow) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(usernameToUnfollow, "usernameToFollow"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user " + username)
            : Either.<String, User>right(network.get(usernameToUnfollow))
                .flatMap(
                    u2 -> u2 == null ? Either.left("unknown user " + usernameToUnfollow)
                        : Either.right(Pair.of(u, u2))))
        .flatMap(p -> {
          p.fst().removeFollowing(p.snd().username);
          p.snd().removeFollower(p.fst().username);
          return Either.<String, Void>right(null);
        });
  }

  public String toJSON() {

    var networkLine = "\"network\":{";
    networkLine += this.network.entrySet()
        .stream()
        .map(e -> "\"" + e.getKey() + "\":" + e.getValue().toJSON())
        .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    networkLine += "}";

    var loggedUsersLine = "\"loggedUsers\":{";
    loggedUsersLine += this.loggedUsers.entrySet()
        .stream()
        .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
        .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    loggedUsersLine += "}";

    return String.join("",
        "{",
        networkLine + ",",
        loggedUsersLine,
        "}");
  }

}

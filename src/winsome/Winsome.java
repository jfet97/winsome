package winsome;

import java.io.File;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.annotation.JsonProperty;

import domain.factories.CommentFactory;
import domain.factories.PostFactory;
import domain.factories.ReactionFactory;
import domain.factories.UserFactory;
import domain.pojos.Comment;
import domain.pojos.Post;
import domain.pojos.Reaction;
import domain.pojos.User;
import domain.pojos.wallet.Wallet;
import io.vavr.control.Either;
import secrets.Secrets;
import utils.HashPassword;
import utils.Pair;
import utils.Triple;
import utils.Wrapper;

public class Winsome {

  @JsonProperty("network")
  private final ConcurrentMap<String, User> network = new ConcurrentHashMap<>();
  @JsonProperty("loggedUsers")
  private final ConcurrentMap<String, Boolean> loggedUsers = new ConcurrentHashMap<>();
  @JsonProperty("wallet")
  private final Wallet wallet = Wallet.of();

  // internals

  private <T> Either<String, T> nullGuard(T x, String name) {
    if (x == null) {
      return Either.left(name + " cannot be null");
    } else {
      return Either.right(x);
    }
  }

  private Either<String, Post> getPost(String author, String postUuid) {

    return nullGuard(author, "author")
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        .flatMap(__ -> Either.<String, User>right(network.get(author)))
        .flatMap(a -> {
          var post = a.posts.get(postUuid);
          return post == null ? Either.left("unknown post") : Either.right(post);
        });
  }

  private Either<String, Post> makePost(String username, String title, String content) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(title, "title"))
        .flatMap(__ -> nullGuard(content, "content"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> PostFactory.create(title, content, username)
            .toEither()
            .mapLeft(seq -> seq.mkString("\n"))
            .map(post -> Pair.of(u, post)))
        .map(pair -> pair.fst().posts.computeIfAbsent(pair.snd().uuid, __ -> pair.snd()));
  }

  public Either<String, List<String>> viewUserBlog(String username) {
    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .map(u -> u.posts.entrySet()
            .stream()
            .map(e -> e.getValue().toJSONMinimal())
            .collect(Collectors.toList()));
  }

  // API

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
            var addUserRes = wallet.addUser(username);
            if (addUserRes.isRight()) {
              return euser;
            } else {
              // maintain consistency
              network.remove(username);
              return addUserRes.flatMap(__ -> euser);
            }

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
        .flatMap(p -> !loggedUsers.containsKey(p.fst().username) ? Either.left("user is not logged") : Either.right(p))
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
        .flatMap(p -> !loggedUsers.containsKey(p.fst().username) ? Either.left("user is not logged") : Either.right(p))
        .flatMap(p -> {
          p.fst().removeFollowing(p.snd().username);
          p.snd().removeFollower(p.fst().username);
          return Either.<String, Void>right(null);
        });
  }

  public Either<String, List<String>> viewBlog(String username) {

    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .flatMap(u -> viewUserBlog(u.username));
  }

  public Either<String, Post> createPost(String username, String title, String content) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(title, "title"))
        .flatMap(__ -> nullGuard(content, "content"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .flatMap(u -> makePost(title, content, u.username));
  }

  public Either<String, List<String>> showFeed(String username) {

    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .map(u -> u.getFollowing())
        .flatMap(us -> Either.sequence(
            us.stream()
                .map(u -> viewUserBlog(u))
                .collect(Collectors.toList()))
            .mapLeft(seq -> seq.mkString("\n"))
            .map(seq -> seq.fold(new LinkedList<String>(), (acc, curr) -> {
              acc.addAll(curr);
              return acc;
            })));
  }

  public Either<String, String> showPost(String username, String author, String postUuid) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(author, "author"))
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user " + username) : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .flatMap(__ -> Either.<String, User>right(network.get(author)))
        .flatMap(a -> a == null ? Either.left("unknown user " + author) : Either.right(a))
        .flatMap(a -> getPost(a.username, postUuid))
        .map(p -> p.toJSONDetails());
  }

  public Either<String, Void> deletePost(String username, String postUuid) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .flatMap(u -> {
          var post = u.posts.get(postUuid);
          var toRet = Either.<String, Void>right(null);

          var isPostNull = post == null;
          var isAuthor = isPostNull ? false : post.author.equals(username);

          if (!isPostNull && isAuthor)
            u.posts.remove(post.uuid);
          else if (!isPostNull && !isAuthor)
            toRet = Either.left("invalid post owner");
          else
            toRet = Either.left("unknown post");

          return toRet;
        });
  }

  public Either<String, Post> rewinPost(String username, String author, String postUuid) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(author, "author"))
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user " + username) : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .flatMap(u -> Either.<String, User>right(network.get(author))
            .flatMap(a -> a == null ? Either.left("unknown user " + author)
                : getPost(a.username, postUuid).map(p -> Triple.of(u, a, p))))
        .flatMap(
            t -> t.fst().username.equals(t.snd().username) ? Either.left("cannot rewin own post") : Either.right(t))
        .flatMap(t -> makePost(t.trd().title, t.trd().content, t.fst().username));
  }

  public Either<String, Reaction> ratePost(String username, String author, String postUuid, Boolean isUpvote) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(author, "author"))
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        .flatMap(__ -> nullGuard(isUpvote, "isUpvote"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user " + username) : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .flatMap(u -> Either.<String, User>right(network.get(author))
            .flatMap(a -> a == null ? Either.left("unknown user " + author)
                : getPost(a.username, postUuid).map(p -> Triple.of(u, a, p))))
        .flatMap(
            t -> t.fst().username.equals(t.snd().username) ? Either.left("cannot rate own post") : Either.right(t))
        .flatMap(
            t -> {
              var u = t.fst();
              var a = t.snd();
              var p = t.trd();

              synchronized (u.following) {
                synchronized (p.reactions) {
                  var toRet = Either.<String, Reaction>right(null);

                  if (!u.following.contains(a.username))
                    toRet = Either.left("cannot rate post not in feed");
                  else if (p.reactions.stream().anyMatch(r -> r.username.equals(u.username)))
                    toRet = Either.left("cannot rate a post twice");
                  else
                    toRet = ReactionFactory.create(isUpvote, p.uuid, u.username)
                        .toEither()
                        .mapLeft(set -> set.mkString("\n"));

                  toRet.forEach(p.reactions::add);

                  return toRet;

                }
              }
            });
  }

  public Either<String, Comment> addComment(String username, String author, String postUuid, String text) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(author, "author"))
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        .flatMap(__ -> nullGuard(text, "text"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user " + username) : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .flatMap(u -> Either.<String, User>right(network.get(author))
            .flatMap(a -> a == null ? Either.left("unknown user " + author)
                : getPost(a.username, postUuid).map(p -> Triple.of(u, a, p))))
        .flatMap(
            t -> t.fst().username.equals(t.snd().username) ? Either.left("cannot comment own post") : Either.right(t))
        .flatMap(
            t -> {
              var u = t.fst();
              var a = t.snd();
              var p = t.trd();

              synchronized (u.following) {
                synchronized (p.comments) {
                  var toRet = Either.<String, Comment>right(null);

                  if (!u.following.contains(a.username))
                    toRet = Either.left("cannot rate post not in feed");
                  else
                    toRet = CommentFactory.create(text, postUuid, u.username)
                        .toEither()
                        .mapLeft(set -> set.mkString("\n"));

                  toRet.forEach(p.comments::add);

                  return toRet;

                }
              }
            });
  }

  public Either<String, Runnable> makePersistenceRunnable(Long interval, String path, Boolean minify) {
    return nullGuard(interval, "interval")
        .flatMap(__ -> nullGuard(path, "path"))
        .flatMap(__ -> nullGuard(minify, "minify"))
        .map(__ -> () -> {
          var interrupted = false;
          while (!interrupted) {
            try {
              // could be interrupted
              Files.write(Paths.get(path + ".temp"), this.toJSON().getBytes());

              var oldSnapshot = new File(path);
              oldSnapshot.delete();
              var newSnapshot = new File(path + ".temp");
              newSnapshot.renameTo(new File(path));

              Thread.sleep(interval);
            } catch (InterruptedException e) {
              interrupted = true;

              // does not matter if the file does not exist
              var newSnapshot = new File(path + ".temp");
              newSnapshot.delete();
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        });
  }

  public Either<String, Runnable> makeWalletRunnable(Long interval, Integer authorPercentage) {
    return nullGuard(interval, "interval")
        .flatMap(__ -> nullGuard(authorPercentage, "authorPercentage"))
        .filterOrElse(p -> p >= 0 && p <= 100, p -> p + " is an invalid author percentage")
        .map(__ -> () -> {
          var interrupted = false;
          var prevTimestamp = Wrapper.of(0L);
          var nowTimestamp = Wrapper.of(0L);

          while (!interrupted) {
            try {

              nowTimestamp.value = new Date().getTime();

              var posts = this.network
                  .entrySet()
                  .stream()
                  .flatMap(ne -> ne.getValue().posts
                      .entrySet()
                      .stream()
                      .map(ue -> ue.getValue()));

              posts.forEach(post -> {

                var reactions = post.reactions
                    .stream()
                    .filter(r -> r.timestamp >= prevTimestamp.value && r.timestamp < nowTimestamp.value);

                var reactionsSum = reactions
                    .map(r -> r.isUpvote ? 1 : -1)
                    .reduce(0, (acc, val) -> acc + val);

                var reactionsContribute = Math.log(Math.max(reactionsSum, 0) + 1);

                var commentsByOtherUsers = post.comments
                    .stream()
                    .filter(c -> c.timestamp >= prevTimestamp.value && c.timestamp < nowTimestamp.value)
                    .collect(Collectors.groupingBy(c -> c.author))
                    .entrySet()
                    .stream();

                var commentsSum = commentsByOtherUsers
                    .map(ce -> (double) ce.getValue().size())
                    .reduce(0., (acc, val) -> acc + (2. / (1 + Math.pow(Math.E, -(val.intValue() - 1)))));

                var commentsContribute = Math.log(commentsSum + 1);

                var gain = (reactionsContribute + commentsContribute) / post.getWalletScannerIteration();

                var authorGain = (gain / 100) * authorPercentage;
                var othersGain = ((gain / 100) * (100 - authorPercentage)) / commentsByOtherUsers.count();

                this.wallet
                    .addTransaction(post.author, authorGain)
                    .swap()
                    .forEach(System.out::println);

                commentsByOtherUsers
                    .map(ce -> ce.getKey())
                    .forEach(ou -> this.wallet
                        .addTransaction(ou, othersGain)
                        .swap()
                        .forEach(System.out::println));

                post.incrementWalletScannerIteration();
              });

              prevTimestamp.value = nowTimestamp.value;

              Thread.sleep(interval);
            } catch (InterruptedException e) {
              interrupted = true;
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
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
        loggedUsersLine + ",",
        "\"wallet\":" + wallet.toJSON(),
        "}");
  }

  // static

  public static Winsome of() {
    return new Winsome();
  }
}

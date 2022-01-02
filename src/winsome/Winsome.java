package winsome;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import domain.comment.Comment;
import domain.comment.CommentFactory;
import domain.jwt.WinsomeJWT;
import domain.post.AuthorPostUuid;
import domain.post.Post;
import domain.post.PostFactory;
import domain.reaction.Reaction;
import domain.reaction.ReactionFactory;
import domain.user.User;
import domain.user.UserFactory;
import domain.wallet.Wallet;
import domain.wallet.WalletTransaction;
import io.vavr.control.Either;
import utils.HashPassword;
import utils.Pair;
import utils.TriConsumer;
import utils.Triple;
import utils.Wrapper;

// to ignore JWT_SIGN_SECRET
@JsonIgnoreProperties(ignoreUnknown = true)
public class Winsome {

  @JsonProperty("network")
  private final ConcurrentMap<String, User> network = new ConcurrentHashMap<>();
  @JsonProperty("loggedUsers")
  private final ConcurrentMap<String, String> loggedUsers = new ConcurrentHashMap<>();
  @JsonProperty("postAuthors")
  private final ConcurrentMap<String, String> postAuthors = new ConcurrentHashMap<>();
  @JsonProperty("wallet")
  private final Wallet wallet = Wallet.of();

  private String JWT_SIGN_SECRET = "";

  private TriConsumer<String, String, Boolean> onChangeFollowers = (performer, receiver, hasFollowed) -> {
  };

  // ---------------------------------------
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
        .map(pair -> pair.fst().posts
            .compute(pair.snd().uuid, (k, v) -> {
              this.postAuthors.put(pair.snd().uuid, pair.fst().username);
              return pair.snd();
            }));
  }

  private Either<String, List<Post>> viewUserBlog(String username) {
    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .map(u -> u.posts.entrySet()
            .stream()
            .map(e -> e.getValue())
            .collect(Collectors.toList()));
  }

  private Either<String, List<WalletTransaction>> getWalletOfUser(String username) {
    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .map(u -> this.wallet.getWallet().get(u.username).stream().collect(Collectors.toList()));
  }

  // ---------------------------------------
  // API

  public void setJWTSecret(String jwtSecret) {
    this.JWT_SIGN_SECRET = jwtSecret != null ? jwtSecret : "";
  }

  public void setOnChangeFollowers(TriConsumer<String, String, Boolean> cb) {
    this.onChangeFollowers = cb;
  }

  public Either<String, String> getAuthorFromPostUuid(String uuid) {
    var toRet = this.postAuthors.get(uuid);

    return toRet == null ? Either.left("unknown post") : Either.right(toRet);
  }

  public void synchronizedActionOnFollowersOfUser(String username, Consumer<Set<String>> cb) {
    this.network.computeIfPresent(username, (__, user) -> {
      user.synchronizedActionOnFollowers(cb);
      return user;
    });
  }

  public Either<String, List<String>> getUserTags(String username) {
    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .map(u -> u.tags.stream().collect(Collectors.toList()));
  }

  public Either<String, String> getUserJWT(String username) {
    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> {
          var jwt = loggedUsers.get(u.username);
          if (jwt == null)
            return Either.left("user is not logged");
          else
            return Either.right(jwt);
        });
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

  public Either<String, String> login(String username, String password, Boolean forceLogin) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(password, "password"))
        .flatMap(__ -> nullGuard(password, "jwt"))
        .flatMap(__ -> JWT_SIGN_SECRET.equals("") ? Either.left("INVALID_JWT_SECRET") : Either.right(null))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !HashPassword.hash(password).equals(u.password) ? Either.left("invalid password")
            : Either.right(u))
        .flatMap(u -> {

          var newjwt = WinsomeJWT.createJWT(JWT_SIGN_SECRET, username);
          var currJWT = loggedUsers.putIfAbsent(u.username, newjwt);

          // putIfAbsent returns null if there was no previous mapping for the key
          if (currJWT == null) {
            return WinsomeJWT.wrapWithMessageJSON(newjwt, "user successfully logged");
          } else if (forceLogin) {
            // update the user token and send it back
            loggedUsers.put(u.username, newjwt);
            return WinsomeJWT.wrapWithMessageJSON(newjwt, "user was already logged, previous sessions are now invalid");
          } else {
            // it is an error
            return Either.left("user seems to be already logged somewhere else");
          }
        });
  }

  public Either<String, Void> logout(String username) {

    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> {
          if (loggedUsers.remove(u.username) != null) {
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
              .filter(
                  e -> !e.getKey().equals(u.username) && e.getValue().tags.stream().anyMatch(t -> u.tags.contains(t)))
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
        .flatMap(
            __ -> username.equals(usernameToFollow) ? Either.left("an user cannot follow itself") : Either.right(null))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user " + username)
            : Either.<String, User>right(network.get(usernameToFollow))
                .flatMap(
                    u2 -> u2 == null ? Either.left("unknown user " + usernameToFollow) : Either.right(Pair.of(u, u2))))
        .flatMap(p -> !loggedUsers.containsKey(p.fst().username) ? Either.left("user is not logged") : Either.right(p))
        .flatMap(p -> {
          var b1 = false;
          var b2 = false;
          synchronized (p.fst().following) {
            synchronized (p.snd().followers) {
              b1 = p.fst().addFollowing(p.snd().username);
              b2 = p.snd().addFollower(p.fst().username);
            }
          }
          if (b1 && b2) {
            onChangeFollowers.accept(p.fst().username, p.snd().username, true);
            return Either.<String, Void>right(null);
          } else {
            return Either.left(username + " was already following " + usernameToFollow);
          }

        });
  }

  public Either<String, Void> unfollowUser(String username, String usernameToUnfollow) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(usernameToUnfollow, "usernameToUnfollow"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user " + username)
            : Either.<String, User>right(network.get(usernameToUnfollow))
                .flatMap(
                    u2 -> u2 == null ? Either.left("unknown user " + usernameToUnfollow)
                        : Either.right(Pair.of(u, u2))))
        .flatMap(p -> !loggedUsers.containsKey(p.fst().username) ? Either.left("user is not logged") : Either.right(p))
        .flatMap(p -> {
          var b1 = false;
          var b2 = false;
          synchronized (p.fst().following) {
            synchronized (p.snd().followers) {
              b1 = p.fst().removeFollowing(p.snd().username);
              b2 = p.snd().removeFollower(p.fst().username);
            }
          }
          if (b1 && b2) {
            onChangeFollowers.accept(p.fst().username, p.snd().username, false);
            return Either.<String, Void>right(null);
          } else {
            return Either.left(username + " wasn't following " + usernameToUnfollow);
          }
        });
  }

  public Either<String, List<Post>> viewBlog(String username) {

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
        .flatMap(u -> makePost(u.username, title, content));
  }

  public Either<String, List<Post>> showFeed(String username) {

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
            .map(seq -> seq.fold(new LinkedList<Post>(), (acc, curr) -> {
              acc.addAll(curr);
              return acc;
            })));
  }

  public Either<String, Post> showPost(String username, String author, String postUuid) {
    // it seems that any user can see any other user's post

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(author, "author"))
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user " + username) : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .flatMap(__ -> Either.<String, User>right(network.get(author)))
        .flatMap(a -> a == null ? Either.left("unknown user " + author) : Either.right(a))
        .flatMap(a -> getPost(a.username, postUuid));
  }

  public Either<String, Post> deletePost(String username, String postUuid) {

    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user") : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .flatMap(u -> {
          var post = u.posts.get(postUuid);
          var toRet = Either.<String, Post>right(post);

          if (post != null) {
            synchronized (post) {
              // synchronized with rewinPost
              post.justDeleted = true;

              post.rewins
                  .stream()
                  .forEach(p -> deletePost(p.author, p.postUuid));

              this.postAuthors.remove(post.uuid);
              u.posts.remove(post.uuid);
            }
          } else {
            toRet = Either.left("unknown post");
          }

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
        .flatMap(t -> {
          var user = t.fst();
          var ath = t.snd();

          synchronized (user.following) {
            if (!user.following.contains(ath.username))
              return Either.left("cannot rewin post not in feed");
            else
              return Either.right(t);
          }
        })
        .flatMap(t -> {
          var user = t.fst();
          var post = t.trd();
          var toRet = Either.<String, Post>right(null);

          synchronized (post) {
            // synchronized with deletePost

            if (post.justDeleted) {
              toRet = Either.left("the post has just been deleted");
            } else {
              toRet = makePost(user.username, post.title, post.content);
              toRet.forEach(p -> post.rewins.add(AuthorPostUuid.of(p.author, p.uuid)));
            }
          }

          return toRet;

        });
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
                  else if (p.reactions.stream().anyMatch(r -> r.author.equals(u.username)))
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
                    toRet = Either.left("cannot comment post not in feed");
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

  public Either<String, List<WalletTransaction>> getUserWallet(String username) {
    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(u -> u == null ? Either.left("unknown user " + username) : Either.right(u))
        .flatMap(u -> !loggedUsers.containsKey(u.username) ? Either.left("user is not logged") : Either.right(u))
        .flatMap(u -> getWalletOfUser(u.username));
  }

  public Either<String, Double> getUserWalletInWincoin(String username) {
    return getUserWallet(username)
        .map(ts -> ts.stream().map(t -> t.gain).reduce(0., (acc, val) -> acc + val));
  }

  public Either<String, Double> getUserWalletInBitcoin(String username) {
    return getUserWalletInWincoin(username)
        .flatMap(ws -> {

          try {

            var url = new URL("https://www.random.org/decimal-fractions/?num=1&dec=10&col=1&format=plain&rnd=new");
            var con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");

            if (con.getResponseCode() == HttpURLConnection.HTTP_OK) {
              var reader = new BufferedReader(new InputStreamReader(con.getInputStream()));

              var inputLine = "";
              var res = new StringBuffer();

              while ((inputLine = reader.readLine()) != null) {
                res.append(inputLine);
              }
              reader.close();

              return Either.right(Double.parseDouble(res.toString()) * ws);
            } else {
              throw new RuntimeException("get request to random.org has failed");
            }

          } catch (Exception e) {
            e.printStackTrace();
            return Either.left("conversion in bitcoin has failed");
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

              // does not matter if the file does not exist
              var oldSnapshot = new File(path);
              oldSnapshot.delete();

              var newSnapshot = new File(path + ".temp");
              newSnapshot.renameTo(new File(path));

              Thread.sleep(interval);
            } catch (InterruptedException e) {
              interrupted = true;

              // does not matter if the file does not exist
              // var tempSnapshot = new File(path + ".temp");
              // tempSnapshot.delete();
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        });
  }

  public Either<String, Runnable> makeWalletRunnable(Long interval, Integer authorPercentage) {
    return this.makeWalletRunnable(interval, authorPercentage, () -> {
    });
  }

  public Either<String, Runnable> makeWalletRunnable(Long interval, Integer authorPercentage, Runnable action) {
    return nullGuard(interval, "interval")
        .flatMap(__ -> nullGuard(authorPercentage, "authorPercentage"))
        .flatMap(__ -> nullGuard(authorPercentage, "action"))
        .filterOrElse(p -> p >= 0 && p <= 100, p -> p + " is an invalid author percentage")
        .map(__ -> () -> {
          var interrupted = false;
          var nowTimestamp = Wrapper.of(0L);

          while (!interrupted) {
            try {

              nowTimestamp.value = new Date().getTime();

              var runAction = Wrapper.of(false);

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
                    .filter(r -> r.timestamp >= this.wallet.prevTimestamp && r.timestamp < nowTimestamp.value)
                    .collect(Collectors.toList());

                var positiveReactions = reactions
                    .stream()
                    .filter(r -> r.isUpvote)
                    .collect(Collectors.toList());

                var comments = post.comments
                    .stream()
                    .filter(c -> c.timestamp >= this.wallet.prevTimestamp && c.timestamp < nowTimestamp.value)
                    .collect(Collectors.groupingBy(c -> c.author))
                    .entrySet()
                    .stream()
                    .collect(Collectors.toList());

                if (positiveReactions.size() != 0 || comments.size() != 0) {

                  var reactionsSum = reactions
                      .stream()
                      .map(r -> r.isUpvote ? 1 : -1)
                      .reduce(0, (acc, val) -> acc + val);

                  var reactionsContribute = Math.log(Math.max(reactionsSum, 0) + 1);

                  var commentsSum = comments
                      .stream()
                      .map(ce -> (double) ce.getValue().size())
                      .reduce(0., (acc, val) -> acc + (2. / (1 + Math.pow(Math.E, -(val.intValue() - 1)))));

                  var commentsContribute = Math.log(commentsSum + 1);

                  var gain = (reactionsContribute + commentsContribute) / post.getWalletScannerIteration();

                  var authorGain = (gain / 100) * authorPercentage;

                  var otherUsers = Stream.concat(
                      positiveReactions
                          .stream()
                          .map(r -> r.author),
                      comments
                          .stream()
                          .map(ce -> ce.getKey()))
                      .collect(Collectors.toList());

                  var othersGain = ((gain / 100) * (100 - authorPercentage)) / otherUsers.stream().distinct().count();

                  this.wallet
                      .addTransaction(post.author, authorGain)
                      .swap()
                      .forEach(System.out::println);

                  otherUsers
                      .stream()
                      .forEach(u -> this.wallet
                          .addTransaction(u, othersGain)
                          .swap()
                          .forEach(System.out::println));

                  post.incrementWalletScannerIteration();

                  runAction.value = true;
                }
              });

              this.wallet.prevTimestamp = nowTimestamp.value;

              // run the action at the end of the wallet updating process
              // if the gain of at least one post was computed
              if (runAction.value) {
                action.run();
              }

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
        .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
        .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    loggedUsersLine += "}";

    var postAuthorsLine = "\"postAuthors\":{";
    postAuthorsLine += this.postAuthors.entrySet()
        .stream()
        .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
        .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    postAuthorsLine += "}";

    return String.join("",
        "{",
        networkLine + ",",
        loggedUsersLine + ",",
        postAuthorsLine + ",",
        "\"wallet\":" + wallet.toJSON(),
        "}");
  }

  // static

  public static Winsome of() {
    return new Winsome();
  }
}

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import domain.comment.Comment;
import domain.comment.CommentFactory;
import domain.post.AuthorPostUuid;
import domain.post.Post;
import domain.post.PostFactory;
import domain.reaction.Reaction;
import domain.reaction.ReactionFactory;
import domain.user.User;
import domain.user.UserFactory;
import domain.wallet.Wallet;
import domain.wallet.WalletTransaction;
import http.HttpConstants;
import io.vavr.control.Either;
import utils.HashPassword;
import utils.Pair;
import utils.TriConsumer;
import utils.Triple;
import utils.WinsomeJWT;
import utils.Wrapper;

// to ignore JWT_SIGN_SECRET
@JsonIgnoreProperties(ignoreUnknown = true)
public class Winsome {

  // ---------------------------------------
  // internal properties

  // network: a concurrent hashmap containing the
  // relation username -> User instance
  @JsonProperty("network")
  private final ConcurrentMap<String, User> network = new ConcurrentHashMap<>();

  // loggedUsers: a concurrent hashmap containing the relation
  // username -> JWT token
  @JsonProperty("loggedUsers")
  private final ConcurrentMap<String, String> loggedUsers = new ConcurrentHashMap<>();

  // postAuthors: a concurrent hashmap containing the relation
  // post UUID -> author
  @JsonProperty("postAuthors")
  private final ConcurrentMap<String, String> postAuthors = new ConcurrentHashMap<>();

  // wallet: a data structure containing the history of
  // transactions of each user
  @JsonProperty("wallet")
  private final Wallet wallet = Wallet.of();

  // the jwt secret used to sign the issued JWT tokens
  private String JWT_SIGN_SECRET = "";

  // this callback is called when the followers set of a user changes
  // it receives a reference to the performer of the follow/unfollow action,
  // the username of the receiver and a boolean value that indicates if
  // it was a follow or an unfollow
  private TriConsumer<User, String, Boolean> onChangeFollowers = (performer, receiver, hasFollowed) -> {
  };

  // ---------------------------------------
  // internal methods

  // return the input argument as is if the argument is null
  // or an error in the form of a string if not
  private <T> Either<String, T> nullGuard(T x, String name) {
    if (x == null) {
      return Either.left(name + " cannot be null");
    } else {
      return Either.right(x);
    }
  }

  // return a specific post of a specific author if
  // both the author and the post UUID are valid
  // or an error in the form of a string if not
  private Either<String, Post> getPost(String author, String postUuid) {
    return nullGuard(author, "author")
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        // extract the author by its username
        .flatMap(__ -> Either.<String, User>right(network.get(author)))
        .flatMap(userAuthor -> {
          var post = userAuthor.posts.get(postUuid);
          return post == null ? Either.left("unknown post") : Either.right(post);
        });
  }

  // creates a new post if all the arguments are valid
  // or an error in the form of a string if not
  private Either<String, Post> makePost(String username, String title, String content) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(title, "title"))
        .flatMap(__ -> nullGuard(content, "content"))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        // create a new Post instance
        .flatMap(user -> PostFactory.create(title, content, username)
            .toEither()
            // collect together eventual errors
            .mapLeft(seq -> seq.mkString("\n"))
            // otherwise insert into the user's post the new post
            .map(post -> user.posts.compute(post.uuid, (k, v) -> {
              // save the relation post UUID -> author
              this.postAuthors.put(post.uuid, user.username);
              return post;
            })));
  }

  // return the blog of a user if the argument is valid
  // or an error in the form of a string if not
  private Either<String, List<Post>> viewUserBlog(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        // collect its post into a list
        .map(user -> user.posts.entrySet()
            .stream()
            .map(entry -> entry.getValue())
            .collect(Collectors.toList()));
  }

  // return the wallet of a user if the argument is valid
  // or an error in the form of a string if not
  private Either<String, List<WalletTransaction>> getWalletOfUser(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        // return a deep copy of its transactions
        .flatMap(user -> this.wallet.getWalletOf(user.username));
  }

  // delete and return a post if the arguments are valid
  // or an error in the form of a string if not
  private Either<String, Post> cancelPost(String username, String postUuid) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> {
          var post = user.posts.get(postUuid);
          var toRet = Either.<String, Post>right(post);

          if (post != null) {
            // synchronized with rewinPost
            synchronized (post) {
              // signal the fact that the post is going to be deleted soon
              post.justDeleted = true;

              // recursive deletion of post's rewins
              post.rewins
                  .stream()
                  .forEach(p -> {
                    cancelPost(p.author, p.postUuid);
                  });

              // remove all the internal references to the post
              this.postAuthors.remove(post.uuid);
              user.posts.remove(post.uuid);
            }
          } else {
            toRet = Either.left("unknown post");
          }
          return toRet;
        });
  }

  // ---------------------------------------
  // API

  // set the jwt secret stored internally
  public void setJWTSecret(String jwtSecret) {
    this.JWT_SIGN_SECRET = jwtSecret != null ? jwtSecret : "";
  }

  // set the callback to call when the set of followers of a user changes
  public void setOnChangeFollowers(TriConsumer<User, String, Boolean> cb) {
    this.onChangeFollowers = cb;
  }

  // get the author of a post given its UUID if the argument is valid
  // or an error in the form of a string if not
  public Either<String, String> getAuthorFromPostUuid(String uuid) {
    var toRet = this.postAuthors.get(uuid);
    return toRet == null ? Either.left("unknown post") : Either.right(toRet);
  }

  // get the tags of a user given its username if the argument is valid
  // or an error in the form of a string if not
  public Either<String, List<String>> getUserTags(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        .map(users -> users.tags.stream().collect(Collectors.toList()));
  }

  // get the jwt token of a user given its username if the argument is valid
  // or an error in the form of a string if not
  public Either<String, String> getUserJWT(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        .flatMap(user -> {
          var jwt = loggedUsers.get(user.username);
          if (jwt == null)
            return Either.left("user is not logged");
          else
            return Either.right(jwt);
        });
  }

  // get a user given its username if the argument is valid
  // or an error in the form of a string if not
  public Either<String, User> getUser(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user));
  }

  // do a synchronized action on the set of followers of a user
  public void synchronizedActionOnFollowersOfUser(String username, Consumer<List<String>> cb) {
    this.network.computeIfPresent(username, (__, user) -> {
      user.synchronizedActionOnFollowers(cb);
      return user;
    });
  }

  // register a new user if the arguments are valid
  // or an error in the form of a string if not
  public Either<String, User> register(String username, String password, List<String> tags) {

    // try to create a new user using the factory
    var euser = UserFactory
        .create(username, password, tags)
        .toEither()
        // collect together eventual errors
        .mapLeft(seq -> seq.mkString("\n"));

    return euser
        // try to store the new user into the network
        .map(user -> network.putIfAbsent(username, user))
        .flatMap(user -> {
          // putIfAbsent return null if there was no previous mapping for the key
          // => success
          if (user == null) {
            // add the user to the wallet too
            var addUserRes = wallet.addUser(username);
            if (addUserRes.isRight()) {
              return euser;
            } else {
              // maintain consistency: if the user cannot be added to the wallet
              // remove it from the network and return the error
              network.remove(username);
              return addUserRes.flatMap(__ -> euser);
            }
          } else {
            return Either.left("user already exists");
          }
        });
  }

  // login a user if the arguments and the internal jwt secret are valid
  // or an error in the form of a string if not
  // return the created jwt
  public Either<String, String> login(String username, String password, Boolean forceLogin) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(password, "password"))
        .flatMap(__ -> nullGuard(password, "jwt"))
        .flatMap(__ -> JWT_SIGN_SECRET.equals("") ? Either.left("INVALID_JWT_SECRET") : Either.right(null))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        // check the passowrd
        .flatMap(user -> !HashPassword.hash(password).equals(user.password) ? Either.left("invalid password")
            : Either.right(user))
        .flatMap(user -> {

          // create a new jwt and try to store it
          var newjwt = WinsomeJWT.createJWT(JWT_SIGN_SECRET, username);
          var currJWT = loggedUsers.putIfAbsent(user.username, newjwt);

          // putIfAbsent return null if there was no previous mapping for the key
          if (currJWT == null) {
            return WinsomeJWT.wrapWithMessageJSON(newjwt, "user successfully logged");
          } else if (forceLogin) {
            // update the user token and send it back
            loggedUsers.put(user.username, newjwt);
            return WinsomeJWT.wrapWithMessageJSON(newjwt, "user was already logged, previous sessions are now invalid");
          } else {
            // it is an error
            return Either.left("user seems to be already logged somewhere else");
          }
        });
  }

  // login a user if the argument is valid
  // or an error in the form of a string if not
  public Either<String, Void> logout(String username) {
    return nullGuard(username, "username")
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        // extract the user by its username
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        .flatMap(user -> {
          if (loggedUsers.remove(user.username) != null) {
            return Either.<String, Void>right(null);
          } else {
            return Either.left("user was not logged");
          }
        });
  }

  // return a list of users having at least one common tag with the provided user
  // or an error in the form of a string if the argument is not valid
  public Either<String, List<String>> listUsers(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        .map(user -> {
          return network.entrySet()
              .stream()
              .filter(
                  // filter out those users who haven't at least a tag in common
                  // and filter out the provided user
                  e -> !e.getKey().equals(user.username)
                      && e.getValue().tags.stream().anyMatch(t -> user.tags.contains(t)))
              // get only their usernames
              .map(e -> e.getValue().username)
              .collect(Collectors.toList());
        });
  }

  // return a list of users who follow the provided user
  // or an error in the form of a string if the argument is not valid
  public Either<String, List<String>> listFollowers(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        // synchronized operation
        .map(user -> user.getFollowers());
  }

  // return a list of users who are followed by the provided user
  // or an error in the form of a string if the argument is not valid
  public Either<String, List<String>> listFollowing(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        // synchronized operation
        .map(user -> user.getFollowing());
  }

  // make a user to follow another user if the argumetns are valid
  // or an error in the form of a string if the argument is not valid
  public Either<String, Void> followUser(String username, String usernameToFollow) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(usernameToFollow, "usernameToFollow"))
        .flatMap(
            __ -> username.equals(usernameToFollow) ? Either.left("a user cannot follow itself") : Either.right(null))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user " + username)
            // extract the userToFollow by its username
            : Either.<String, User>right(network.get(usernameToFollow))
                .flatMap(
                    userToFollow -> userToFollow == null ? Either.left("unknown user " + usernameToFollow)
                        // create a pair with them
                        : Either.right(Pair.of(user, userToFollow))))
        .flatMap(pair -> !loggedUsers.containsKey(pair.fst().username) ? Either.left("user is not logged")
            : Either.right(pair))
        .flatMap(pair -> {
          var user = pair.fst();
          var userToFollow = pair.snd();

          // useful flags to track the success or the failure of the following actions
          var b1 = false;
          var b2 = false;
          // synchronized operations
          synchronized (user.following) {
            synchronized (userToFollow.followers) {
              b1 = user.addFollowing(userToFollow.username);
              b2 = userToFollow.addFollower(user.username);
            }
          }
          if (b1 && b2) {
            // call the callback if everything went fine
            onChangeFollowers.accept(user, userToFollow.username, true);
            return Either.<String, Void>right(null);
          } else {
            return Either.left(username + " was already following " + usernameToFollow);
          }

        });
  }

  // make a user to unfollow another user if the argumetns are valid
  // or an error in the form of a string if the argument is not valid
  public Either<String, Void> unfollowUser(String username, String usernameToUnfollow) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(usernameToUnfollow, "usernameToUnfollow"))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user " + username)
            // extract the userToUnfollow by its username
            : Either.<String, User>right(network.get(usernameToUnfollow))
                .flatMap(
                    userToUnfollow -> userToUnfollow == null ? Either.left("unknown user " + usernameToUnfollow)
                        // create a pair with them
                        : Either.right(Pair.of(user, userToUnfollow))))
        .flatMap(pair -> !loggedUsers.containsKey(pair.fst().username) ? Either.left("user is not logged")
            : Either.right(pair))
        .flatMap(pair -> {
          var user = pair.fst();
          var userToUnfollow = pair.snd();

          // useful flags to track the success or the failure of the following actions
          var b1 = false;
          var b2 = false;
          // synchronized operations
          synchronized (user.following) {
            synchronized (userToUnfollow.followers) {
              b1 = user.removeFollowing(userToUnfollow.username);
              b2 = userToUnfollow.removeFollower(user.username);
            }
          }
          // call the callback if everything went fine
          if (b1 && b2) {
            onChangeFollowers.accept(user, userToUnfollow.username, false);
            return Either.<String, Void>right(null);
          } else {
            return Either.left(username + " wasn't following " + usernameToUnfollow);
          }
        });
  }

  // return the blog of a user if the argument is valid
  // or an error in the form of a string if not
  public Either<String, List<Post>> viewBlog(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        .flatMap(user -> viewUserBlog(user.username));
  }

  // create a new post if the arguments are valid
  // or an error in the form of a string if not
  public Either<String, Post> createPost(String username, String title, String content) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(title, "title"))
        .flatMap(__ -> nullGuard(content, "content"))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        .flatMap(user -> makePost(user.username, title, content));
  }

  // return the blog of a user if the argument is valid
  // or an error in the form of a string if not
  public Either<String, List<Post>> showFeed(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        // synchronized operations
        .map(user -> user.getFollowing())
        .flatMap(fs -> Either.sequence(
            fs.stream()
                // get the blog of each followed user
                .map(user -> viewUserBlog(user))
                // collect the blogs into a list
                .collect(Collectors.toList()))
            // collect together eventual errors
            .mapLeft(seq -> seq.mkString("\n"))
            // flatten the List<List<Post> into a List<Post>
            .map(seq -> seq.fold(new LinkedList<Post>(), (acc, curr) -> {
              acc.addAll(curr);
              return acc;
            })));
  }

  // return a specific post of a specific author if the arguments are valid
  // or an error in the form of a string if not
  // (username wants to see an author's post)
  public Either<String, Post> showPost(String username, String author, String postUuid) {
    // it seems that any user can see any other user's post
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(author, "author"))
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user " + username) : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        // extract the author by its username
        .flatMap(__ -> Either.<String, User>right(network.get(author)))
        .flatMap(authorUser -> authorUser == null ? Either.left("unknown user " + author) : Either.right(authorUser))
        .flatMap(authorUser -> getPost(authorUser.username, postUuid));
  }

  // delete a specific post of a specific user if the arguments are valid
  // or an error in the form of a string if not
  public Either<String, Post> deletePost(String username, String postUuid) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user") : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        .flatMap(user -> cancelPost(user.username, postUuid));
  }

  // rewin a specific post of a specific author if the arguments are valid
  // or an error in the form of a string if not
  // (username wants to rewin an author's post)
  public Either<String, Post> rewinPost(String username, String author, String postUuid) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(author, "author"))
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user " + username) : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        // extract the author by its username
        .flatMap(user -> Either.<String, User>right(network.get(author))
            .flatMap(authorUser -> authorUser == null ? Either.left("unknown user " + author)
                // get the post to rewin
                : getPost(authorUser.username, postUuid)
                    // pack all in a triple
                    .map(post -> Triple.of(user, authorUser, post))))
        .flatMap(
            // check if a user is trying to rewin one of its own posts
            t -> t.fst().username.equals(t.snd().username) ? Either.left("cannot rewin own post") : Either.right(t))
        .flatMap(t -> {
          var user = t.fst();
          var ath = t.snd();

          // syncronized operation
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
            // synchronized with cancelPost
            if (post.justDeleted) {
              toRet = Either.left("the post has just been deleted");
            } else {
              // rewin the post
              toRet = makePost(user.username, post.title, post.content);
              // save a reference into the rewinned post to the rewin
              toRet.forEach(rewinned -> post.rewins.add(AuthorPostUuid.of(rewinned.author, rewinned.uuid)));
            }
          }
          return toRet;
        });
  }

  // rate a specific post of a specific author if the arguments are valid
  // or an error in the form of a string if not
  // (username wants to rate an author's post)
  public Either<String, Reaction> ratePost(String username, String author, String postUuid, Boolean isUpvote) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(author, "author"))
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        .flatMap(__ -> nullGuard(isUpvote, "isUpvote"))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user " + username) : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        // extract the author by its username
        .flatMap(user -> Either.<String, User>right(network.get(author))
            .flatMap(authorUser -> authorUser == null ? Either.left("unknown user " + author)
                // get the post to rate
                : getPost(authorUser.username, postUuid)
                    // pack all in a triple
                    .map(post -> Triple.of(user, authorUser, post))))
        .flatMap(
            // check if a user is trying to rate one of its own posts
            t -> t.fst().username.equals(t.snd().username) ? Either.left("cannot rate own post") : Either.right(t))
        .flatMap(
            t -> {
              var user = t.fst();
              var authorUser = t.snd();
              var post = t.trd();

              // synchronized operations
              synchronized (user.following) {
                synchronized (post.reactions) {
                  var toRet = Either.<String, Reaction>right(null);

                  // is the post into the user's feed?
                  if (!user.following.contains(authorUser.username)) {
                    toRet = Either.left("cannot rate post not in feed");
                    // has the user already rated this post?
                  } else if (post.reactions.stream().anyMatch(r -> r.author.equals(user.username))) {
                    toRet = Either.left("cannot rate a post twice");
                  } else {
                    // create the reaction
                    toRet = ReactionFactory.create(isUpvote, post.uuid, user.username)
                        .toEither()
                        // collect together eventual errors
                        .mapLeft(set -> set.mkString("\n"));

                    toRet.forEach(post.reactions::add);
                  }
                  return toRet;
                }
              }
            });
  }

  // comment a specific post of a specific author if the arguments are valid
  // or an error in the form of a string if not
  // (username wants to comment an author's post)
  public Either<String, Comment> addComment(String username, String author, String postUuid, String text) {
    return nullGuard(username, "username")
        .flatMap(__ -> nullGuard(author, "author"))
        .flatMap(__ -> nullGuard(postUuid, "postUuid"))
        .flatMap(__ -> nullGuard(text, "text"))
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user " + username) : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        // extract the author by its username
        .flatMap(user -> Either.<String, User>right(network.get(author))
            .flatMap(authorUser -> authorUser == null ? Either.left("unknown user " + author)
                // extract the post the user wants to comment
                : getPost(authorUser.username, postUuid)
                    // pack all in a triple
                    .map(post -> Triple.of(user, authorUser, post))))
        .flatMap(
            t -> t.fst().username.equals(t.snd().username) ? Either.left("cannot comment own post") : Either.right(t))
        .flatMap(
            t -> {
              var user = t.fst();
              var authorUser = t.snd();
              var post = t.trd();

              // synchronized operations
              synchronized (user.following) {
                synchronized (post.comments) {
                  var toRet = Either.<String, Comment>right(null);

                  // is the post into the user's feed?
                  if (!user.following.contains(authorUser.username)) {
                    toRet = Either.left("cannot comment post not in feed");
                  } else {
                    // create the comment
                    toRet = CommentFactory.create(text, postUuid, user.username)
                        .toEither()
                        // collect together eventual errors
                        .mapLeft(set -> set.mkString("\n"));

                    toRet.forEach(post.comments::add);
                  }
                  return toRet;
                }
              }
            });
  }

  // return the wallet of a user if the argument is valid
  // or an error in the form of a string if not
  public Either<String, List<WalletTransaction>> getUserWallet(String username) {
    return nullGuard(username, "username")
        // extract the user by its username
        .flatMap(__ -> Either.<String, User>right(network.get(username)))
        .flatMap(user -> user == null ? Either.left("unknown user " + username) : Either.right(user))
        .flatMap(
            user -> !loggedUsers.containsKey(user.username) ? Either.left("user is not logged") : Either.right(user))
        .flatMap(user -> getWalletOfUser(user.username));
  }

  // return the wallet of a user, adding together the transactions
  // or an error in the form of a string if the argument is not valid
  public Either<String, Double> getUserWalletInWincoin(String username) {
    return getUserWallet(username)
        .map(ts -> ts.stream().map(t -> t.gain).reduce(0., (acc, val) -> acc + val));
  }

  // return the wallet of a user, adding together the transactions using bitcoin
  // as currency, plus the used rate
  // or an error in the form of a string if the argument is not valid
  public Either<String, Pair<Double, Double>> getUserWalletInBitcoin(String username) {
    return getUserWalletInWincoin(username)
        .flatMap(ws -> {

          // get a fake conversion rate from random.org
          try {
            var url = new URL("https://www.random.org/decimal-fractions/?num=1&dec=10&col=1&format=plain&rnd=new");
            var con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod(HttpConstants.GET);

            if (con.getResponseCode() == HttpURLConnection.HTTP_OK) {
              var reader = new BufferedReader(new InputStreamReader(con.getInputStream()));

              var inputLine = "";
              var res = new StringBuffer();

              while ((inputLine = reader.readLine()) != null) {
                res.append(inputLine);
              }
              reader.close();

              var rate = Double.parseDouble(res.toString());

              return Either.right(Pair.of(rate, rate * ws));
            } else {
              throw new RuntimeException("get request to random.org has failed");
            }
          } catch (Exception e) {
            e.printStackTrace();
            return Either.left("conversion in bitcoin has failed");
          }
        });
  }

  // create a deamon to persist the server's state on the disk
  public Either<String, Runnable> makePersistenceRunnable(Long interval, String path, Boolean minify) {
    return nullGuard(interval, "interval")
        .flatMap(__ -> nullGuard(path, "path"))
        .flatMap(__ -> nullGuard(minify, "minify"))
        .map(__ -> () -> {
          while (!Thread.currentThread().isInterrupted()) {
            try {
              // could be interrupted, the internal state is serialized
              // usign the json format
              Files.write(Paths.get(path + ".temp"), this.toJSON().getBytes());

              // does not matter if the file does not exist
              var oldSnapshot = new File(path);
              oldSnapshot.delete();

              var newSnapshot = new File(path + ".temp");
              newSnapshot.renameTo(new File(path));

              Thread.sleep(interval);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();

              // does not matter if the file does not exist
              var tempSnapshot = new File(path + ".temp");

              tempSnapshot.delete();
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        });
  }

  // create a deamon to persist the server's state on the disk
  public Either<String, Runnable> makeWalletRunnable(Long interval, Integer authorPercentage) {
    return this.makeWalletRunnable(interval, authorPercentage, () -> {
    });
  }

  // create a deamon to periodically update the wallet of each user
  // the Runnable action is performed after each iteration, but only if at least
  // one user's wallet has been updated
  public Either<String, Runnable> makeWalletRunnable(Long interval, Integer authorPercentage, Runnable action) {
    return nullGuard(interval, "interval")
        .flatMap(__ -> nullGuard(authorPercentage, "authorPercentage"))
        .flatMap(__ -> nullGuard(authorPercentage, "action"))
        .filterOrElse(p -> p >= 0 && p <= 100, p -> p + " is an invalid author percentage")
        .map(__ -> () -> {
          var nowTimestamp = Wrapper.of(0L);

          while (!Thread.currentThread().isInterrupted()) {
            try {

              // get the current time
              nowTimestamp.value = new Date().getTime();

              // should the action run?
              var runAction = Wrapper.of(false);

              // collect all the posts of the network
              var posts = this.network
                  .entrySet()
                  .stream()
                  .flatMap(ne -> ne.getValue().posts
                      .entrySet()
                      .stream()
                      .map(ue -> ue.getValue()));

              // for each post
              posts.forEach(post -> {

                // collect all its reactions with a timestamp that is
                // greater than the one of the previous iteration of the daemon
                var reactions = post.getReactions()
                    .stream()
                    .filter(r -> r.timestamp >= this.wallet.getPrevTimestamp() && r.timestamp < nowTimestamp.value)
                    .collect(Collectors.toList());

                // extract the positive reactions
                var positiveReactions = reactions
                    .stream()
                    .filter(r -> r.isUpvote)
                    .collect(Collectors.toList());

                // collect all its comments with a timestamp that is
                // greater than the one of the previous iteration of the daemon
                // into a list of entries <author, list of its comments to the post>
                var comments = post.getComments()
                    .stream()
                    .filter(c -> c.timestamp >= this.wallet.getPrevTimestamp() && c.timestamp < nowTimestamp.value)
                    .collect(Collectors.groupingBy(c -> c.author))
                    .entrySet()
                    .stream()
                    .collect(Collectors.toList());

                // continue only if there is at least a positive reaction or a comment
                if (positiveReactions.size() != 0 || comments.size() != 0) {

                  // sum the values of all the reactions
                  var reactionsSum = reactions
                      .stream()
                      .map(r -> r.isUpvote ? 1 : -1)
                      .reduce(0, (acc, val) -> acc + val);

                  // compute their contribute
                  var reactionsContribute = Math.log(Math.max(reactionsSum, 0) + 1);

                  // sum the values of all the comments and computes their contribute
                  var commentsSum = comments
                      .stream()
                      .map(ce -> (double) ce.getValue().size())
                      .reduce(0., (acc, val) -> acc + (2. / (1 + Math.pow(Math.E, -(val.intValue() - 1)))));

                  var commentsContribute = Math.log(commentsSum + 1);

                  // overall gain
                  var gain = (reactionsContribute + commentsContribute) / post.getWalletScannerIteration();

                  // compute the gain of the author
                  var authorGain = (gain / 100) * authorPercentage;

                  // list of other users that have contributed to the post (contains no
                  // duplicates)
                  var otherUsers = Stream.concat(
                      positiveReactions
                          .stream()
                          .map(r -> r.author),
                      comments
                          .stream()
                          .map(ce -> ce.getKey()))
                      .distinct()
                      .collect(Collectors.toList());

                  // compute the gain of the other users
                  var othersGain = ((gain / 100) * (100 - authorPercentage)) / otherUsers.stream().count();

                  // update the wallet of the author
                  this.wallet
                      .addTransaction(post.author, authorGain)
                      .swap()
                      .forEach(System.out::println);

                  // update the wallet of other users
                  otherUsers
                      .stream()
                      .forEach(user -> this.wallet
                          .addTransaction(user, othersGain)
                          .swap()
                          .forEach(System.out::println));

                  // update the number of iterations performed on the psot
                  post.incrementWalletScannerIteration();

                  // yes because at least one post has been evaluated
                  runAction.value = true;
                }
              });

              this.wallet.setPrevTimestamp(nowTimestamp.value);

              // run the action at the end of the wallet updating process
              // if the gain of at least one post was computed
              if (runAction.value) {
                action.run();
              }

              Thread.sleep(interval);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
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

  // static utils

  public static Winsome of() {
    return new Winsome();
  }
}

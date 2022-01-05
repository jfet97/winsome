package domain.post;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import domain.comment.Comment;
import domain.reaction.Reaction;
import utils.ToJSON;

// ignore 'upvotes' and 'downvotes' that are not actual Post's fields
@JsonIgnoreProperties(ignoreUnknown = true)
public class Post {
  public String uuid;
  public Long timestamp;
  public String title;
  public String content;
  public String author;
  public Boolean justDeleted;
  public List<AuthorPostUuid> rewins; // List<{author, postUuid}>
  public List<Comment> comments;
  public List<Reaction> reactions;
  public Long walletScannerIteration; // current iteration of the wallet thread

  // to store unknown properties collected during
  // json deserialization
  public Map<String, Object> unknowns = new HashMap<>();

  @JsonAnySetter
  void setDetail(String key, Object value) {
    unknowns.put(key, value);
  }

  public static Post of(String title, String content, String author) {

    var instance = new Post();

    instance.uuid = UUID.randomUUID().toString(); // readonly
    instance.timestamp = new Date().getTime(); // readonly
    instance.title = title; // readonly
    instance.content = content; // readonly
    instance.author = author; // readonly
    instance.justDeleted = false; // needs manual synchronization
    instance.rewins = new LinkedList<AuthorPostUuid>(); // needs manual synchronization
    instance.comments = new LinkedList<Comment>(); // needs manual synchronization
    instance.reactions = new LinkedList<Reaction>(); // needs manual synchronization
    instance.walletScannerIteration = 1L; // needs manual synchronization (wallet thread and persistence thread)

    return instance;
  }

  // get positive reactions
  public Long getUpvotes() {
    // by returning a clone, we can safely perform
    // further actions on the list without the need
    // of the lock, although "the read may not get the latest write"
    // (eventual consistency)
    synchronized (this.reactions) {
      return this.reactions
          .stream()
          .filter(r -> r.isUpvote)
          .count();
    }
  }

  // get negative reactions
  public Long getDownvotes() {
    // by returning a clone, we can safely perform
    // further actions on the list without the need
    // of the lock, although "the read may not get the latest write"
    // (eventual consistency)
    synchronized (this.reactions) {
      return this.reactions
          .stream()
          .filter(r -> !r.isUpvote)
          .count();
    }
  }

  // get all the reactions
  public List<Reaction> getReactions() {
    // by returning a clone, we can safely perform
    // further actions on the list without the need
    // of the lock, although "the read may not get the latest write"
    // (eventual consistency)
    synchronized (reactions) {
      return this.reactions
          .stream()
          .collect(Collectors.toList());
    }
  }

  // get all the comments
  public List<Comment> getComments() {
    // by returning a clone, we can safely perform
    // further actions on the list without the need
    // of the lock, although "the read may not get the latest write"
    // (eventual consistency)
    synchronized (comments) {
      return this.comments
      .stream()
      .collect(Collectors.toList());
    }
  }

  public synchronized Long getWalletScannerIteration() {
    return this.walletScannerIteration;
  }

  public synchronized void incrementWalletScannerIteration() {
    this.walletScannerIteration += 1;
  }

  public String toJSON() {

    var commentsLine = "\"comments\":[";
    synchronized (this.comments) {
      commentsLine += this.comments
          .stream()
          .map(c -> c.toJSON())
          .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    }
    commentsLine += "]";

    var reactionsLine = "\"reactions\":[";
    synchronized (this.reactions) {
      reactionsLine += this.reactions
          .stream()
          .map(r -> r.toJSON())
          .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    }
    reactionsLine += "]";

    var rewinsLine = "\"rewins\":[";
    synchronized (this.rewins) {
      rewinsLine += this.rewins
          .stream()
          .map(p -> p.ToJSON())
          .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    }
    rewinsLine += "]";

    return String.join("",
        "{",
        "\"uuid\":" + "\"" + this.uuid + "\"" + ",",
        "\"timestamp\":" + this.timestamp + ",",
        "\"title\":" + "\"" + this.title + "\"" + ",",
        "\"content\":" + ToJSON.toJSON(this.content) + ",",
        "\"author\":" + "\"" + this.author + "\"" + ",",
        "\"justDeleted\":" + this.justDeleted + ",",
        "\"walletScannerIteration\":" + "\"" + this.getWalletScannerIteration() + "\"" + ",",
        "\"upvotes\":" + "\"" + this.getUpvotes() + "\"" + ",",
        "\"downvotes\":" + "\"" + this.getDownvotes() + "\"" + ",",
        rewinsLine + ",",
        commentsLine + ",",
        reactionsLine,
        "}");
  }
}

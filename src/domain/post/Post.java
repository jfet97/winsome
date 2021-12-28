package domain.post;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import domain.comment.Comment;
import domain.reaction.Reaction;

// ignore 'upvotes' and 'downvotes' that are not actual Post's fields
@JsonIgnoreProperties(ignoreUnknown = true)
public class Post {
  public String uuid;
  public Long timestamp;
  public String title;
  public String content;
  public String author;
  public List<Comment> comments;
  public List<Reaction> reactions;
  public Long walletScannerIteration;

  public static Post of(String title, String content, String author) {

    var instance = new Post();

    instance.uuid = UUID.randomUUID().toString(); // readonly
    instance.timestamp = new Date().getTime(); // readonly
    instance.title = title; // readonly
    instance.content = content; // readonly
    instance.author = author; // readonly
    instance.comments = new LinkedList<Comment>(); // needs manual synchronization
    instance.reactions = new LinkedList<Reaction>(); // needs manual synchronization
    instance.walletScannerIteration = 1L; // needs manual synchronization (wallet thread and persistence thread)

    return instance;
  }

  public Long getUpvotes() {
    synchronized (this.reactions) {
      return this.reactions
          .stream()
          .filter(r -> r.isUpvote)
          .count();
    }
  }

  public Long getDownvotes() {
    synchronized (this.reactions) {
      return this.reactions
          .stream()
          .filter(r -> !r.isUpvote)
          .count();
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

    return String.join("",
        "{",
        "\"uuid\":" + "\"" + this.uuid + "\"" + ",",
        "\"timestamp\":" + this.timestamp + ",",
        "\"title\":" + "\"" + this.title + "\"" + ",",
        "\"content\":" + "\"" + this.content + "\"" + ",",
        "\"author\":" + "\"" + this.author + "\"" + ",",
        "\"walletScannerIteration\":" + "\"" + this.getWalletScannerIteration() + "\"" + ",",
        "\"upvotes\":" + "\"" + this.getUpvotes() + "\"" + ",",
        "\"downvotes\":" + "\"" + this.getDownvotes() + "\"" + ",",
        commentsLine + ",",
        reactionsLine,
        "}");
  }
}

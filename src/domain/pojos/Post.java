package domain.pojos;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

public class Post {
  public String uuid;
  public Long date;
  public String title;
  public String content;
  public String author;
  public List<Comment> comments;
  public List<Reaction> reactions;

  public static Post of(String title, String content, String author) {

    var instance = new Post();

    instance.uuid = UUID.randomUUID().toString(); // readonly
    instance.date = new Date().getTime(); // readonly
    instance.title = title; // readonly
    instance.content = content; // readonly
    instance.author = author; // readonly
    instance.comments = new LinkedList<Comment>(); // needs manual synchronization
    instance.reactions = new LinkedList<Reaction>(); // needs manual synchronization

    return instance;
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
          .map(c -> c.toJSON())
          .reduce("", (acc, curr) -> acc.equals("") ? curr : acc + "," + curr);
    }
    reactionsLine += "]";

    return String.join("",
        "{",
        "\"uuid\":" + "\"" + this.uuid + "\"" + ",",
        "\"date\":" + this.date + ",",
        "\"title\":" + "\"" + this.title + "\"" + ",",
        "\"content\":" + "\"" + this.content + "\"" + ",",
        "\"author\":" + "\"" + this.author + "\"" + ",",
        commentsLine + ",",
        reactionsLine,
        "}");
  }
}

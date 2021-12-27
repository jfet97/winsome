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
}

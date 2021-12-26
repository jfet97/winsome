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
  public List<String> comments;
  public Integer upvotes;
  public Integer downvotes;

  public static Post of(String title, String content, String author) {

    var newPost = new Post();

    newPost.uuid = UUID.randomUUID().toString(); // readonly
    newPost.date = new Date().getTime(); // readonly
    newPost.title = title; // readonly
    newPost.content = content; // readonly
    newPost.author = author; // readonly
    newPost.comments = new LinkedList<String>(); // needs manual synchronization
    newPost.upvotes = 0; // needs manual synchronization
    newPost.downvotes = 0; // needs manual synchronization

    return newPost;
  }
}

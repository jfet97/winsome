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

    newPost.uuid = UUID.randomUUID().toString();
    newPost.date = new Date().getTime();
    newPost.title = title;
    newPost.content = content;
    newPost.author = author;
    newPost.comments = new LinkedList<String>();
    newPost.upvotes = 0;
    newPost.downvotes = 0;

    return newPost;
  }
}

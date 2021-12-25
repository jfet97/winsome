package domain.factories;

import domain.pojos.Post;
import io.vavr.collection.Seq;
import io.vavr.control.Validation;

public class PostFactory {
  private PostFactory() {
  }

  private static interface PostValidator {
    public Validation<Seq<String>, Post> validatePost(String title, String content, String author);
  }

  private static PostValidator validator = new PostValidator() {
    @Override
    public Validation<Seq<String>, Post> validatePost(String title, String content, String author) {
      return Validation.combine(validateTitle(title), validateContent(content), validateAuthor(author)).ap(Post::of);
    }

    private Validation<String, String> validateTitle(String title) {
      var errorMessage = "";

      if (title == null)
        errorMessage = "title cannot be null";
      else if (title.length() > 20)
        errorMessage = "title max length is 20 characters";
      else if (title == "")
        errorMessage = "title cannot be empty";

      return errorMessage != "" ? Validation.invalid(errorMessage) : Validation.valid(title);
    }

    private Validation<String, String> validateContent(String content) {
      var errorMessage = "";

      if (content == null)
        errorMessage = "content cannot be null";
      else if (content.length() > 500)
        errorMessage = "content max length is 500 characters";
      else if (content == "")
        errorMessage = "content cannot be empty";

      return errorMessage != "" ? Validation.invalid(errorMessage) : Validation.valid(content);
    }

    private Validation<String, String> validateAuthor(String author) {
      return author == null ? Validation.invalid("author cannot be null") : Validation.valid(author);
    }
  };

  public static Validation<Seq<String>, Post> create(String title, String content, String author) {
    return validator.validatePost(title, content, author);
  }
}

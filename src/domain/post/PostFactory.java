package domain.post;

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
      var titleTrimmed = title != null ? title.trim().replaceAll("\n|\r|\r\n", "") : null;

      if (titleTrimmed == null)
        errorMessage = "title cannot be null";
      else if (titleTrimmed.length() > 20)
        errorMessage = "title max length is 20 characters";
      else if (titleTrimmed.equals(""))
        errorMessage = "title cannot be empty";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(titleTrimmed);
    }

    private Validation<String, String> validateContent(String content) {
      var errorMessage = "";
      var contentTrimmed = content != null ? content.trim() : null;

      if (contentTrimmed == null)
        errorMessage = "content cannot be null";
      else if (contentTrimmed.length() > 500)
        errorMessage = "content max length is 500 characters";
      else if (contentTrimmed.equals(""))
        errorMessage = "content cannot be empty";

      return !errorMessage.equals("") ? Validation.invalid(errorMessage) : Validation.valid(contentTrimmed);
    }

    private Validation<String, String> validateAuthor(String author) {
      return author == null ? Validation.invalid("author cannot be null") : Validation.valid(author.trim());
    }
  };

  public static Validation<Seq<String>, Post> create(String title, String content, String author) {
    return validator.validatePost(title, content, author);
  }
}

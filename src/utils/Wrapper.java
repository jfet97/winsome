package utils;

public class Wrapper<T> {
  public T value;

  private Wrapper(T value) {
    this.value = value;
  }

  public static <T> Wrapper<T> of(T value) {
    return new Wrapper<T>(value);
  }
}